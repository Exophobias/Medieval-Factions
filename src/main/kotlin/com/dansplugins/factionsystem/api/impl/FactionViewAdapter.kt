package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionHierarchyView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionRoleView
import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.Location
import java.util.UUID

/** Wraps an internal [MfFaction] as a stable [FactionView]. */
class FactionViewAdapter(
    private val plugin: MedievalFactions,
    private val faction: MfFaction
) : FactionView {

    override val id: FactionId get() = FactionId(faction.id.value)
    override val name: String get() = faction.name
    override val description: String get() = faction.description
    override val home: Location? get() = faction.home?.toBukkitLocation()
    override val memberIds: List<UUID> get() = faction.members.map { UUID.fromString(it.playerId.value) }
    override val claimCount: Int get() = plugin.services.claimService.getClaimCount(faction.id)

    override val factionsAtWarWith: List<FactionId>
        get() = plugin.services.factionRelationshipService.getFactionsAtWarWith(faction.id)
            .map { FactionId(it.value) }

    override fun isAtWarWith(other: FactionId): Boolean =
        plugin.services.factionRelationshipService.getFactionsAtWarWith(faction.id)
            .any { it.value == other.value }

    override val primaryOwnerId: UUID?
        get() = faction.primaryOwnerId?.let { UUID.fromString(it.value) }

    override fun roleOf(playerId: UUID): FactionRoleView? =
        faction.getRole(MfPlayerId(playerId.toString()))?.let { role ->
            FactionRoleViewAdapter(plugin, faction, role)
        }

    // Overridden rather than inherited from FactionView's default, which would wrap a role view per
    // member and re-scan the member list for each. One pass over the members MF already holds in
    // memory answers the same question.
    override val leaderIds: List<UUID>
        get() = faction.members
            .filter { it.role.hasPermission(faction, plugin.factionPermissions.disband) }
            .map { UUID.fromString(it.playerId.value) }

    // Assembled from the relationship service's own indexed lookups rather than from getVassalTree,
    // which materialises the whole subtree. See FactionHierarchyView for what this costs.
    override val hierarchy: FactionHierarchyView
        get() {
            val relationshipService = plugin.services.factionRelationshipService
            return FactionHierarchyView(
                liege = relationshipService.getLiege(faction.id)?.let { FactionId(it.value) },
                vassals = relationshipService.getVassals(faction.id).map { FactionId(it.value) },
                depthBelowSovereign = relationshipService.getDepthBelowSovereign(faction.id),
                vassalsHoldingVassals = relationshipService.getVassalsHoldingVassals(faction.id).size
            )
        }
}
