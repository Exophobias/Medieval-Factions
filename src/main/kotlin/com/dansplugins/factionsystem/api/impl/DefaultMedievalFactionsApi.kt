package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ApiOutcome
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
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.faction.withRole
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
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

    // Constructs MfClaimedChunk from coordinates rather than from a Chunk, which is what the internal
    // model holds anyway. The world is resolved only to confirm it exists -- getWorld is a map lookup
    // over loaded worlds and loads nothing.
    override fun claim(faction: FactionId, worldId: UUID, chunkX: Int, chunkZ: Int): ApiResult {
        if (plugin.server.getWorld(worldId) == null) {
            return ApiResult.failure("No loaded world with id $worldId")
        }
        return plugin.services.claimService
            .save(MfClaimedChunk(worldId, chunkX, chunkZ, MfFactionId(faction.value)))
            .toApiResult()
    }

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

    // Mirrors MfFactionCreateCommand rather than sharing code with it, because that command is a
    // chain of chat messages with a save in the middle and there is nothing extractable without
    // rewriting it. The checks below are the same checks in the same order; if one moves there, it
    // has to move here.
    override fun createFaction(name: String, founderId: UUID): ApiOutcome<FactionId> {
        if (name.isBlank()) return ApiOutcome.failure("Faction name is blank")
        val maxNameLength = plugin.config.getInt("factions.maxNameLength")
        if (name.length > maxNameLength) {
            return ApiOutcome.failure("Faction name is longer than $maxNameLength characters")
        }
        val factionService = plugin.services.factionService
        if (factionService.getFaction(name) != null) {
            return ApiOutcome.failure("A faction named $name already exists")
        }
        val playerService = plugin.services.playerService
        val playerId = MfPlayerId(founderId.toString())
        // The founder is RELOCATED, not refused, and this used to be a refusal. It was wrong, and
        // wrong in a way that made the whole calling feature impossible: the motivating consumer is a
        // secession, where a lord takes part of a realm out of it, and such a lord is by construction
        // still a member of the realm they are leaving at the moment the new one is founded. Refusing
        // meant createFaction could only ever be called for somebody who already belonged nowhere,
        // which is a player who does not need a faction founded around them.
        //
        // Removed BEFORE the new faction is saved, deliberately. MF resolves a player found in two
        // factions to NEITHER -- getFaction(playerId) is a singleOrNull over every faction -- so the
        // window between the two writes must leave them factionless rather than doubly seated. The
        // same ordering, and the same reason, as transferMembers.
        val previousFaction = factionService.getFaction(playerId)
        if (previousFaction != null) {
            // Their departure runs MF's ordinary machinery: succession reseats the faction if the
            // founder was its head, and FactionMemberLeftEvent is delivered so consumers holding
            // sub-group state can react. A caller that did not want that should not be founding a
            // faction around somebody who is already in one.
            val departed = factionService.save(
                previousFaction.copy(members = previousFaction.members.filterNot { it.playerId == playerId })
            )
            if (departed is Failure) {
                return ApiOutcome.failure("Could not remove $founderId from their current faction: ${departed.reason.message}")
            }
        }
        val mfPlayer = playerService.getPlayer(playerId)
            ?: playerService.save(MfPlayer(plugin, plugin.server.getOfflinePlayer(founderId)))
                .let { result ->
                    when (result) {
                        is Success -> result.value
                        is Failure -> return ApiOutcome.failure("Failed to save player: ${result.reason.message}")
                    }
                }
        val factionId = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, factionId)
        // roles.default rather than a throw, for the reason MfFactionCreateCommand gives: these are
        // freshly generated defaults, so a null leaderRole means MF's own default set has lost its
        // top role, and refusing to found the faction is a worse answer than founding one whose
        // founder holds the ordinary role and is still recorded as its head.
        val ownerRole = roles.leaderRole ?: roles.default
        val faction = MfFaction(
            plugin,
            id = factionId,
            name = name,
            roles = roles,
            members = listOf(mfPlayer.withRole(ownerRole)),
            primaryOwnerId = mfPlayer.id
        )
        return when (val result = factionService.save(faction)) {
            is Success -> ApiOutcome.success(FactionId(result.value.id.value))
            is Failure -> ApiOutcome.failure(result.reason.message)
        }
    }

    override fun disbandFaction(faction: FactionId): ApiResult {
        val id = MfFactionId(faction.value)
        if (plugin.services.factionService.getFaction(id) == null) {
            return ApiResult.failure("No faction with id ${faction.value}")
        }
        return plugin.services.factionService.delete(id).toApiResult()
    }

    override fun transferMembers(from: FactionId, to: FactionId, playerIds: Collection<UUID>): ApiResult {
        if (from.value == to.value) return ApiResult.failure("Source and destination are the same faction")
        val factionService = plugin.services.factionService
        val source = factionService.getFaction(MfFactionId(from.value))
            ?: return ApiResult.failure("No faction with id ${from.value}")
        val destination = factionService.getFaction(MfFactionId(to.value))
            ?: return ApiResult.failure("No faction with id ${to.value}")
        // Distinct first: a duplicate would otherwise be admitted twice, and MfFaction.members is a
        // plain list with no key, so the destination would hold two rows for one player.
        val moving = playerIds.map { MfPlayerId(it.toString()) }.distinct()
        if (moving.isEmpty()) return ApiResult.success()
        // Ids that are no longer members are SKIPPED, not refused, and this used to fail the whole
        // call. All-or-nothing was the wrong shape for every real caller: the motivating one moves a
        // group of players recorded minutes or days earlier, and any one of them leaving in the
        // meantime turned a routine move into a failure that stranded everybody else. Worse, the
        // callers that then carried on regardless -- because the move looked like housekeeping --
        // left the entire remaining group factionless.
        //
        // A caller that genuinely needs all-or-nothing can compare the count it asked for against the
        // count it got, which is why this reports one.
        val movable = moving.filter { id -> source.members.any { it.playerId == id } }
        if (movable.isEmpty()) {
            return ApiResult.success()
        }
        val movingSet = movable.toSet()
        // Removed first. See the contract note on MedievalFactionsApi.transferMembers: the window
        // between these two saves must leave a player factionless rather than in two factions, since
        // MF resolves a player in two factions to neither.
        val departed = factionService.save(source.copy(members = source.members.filterNot { it.playerId in movingSet }))
        if (departed is Failure) return departed.toApiResult()
        val role = destination.roles.default
        val arrivals = movable.map { MfFactionMember(it, role) }
        return factionService.save(destination.copy(members = destination.members + arrivals)).toApiResult()
    }

    override fun transferAllClaims(from: FactionId, to: FactionId): ApiOutcome<Int> {
        if (from.value == to.value) return ApiOutcome.failure("Source and destination are the same faction")
        val factionService = plugin.services.factionService
        if (factionService.getFaction(MfFactionId(from.value)) == null) {
            return ApiOutcome.failure("No faction with id ${from.value}")
        }
        val destinationId = MfFactionId(to.value)
        if (factionService.getFaction(destinationId) == null) {
            return ApiOutcome.failure("No faction with id ${to.value}")
        }
        val claimService = plugin.services.claimService
        // Snapshotted before the loop. getClaims reads the live per-faction index, and every save
        // below removes an entry from it, so iterating it directly would be a mutation during
        // traversal of the very collection being emptied.
        val claims = claimService.getClaims(MfFactionId(from.value)).toList()
        var moved = 0
        claims.forEach { claim ->
            // A cancelled FactionClaimEvent surfaces as a GENERAL failure and is indistinguishable
            // from a database error here, so both stop the run. Reporting a prefix is honest; carrying
            // on past a database failure would report a number nobody could act on.
            val result = claimService.save(claim.copy(factionId = destinationId))
            if (result is Failure) {
                return if (moved == 0) {
                    ApiOutcome.failure(result.reason.message)
                } else {
                    ApiOutcome.success(moved)
                }
            }
            moved++
        }
        return ApiOutcome.success(moved)
    }

    override fun renounceLiege(vassal: FactionId): ApiResult {
        val vassalId = MfFactionId(vassal.value)
        if (plugin.services.factionService.getFaction(vassalId) == null) {
            return ApiResult.failure("No faction with id ${vassal.value}")
        }
        val relationshipService = plugin.services.factionRelationshipService
        // firstOrNull rather than singleOrNull, matching MfFactionDeclareIndependenceCommand: a
        // faction should never hold two liege rows, and if data corruption gives it one anyway,
        // breaking the first oath is a better answer than refusing to break any.
        val liegeRelationship = relationshipService.getRelationships(vassalId, LIEGE).firstOrNull()
            ?: return ApiResult.failure("Faction ${vassal.value} has no liege")
        val liegeId = liegeRelationship.targetId
        // Every row between the two, both ways -- the vassal's LIEGE row and the liege's VASSAL row
        // are separate records and leaving either behind would produce a half-broken oath that reads
        // differently depending on which side is asked.
        val rows = relationshipService.getRelationships(vassalId, liegeId) +
            relationshipService.getRelationships(liegeId, vassalId)
        rows.forEach { relationship ->
            val result = relationshipService.delete(relationship.id)
            if (result is Failure) {
                return result.toApiResult()
            }
        }
        return ApiResult.success()
    }

    override fun swearFealty(vassal: FactionId, liege: FactionId): ApiResult {
        if (vassal.value == liege.value) return ApiResult.failure("A faction cannot swear to itself")
        val vassalId = MfFactionId(vassal.value)
        val liegeId = MfFactionId(liege.value)
        val factionService = plugin.services.factionService
        if (factionService.getFaction(vassalId) == null) {
            return ApiResult.failure("No faction with id ${vassal.value}")
        }
        if (factionService.getFaction(liegeId) == null) {
            return ApiResult.failure("No faction with id ${liege.value}")
        }
        val relationshipService = plugin.services.factionRelationshipService
        // Refused rather than replaced. A faction with two liege rows is resolved by MF's own walk
        // taking the first, so which oath is in force would depend on row order -- and moving an
        // existing oath is a different act from swearing one, which a caller should have to say.
        if (relationshipService.getRelationships(vassalId, LIEGE).isNotEmpty()) {
            return ApiResult.failure("Faction ${vassal.value} already swears to a liege")
        }
        val sworn = relationshipService.save(
            MfFactionRelationship(factionId = vassalId, targetId = liegeId, type = LIEGE)
        )
        if (sworn is Failure) return sworn.toApiResult()
        return relationshipService.save(
            MfFactionRelationship(factionId = liegeId, targetId = vassalId, type = VASSAL)
        ).toApiResult()
    }

    override fun declareWar(faction: FactionId, otherFaction: FactionId): ApiResult {
        if (faction.value == otherFaction.value) return ApiResult.failure("A faction cannot be at war with itself")
        val a = MfFactionId(faction.value)
        val b = MfFactionId(otherFaction.value)
        val factionService = plugin.services.factionService
        if (factionService.getFaction(a) == null) return ApiResult.failure("No faction with id ${faction.value}")
        if (factionService.getFaction(b) == null) return ApiResult.failure("No faction with id ${otherFaction.value}")
        val relationshipService = plugin.services.factionRelationshipService
        val existing = relationshipService.getRelationships(a, b) + relationshipService.getRelationships(b, a)
        if (existing.any { it.type == AT_WAR }) {
            return ApiResult.failure("Factions are already at war")
        }
        // Both directions, as /f declarewar writes them. ApiRelationshipListener collapses the pair
        // into one FactionWarStartedEvent, so a consumer sees a single declaration.
        val declared = relationshipService.save(MfFactionRelationship(factionId = a, targetId = b, type = AT_WAR))
        if (declared is Failure) return declared.toApiResult()
        return relationshipService.save(MfFactionRelationship(factionId = b, targetId = a, type = AT_WAR)).toApiResult()
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
