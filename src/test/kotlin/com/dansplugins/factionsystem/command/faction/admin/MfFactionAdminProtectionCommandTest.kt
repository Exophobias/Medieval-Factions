package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class MfFactionAdminProtectionCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var sender: CommandSender
    private lateinit var command: Command
    private lateinit var scheduler: BukkitScheduler
    private lateinit var factionService: MfFactionService
    private lateinit var faction: MfFaction
    private var saved: MfFaction? = null

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        sender = mock(CommandSender::class.java)
        command = mock(Command::class.java)
        val server = mock(Server::class.java)
        scheduler = mock(BukkitScheduler::class.java)
        val services = mock(Services::class.java)
        factionService = mock(MfFactionService::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factionService)
        `when`(plugin.language).thenReturn(mock(Language::class.java))
        `when`(sender.hasPermission("mf.admin")).thenReturn(true)
        `when`(sender.hasPermission("mf.admin.protection")).thenReturn(true)
        faction = MfFaction(
            plugin,
            name = "Safe Haven",
            flags = MfFlagValues(plugin),
            roles = mock(MfFactionRoles::class.java),
            defaultPermissionsByName = emptyMap(),
            adminLeaderless = true
        )
        `when`(factionService.getFaction("Safe Haven")).thenReturn(faction)
        `when`(factionService.save(anyArg())).thenAnswer {
            val candidate = it.getArgument<MfFaction>(0)
            saved = candidate
            Success(candidate)
        }
    }

    private fun runScheduledCommand() {
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
    }

    @Test
    fun staffCanBlockAndResetPvpWithoutChangingOtherFactionState() {
        val router = MfFactionAdminCommand(plugin)
        assertTrue(router.onCommand(sender, command, "f", arrayOf("protection", "Safe", "Haven", "pvp", "off")))
        runScheduledCommand()
        val blocked = requireNotNull(saved)
        assertFalse(AdminFactionProtection.PVP.isAllowed(blocked))
        assertTrue(AdminFactionProtection.PVP.isExplicit(blocked))
        assertTrue(AdminFactionProtection.PLAYER_DAMAGE.isAllowed(blocked))
        assertTrue(blocked.copy(flags = faction.flags) == faction)

        saved = null
        `when`(factionService.getFaction("Safe Haven")).thenReturn(blocked)
        assertTrue(MfFactionAdminProtectionCommand(plugin).onCommand(sender, command, "f", arrayOf("Safe", "Haven", "pvp", "reset")))
        // The second scheduled task is captured separately because this test ran the router first.
        val tasks = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler, org.mockito.Mockito.times(2)).runTaskAsynchronously(eq(plugin), tasks.capture())
        tasks.allValues.last().run()
        val reset = requireNotNull(saved)
        assertTrue(AdminFactionProtection.PVP.isAllowed(reset))
        assertFalse(AdminFactionProtection.PVP.isExplicit(reset))
    }

    @Test
    fun ordinaryFactionCannotAcquireAdminProtectionSettings() {
        `when`(factionService.getFaction("Safe Haven")).thenReturn(faction.copy(adminLeaderless = false))

        assertTrue(MfFactionAdminProtectionCommand(plugin).onCommand(sender, command, "f", arrayOf("Safe", "Haven", "playerdamage", "off")))
        runScheduledCommand()

        assertTrue(saved == null)
    }

    @Test
    fun permissionIsRequiredBeforeScheduling() {
        `when`(sender.hasPermission("mf.admin.protection")).thenReturn(false)

        assertTrue(MfFactionAdminProtectionCommand(plugin).onCommand(sender, command, "f", arrayOf("Safe", "Haven", "mobspawning", "off")))

        verifyNoInteractions(scheduler)
    }
}
