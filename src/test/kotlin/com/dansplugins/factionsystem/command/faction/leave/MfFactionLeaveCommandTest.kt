package com.dansplugins.factionsystem.command.faction.leave

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

class MfFactionLeaveCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var player: Player
    private lateinit var command: Command
    private var savedFaction: MfFaction? = null
    private val playerId = MfPlayerId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(true)
        `when`(config.getBoolean("factions.adminOnlyLeaderlessFactions")).thenReturn(true)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)
        val playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
        `when`(factionService.save(anyArg())).thenAnswer {
            val saved = it.getArgument<MfFaction>(0)
            savedFaction = saved
            Success(saved)
        }

        player = mock(Player::class.java)
        `when`(player.hasPermission("mf.leave")).thenReturn(true)
        `when`(playerService.getPlayer(player)).thenReturn(MfPlayer(playerId, name = "Member"))
        command = mock(Command::class.java)
    }

    private fun leave(faction: MfFaction) {
        `when`(factionService.getFaction(playerId)).thenReturn(faction)
        assertTrue(MfFactionLeaveCommand(plugin).onCommand(player, command, "f", emptyArray()))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
    }

    private fun faction(adminLeaderless: Boolean): MfFaction {
        val faction = MfFaction(plugin, name = "House", adminLeaderless = adminLeaderless)
        return faction.copy(members = listOf(MfFactionMember(playerId, faction.roles.leaderRole ?: faction.roles.default)))
    }

    @Test
    fun lastMemberLeavingAnOrdinaryFactionDisbandsIt() {
        val faction = faction(adminLeaderless = false)
        `when`(factionService.delete(faction.id)).thenReturn(Success(Unit))

        leave(faction)

        verify(factionService).delete(faction.id)
        assertNull(savedFaction)
    }

    @Test
    fun lastMemberLeavingAStaffDesignatedFactionPreservesIt() {
        leave(faction(adminLeaderless = true))

        val saved = requireNotNull(savedFaction)
        assertTrue(saved.members.isEmpty())
        assertTrue(saved.adminLeaderless)
    }
}
