package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

class MfFactionAdminMakeLeaderlessCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var factionService: MfFactionService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var sender: CommandSender
    private lateinit var command: Command
    private var savedFaction: MfFaction? = null

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(true)
        `when`(config.getInt("factions.maxNameLength")).thenReturn(32)
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
        savedFaction = null
        `when`(factionService.save(anyArg())).thenAnswer {
            val faction = it.getArgument<MfFaction>(0)
            savedFaction = faction
            Success(faction)
        }

        sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mf.admin")).thenReturn(true)
        command = mock(Command::class.java)
    }

    private fun runScheduledCommand() {
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
    }

    @Test
    fun convertsExistingFactionWithoutChangingItsMembersOrOtherState() {
        val factionId = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, factionId)
        val owner = MfPlayerId(UUID.randomUUID().toString())
        val heir = MfPlayerId(UUID.randomUUID().toString())
        val faction = MfFaction(
            plugin,
            id = factionId,
            name = "Existing Faction",
            description = "keep this",
            members = listOf(MfFactionMember(owner, roles.leaderRole ?: roles.default), MfFactionMember(heir, roles.default)),
            roles = roles,
            bonusPower = 42.0,
            primaryOwnerId = owner,
            heirId = heir
        )
        `when`(factionService.getFaction("Existing Faction")).thenReturn(faction)
        `when`(sender.hasPermission("mf.admin.makeleaderless")).thenReturn(true)

        assertTrue(MfFactionAdminCommand(plugin).onCommand(sender, command, "f", arrayOf("MAKELEADERLESS", "Existing", "Faction")))
        runScheduledCommand()

        val saved = requireNotNull(savedFaction)
        assertEquals(faction.copy(primaryOwnerId = null, heirId = null, adminLeaderless = true), saved)
        assertTrue(saved.adminLeaderless)
        assertNull(saved.primaryOwnerId)
        assertNull(saved.heirId)
    }

    @Test
    fun ordinarySenderCannotScheduleConversion() {
        assertTrue(MfFactionAdminCommand(plugin).onCommand(sender, command, "f", arrayOf("makeleaderless", "Existing", "Faction")))
        verifyNoInteractions(scheduler)
    }

    @Test
    fun disabledLeaderlessSettingCannotScheduleConversion() {
        `when`(sender.hasPermission("mf.admin.makeleaderless")).thenReturn(true)
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(false)

        assertTrue(MfFactionAdminCommand(plugin).onCommand(sender, command, "f", arrayOf("makeleaderless", "Existing", "Faction")))
        verifyNoInteractions(scheduler)
    }

    @Test
    fun adminCreateMarksNewEmptyFactionAsAdminLeaderless() {
        `when`(sender.hasPermission("mf.admin.create")).thenReturn(true)

        assertTrue(MfFactionAdminCreateCommand(plugin).onCommand(sender, command, "f", arrayOf("New", "Faction")))
        runScheduledCommand()

        val saved = requireNotNull(savedFaction)
        assertEquals("New Faction", saved.name)
        assertTrue(saved.members.isEmpty())
        assertNull(saved.primaryOwnerId)
        assertTrue(saved.adminLeaderless)
    }
}
