package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent
import com.dansplugins.factionsystem.area.MfPosition
import com.dansplugins.factionsystem.event.faction.FactionCreateEvent
import com.dansplugins.factionsystem.event.faction.FactionDescriptionChangeEvent
import com.dansplugins.factionsystem.event.faction.FactionDisbandEvent
import com.dansplugins.factionsystem.event.faction.FactionJoinEvent
import com.dansplugins.factionsystem.event.faction.FactionLeaveEvent
import com.dansplugins.factionsystem.event.faction.FactionPrefixChangeEvent
import com.dansplugins.factionsystem.event.faction.FactionRenameEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.field.MfFactionField
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.failure.ServiceFailureType.CONFLICT
import com.dansplugins.factionsystem.failure.ServiceFailureType.GENERAL
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An [MfPlayerId] as a Bukkit UUID, or null if it does not parse as one.
 *
 * Null rather than throwing because a malformed id is a data problem in one row, and the callers
 * here are reporting a change that has already been persisted. Failing the report is strictly worse
 * than omitting it, since throwing from a save would roll an ordinary /f leave back.
 */
private fun MfPlayerId.toUuidOrNull(): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

class MfFactionService(private val plugin: MedievalFactions, private val repository: MfFactionRepository) {

    private val factionsById: MutableMap<MfFactionId, MfFaction> = ConcurrentHashMap()
    val factions: List<MfFaction>
        get() = factionsById.values.toList()

    private val _fields: MutableList<MfFactionField> = CopyOnWriteArrayList()
    val fields: List<MfFactionField>
        get() = _fields

    /**
     * Third-party rules for who inherits a faction whose head has departed.
     *
     * Lives on the service rather than on the plugin for the same reason `claimOverrides` lives on
     * MfClaimService: it belongs to the thing whose decision it modifies, and succession is decided
     * inside [save]. See [SuccessionPolicyRegistry] for what a policy may and may not answer.
     */
    val successionPolicies = SuccessionPolicyRegistry(plugin.logger)

    init {
        plugin.logger.info("Loading factions...")
        var startTime = System.currentTimeMillis()
        factionsById.putAll(repository.getFactions().associateBy(MfFaction::id))
        plugin.logger.info("${factionsById.size} factions loaded (${System.currentTimeMillis() - startTime}ms)")
        if (!plugin.config.getBoolean("factions.allowNeutrality")) {
            plugin.logger.info("Disabling neutrality for existing factions due to config setting...")
            startTime = System.currentTimeMillis()
            val updatedFactions = factions.filter { it.flags[plugin.flags.isNeutral] }.map { faction ->
                save(faction.copy(flags = faction.flags + (plugin.flags.isNeutral to false))).onFailure { throw it.reason.cause }
            }.associateBy(MfFaction::id)
            if (updatedFactions.isNotEmpty()) {
                factionsById.putAll(updatedFactions)
                plugin.logger.info("Updated neutrality setting for ${updatedFactions.size} factions (${System.currentTimeMillis() - startTime}ms)")
            } else {
                plugin.logger.info("No factions required updating.")
            }
        }
    }

    fun getFaction(name: String): MfFaction? = factions.singleOrNull { it.name == name }

    @JvmName("getFactionByPlayerId")
    fun getFaction(playerId: MfPlayerId): MfFaction? = factions.singleOrNull { faction ->
        faction.members.any { member -> member.playerId == playerId }
    }

    @JvmName("getFactionByFactionId")
    fun getFaction(factionId: MfFactionId): MfFaction? = factionsById[factionId]

