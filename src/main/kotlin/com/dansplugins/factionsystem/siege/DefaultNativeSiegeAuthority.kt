package com.dansplugins.factionsystem.siege

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.*
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.gate.MfGate
import com.dansplugins.factionsystem.gate.MfGateId
import com.dansplugins.factionsystem.gate.MfGateStatus
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.listener.MercenaryCombatGate
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import org.bukkit.entity.Player
import java.util.UUID

class DefaultNativeSiegeAuthority(private val plugin: MedievalFactions) : NativeSiegeAuthority {
    override val epoch: UUID get() = NativeFenceRegistry.get(plugin).epoch
    override fun isAvailable() = available() && NativeFenceRegistry.get(plugin).isReadable()
    private fun available() = plugin.isEnabled && plugin.server.isPrimaryThread

    override fun warIdentity(firstRealm: String, secondRealm: String): String? {
        if (!plugin.isEnabled || firstRealm == secondRealm ||
            listOf(firstRealm, secondRealm).any { !plugin.services.factionService.factionExistsForSiege(MfFactionId(it)) ||
                plugin.services.factionService.isDeletionPending(MfFactionId(it)) }) return null
        val service = plugin.services.factionRelationshipService
        val first = service.getRelationships(MfFactionId(firstRealm), MfFactionId(secondRealm)).filter { it.type == AT_WAR }
        val second = service.getRelationships(MfFactionId(secondRealm), MfFactionId(firstRealm)).filter { it.type == AT_WAR }
        // Native relationship IDs survive restart and new declarations generate fresh IDs.
        // Duplicate/partial pairs cannot acquire an artillery entitlement.
        if (first.size != 1 || second.size != 1) return null
        return listOf(first.single().id.value, second.single().id.value).sorted().joinToString("/")
    }

    override fun claimOwner(worldId: UUID, chunkX: Int, chunkZ: Int): String? =
        if (plugin.isEnabled) plugin.services.claimService.getClaim(worldId, chunkX, chunkZ)?.factionId?.value else null

    override fun permitsDestruction(actor: UUID, attackerRealm: String, targetRealm: String, blocks: List<SiegeBlock>): Boolean {
        if (!available() || blocks.isEmpty() || blocks.size > 512 || warIdentity(attackerRealm, targetRealm) == null) return false
        if (plugin.server.getPlayer(actor)?.isOnline != true) return false
        val target = plugin.services.factionService.getFaction(MfFactionId(targetRealm)) ?: return false
        // Player artillery never borrows staff bypass or attacks an administrative faction.
        if (target.adminLeaderless || !AdminFactionProtection.EXPLOSIONS.isAllowed(target)) return false
        return blocks.all { block ->
            val world = plugin.server.getWorld(block.worldId)
            world != null && world.isChunkLoaded(block.x shr 4, block.z shr 4) &&
                claimOwner(block.worldId, block.x shr 4, block.z shr 4) == targetRealm &&
                !plugin.services.embassyService.hasActiveOrClearingEmbassy(block.worldId, block.x shr 4, block.z shr 4)
        }
    }

    override fun permitsPlayerDamage(attacker: UUID, victim: UUID): Boolean {
        if (!available()) return false
        val attackPlayer = plugin.server.getPlayer(attacker) ?: return false
        val victimPlayer = plugin.server.getPlayer(victim) ?: return false
        if (!attackPlayer.isOnline || !victimPlayer.isOnline || attackPlayer.world != victimPlayer.world) return false
        return playerPolicy(attackPlayer, victimPlayer)
    }

    /** Shared by the native listener, retaining native duel/factionless/friendly-fire ordering. */
    internal fun playerPolicy(attacker: Player, victim: Player): Boolean {
        val services = plugin.services
        val attackerId = MfPlayerId(attacker.uniqueId.toString())
        val victimId = MfPlayerId(victim.uniqueId.toString())
        val location = victim.location
        val claim = location?.world?.let { world ->
            services.claimService.getClaim(world.uid, location.blockX shr 4, location.blockZ shr 4)
        }
        val territory = claim?.let { services.factionService.getFaction(it.factionId) }
        if (territory != null && (!AdminFactionProtection.PLAYER_DAMAGE.isAllowed(territory) ||
                !AdminFactionProtection.PVP.isAllowed(territory))) return false
        val duelOne = services.duelService.getDuel(attackerId)
        val duelTwo = services.duelService.getDuel(victimId)
        if (duelOne != null && duelTwo != null && duelOne.id == duelTwo.id) return true
        if (duelOne != null || duelTwo != null) return false
        val first = services.factionService.getFaction(attackerId)
        val second = services.factionService.getFaction(victimId)
        if (first == null || second == null) return plugin.config.getBoolean("pvp.enabledForFactionlessPlayers")
        if (first.id == second.id) return plugin.config.getBoolean("pvp.friendlyFire") || first.flags[plugin.flags.allowFriendlyFire]
        when (MercenaryCombatGate.decide(plugin, attacker.uniqueId, victim.uniqueId)) {
            MercenaryCombatProvider.Decision.ALLOW -> return true
            MercenaryCombatProvider.Decision.DENY -> return false
            else -> Unit
        }
        return !plugin.config.getBoolean("pvp.warRequiredForPlayersOfDifferentFactions") ||
            (services.factionRelationshipService.getRelationships(first.id, second.id) +
                services.factionRelationshipService.getRelationships(second.id, first.id)).any { it.type == AT_WAR }
    }

