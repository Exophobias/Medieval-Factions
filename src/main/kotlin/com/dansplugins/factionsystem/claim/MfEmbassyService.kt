package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.EmbassyOfferAttemptEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.ChildMutationCallbackGuard
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.map
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.resultFrom
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Native faction tenancy over a connected area of already claimed chunks. Persistence completes before the
 * new immutable snapshot is published to the hot protection path. Reads never touch storage or chunks.
 */
class MfEmbassyService(
    private val plugin: MedievalFactions,
    private val repository: MfEmbassyRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    data class ChunkPos(val x: Int, val z: Int)

    private data class Key(val worldId: UUID, val x: Int, val z: Int) {
        constructor(row: MfEmbassy) : this(row.worldId, row.chunkX, row.chunkZ)
    }

    private val mutationLock = ReentrantLock(true)
    @Volatile private var byChunk: Map<Key, MfEmbassy> = emptyMap()
    @Volatile private var newAgreementBlock: String? = null
    // Conservative clock fences survive failed writes in this process and are retried by sweep.
    private data class ClockFence(val base: MfEmbassy, val fenced: MfEmbassy)
    private val warClockFences = ConcurrentHashMap<Key, ClockFence>()
    private val startupRecoveryCandidates = ConcurrentHashMap.newKeySet<Key>()
    /** Factions whose parent delete is fenced; guarded by [mutationLock]. */
    private val deletingFactions = HashSet<MfFactionId>()

    init {
        val rows = repository.getAll()
        rows.forEach(::validateStored)
        require(rows.size == rows.distinctBy(::Key).size) { "Duplicate embassy chunk in storage" }
        rows.groupBy { it.hostId to it.guestId }.values.forEach { pair ->
            require(pair.map { it.worldId }.distinct().size == 1) { "Embassy agreement spans multiple worlds" }
            val offers = pair.filter { it.status == MfEmbassyStatus.OFFERED }
            require(offers.map { Triple(it.createdAt, it.deadlineAt, it.offerSize) }.distinct().size <= 1) {
                "Embassy agreement has multiple pending offers"
            }
        }
        byChunk = rows.associateBy(::Key)
        rows.filter { (it.status == MfEmbassyStatus.CLEARING || it.status == MfEmbassyStatus.CONQUEST_PASSAGE ||
            it.status == MfEmbassyStatus.CONQUEST_DECISION) &&
            it.pausedAt == null }.mapTo(startupRecoveryCandidates, ::Key)
        plugin.logger.info("${rows.size} faction embassies loaded")
    }

    /** Effective row only: expired rows are inert; a changed owner becomes a closed conquest decision. */
    fun getAt(worldId: UUID, chunkX: Int, chunkZ: Int): MfEmbassy? =
        effective(byChunk[Key(worldId, chunkX, chunkZ)], clock.millis())

    fun listFor(factionId: MfFactionId): List<MfEmbassy> = byChunk.values
        .mapNotNull { effective(it, clock.millis()) }
        .filter { it.hostId == factionId || it.guestId == factionId || it.conquerorId == factionId }
        .sortedWith(compareBy({ it.worldId.toString() }, { it.chunkX }, { it.chunkZ }))

    /** Used by claim/unclaim policy and automation boundary checks. */
    fun hasActiveOrClearingEmbassy(worldId: UUID, chunkX: Int, chunkZ: Int): Boolean {
        val status = getAt(worldId, chunkX, chunkZ)?.status
        return status != null && status != MfEmbassyStatus.OFFERED
    }

    /** Physical parcel boundary policy is suspended during host–guest war. */
    fun isParcelProtectionActive(worldId: UUID, chunkX: Int, chunkZ: Int): Boolean {
        val row = getAt(worldId, chunkX, chunkZ) ?: return false
        return row.status != MfEmbassyStatus.OFFERED && !atWar(row)
    }

    /** Includes an unexpired offer, so Fiefs cannot claim the parcel while agreement is pending. */
    fun hasReservedEmbassy(worldId: UUID, chunkX: Int, chunkZ: Int): Boolean =
        getAt(worldId, chunkX, chunkZ) != null

    /** Any outstanding embassy involvement blocks adoption into a mobile Nomad realm. */
    fun hasActiveOrClearingForFaction(factionId: MfFactionId): Boolean = byChunk.values.any { stored ->
        val row = getAt(stored.worldId, stored.chunkX, stored.chunkZ)
        row != null && (row.hostId == factionId || row.guestId == factionId ||
            currentLandholder(row) == factionId)
    }

    /**
     * Used by a single claim release. The check and repository delete must share this lock, or an
     * acceptance can land between them and be silently destroyed by the claim's FK cascade.
     */
    internal fun <T> withClaimRelease(claim: MfClaimedChunk, action: () -> T): T = mutationLock.withLock {
        require(getAt(claim.worldId, claim.x, claim.z) == null) {
            "Cancel the embassy offer or finish the clearing period before unclaiming this chunk"
        }
        action()
    }

    /** Equivalent fence for /f unclaimall. */
    internal fun <T> withAllClaimsRelease(host: MfFactionId, action: () -> T): T = mutationLock.withLock {
        require(byChunk.values.none { stored ->
            val row = getAt(stored.worldId, stored.chunkX, stored.chunkZ)
            row != null && currentLandholder(row) == host
        }) {
            "Cancel all embassy offers and finish clearing periods before unclaiming all land"
        }
        action()
    }

    /**
     * Called as one child-service fence during faction deletion. If an active lease exists, refuse
     * deletion before its parent-row cascade can erase the guest's property recovery window.
     */
    internal fun blockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        require(byChunk.values.none { stored ->
            val row = getAt(stored.worldId, stored.chunkX, stored.chunkZ)
            row != null &&
                (row.hostId == factionId || currentLandholder(row) == factionId || row.guestId == factionId)
        }) {
            "Cancel embassy offers and finish clearing periods before dissolving this faction"
        }
        check(deletingFactions.add(factionId)) { "Faction ${factionId.value} is already being deleted" }
    }

    internal fun unblockFactionDeletion(factionId: MfFactionId) = mutationLock.withLock {
        deletingFactions.remove(factionId)
    }

    /**
     * Authoritative parcel decision for territory listeners. Combat and explosions stay with MF's
     * existing rules. A war falls back to ordinary territory rules without erasing the lease.
     */
    fun access(playerId: MfPlayerId, claim: MfClaimedChunk, action: ClaimAction): EmbassyAccessDecision {
        if (action !in PARCEL_ACTIONS) return EmbassyAccessDecision.NONE
        val row = getAt(claim.worldId, claim.x, claim.z) ?: return EmbassyAccessDecision.NONE
        if (row.status == MfEmbassyStatus.OFFERED) return EmbassyAccessDecision.NONE
        if (atWar(row)) return EmbassyAccessDecision.NONE
        if (currentLandholder(row) != claim.factionId) return EmbassyAccessDecision.DENY
        if (row.status == MfEmbassyStatus.CONQUEST_DECISION) return EmbassyAccessDecision.DENY
        val faction = plugin.services.factionService.getFaction(playerId)
        if (faction?.id != row.guestId) return EmbassyAccessDecision.DENY
        if ((row.status == MfEmbassyStatus.CLEARING || row.status == MfEmbassyStatus.CONQUEST_PASSAGE) &&
            action !in RECOVERY_ACTIONS) {
            return EmbassyAccessDecision.DENY
        }
        return EmbassyAccessDecision.GRANT
    }

    /** Physical parcel admission. Wartime movement falls back to ordinary MF/Paper movement rules. */
    fun entryDecision(
        playerId: MfPlayerId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): EmbassyAccessDecision {
        val row = getAt(worldId, chunkX, chunkZ) ?: return EmbassyAccessDecision.NONE
        if (row.status == MfEmbassyStatus.OFFERED || atWar(row)) return EmbassyAccessDecision.NONE
        if (row.status == MfEmbassyStatus.CONQUEST_DECISION) return EmbassyAccessDecision.DENY
        val faction = plugin.services.factionService.getFaction(playerId)
        return if (faction?.id == row.guestId) EmbassyAccessDecision.GRANT else EmbassyAccessDecision.DENY
    }

    /** Refuse new agreements when a required runtime protection hook is unavailable. */
    fun blockNewAgreements(reason: String) {
        require(reason.isNotBlank()) { "Embassy protection failure must have a reason" }
        newAgreementBlock = reason
    }

    /** Same cap applies independently to this faction's hosted and held chunk pools. */
    fun chunkLimit(factionId: MfFactionId): Int {
        val faction = plugin.services.factionService.getFaction(factionId) ?: return 0
        // A malformed explicit value is closed, rather than silently becoming the default.
        val limit = runCatching { faction.flags[plugin.flags.maxEmbassyChunks] }.getOrNull() ?: return 0
        return if (limit in 0..MfFlags.MAX_EMBASSY_CHUNKS) limit else 0
    }

    fun hostedChunkCount(factionId: MfFactionId): Int = effectiveRows(clock.millis())
        .count { currentLandholder(it) == factionId }

    fun heldChunkCount(factionId: MfFactionId): Int = effectiveRows(clock.millis())
        .count { it.guestId == factionId }

    /** One pending expansion at a time; all offered cells are accepted together. */
    fun pendingOfferAt(worldId: UUID, chunkX: Int, chunkZ: Int): List<MfEmbassy> {
        val now = clock.millis()
        val snapshot = byChunk
        val selected = effective(snapshot[Key(worldId, chunkX, chunkZ)], now) ?: return emptyList()
        if (selected.status != MfEmbassyStatus.OFFERED) return emptyList()
        val offered = snapshot.values.filter { sameAgreement(it, selected) && it.status == MfEmbassyStatus.OFFERED }
        if (offered.size != selected.offerSize) return emptyList()
        val effectiveOffers = offered.mapNotNull { effective(it, now) }
        if (effectiveOffers.size != offered.size) return emptyList()
        return effectiveOffers.sortedWith(compareBy({ it.chunkX }, { it.chunkZ }))
    }

    /** Internal automation may cross only cells with the same peaceful access phase and landholder. */
    fun sameProtectedArea(
        worldId: UUID, fromChunkX: Int, fromChunkZ: Int, toChunkX: Int, toChunkZ: Int
    ): Boolean {
        val snapshot = byChunk
        val now = clock.millis()
        val from = effective(snapshot[Key(worldId, fromChunkX, fromChunkZ)], now) ?: return false
        val to = effective(snapshot[Key(worldId, toChunkX, toChunkZ)], now) ?: return false
        if (from.status == MfEmbassyStatus.CONQUEST_DECISION || from.status == MfEmbassyStatus.CONQUEST_PASSAGE) {
            return fromChunkX == toChunkX && fromChunkZ == toChunkZ && !atWar(from)
        }
        return from.status != MfEmbassyStatus.OFFERED && to.status == from.status &&
            sameAgreement(from, to) && currentLandholder(from) == currentLandholder(to) &&
            !atWar(from) && !atWar(to)
    }

    /** Cells sharing one protected phase; conquest recovery remains an independent single cell. */
    fun protectedAreaAt(worldId: UUID, chunkX: Int, chunkZ: Int): List<MfEmbassy> {
        val now = clock.millis()
        val snapshot = byChunk
        val selected = effective(snapshot[Key(worldId, chunkX, chunkZ)], now) ?: return emptyList()
        if (selected.status == MfEmbassyStatus.OFFERED || atWar(selected)) return emptyList()
        if (selected.status == MfEmbassyStatus.CONQUEST_DECISION || selected.status == MfEmbassyStatus.CONQUEST_PASSAGE) {
            return listOf(selected)
        }
        return snapshot.values.mapNotNull { effective(it, now) }.filter {
            sameAgreement(it, selected) && it.status == selected.status &&
                currentLandholder(it) == currentLandholder(selected) && !atWar(it)
        }
    }

    /** Compatibility entry point for a single-cell offer. */
    fun offer(
        host: MfFactionId, guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<MfEmbassy, ServiceFailure> =
        offerArea(host, guest, worldId, listOf(ChunkPos(chunkX, chunkZ))).map { it.single() }

    /** Offer a connected initial area or one adjacent expansion, atomically reserving every cell. */
    fun offerArea(
        host: MfFactionId, guest: MfFactionId, worldId: UUID, chunks: List<ChunkPos>
    ): Result4k<List<MfEmbassy>, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            requireParcelChecksAvailable()
            val now = clock.millis()
            sweepLocked(now)
            require(host != guest) { "A faction cannot host itself" }
            require(host !in deletingFactions && guest !in deletingFactions) { "A faction is being dissolved" }
            require(plugin.services.factionService.getFaction(host) != null) { "Host faction no longer exists" }
            require(plugin.services.factionService.getFaction(guest) != null) { "Guest faction no longer exists" }
            validateNewArea(host, guest, worldId, chunks)
            chunks.forEach { pos ->
                requireEmptyParcel(host, worldId, pos.x, pos.z)
                preflight(worldId, pos.x, pos.z, host, guest, accepting = false)
            }
            // A synchronous consumer may mutate another service before returning. Recheck the
            // entire area and its capacity after every callback has completed.
            validateNewArea(host, guest, worldId, chunks)
            chunks.forEach { requireEmptyParcel(host, worldId, it.x, it.z) }
            val offered = chunks.map { pos ->
                MfEmbassy(worldId, pos.x, pos.z, host, guest, MfEmbassyStatus.OFFERED,
                    now, now, Math.addExact(now, OFFER_MILLIS), offerSize = chunks.size)
            }
            applyChanges(offered, emptyList())
            offered
        }.mapFailure(::failure)
    }

    /** Compatibility entry point; acceptance still signs every cell in the pending offer. */
    fun accept(
        guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<MfEmbassy, ServiceFailure> =
        acceptArea(guest, worldId, chunkX, chunkZ).map { rows ->
            rows.single { it.chunkX == chunkX && it.chunkZ == chunkZ }
        }

    fun acceptArea(
        guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<List<MfEmbassy>, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            requireParcelChecksAvailable()
            val now = clock.millis()
            sweepLocked(now)
            val row = requireNotNull(byChunk[Key(worldId, chunkX, chunkZ)]) { "No embassy offer at this chunk" }
            require(row.status == MfEmbassyStatus.OFFERED && row.guestId == guest) { "This offer is not for your faction" }
            require(row.hostId !in deletingFactions && guest !in deletingFactions) { "A faction is being dissolved" }
            require(plugin.services.factionService.getFaction(guest) != null) { "Guest faction no longer exists" }
            val offers = pendingOfferAt(worldId, chunkX, chunkZ)
            require(offers.isNotEmpty()) { "Embassy offer has expired" }
            validatePendingArea(row, offers)
            require(!atWar(row)) { "Factions at war cannot activate an embassy" }
            offers.forEach { cell ->
                requireEmptyParcel(cell.hostId, cell.worldId, cell.chunkX, cell.chunkZ)
                preflight(cell.worldId, cell.chunkX, cell.chunkZ, cell.hostId, guest, accepting = true)
            }
            plugin.services.lockService.withMutationLock {
                plugin.services.gateService.withMutationLock {
                    require(pendingOfferAt(worldId, chunkX, chunkZ) == offers) { "Embassy offer changed during acceptance" }
                    validatePendingArea(row, offers)
                    require(!atWar(row)) { "Factions at war cannot activate an embassy" }
                    offers.forEach { requireEmptyParcel(it.hostId, it.worldId, it.chunkX, it.chunkZ) }
                    // Capacity was reserved at offer time. A later cap reduction does not evict it.
                    val active = offers.map { it.copy(status = MfEmbassyStatus.ACTIVE, changedAt = now, deadlineAt = null) }
                    applyChanges(active, emptyList())
                    active
                }
            }
        }.mapFailure(::failure)
    }

    private fun validateNewArea(host: MfFactionId, guest: MfFactionId, worldId: UUID, chunks: List<ChunkPos>) {
        require(chunks.isNotEmpty() && chunks.size == chunks.distinct().size) { "Embassy area must have distinct chunks" }
        require(chunks.size <= MfFlags.MAX_EMBASSY_CHUNKS) { "Embassy area is too large" }
        require(!atWar(host, guest)) { "Factions at war cannot establish an embassy" }
        val rows = effectiveRows(clock.millis())
        val pair = rows.filter { it.hostId == host && it.guestId == guest }
        require(pair.all { it.worldId == worldId }) { "This embassy agreement is in another world" }
        require(pair.none { it.status == MfEmbassyStatus.OFFERED }) { "Accept or cancel this pair's pending offer first" }
        require(pair.all { it.status == MfEmbassyStatus.ACTIVE }) { "Finish embassy recovery before expanding this area" }
        require(chunks.all { byChunk[Key(worldId, it.x, it.z)] == null }) { "An area chunk already has an embassy" }
        requireConnected(chunks + pair.map { ChunkPos(it.chunkX, it.chunkZ) })
        val hosted = rows.count { currentLandholder(it) == host }
        val held = rows.count { it.guestId == guest }
        require(hosted.toLong() + chunks.size <= chunkLimit(host)) { "Host faction's embassy chunk limit is reached" }
        require(held.toLong() + chunks.size <= chunkLimit(guest)) { "Guest faction's embassy chunk limit is reached" }
    }

    private fun validatePendingArea(selected: MfEmbassy, offers: List<MfEmbassy>) {
        val agreement = effectiveRows(clock.millis()).filter { sameAgreement(it, selected) }
        require(agreement.all { it.status == MfEmbassyStatus.ACTIVE || it.status == MfEmbassyStatus.OFFERED }) {
            "Embassy agreement entered recovery before this offer was accepted"
        }
        requireConnected(offers.map { ChunkPos(it.chunkX, it.chunkZ) } +
            agreement.filter { it.status == MfEmbassyStatus.ACTIVE }.map { ChunkPos(it.chunkX, it.chunkZ) })
    }

    private fun requireConnected(chunks: List<ChunkPos>) {
        val unseen = chunks.toMutableSet()
        val queue = ArrayDeque<ChunkPos>()
        val first = unseen.first()
        unseen.remove(first)
        queue.add(first)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val neighbors = listOfNotNull(
                if (pos.x > Int.MIN_VALUE) ChunkPos(pos.x - 1, pos.z) else null,
                if (pos.x < Int.MAX_VALUE) ChunkPos(pos.x + 1, pos.z) else null,
                if (pos.z > Int.MIN_VALUE) ChunkPos(pos.x, pos.z - 1) else null,
                if (pos.z < Int.MAX_VALUE) ChunkPos(pos.x, pos.z + 1) else null
            )
            neighbors.forEach { if (unseen.remove(it)) queue.add(it) }
        }
        require(unseen.isEmpty()) { "Embassy chunks must form one connected area with shared edges" }
    }

    private fun requireEmptyParcel(host: MfFactionId, worldId: UUID, x: Int, z: Int) {
        val claim = requireHostClaim(host, worldId, x, z)
        require(plugin.services.lockService.getLockedBlocks(claim).isEmpty()) {
            "Remove existing block locks before offering or accepting this parcel"
        }
        require(!plugin.services.gateService.hasGateAreaInChunk(worldId, x, z)) {
            "Remove existing gates that change blocks in this parcel"
        }
    }

    private fun sameAgreement(first: MfEmbassy, second: MfEmbassy): Boolean =
        first.worldId == second.worldId && first.hostId == second.hostId && first.guestId == second.guestId

    private fun effectiveRows(now: Long): List<MfEmbassy> = byChunk.values.mapNotNull { effective(it, now) }

    /** Host cancellation or 14-day revocation notice. A second notice is idempotent. */
    fun revoke(
        host: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<MfEmbassy?, ServiceFailure> = endBy(host, worldId, chunkX, chunkZ, hostActs = true)

    /** Guest rejection or 14-day voluntary departure notice. A second notice is idempotent. */
    fun release(
        guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<MfEmbassy?, ServiceFailure> = endBy(guest, worldId, chunkX, chunkZ, hostActs = false)

    /** Reject only the pending initial offer or expansion, preserving an already active area. */
    fun decline(
        guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            sweepLocked(clock.millis())
            val row = requireNotNull(byChunk[Key(worldId, chunkX, chunkZ)]) { "No embassy offer at this chunk" }
            require(row.guestId == guest && row.status == MfEmbassyStatus.OFFERED) {
                "Only the invited guest may decline a pending offer"
            }
            val offers = byChunk.values.filter { sameAgreement(it, row) && it.status == MfEmbassyStatus.OFFERED }
            applyChanges(emptyList(), offers)
            Unit
        }.mapFailure(::failure)
    }

    /** Guest confirms the ordinary agreement is cleared; conquest passage is finished per cell. */
    fun finish(
        guest: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int
    ): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            sweepLocked(clock.millis())
            val row = requireNotNull(byChunk[Key(worldId, chunkX, chunkZ)]) { "No embassy at this chunk" }
            require(row.guestId == guest &&
                (row.status == MfEmbassyStatus.CLEARING || row.status == MfEmbassyStatus.CONQUEST_PASSAGE)) {
                "Only the guest may finish a clearing embassy"
            }
            val cleared = if (row.status == MfEmbassyStatus.CONQUEST_PASSAGE) listOf(row)
                else byChunk.values.filter { sameAgreement(it, row) && it.status == MfEmbassyStatus.CLEARING }
            applyChanges(emptyList(), cleared)
            Unit
        }.mapFailure(::failure)
    }

    /** Reconciles conquest and war state, then retires expired offers and retrieval windows. */
    fun sweep(): Result4k<Int, ServiceFailure> = mutationLock.withLock {
        resultFrom { sweepLocked(clock.millis()) }.mapFailure(::failure)
    }

    /**
     * Called after a claim write commits. A new owner gets a seven-day decision window; old guest
     * build rights stop immediately. Null ownership means the charter has no parcel to protect.
     */
    fun invalidateClaim(worldId: UUID, chunkX: Int, chunkZ: Int): Result4k<Boolean, ServiceFailure> =
        mutationLock.withLock {
            resultFrom {
                val row = byChunk[Key(worldId, chunkX, chunkZ)] ?: return@resultFrom false
                val owner = plugin.services.claimService.getClaim(worldId, chunkX, chunkZ)?.factionId
                if (owner == currentLandholder(row)) {
                    return@resultFrom false
                }
                if (row.status == MfEmbassyStatus.OFFERED) {
                    val offers = byChunk.values.filter { sameAgreement(it, row) && it.status == MfEmbassyStatus.OFFERED }
                    applyChanges(emptyList(), offers)
                    noticeOwnerChange(row, null)
                    return@resultFrom true
                }
                changeOwner(row, owner, clock.millis())
                true
            }.mapFailure(::failure)
        }

    /** Conqueror elects immediate seizure or gives the guest fourteen days to retrieve property. */
    fun chooseConquest(
        conqueror: MfFactionId, worldId: UUID, chunkX: Int, chunkZ: Int, passage: Boolean
    ): Result4k<MfEmbassy?, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val now = clock.millis()
            sweepLocked(now)
            val row = requireNotNull(byChunk[Key(worldId, chunkX, chunkZ)]) { "No conquered embassy at this chunk" }
            require(row.status == MfEmbassyStatus.CONQUEST_DECISION && row.conquerorId == conqueror) {
                "Only the current conqueror may decide this embassy"
            }
            require(plugin.services.claimService.getClaim(worldId, chunkX, chunkZ)?.factionId == conqueror) {
                "Conqueror no longer owns this chunk"
            }
            if (!passage) {
                remove(row)
                null
            } else {
                row.copy(status = MfEmbassyStatus.CONQUEST_PASSAGE,
                    changedAt = now, deadlineAt = Math.addExact(now, CLEARING_MILLIS),
                    pausedAt = if (atWar(conqueror, row.guestId)) now else null)
                    .also(::publish)
            }
        }.mapFailure(::failure)
    }

    /** Persist war start/end for every affected cell together; failure keeps a conservative clock fence. */
    fun onWarStateChanged(
        first: MfFactionId, second: MfFactionId, atWar: Boolean
    ): Result4k<Int, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val now = clock.millis()
            val upserts = mutableListOf<MfEmbassy>()
            val deletes = mutableListOf<MfEmbassy>()
            val notices = mutableListOf<Pair<MfEmbassy, Boolean>>()
            byChunk.values.filter { row ->
                row.status == MfEmbassyStatus.CLEARING || row.status == MfEmbassyStatus.CONQUEST_PASSAGE ||
                    row.status == MfEmbassyStatus.CONQUEST_DECISION
            }.filter { row ->
                (currentLandholder(row) == first && row.guestId == second) ||
                    (currentLandholder(row) == second && row.guestId == first)
            }.forEach { stored ->
                val row = applyClockFence(stored, now)
                val deadline = requireNotNull(row.deadlineAt)
                val next = when (row.status) {
                    MfEmbassyStatus.CONQUEST_DECISION -> when {
                        atWar && now < deadline -> if (row.pausedAt == null) row.copy(pausedAt = now) else row
                        atWar && row.pausedAt != null -> row.copy(
                            status = MfEmbassyStatus.CONQUEST_PASSAGE, changedAt = deadline,
                            deadlineAt = Math.addExact(deadline, CLEARING_MILLIS), pausedAt = deadline
                        )
                        atWar && now < Math.addExact(deadline, CLEARING_MILLIS) -> row.copy(
                            status = MfEmbassyStatus.CONQUEST_PASSAGE, changedAt = deadline,
                            deadlineAt = Math.addExact(deadline, CLEARING_MILLIS), pausedAt = now
                        )
                        atWar -> null
                        row.pausedAt != null && now >= deadline -> row.copy(
                            status = MfEmbassyStatus.CONQUEST_PASSAGE, changedAt = now,
                            deadlineAt = Math.addExact(now, CLEARING_MILLIS), pausedAt = null
                        )
                        row.pausedAt != null -> row.copy(pausedAt = null)
                        else -> row
                    }
                    MfEmbassyStatus.CLEARING, MfEmbassyStatus.CONQUEST_PASSAGE -> when {
                        atWar && row.pausedAt == null && now < deadline -> row.copy(pausedAt = now)
                        !atWar && row.pausedAt != null -> row.copy(
                            deadlineAt = Math.addExact(deadline, now - row.pausedAt),
                            pausedAt = null, changedAt = now
                        )
                        else -> row
                    }
                    else -> row
                }
                if (next == null) {
                    deletes.add(stored)
                    notices.add(stored to false)
                } else if (next != stored) {
                    // Record before persistence: a failed pause write must not consume recovery.
                    if (atWar && next.pausedAt != null) warClockFences.putIfAbsent(Key(stored), ClockFence(stored, next))
                    upserts.add(next)
                    if (stored.status == MfEmbassyStatus.CONQUEST_DECISION &&
                        next.status == MfEmbassyStatus.CONQUEST_PASSAGE) notices.add(next to true)
                }
            }
            applyChanges(upserts, deletes)
            notices.forEach { (row, passage) -> if (passage) noticeDefaultPassage(row) else noticeExpiry(row) }
            upserts.size + deletes.size
        }.mapFailure(::failure)
    }

    private fun endBy(
        actor: MfFactionId, worldId: UUID, x: Int, z: Int, hostActs: Boolean
    ): Result4k<MfEmbassy?, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val now = clock.millis()
            sweepLocked(now)
            val row = requireNotNull(byChunk[Key(worldId, x, z)]) { "No embassy at this chunk" }
            require(actor == (if (hostActs) row.hostId else row.guestId)) { "Faction is not a party to this embassy" }
            require(row.status != MfEmbassyStatus.CONQUEST_DECISION && row.status != MfEmbassyStatus.CONQUEST_PASSAGE) {
                "Conquest has its own decision and passage process"
            }
            val agreement = byChunk.values.filter { sameAgreement(it, row) }
            val offered = agreement.filter { it.status == MfEmbassyStatus.OFFERED }
            if (hostActs && row.status == MfEmbassyStatus.OFFERED) {
                applyChanges(emptyList(), offered)
                return@resultFrom null
            }
            val clearing = agreement.filter { it.status == MfEmbassyStatus.ACTIVE }.map { active ->
                active.copy(status = MfEmbassyStatus.CLEARING, changedAt = now,
                    deadlineAt = Math.addExact(now, CLEARING_MILLIS), pausedAt = if (atWar(active)) now else null)
            }
            applyChanges(clearing, offered)
            val resulting = clearing + agreement.filter { it.status == MfEmbassyStatus.CLEARING }
            resulting.firstOrNull { it.chunkX == x && it.chunkZ == z } ?: resulting.firstOrNull()
        }.mapFailure(::failure)
    }

    private fun effective(row: MfEmbassy?, now: Long): MfEmbassy? {
        if (row == null) return null
        val owner = plugin.services.claimService.getClaim(row.worldId, row.chunkX, row.chunkZ)?.factionId ?: return null
        if (owner != currentLandholder(row)) {
            // The claim can commit before our separate conquest write succeeds. Keep the property
            // closed even across a failed write or restart, until sweep persists the new decision.
            if (row.status == MfEmbassyStatus.OFFERED || owner == row.guestId ||
                plugin.services.factionService.getFaction(owner) == null ||
                plugin.services.factionService.getFaction(row.guestId) == null) return null
            return row.copy(status = MfEmbassyStatus.CONQUEST_DECISION, conquerorId = owner,
                changedAt = now, deadlineAt = Long.MAX_VALUE, pausedAt = null)
        }
        if (plugin.services.factionService.getFaction(currentLandholder(row)) == null ||
            plugin.services.factionService.getFaction(row.guestId) == null) return null
        val reconciled = reconcileWar(applyClockFence(row, now), now)
        val deadline = reconciled.deadlineAt
        if (reconciled.status == MfEmbassyStatus.CONQUEST_DECISION) {
            if (now < requireNotNull(deadline)) return reconciled
            val passageDeadline = Math.addExact(deadline, CLEARING_MILLIS)
            val pauseStart = when {
                reconciled.pausedAt != null -> deadline
                atWar(reconciled) && now < passageDeadline -> now
                else -> null
            }
            if (pauseStart == null && now >= passageDeadline) return null
            val passage = reconciled.copy(status = MfEmbassyStatus.CONQUEST_PASSAGE,
                changedAt = deadline, deadlineAt = passageDeadline, pausedAt = pauseStart)
            return reconcileWar(passage, now)
        }
        if (deadline == null || reconciled.pausedAt != null || now < deadline) return reconciled
        return null
    }

    private fun sweepLocked(now: Long): Int {
        val upserts = mutableListOf<MfEmbassy>()
        val deletes = mutableListOf<MfEmbassy>()
        val notices = mutableListOf<() -> Unit>()
        // An offer is one promise over every pending cell. Losing one cell cancels the batch,
        // rather than silently activating a smaller or disconnected area.
        val cancelledOfferPairs = byChunk.values.filter { it.status == MfEmbassyStatus.OFFERED }
            .groupBy { it.hostId to it.guestId }.filterValues { offer ->
                offer.size != offer.first().offerSize || offer.any { effective(it, now) == null }
            }.keys
        byChunk.values.forEach { row ->
            if (row.status == MfEmbassyStatus.OFFERED && (row.hostId to row.guestId) in cancelledOfferPairs) {
                deletes.add(row)
                notices.add {
                    if (now >= requireNotNull(row.deadlineAt)) noticeExpiry(row) else noticeOwnerChange(row, null)
                }
                return@forEach
            }
            val owner = plugin.services.claimService.getClaim(row.worldId, row.chunkX, row.chunkZ)?.factionId
            if (owner != currentLandholder(row)) {
                val changed = ownerChangedRow(row, owner, now)
                if (changed == null) deletes.add(row) else upserts.add(changed)
                notices.add { noticeOwnerChange(row, changed) }
            } else {
                val effective = effective(row, now)
                when {
                    effective == null -> {
                        deletes.add(row)
                        notices.add { noticeExpiry(row) }
                    }
                    effective != row -> {
                        upserts.add(effective)
                        if (row.status == MfEmbassyStatus.CONQUEST_DECISION &&
                            effective.status == MfEmbassyStatus.CONQUEST_PASSAGE) {
                            notices.add { noticeDefaultPassage(effective) }
                        }
                    }
                }
            }
        }
        applyChanges(upserts, deletes)
        notices.forEach { it() }
        return upserts.size + deletes.size
    }

    private fun ownerChangedRow(row: MfEmbassy, owner: MfFactionId?, now: Long): MfEmbassy? {
        if (owner == null || owner == row.guestId || row.status == MfEmbassyStatus.OFFERED ||
            plugin.services.factionService.getFaction(row.guestId) == null) return null
        return row.copy(status = MfEmbassyStatus.CONQUEST_DECISION,
            changedAt = now, deadlineAt = Math.addExact(now, CONQUEST_DECISION_MILLIS),
            conquerorId = owner, pausedAt = if (atWar(owner, row.guestId)) now else null)
    }

    private fun changeOwner(row: MfEmbassy, owner: MfFactionId?, now: Long) {
        val changed = ownerChangedRow(row, owner, now)
        if (changed == null) remove(row) else publish(changed)
        noticeOwnerChange(row, changed)
    }

    private fun noticeOwnerChange(old: MfEmbassy, changed: MfEmbassy?) {
        if (changed == null) notice(old, "Embassy ended",
            "The parcel at ${parcel(old)} changed ownership or lost a party; its embassy agreement ended.")
        else notice(changed, "Embassy land conquered",
            "The former embassy at ${parcel(changed)} changed landholder. The guest's property is closed while the new landholder has 7 days to choose seizure or passage.")
    }

    private fun applyClockFence(row: MfEmbassy, now: Long): MfEmbassy {
        val key = Key(row)
        warClockFences[key]?.let { return if (it.base == row) it.fenced else row }
        if (!startupRecoveryCandidates.contains(key)) return row
        if (!atWar(row)) {
            startupRecoveryCandidates.remove(key)
            return row
        }
        return warClockFences.computeIfAbsent(key) {
            val deadline = requireNotNull(row.deadlineAt)
            val missedDeadline = now >= deadline
            val preserved = if (row.status == MfEmbassyStatus.CONQUEST_DECISION && missedDeadline) {
                row.copy(status = MfEmbassyStatus.CONQUEST_PASSAGE, changedAt = now,
                    deadlineAt = Math.addExact(now, CLEARING_MILLIS), pausedAt = now)
            } else row.copy(pausedAt = now,
                deadlineAt = if (missedDeadline) Math.addExact(now, CLEARING_MILLIS) else deadline)
            plugin.logger.warning("Recovered an unpaused wartime embassy clock at ${parcel(row)}; " +
                (if (missedDeadline) "the former deadline passed, so a full 14-day peaceful recovery allowance was retained. "
                else "the remaining recovery allowance was retained. ") +
                "Sweep will retry persistence; inspect storage errors if this repeats after restart.")
            ClockFence(row, preserved)
        }.let { if (it.base == row) it.fenced else row }
    }

    private fun currentLandholder(row: MfEmbassy): MfFactionId = row.conquerorId ?: row.hostId

    private fun reconcileWar(row: MfEmbassy, now: Long): MfEmbassy {
        if (row.status != MfEmbassyStatus.CLEARING && row.status != MfEmbassyStatus.CONQUEST_PASSAGE) return row
        val deadline = requireNotNull(row.deadlineAt)
        val war = atWar(row)
        return when {
            war && row.pausedAt == null && now < deadline -> row.copy(pausedAt = now)
            !war && row.pausedAt != null -> row.copy(
                deadlineAt = Math.addExact(deadline, now - row.pausedAt), pausedAt = null, changedAt = now)
            else -> row
        }
    }

    private fun publish(row: MfEmbassy) = applyChanges(listOf(row), emptyList())

    private fun remove(row: MfEmbassy) = applyChanges(emptyList(), listOf(row))

    private fun applyChanges(upserts: List<MfEmbassy>, deletes: List<MfEmbassy>) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        upserts.forEach(::validateStored)
        require(upserts.size == upserts.distinctBy(::Key).size) { "Duplicate embassy batch cell" }
        repository.applyChanges(upserts, deletes)
        val next = byChunk.toMutableMap()
        deletes.forEach { next.remove(Key(it)) }
        upserts.forEach { next[Key(it)] = it }
        byChunk = next.toMap()
        (upserts + deletes).forEach {
            warClockFences.remove(Key(it))
            startupRecoveryCandidates.remove(Key(it))
        }
    }

    private fun noticeDefaultPassage(row: MfEmbassy) = notice(row, "Embassy passage",
        "The decision period at ${parcel(row)} ended and a 14-day guest recovery period began. War pauses that recovery clock.")

    private fun noticeExpiry(row: MfEmbassy) {
        val message = when (row.status) {
            MfEmbassyStatus.OFFERED -> "The embassy offer at ${parcel(row)} expired."
            MfEmbassyStatus.CLEARING -> "The embassy clearing period at ${parcel(row)} ended; ordinary host rights resumed."
            MfEmbassyStatus.CONQUEST_PASSAGE -> "The guest's property recovery period at ${parcel(row)} ended."
            MfEmbassyStatus.CONQUEST_DECISION -> "The conquered embassy recovery period at ${parcel(row)} ended."
            MfEmbassyStatus.ACTIVE -> return
        }
        notice(row, "Embassy period ended", message)
    }

    /** Faction messaging handles online players and offline mail; always enter it on the server thread. */
    private fun notice(row: MfEmbassy, title: String, message: String) {
        val recipients = listOfNotNull(row.hostId, row.guestId, row.conquerorId).distinct()
        plugin.server.scheduler.runTask(plugin, Runnable {
            recipients.mapNotNull { factionId -> plugin.services.factionService.getFaction(factionId) }
                .forEach { it.sendMessage(title, message) }
        })
    }

    private fun parcel(row: MfEmbassy): String = "${row.worldId}:${row.chunkX},${row.chunkZ}"

    private fun requireHostClaim(host: MfFactionId, worldId: UUID, x: Int, z: Int): MfClaimedChunk {
        val claim = requireNotNull(plugin.services.claimService.getClaim(worldId, x, z)) { "Chunk is unclaimed" }
        require(claim.factionId == host) { "Host no longer owns this chunk" }
        return claim
    }

    private fun preflight(worldId: UUID, x: Int, z: Int, host: MfFactionId, guest: MfFactionId, accepting: Boolean) {
        val event = EmbassyOfferAttemptEvent(worldId, x, z, FactionId(host.value), FactionId(guest.value),
            accepting, !plugin.server.isPrimaryThread)
        ChildMutationCallbackGuard.callEvent(plugin, event)
        if (event.isCancelled) throw EventCancelledException("Embassy parcel was refused by another landholder")
    }

    private fun requireParcelChecksAvailable() {
        require(newAgreementBlock == null) { requireNotNull(newAgreementBlock) }
        require(!ChildMutationCallbackGuard.isActive()) { "Embassy changes cannot run inside a land-check callback" }
        require(plugin.server.isPrimaryThread) { "Embassy offer and acceptance must run on the server thread" }
        val manager = plugin.server.pluginManager
        for (name in listOf("Fiefs", "PatriamReligion", "PatriamNomads")) {
            val integration = manager.getPlugin(name)
            require(integration == null || integration.isEnabled) {
                "$name is installed but unavailable; embassy parcel checks are closed"
            }
        }
    }

    private fun atWar(row: MfEmbassy): Boolean = atWar(currentLandholder(row), row.guestId)

    private fun atWar(host: MfFactionId, guest: MfFactionId): Boolean {
        val relationships = plugin.services.factionRelationshipService
        return relationships.getRelationships(host, guest).any { it.type == MfFactionRelationshipType.AT_WAR } ||
            relationships.getRelationships(guest, host).any { it.type == MfFactionRelationshipType.AT_WAR }
    }

    private fun validateStored(row: MfEmbassy) = row.validateStoredEmbassy()

    private fun failure(exception: Exception) = ServiceFailure(
        when (exception) {
            is IllegalArgumentException, is IllegalStateException, is EventCancelledException -> ServiceFailureType.RULES_VIOLATION
            else -> ServiceFailureType.GENERAL
        },
        "Embassy operation failed: ${exception.message}", exception
    )

    companion object {
        const val OFFER_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        const val CLEARING_MILLIS: Long = 14L * 24 * 60 * 60 * 1000
        const val CONQUEST_DECISION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        private val PARCEL_ACTIONS = setOf(
            ClaimAction.BUILD, ClaimAction.BREAK, ClaimAction.INTERACT, ClaimAction.DOOR,
            ClaimAction.CONTAINER, ClaimAction.BUCKET
        )
        private val RECOVERY_ACTIONS = setOf(
            ClaimAction.BREAK, ClaimAction.DOOR, ClaimAction.CONTAINER
        )
    }
}