    fun save(faction: MfFaction): Result4k<MfFaction, ServiceFailure> = resultFrom {
        val previousState = getFaction(faction.id)
        var factionToSave = faction
        // A ruler may name the head of one of their vassals as heir, and a player belongs to exactly
        // one faction, so that heir has to leave their own to take this one. Resolved before the
        // events below so the heir's arrival is announced like any other, and before the succession
        // rule below so it needs no special case: by then the heir is simply a member.
        if (previousState != null) {
            factionToSave = withVassalHeirAscended(factionToSave)
        }
        if (previousState == null) {
            val event = FactionCreateEvent(faction.id, faction, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) {
                throw EventCancelledException("Event cancelled")
            }
            factionToSave = event.faction
        } else {
            if (previousState.name != faction.name) {
                val event = FactionRenameEvent(faction.id, faction.name, !plugin.server.isPrimaryThread)
                plugin.server.pluginManager.callEvent(event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
            }
            if (previousState.description != faction.description) {
                val event = FactionDescriptionChangeEvent(faction.id, faction.description, !plugin.server.isPrimaryThread)
                plugin.server.pluginManager.callEvent(event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
            }
            if (previousState.prefix != faction.prefix) {
                val event = FactionPrefixChangeEvent(faction.id, faction.prefix, !plugin.server.isPrimaryThread)
                plugin.server.pluginManager.callEvent(event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
            }
            val newMembers = faction.members.map(MfFactionMember::playerId) - previousState.members.map(MfFactionMember::playerId)
            newMembers.forEach { newMember ->
                val event = FactionJoinEvent(faction.id, newMember, !plugin.server.isPrimaryThread)
                plugin.server.pluginManager.callEvent(event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
            }
            val oldMembers = previousState.members.map(MfFactionMember::playerId) - faction.members.map(MfFactionMember::playerId)
            oldMembers.forEach { oldMember ->
                val event = FactionLeaveEvent(faction.id, oldMember, !plugin.server.isPrimaryThread)
                plugin.server.pluginManager.callEvent(event)
                if (event.isCancelled) {
                    throw EventCancelledException("Event cancelled")
                }
                val lockService = plugin.services.lockService
                lockService.getLockedBlocks(oldMember).forEach { lockedBlock ->
                    lockService.delete(lockedBlock.block)
                        .onFailure { failure -> throw failure.reason.cause }
                }
            }
        }
        // Every member-list change in MF funnels through here, so this is the one place that has to
        // notice a departed head of House and hand the faction on. See
        // MfFaction.withPrimaryOwnerSuccession.
        val successor = factionToSave.withPrimaryOwnerSuccession()
        // Stamp when the head came to the seat, here rather than at the five call sites that can move
        // one (/f create, /f transfer, /f admin setleader, succession, and the API's
        // setPrimaryOwner). One comparison in the write path cannot be forgotten by a sixth route
        // added later; five scattered assignments can, and the failure would be silent -- a head
        // whose recorded tenure dated from whenever the PREVIOUS one took over, which reads as a
        // long-standing ruler and clears every gate that asks how long they have held it.
        //
        // AFTER withPrimaryOwnerSuccession and after the events, and both matter. Succession moves
        // the head at this line, so a stamp taken earlier would miss the single most important case;
        // and FactionCreateEvent assigns event.faction back over factionToSave, so anything set
        // before it is discarded on the create path.
        //
        // Only when it actually changes. An ordinary save that leaves the head alone must not push
        // the date forward, or every /f desc would reset the tenure of whoever is sitting there.
        //
        // EXCEPT for a faction that predates MF recording heads at all. V9 leaves every existing
        // row's primary_owner_id null on purpose and V10 defaults primary_owner_since to 0, on the
        // stated reasoning that "zero reads as held since the epoch, so every existing head clears
        // every tenure gate immediately" -- the alternative being to freeze the whole server out of
        // those gates for a week. But that protection could never apply: the first time an operator
        // seated a head with /f admin setleader, null != <newOwner> was true and the stamp landed on
        // "now", which is precisely the outcome the migration says it exists to avoid.
        //
        // A brand-new faction has no previousState. A pre-existing one has a previousState with no
        // recorded owner and a zero date, and only that combination is left at zero. A faction that
        // went headless later under allowLeaderlessFactions was stamped when it emptied, so its date
        // is non-zero and it is still treated as taking a new head.
        val firstHeadOfAPreV9Faction = previousState != null &&
            previousState.primaryOwnerId == null &&
            previousState.primaryOwnerSince == 0L
        val stamped = if (successor.primaryOwnerId != previousState?.primaryOwnerId && !firstHeadOfAPreV9Faction) {
            successor.copy(primaryOwnerSince = System.currentTimeMillis())
        } else {
            successor
        }
        val result = repository.upsert(stamped)
        factionsById[result.id] = result
        // Announced here rather than bridged from an internal event, because there is no internal
        // event to bridge and adding one would be misleading: MF's internal faction events are
        // cancellable gates fired BEFORE the write, and by this line the head has already changed
        // and cannot be vetoed. A consumer that wants to decide the outcome registers a
        // SuccessionPolicy instead, which is consulted a few lines above.
        //
        // Fired for existing factions only. A new faction's head going from nobody to the founder is
        // reported by FactionCreateEvent, and firing both would make every consumer disambiguate.
        if (previousState != null && previousState.primaryOwnerId != result.primaryOwnerId) {
            val event = FactionPrimaryOwnerChangedEvent(
                FactionId(result.id.value),
                previousState.primaryOwnerId?.toUuidOrNull(),
                result.primaryOwnerId?.toUuidOrNull()
            )
            fireOnMainThread(event)
        }
        val mapService = plugin.services.mapService
        if (mapService != null && !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")) {
            plugin.server.scheduler.runTask(
                plugin,
                Runnable {
                    mapService.scheduleUpdateClaims(result)
                }
            )
        }
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    /**
     * Announces a stable-API event on the next tick, and never lets that failing disturb the save
     * that produced it.
     *
     * Next tick rather than inline because [save] is reached from command handlers that may be
     * running asynchronously, and an API consumer must not have to ask which thread it is on before
     * touching the world.
     *
     * The scheduler is not always willing to take the task. It refuses outright once the plugin is
     * disabling, and MF saves factions during shutdown. That refusal would otherwise propagate out
     * of [save], be wrapped as a ServiceFailure, and turn a successful, already-persisted write into
     * an error the caller reports to a player - so an ordinary /f leave would appear to fail having
     * actually succeeded. The event is a past-tense notification; losing one is a smaller problem
     * than that by a wide margin, so it is logged and dropped.
     */
    private fun fireOnMainThread(event: org.bukkit.event.Event) {
        runCatching {
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.server.pluginManager.callEvent(event) })
        }.onFailure { throwable ->
            plugin.logger.log(
                java.util.logging.Level.WARNING,
                "Could not announce ${event.javaClass.simpleName}; the change was saved but no " +
                    "listener will hear about it.",
                throwable
            )
        }
    }

    /**
     * Factions part-way through handing themselves to a vassal's head, so a cascade cannot re-enter
     * one and recurse forever.
     *
     * Each step of a cascade moves strictly down the vassal chain, because the heir must lead a
     * faction sworn to the one being inherited, so a loop needs an actual ring in the liege
     * relationships. Those are two rows apiece and nothing in the schema forbids a ring, and a
     * succession that overflowed the stack would do so having already written to other factions. The
     * guard costs one set insertion and turns that into a nomination that simply does not apply.
     */
    private val ascendingHeirs: MutableSet<MfFactionId> = ConcurrentHashMap.newKeySet()

    /**
     * The given faction with its nominated vassal's head released from their own faction and admitted
     * to this one, or the faction untouched if no such succession is due or it cannot be carried out.
     *
     * Releasing the heir is an ordinary save of the faction they are leaving, which is the point: it
     * fires that faction's own succession, which may in turn be a nomination of ITS vassal's head, and
     * so a single departure cascades down the chain. Nothing here special-cases the cascade; it falls
     * out of the rule applying at every level.
     *
     * Best effort by design. If the vassal cannot spare its head - it would be emptied and the server
     * forbids leaderless factions, say - the nomination is treated exactly as a stale one and the
     * ordinary succession order applies, rather than the greater faction's save failing and a player
     * being unable to leave.
     */
    private fun withVassalHeirAscended(faction: MfFaction): MfFaction {
        val vassalFactionId = faction.vassalHeirAscensionDue ?: return faction
        val heirId = faction.heirId ?: return faction
        if (!ascendingHeirs.add(faction.id)) return faction
        try {
            val vassalFaction = getFaction(vassalFactionId) ?: return faction
            save(vassalFaction.copy(members = vassalFaction.members.filter { it.playerId != heirId }))
                .onFailure {
                    plugin.logger.warning(
                        "Faction ${faction.id.value} could not take its heir from vassal ${vassalFactionId.value}, " +
                            "so the nomination was passed over: ${it.reason.message}"
                    )
                    return faction
                }
            return faction.withVassalHeirAdmitted()
        } finally {
            ascendingHeirs.remove(faction.id)
        }
    }

    @JvmName("deleteFactionByFactionId")
    fun delete(factionId: MfFactionId): Result4k<Unit, ServiceFailure> = resultFrom {
        val event = FactionDisbandEvent(factionId, !plugin.server.isPrimaryThread)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) {
            throw EventCancelledException("Event cancelled")
        }
        val claimService = plugin.services.claimService
        claimService.deleteAllClaims(factionId).onFailure {
            throw it.reason.cause
        }
        val gateService = plugin.services.gateService
        gateService.deleteAllGates(factionId).onFailure {
            throw it.reason.cause
        }
        val result = repository.delete(factionId)
        factionsById.remove(factionId)
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmOverloads
    fun createFaction(
        name: String,
        id: String = MfFactionId.generate().value,
        description: String = "",
        members: List<MfFactionMember> = emptyList(),
        invites: List<MfFactionInvite> = emptyList(),
        flags: MfFlagValues = plugin.flags.defaults(),
        prefix: String? = null,
        home: MfPosition? = null,
        bonusPower: Double = 0.0,
        autoclaim: Boolean = false,
        roles: MfFactionRoles = MfFactionRoles.defaults(plugin, MfFactionId(id))
    ): Result4k<MfFaction, ServiceFailure> {
        return save(
            MfFaction(
                plugin = plugin,
                id = MfFactionId(id),
                name = name,
                description = description,
                members = members,
                invites = invites,
                flags = flags,
                prefix = prefix,
                home = home,
                bonusPower = bonusPower,
                autoclaim = autoclaim,
                roles = roles
            )
        )
    }

    fun addField(field: MfFactionField) {
        _fields.add(field)
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> CONFLICT
            else -> GENERAL
        }
    }

    fun cancelAllApplicationsForPlayer(player: MfPlayer) {
        plugin.logger.info("Cancelling all applications for player ${player.name}")
        factions.forEach { faction ->
            plugin.logger.info("Checking faction ${faction.name}")
            save(
                faction.copy(
                    applications = faction.applications.filter { it.applicantId != player.id }
                )
            ).onFailure {
                plugin.logger.warning("Failed to cancel applications for player ${player.name} in faction ${faction.name}: ${it.reason.message}")
                throw it.reason.cause
            }
        }
    }
}
