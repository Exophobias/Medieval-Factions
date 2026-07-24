package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.ClaimView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.api.MedievalFactionsApi
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

    override fun getFaction(name: String): FactionView? =
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

    private fun toView(faction: MfFaction): FactionView = FactionViewAdapter(plugin, faction)

    private fun Result4k<*, ServiceFailure>.toApiResult(): ApiResult = when (this) {
        is Success -> ApiResult.success()
        is Failure -> ApiResult.failure(reason.message)
    }
}
