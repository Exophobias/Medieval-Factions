package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.siege.fenceSiegeMutation
import com.dansplugins.factionsystem.siege.commitSiegeRepository
import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level.SEVERE
import kotlin.concurrent.withLock

class MfGateService(
    private val plugin: MedievalFactions,
    private val gateRepo: MfGateRepository,
    private val gateCreationContextRepo: MfGateCreationContextRepository
) {

    /** Serialises repository writes with publication to every gate index. */
    private val mutationLock = ReentrantLock(true)

    /** Coordinate gate writes with activation of an embassy over the gate's block area. */
    internal fun <T> withMutationLock(action: () -> T): T = plugin.fenceSiegeMutation { mutationLock.withLock(action) }

    /** Factions whose parent row is being cascade-deleted; guarded by [mutationLock]. */
    private val deletingFactions = HashSet<MfFactionId>()

    private data class GateChunkKey(val worldId: UUID, val chunkX: Int, val chunkZ: Int)

    private val gatesById: MutableMap<MfGateId, MfGate> = ConcurrentHashMap()

    // Spatial indexes maintained alongside gatesById so location lookups don't scan every gate.
    // triggerIndex: exact trigger block position -> gate ids (O(1) getGatesByTrigger).
    // areaChunkIndex: chunk key -> ids of gates whose area overlaps that chunk (O(gates in the chunk) getGatesAt).
    private val triggerIndex: MutableMap<MfBlockPosition, MutableSet<MfGateId>> = ConcurrentHashMap()
    private val areaChunkIndex: MutableMap<GateChunkKey, MutableSet<MfGateId>> = ConcurrentHashMap()

    val gates: List<MfGate>
        get() = gatesById.values.toList()

    /** No repository call on the actuation/world path. */
    fun actuatorCurrent(gate: MfGate): Boolean = gatesById[gate.id]?.let {
        it.version == gate.version && it.status == gate.status && it.status != MfGateStatus.DISRUPTED && !reservationByGate.containsKey(gate.id)
    } == true

    private class Reservation(val record: MfGateDisruption, phase: String) {
        val phase = java.util.concurrent.atomic.AtomicReference(phase)
    }
    private val reservations = ConcurrentHashMap<UUID, Reservation>()
    private val reservationByGate = ConcurrentHashMap<MfGateId, UUID>()
    private val committedByGate = ConcurrentHashMap<MfGateId, MfGateDisruption>()

    fun disruptionMetadata(gateId: MfGateId): Pair<String, String>? {
        reservationByGate[gateId]?.let { operation ->
            reservations[operation]?.let { return operation.toString() to it.phase.get() }
        }
        return committedByGate[gateId]?.takeIf {
            gatesById[gateId] == it.original.copy(version = it.original.version + 1, status = MfGateStatus.DISRUPTED)
        }?.let { it.operationId.toString() to "COMMITTED" }
    }

    fun prepareDisruption(operationId: UUID, gateId: MfGateId, expectedRevision: Int): MfGate = plugin.fenceSiegeMutation {
        mutationLock.withLock {
            gateRepo.getGateDisruption(operationId)?.let { prior ->
                requireIdentity(prior, gateId, expectedRevision)
                if (prior.phase == "COMMITTED") return@withLock prior.original.copy(version = expectedRevision + 1, status = MfGateStatus.DISRUPTED)
                require(prior.phase == "PREPARED" && reservations[operationId]?.phase?.get() == "PREPARED") { "Gate disruption needs explicit review" }
                return@withLock prior.original
            }
            require(!reservationByGate.containsKey(gateId)) { "Gate has an unresolved disruption" }
            val live = requireNotNull(gatesById[gateId]) { "Unknown gate" }
            require(live.version == expectedRevision && live.status == MfGateStatus.CLOSED) { "Gate revision/state changed" }
            val min = live.area.minPosition; val max = live.area.maxPosition
            require(Math.multiplyExact(Math.multiplyExact(max.x.toLong() - min.x + 1, max.y.toLong() - min.y + 1), max.z.toLong() - min.z + 1) in 1..512) {
                "Gate exceeds supported siege footprint"
            }
            require(live.factionId !in deletingFactions) { "Gate owner is being deleted" }
            val prepared = plugin.commitSiegeRepository { gateRepo.prepareDisruption(operationId, live) }
            check(prepared.phase == "PREPARED" && prepared.original == live)
            reservations[operationId] = Reservation(prepared, "PREPARED")
            reservationByGate[gateId] = operationId
            live
        }
    }

    /** The caller has already checked battle, actor and WorldGuard under its combined lease. */
    fun activateDisruption(operationId: UUID, gateId: MfGateId, expectedRevision: Int): MfGate {
        check(plugin.server.isPrimaryThread) { "Gate activation belongs to the server thread" }
        com.dansplugins.factionsystem.siege.NativeFenceRegistry.get(plugin).requireEffectLease()
        val reservation = requireNotNull(reservations[operationId]) { "No runtime gate preparation" }
        requireIdentity(reservation.record, gateId, expectedRevision)
        val original = reservation.record.original
        val phase = reservation.phase.get()
        if (phase == "ACTIVATED") return requireNotNull(gatesById[gateId]).also {
            check(it == original.copy(version = expectedRevision + 1, status = MfGateStatus.DISRUPTED))
        }
        require(phase == "PREPARED" && gatesById[gateId] == original) { "Gate preparation changed or requires review" }
        val activated = original.copy(version = expectedRevision + 1, status = MfGateStatus.DISRUPTED)
        check(reservation.phase.compareAndSet("PREPARED", "ACTIVATED")) { "Gate activation raced" }
        gatesById[gateId] = activated
        return activated
    }

    fun commitDisruption(operationId: UUID, gateId: MfGateId, expectedRevision: Int): MfGate =
        finishDisruption(operationId, gateId, expectedRevision, true, false)

    fun cancelDisruption(operationId: UUID, gateId: MfGateId, expectedRevision: Int): MfGate =
        finishDisruption(operationId, gateId, expectedRevision, false, false)

    /** Called only after the public provider verifies real-player staff permission on main. */
    fun beginDisruptionReview(operationId: UUID, gateId: MfGateId, expectedRevision: Int) {
        com.dansplugins.factionsystem.siege.NativeFenceRegistry.get(plugin).requireEffectLease()
        val reservation = requireNotNull(reservations[operationId]) { "No unresolved native gate reservation" }
        requireIdentity(reservation.record, gateId, expectedRevision)
        require(reservation.phase.get() in setOf("QUARANTINED", "ACTIVATED", "PREPARED")) { "Gate review already in progress" }
        reservation.phase.set("REVIEWING")
    }

    fun abandonDisruptionReview(operationId: UUID) {
        reservations[operationId]?.phase?.compareAndSet("REVIEWING", "QUARANTINED")
    }

    fun finishDisruptionReview(operationId: UUID, gateId: MfGateId, expectedRevision: Int, observedAfter: Boolean): MfGate =
        finishDisruption(operationId, gateId, expectedRevision, observedAfter, true)

    private fun finishDisruption(operationId: UUID, gateId: MfGateId, expectedRevision: Int, after: Boolean, review: Boolean): MfGate = plugin.fenceSiegeMutation {
        mutationLock.withLock {
            val reservation = reservations[operationId]
            if (reservation == null) {
                val terminal = requireNotNull(gateRepo.getGateDisruption(operationId)) { "Unknown native disruption" }
                requireIdentity(terminal, gateId, expectedRevision)
                require(terminal.phase == if (after) "COMMITTED" else "CANCELLED") { "No activated runtime preparation" }
                return@withLock if (after) terminal.original.copy(version = expectedRevision + 1, status = MfGateStatus.DISRUPTED) else terminal.original
            }
            requireIdentity(reservation.record, gateId, expectedRevision)
            require(reservation.phase.get() == if (review) "REVIEWING" else if (after) "ACTIVATED" else "PREPARED") {
                "Native gate state requires explicit review"
            }
            try {
                val terminal = plugin.commitSiegeRepository { gateRepo.finishDisruption(operationId, reservation.record.original, after) }
                check(terminal.phase == if (after) "COMMITTED" else "CANCELLED")
                val result = if (after) terminal.original.copy(version = expectedRevision + 1, status = MfGateStatus.DISRUPTED) else terminal.original
                gatesById[gateId] = result
                if (after) committedByGate[gateId] = terminal else committedByGate.remove(gateId)
                reservationByGate.remove(gateId, operationId)
                reservations.remove(operationId, reservation)
                result
            } catch (failure: Exception) {
                reservation.phase.set("QUARANTINED")
                throw failure
            }
        }
    }

    private fun requireIdentity(record: MfGateDisruption, gateId: MfGateId, revision: Int) {
        require(record.original.id == gateId && record.original.version == revision) { "Disruption operation identity differs" }
    }

    // Restricted block materials now comes from the config file
    val restrictedBlockMaterials: Set<Material>

    init {
        plugin.logger.info("Loading gates...")
        val startTime = System.currentTimeMillis()
        gatesById.putAll(gateRepo.getGates().associateBy { it.id })
        gatesById.values.forEach(::indexGate)
        gateRepo.getGateDisruptions().forEach { record ->
            if (record.phase == "PREPARED") {
                check(reservationByGate.putIfAbsent(record.original.id, record.operationId) == null) { "Duplicate unresolved gate reservations" }
                reservations[record.operationId] = Reservation(record, "QUARANTINED")
            } else if (record.phase == "COMMITTED" && gatesById[record.original.id] == record.original.copy(
                    version = record.original.version + 1, status = MfGateStatus.DISRUPTED)) {
                check(committedByGate.putIfAbsent(record.original.id, record) == null) { "Ambiguous committed gate receipt" }
            }
        }
        if (reservations.isNotEmpty()) plugin.logger.warning("${reservations.size} prepared gate disruptions quarantined; explicit observed-state staff review required")
        plugin.logger.info("${gatesById.size} gates loaded (${System.currentTimeMillis() - startTime}ms)")

        restrictedBlockMaterials = loadRestrictedBlocksFromConfig()
        plugin.logger.info("Loaded ${restrictedBlockMaterials.size} restricted block materials.")

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    updateGatesWithRestrictedBlocks()
                } catch (e: Exception) {
                    plugin.logger.log(SEVERE, "Error during gate material review:", e)
                }
            }
        )
    }

    fun getGatesByTrigger(trigger: MfBlockPosition): List<MfGate> =
        triggerIndex[trigger]?.mapNotNull(gatesById::get) ?: emptyList()

    fun getGatesAt(block: MfBlockPosition): List<MfGate> {
        val candidates = areaChunkIndex[GateChunkKey(block.worldId, block.x shr 4, block.z shr 4)] ?: return emptyList()
        return candidates.mapNotNull(gatesById::get).filter { it.area.contains(block) }
    }

    /** Indexed overlap query for an embassy parcel; a gate can change blocks remotely. */
    fun hasGateAreaInChunk(worldId: UUID, chunkX: Int, chunkZ: Int): Boolean =
        areaChunkIndex[GateChunkKey(worldId, chunkX, chunkZ)]?.isNotEmpty() == true

    @JvmName("getGatesByFactionId")
    fun getGatesByFaction(factionId: MfFactionId) = gatesById.values.filter { it.factionId == factionId }
    fun getGatesByStatus(status: MfGateStatus) = gatesById.values.filter { it.status == status && !reservationByGate.containsKey(it.id) }

    /**
     * Save a gate to the database with automatic retry on optimistic locking failures.
     * * This method implements a retry mechanism to handle concurrent updates to the same gate,
     * which commonly occurs during gate opening/closing animations where multiple async tasks
     * may attempt to save status changes simultaneously.
     * * On retry, the method re-fetches the current gate state from the database and re-applies
     * the intended status change. This is designed for the common case where only the status
     * field is being modified (e.g., gate.copy(status = OPENING)).
     * * @param gate The gate to save (typically with a status change)
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @return Result containing the saved gate or a ServiceFailure
     */
    fun save(gate: MfGate, maxRetries: Int = 3) = plugin.fenceSiegeMutation { mutationLock.withLock {
        resultFrom {
            require(!reservationByGate.containsKey(gate.id)) { "Gate has an unresolved disruption" }
            require(gate.factionId !in deletingFactions) { "Faction ${gate.factionId.value} is being deleted" }
            val previousOwner = gatesById[gate.id]?.factionId
            require(previousOwner == null || previousOwner !in deletingFactions) {
                "Faction ${previousOwner?.value} is being deleted"
            }
            require(
                areaChunkKeys(gate.area).none { key ->
                plugin.services.embassyService.hasActiveOrClearingEmbassy(key.worldId, key.chunkX, key.chunkZ)
            }
            ) { "A gate cannot change blocks inside an active embassy" }
            require(gatesById[gate.id]?.status != MfGateStatus.DISRUPTED || gate.status == MfGateStatus.DISRUPTED) {
                "A disrupted gate cannot be reactivated; rebuild and register a new gate"
            }
            var lastException: Exception? = null
            var currentGate = gate
            val targetStatus = gate.status // Preserve the intended status change

            repeat(maxRetries) { attempt ->
                try {
                    val result = plugin.commitSiegeRepository { gateRepo.upsert(currentGate) }
                    val previousGate = gatesById.put(result.id, result)
                    reindexGate(previousGate, result)
                    return@resultFrom result
                } catch (e: OptimisticLockingFailureException) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        // Re-fetch the current state from the database for next retry
                        val freshGate = gateRepo.getGate(currentGate.id) ?: throw e
                        // Apply the intended status change to the fresh gate state
                        currentGate = freshGate.copy(status = targetStatus)
                        // Small delay before retry to reduce contention (runs in async context)
                        Thread.sleep(50L * (attempt + 1))
                    }
                }
            }

            throw lastException ?: IllegalStateException("Retry failed without exception")
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    } }

    @JvmName("deleteGateByGateId")
    fun delete(gateId: MfGateId) = plugin.fenceSiegeMutation { mutationLock.withLock {
        resultFrom {
            require(!reservationByGate.containsKey(gateId)) { "Gate has an unresolved disruption" }
            val live = gatesById[gateId]
            require(live == null || live.factionId !in deletingFactions) {
                "Faction ${live?.factionId?.value} is being deleted"
            }
            val result = plugin.commitSiegeRepository { gateRepo.delete(gateId) }
            val removedGate = gatesById.remove(gateId)
            if (removedGate != null) {
                unindexGate(removedGate)
                committedByGate.remove(gateId)
            }
            return@resultFrom result
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    } }

    @JvmName("deleteAllGatesByFactionId")
    fun deleteAllGates(factionId: MfFactionId) = plugin.fenceSiegeMutation { mutationLock.withLock {
        resultFrom {
            require(reservations.values.none { it.record.original.factionId == factionId }) { "Faction has unresolved gate disruptions" }
            require(factionId !in deletingFactions) { "Faction ${factionId.value} is being deleted" }
            val result = plugin.commitSiegeRepository { gateRepo.deleteAll(factionId) }
            evictAllGatesLocked(factionId)
            return@resultFrom result
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    } }

    /** Mirror the gate rows removed by a successful faction-delete cascade. */
    internal fun evictAllGates(factionId: MfFactionId) = plugin.fenceSiegeMutation { mutationLock.withLock {
        evictAllGatesLocked(factionId)
    } }

    private fun evictAllGatesLocked(factionId: MfFactionId) {
        val gatesToDelete = gatesById.filterValues { it.factionId == factionId }
        gatesToDelete.forEach { (key, value) ->
            if (gatesById.remove(key, value)) {
                committedByGate.remove(key)
                unindexGate(value)
            }
        }
    }

    internal fun blockFactionDeletion(factionId: MfFactionId) = plugin.fenceSiegeMutation { mutationLock.withLock {
        check(reservations.values.none { it.record.original.factionId == factionId }) { "Faction has unresolved gate disruptions; staff must review them before deletion" }
        check(deletingFactions.add(factionId)) { "Faction ${factionId.value} is already being deleted" }
    } }

    internal fun unblockFactionDeletion(factionId: MfFactionId) = plugin.fenceSiegeMutation { mutationLock.withLock {
        deletingFactions.remove(factionId)
    } }

    @JvmName("getGateCreationContextByPlayerId")
    fun getGateCreationContext(playerId: MfPlayerId) = resultFrom {
        gateCreationContextRepo.getContext(playerId)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    fun save(ctx: MfGateCreationContext) = resultFrom {
        gateCreationContextRepo.upsert(ctx)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmName("deleteGateCreationContextByPlayerId")
    fun deleteGateCreationContext(playerId: MfPlayerId) = resultFrom {
        gateCreationContextRepo.delete(playerId)
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    private fun indexGate(gate: MfGate) {
        triggerIndex.computeIfAbsent(gate.trigger) { ConcurrentHashMap.newKeySet() }.add(gate.id)
        areaChunkKeys(gate.area).forEach { chunkKey ->
            areaChunkIndex.computeIfAbsent(chunkKey) { ConcurrentHashMap.newKeySet() }.add(gate.id)
        }
    }

    private fun unindexGate(gate: MfGate) {
        triggerIndex.computeIfPresent(gate.trigger) { _, ids ->
            ids.remove(gate.id)
            if (ids.isEmpty()) null else ids
        }
        areaChunkKeys(gate.area).forEach { chunkKey ->
            areaChunkIndex.computeIfPresent(chunkKey) { _, ids ->
                ids.remove(gate.id)
                if (ids.isEmpty()) null else ids
            }
        }
    }

    // Re-index only when a gate's spatial anchors actually change. Gate saves are dominated by
    // status-only updates during open/close animations, which leave trigger/area untouched.
    private fun reindexGate(previous: MfGate?, current: MfGate) {
        if (previous != null && previous.trigger == current.trigger && previous.area == current.area) return
        if (previous != null) {
            unindexGate(previous)
        }
        indexGate(current)
    }

    private fun areaChunkKeys(area: MfCuboidArea): List<GateChunkKey> {
        val min = area.minPosition
        val max = area.maxPosition
        val keys = ArrayList<GateChunkKey>()
        for (chunkX in (min.x shr 4)..(max.x shr 4)) {
            for (chunkZ in (min.z shr 4)..(max.z shr 4)) {
                keys.add(GateChunkKey(min.worldId, chunkX, chunkZ))
            }
        }
        return keys
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }

    private fun updateGatesWithRestrictedBlocks() {
        val gateService = plugin.services.gateService

        gates.forEach { gate ->
            if (gate.material in restrictedBlockMaterials) {
                plugin.logger.info("Deleting gate with ID: ${gate.id} as it uses a restricted block material: ${gate.material}")

                gateService.delete(gate.id).onFailure {
                    plugin.logger.log(SEVERE, "Failed to delete gate with ID: ${gate.id}.")
                    return@forEach
                }
            }
        }

        plugin.logger.info("Gate material review and deletion completed.")
    }

    private fun loadRestrictedBlocksFromConfig(): Set<Material> {
        val blockNames = plugin.config.getStringList("gates.restrictedBlocks")
        return blockNames.mapNotNull { blockName ->
            try {
                Material.valueOf(blockName)
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid block material in config: $blockName")
                null
            }
        }.toSet()
    }
}