    override fun gatesAt(block: SiegeBlock): List<SiegeGate> {
        if (!available()) return emptyList()
        return plugin.services.gateService.getGatesAt(MfBlockPosition(block.worldId, block.x, block.y, block.z))
            .map(::view)
    }

    override fun tryAcquireEffectLease(expected: SiegeNativeExpectation): SiegeEffectLease? {
        if (!available() || expected.epoch != epoch || expected.wars.size > 64 ||
            expected.claims.isEmpty() || expected.claims.size > 256 || expected.members.size > 64) return null
        val lease = NativeFenceRegistry.get(plugin).tryAcquire() ?: return null
        try {
            if (!available() || expected.wars.any { warIdentity(it.firstRealm, it.secondRealm) != it.identity } ||
                expected.claims.any { claimOwner(it.worldId, it.chunkX, it.chunkZ) != it.owner } ||
                expected.members.any { plugin.services.factionService.isDeletionPending(MfFactionId(it.realm)) ||
                    plugin.services.factionService.getFaction(MfPlayerId(it.actor.toString()))?.id?.value != it.realm }) {
                lease.close(); return null
            }
            return lease
        } catch (failure: Throwable) { lease.close(); throw failure }
    }

    override fun disruptGate(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> =
        ApiOutcome.failure("Use durable prepare, authorized server-thread activation, then durable commit")

    override fun prepareGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> =
        gateWorker { plugin.services.gateService.prepareDisruption(operationId, MfGateId(gateId), expectedRevision) }

    override fun activateGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> {
        if (!available()) return ApiOutcome.failure("Gate activation requires the enabled server-thread provider")
        return gateOutcome { plugin.services.gateService.activateDisruption(operationId, MfGateId(gateId), expectedRevision) }
    }

    override fun commitGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> =
        gateWorker { plugin.services.gateService.commitDisruption(operationId, MfGateId(gateId), expectedRevision) }

    override fun cancelGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> =
        gateWorker { plugin.services.gateService.cancelDisruption(operationId, MfGateId(gateId), expectedRevision) }

    override fun reviewGateDisruption(actor: UUID, operationId: UUID, gateId: String, expectedRevision: Int, observedAfter: Boolean): java.util.concurrent.CompletableFuture<ApiOutcome<SiegeGate>> {
        val future = java.util.concurrent.CompletableFuture<ApiOutcome<SiegeGate>>()
        if (!available() || plugin.server.getPlayer(actor)?.let { it.isOnline && it.hasPermission("mf.gate.disruption.review") } != true) {
            future.complete(ApiOutcome.failure("Native gate review requires an online staff player with mf.gate.disruption.review")); return future
        }
        val service = plugin.services.gateService
        val lease = NativeFenceRegistry.get(plugin).tryAcquire()
        if (lease == null) { future.complete(ApiOutcome.failure("A native transition is in progress")); return future }
        try { service.beginDisruptionReview(operationId, MfGateId(gateId), expectedRevision) }
        catch (failure: Exception) { future.complete(ApiOutcome.failure(failure.message ?: "Native review refused")); return future }
        finally { lease.close() }
        try {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                future.complete(gateOutcome { service.finishDisruptionReview(operationId, MfGateId(gateId), expectedRevision, observedAfter) })
            })
        } catch (failure: Exception) {
            service.abandonDisruptionReview(operationId)
            future.complete(ApiOutcome.failure(failure.message ?: "Native review scheduling failed"))
        }
        return future
    }

    private fun gateWorker(action: () -> MfGate): ApiOutcome<SiegeGate> {
        if (!plugin.isEnabled || plugin.server.isPrimaryThread) return ApiOutcome.failure("Gate persistence requires an enabled provider and a worker thread")
        return gateOutcome(action)
    }
    private fun gateOutcome(action: () -> MfGate): ApiOutcome<SiegeGate> = try { ApiOutcome.success(view(action())) }
        catch (failure: Exception) { ApiOutcome.failure(failure.message ?: "Gate operation failed") }

    private fun view(gate: MfGate): SiegeGate {
        val min = gate.area.minPosition; val max = gate.area.maxPosition
        val count = Math.multiplyExact(Math.multiplyExact(max.x.toLong() - min.x + 1, max.y.toLong() - min.y + 1), max.z.toLong() - min.z + 1)
        require(count in 1..512) { "Gate exceeds the supported siege footprint" }
        val metadata = plugin.services.gateService.disruptionMetadata(gate.id)
        return SiegeGate(gate.id.value, gate.version, gate.factionId.value,
            gate.area.blocks.map { SiegeBlock(it.worldId, it.x, it.y, it.z) }, gate.material.name, gate.status.name,
            metadata?.first, metadata?.second)
    }
}
