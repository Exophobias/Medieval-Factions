package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Locale
import java.util.UUID

class PlayerDeathPowerTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var players: MfPlayerService
    private lateinit var victim: Player
    private lateinit var event: PlayerDeathEvent
    private val saved = mutableListOf<MfPlayer>()

    @BeforeEach
    fun setup() {
        saved.clear()
        plugin = mock(MedievalFactions::class.java)
        val config = YamlConfiguration()
        config.set("players.maxPower", 20.0)
        config.set("players.powerLostOnDeath", 5.0)
        config.set("players.powerGainedOnKill", 5.0)
        config.set("pvp.grantPowerToKillerIfVictimHasZeroPower", true)
        `when`(plugin.config).thenReturn(config)
        val language = mock(Language::class.java) { invocation ->
            if (invocation.method.name == "get") invocation.arguments[0] else RETURNS_DEFAULTS.answer(invocation)
        }
        `when`(language.locale).thenReturn(Locale.ENGLISH)
        `when`(plugin.language).thenReturn(language)
        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java))).thenAnswer {
            it.getArgument<Runnable>(1).run()
            null
        }
        val services = mock(Services::class.java)
        players = mock(MfPlayerService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.factionService).thenReturn(mock(MfFactionService::class.java))
        `when`(players.save(anyArg())).thenAnswer {
            val player = it.getArgument<MfPlayer>(0)
            saved += player
            Success(player)
        }
        victim = mock(Player::class.java)
        `when`(victim.uniqueId).thenReturn(UUID.randomUUID())
        event = mock(PlayerDeathEvent::class.java)
        `when`(event.entity).thenReturn(victim)
    }

    @ParameterizedTest
    @CsvSource("40, 20, 35", "30, 50, 25", "3, 50, 0")
    fun `death subtracts the configured loss without destroying surplus`(power: Double, maximum: Double, expected: Double) {
        val snapshot = MfPlayer(MfPlayerId.fromBukkitPlayer(victim), power = power, maxPowerOverride = maximum)
        `when`(players.getPlayer(victim)).thenReturn(snapshot)

        PlayerDeathListener(plugin).onPlayerDeath(event)

        assertEquals(1, saved.size)
        assertEquals(expected, saved.single().power)
        assertEquals(maximum, saved.single().maxPowerOverride)
    }

    @ParameterizedTest
    @CsvSource("40, 20, 40", "49, 50, 50", "9, 10, 10", "19, , 20")
    fun `kill gains use the killer maximum and leave existing surplus unchanged`(power: Double, maximum: Double?, expected: Double) {
        val killer = mock(Player::class.java)
        `when`(killer.uniqueId).thenReturn(UUID.randomUUID())
        val snapshot = MfPlayer(MfPlayerId.fromBukkitPlayer(killer), power = power, maxPowerOverride = maximum)
        val victimSnapshot = MfPlayer(MfPlayerId.fromBukkitPlayer(victim), power = 10.0)
        `when`(victim.killer).thenReturn(killer)
        `when`(players.getPlayer(victim)).thenReturn(victimSnapshot)
        `when`(players.getPlayer(killer)).thenReturn(snapshot)

        PlayerDeathListener(plugin).onPlayerDeath(event)

        val killerSaves = saved.filter { it.id == snapshot.id }
        if (expected == power) {
            assertEquals(emptyList<MfPlayer>(), killerSaves)
        } else {
            assertEquals(expected, killerSaves.single().power)
            assertEquals(maximum, killerSaves.single().maxPowerOverride)
        }
    }
}
