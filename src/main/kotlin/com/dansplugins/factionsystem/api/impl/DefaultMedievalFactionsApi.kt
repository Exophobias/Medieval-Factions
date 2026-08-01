package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import com.dansplugins.factionsystem.api.ClaimView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.api.MedievalFactionsApi
import com.dansplugins.factionsystem.api.SuccessionPolicy
import com.dansplugins.factionsystem.area.MfPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

/**
 * The adapter that implements [MedievalFactionsApi] over MedievalFactions' internal services. This is
 * the ONLY class that touches both the stable API types and the internal `MfFaction`/`MfClaimedChunk`/
 * service layer, so an internal refactor is contained here and never reaches API consumers.
 */
class DefaultMedievalFactionsApi(private val plugin: MedievalFactions) : MedievalFactionsApi {

    override fun getFaction(id: FactionId): FactionView? =
        plugin.services.factionService.getFaction(MfFactionId(id.value))?.let(::toView)

    override fun getFactionByName(name: String): FactionView? =
        plugin.services.factionService.getFaction(name)?.let(::toView)

    override fun getFactionByPlayer(playerId: UUID): FactionView? =
        plugin.services.factionService.getFaction(MfPlayerId(playerId.toString()))?.let(::toView)

    override fun getFactionAt(chunk: Chunk): FactionView? {
        val claim = plugin.services.claimService.getClaim(chunk) ?: return null
        return plugin.services.factionService.getFaction(claim.factionId)?.let(::toView)
    }

    override fun getClaimAt(chunk: Chunk): ClaimView? =
        plugin.services.claimService.getClaim(chunk)?.let(::ClaimViewAdapter)

    // Deliberately does NOT go through getClaimAt: the whole point of this overload is to skip the
    // Chunk, and therefore the chunk load that obtaining one from a Location implies. MfClaimService
    // keys its in-memory index on (worldId, x, z), so this is a single map lookup.
    override fun isClaimed(world: World, chunkX: Int, chunkZ: Int): Boolean =
        plugin.services.claimService.getClaim(world, chunkX, chunkZ) != null

    override fun getClaimAt(world: World, chunkX: Int, chunkZ: Int): ClaimView? =
        plugin.services.claimService.getClaim(world.uid, chunkX, chunkZ)?.let(::ClaimViewAdapter)

    override fun registerClaimOverrideProvider(provider: ClaimOverrideProvider) {
        plugin.services.claimService.claimOverrides.register(provider)
    }

    override fun unregisterClaimOverrideProvider(provider: ClaimOverrideProvider) {
        plugin.services.claimService.claimOverrides.unregister(provider)
    }

    // 0.0 for an unknown player mirrors how MF's own power callers treat a missing record, so a
    // consumer summing power over a member list needs no null handling.
    override fun getPower(playerId: UUID): Double =
        plugin.services.playerService.getPlayer(MfPlayerId(playerId.toString()))?.power ?: 0.0

    override fun setHome(faction: FactionId, location: Location): ApiResult {
        if (location.world == null) return ApiResult.failure("Location has no world")
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiResult.failure("No faction with id ${faction.value}")
        return plugin.services.factionService
            .save(mfFaction.copy(home = MfPosition.fromBukkitLocation(location)))
            .toApiResult()
    }

    override fun claim(faction: FactionId, chunk: Chunk): ApiResult =
        plugin.services.claimService.save(MfClaimedChunk(chunk, MfFactionId(faction.value))).toApiResult()

    override fun unclaim(chunk: Chunk): ApiResult {
        val claim = plugin.services.claimService.getClaim(chunk)
            ?: return ApiResult.failure("Chunk is not claimed")
        return plugin.services.claimService.delete(claim).toApiResult()
    }

    override fun forcePeace(faction: FactionId, otherFaction: FactionId): ApiResult {
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        val relationshipService = plugin.services.factionRelationshipService
        val warRelationships = (relationshipService.getRelationships(a, b) + relationshipService.getRelationships(b, a))
            .filter { it.type == AT_WAR }
        if (warRelationships.isEmpty()) {
            return ApiResult.failure("Factions are not at war")
        }
        warRelationships.forEach { relationship ->
            val result = relationshipService.delete(relationship.id)
            if (result is Failure) {
                return result.toApiResult()
            }
        }
        return ApiResult.success()
    }

    // Identity only. Deliberately does NOT grant the top role, which is the one way this differs
    // from /f transfer: that command is a player handing their own House on, so "a head who cannot
    // act is not a head" applies. This is a government plugin recording who rules, and a regent
    // seated for a fortnight should not silently acquire the right to disband the faction. The two
    // questions are separate throughout MF - see FactionView.primaryOwnerId versus isLeader - and a
    // caller that wants both should say so.
    override fun setPrimaryOwner(faction: FactionId, playerId: UUID): ApiResult {
        val mfFaction = plugin.services.factionService.getFaction(MfFactionId(faction.value))
            ?: return ApiResult.failure("No faction with id ${faction.value}")
        val newOwner = MfPlayerId(playerId.toString())
        if (mfFaction.members.none { it.playerId == newOwner }) {
            return ApiResult.failure("Player $playerId is not a member of faction ${faction.value}")
        }
        if (mfFaction.primaryOwnerId == newOwner) {
            return ApiResult.success()
        }
        // A nomination is cleared as soon as it is used, which is the contract on MfFaction.heirId.
        // Seating the standing heir IS using it, and leaving it in place would produce a faction
        // that is its own heir. An unrelated nomination is left alone, because the outgoing head
        // making one is not invalidated by somebody else being seated ahead of them.
        val consumedHeir = mfFaction.heirId == newOwner
        return plugin.services.factionService
            .save(mfFaction.copy(primaryOwnerId = newOwner, heirId = if (consumedHeir) null else mfFaction.heirId))
            .toApiResult()
    }

    override fun registerSuccessionPolicy(policy: SuccessionPolicy) {
        plugin.services.factionService.successionPolicies.register(policy)
    }

    override fun unregisterSuccessionPolicy(policy: SuccessionPolicy) {
        plugin.services.factionService.successionPolicies.unregister(policy)
    }

    private fun toView(faction: MfFaction): FactionView = FactionViewAdapter(plugin, faction)

    private fun Result4k<*, ServiceFailure>.toApiResult(): ApiResult = when (this) {
        is Success -> ApiResult.success()
        is Failure -> ApiResult.failure(reason.message)
    }
}
