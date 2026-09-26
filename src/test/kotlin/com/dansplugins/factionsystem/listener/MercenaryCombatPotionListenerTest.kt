package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.MercenaryCombatProvider
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.potion.MfPotionService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.plugin.ServicesManager
import org.bukkit.potion.PotionData
import org.bukkit.potion.PotionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class MercenaryCombatPotionListenerTest {

    @Test
    fun areaCloudDeniesOneTargetWithoutSkippingTheNext() {
        var firstTarget = true
        val fixture = Fixture { _, _ ->
            if (firstTarget.also { firstTarget = false }) MercenaryCombatProvider.Decision.DENY
            else MercenaryCombatProvider.Decision.ALLOW
        }
        val cloud = mock(AreaEffectCloud::class.java)
        val event = mock(AreaEffectCloudApplyEvent::class.java)
        val potionData = mock(PotionData::class.java)
        val affected = mutableListOf<LivingEntity>(fixture.firstVictim, fixture.secondVictim)
        `when`(event.entity).thenReturn(cloud)
        `when`(cloud.basePotionData).thenReturn(potionData)
        `when`(potionData.type).thenReturn(PotionType.POISON)
        `when`(event.affectedEntities).thenReturn(affected)
        `when`(fixture.services.potionService.getLingeringPotionEffectThrower(cloud)).thenReturn(fixture.attacker)

        AreaEffectCloudApplyListener(fixture.plugin).onAreaEffectCloudApply(event)

        assertEquals(listOf(fixture.secondVictim), affected)
        assertEquals(listOf(fixture.firstVictim.uniqueId, fixture.secondVictim.uniqueId), fixture.seenVictims)
    }

    private class Fixture(private val decision: (UUID, UUID) -> MercenaryCombatProvider.Decision) {
        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val attacker = mock(Player::class.java)
        val firstVictim = mock(Player::class.java)
        val secondVictim = mock(Player::class.java)
        val seenVictims = mutableListOf<UUID>()

        init {
            val server = mock(Server::class.java)
            val manager = mock(ServicesManager::class.java)
            val config = mock(FileConfiguration::class.java)
            val playerService = mock(MfPlayerService::class.java)
            val factionService = mock(MfFactionService::class.java)
            val potionService = mock(MfPotionService::class.java)
            `when`(plugin.server).thenReturn(server)
            `when`(server.servicesManager).thenReturn(manager)
            `when`(plugin.services).thenReturn(services)
            `when`(plugin.config).thenReturn(config)
            `when`(config.getBoolean("pvp.warRequiredForPlayersOfDifferentFactions")).thenReturn(true)
            `when`(services.playerService).thenReturn(playerService)
            `when`(services.factionService).thenReturn(factionService)
            `when`(services.duelService).thenReturn(mock(com.dansplugins.factionsystem.duel.MfDuelService::class.java))
            `when`(services.potionService).thenReturn(potionService)

            val provider = MercenaryCombatProvider { attack, victim ->
                seenVictims.add(victim)
                decision(attack, victim)
            }
            `when`(manager.load(MercenaryCombatProvider::class.java)).thenReturn(provider)
            val members = listOf(attacker, firstVictim, secondVictim)
            members.forEachIndexed { index, player ->
                val playerId = UUID.randomUUID()
                `when`(player.uniqueId).thenReturn(playerId)
                val mfPlayer = MfPlayer(MfPlayerId(playerId.toString()))
                `when`(playerService.getPlayer(player)).thenReturn(mfPlayer)
                val faction = mock(MfFaction::class.java)
                `when`(faction.id).thenReturn(MfFactionId("faction-$index"))
                `when`(factionService.getFaction(mfPlayer.id)).thenReturn(faction)
            }
        }
    }
}
