package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class MfFactionMaxPowerTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var players: MfPlayerService
    private lateinit var factions: MfFactionService
    private lateinit var relationships: MfFactionRelationshipService

    @BeforeEach
    fun setup() {
        plugin = mock(MedievalFactions::class.java)
        val config = YamlConfiguration()
        config.set("players.maxPower", 20.0)
        config.set("factions.vassalPowerContributionMultiplier", 0.5)
        `when`(plugin.config).thenReturn(config)
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val services = mock(Services::class.java)
        players = mock(MfPlayerService::class.java)
        factions = mock(MfFactionService::class.java)
        relationships = mock(MfFactionRelationshipService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.factionRelationshipService).thenReturn(relationships)
    }

    private fun id() = MfPlayerId(UUID.randomUUID().toString())

    private fun faction(ids: List<MfPlayerId>, bonus: Double = 0.0): MfFaction {
        val role = MfFactionRole(plugin, name = "Member")
        return MfFaction(
            plugin,
            name = "Power test",
            members = ids.map { MfFactionMember(it, role) },
            roles = MfFactionRoles(role.id, listOf(role)),
            flags = MfFlagValues(plugin, mapOf("acceptBonusPower" to true)),
            defaultPermissionsByName = emptyMap(),
            bonusPower = bonus
        )
    }

    @Test
    fun `faction capacity sums custom and inherited maximums and falls back for absent player records`() {
        val customId = id()
        val normalId = id()
        val missingId = id()
        `when`(players.getPlayer(customId)).thenReturn(MfPlayer(customId, power = 40.0, maxPowerOverride = 50.0))
        `when`(players.getPlayer(normalId)).thenReturn(MfPlayer(normalId, power = 4.0))
        val faction = faction(listOf(customId, normalId, missingId), bonus = 7.0)

        assertEquals(90.0, faction.maxMemberPower)
        assertEquals(44.0, faction.memberPower)
        assertEquals(97.0, faction.maxPower)
        assertEquals(51.0, faction.power)
    }

    @Test
    fun `vassal maximum contributions include individual member maximums`() {
        val overlordId = id()
        val vassalId = id()
        `when`(players.getPlayer(overlordId)).thenReturn(MfPlayer(overlordId, power = 20.0))
        `when`(players.getPlayer(vassalId)).thenReturn(MfPlayer(vassalId, power = 40.0, maxPowerOverride = 50.0))
        val overlord = faction(listOf(overlordId))
        val vassal = faction(listOf(vassalId))
        `when`(relationships.getVassals(overlord.id)).thenReturn(listOf(vassal.id))
        `when`(factions.getFaction(vassal.id)).thenReturn(vassal)

        assertEquals(25.0, overlord.maxVassalPower)
        assertEquals(45.0, overlord.maxPower)
        assertEquals(40.0, overlord.power)
    }
}
