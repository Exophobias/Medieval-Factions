package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
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

    override fun roleOf(playerId: UUID): FactionRoleView? =
        faction.getRole(MfPlayerId(playerId.toString()))?.let { role ->
            object : FactionRoleView {
                override val name: String = role.name
            }
        }
}
