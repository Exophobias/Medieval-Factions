package com.dansplugins.factionsystem.command.faction.flag

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class MfEmbassyCapacityFlagCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var sender: Player
    private lateinit var factions: MfFactionService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var realm: MfFaction
    private lateinit var command: MfFactionFlagSetCommand
    private var saved: MfFaction? = null

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val config = YamlConfiguration()
        config.set("factions.defaults.flags.maxEmbassyChunks", 4)
        `when`(plugin.config).thenReturn(config)
        val language = mock(Language::class.java) { invocation ->
            if (invocation.method.name == "get") invocation.arguments[0].toString() else null
        }
        `when`(plugin.language).thenReturn(language)
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val server = mock(Server::class.java)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        val services = mock(Services::class.java)
        val players = mock(MfPlayerService::class.java)
        factions = mock(MfFactionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(players)
        `when`(services.factionService).thenReturn(factions)
        val id = MfPlayerId(UUID.randomUUID().toString())
        sender = mock(Player::class.java)
        `when`(sender.hasPermission("mf.flag.set")).thenReturn(true)
        `when`(players.getPlayer(sender)).thenReturn(MfPlayer(id))
        realm = MfFaction(plugin, name = "PeerRealm",
            roles = MfFactionRoles(MfFactionRoleId.generate(), emptyList()),
            defaultPermissionsByName = emptyMap())
        `when`(factions.getFaction(id)).thenReturn(realm)
        `when`(factions.getFaction("PeerRealm")).thenReturn(realm)
        doAnswer { invocation ->
            saved = invocation.getArgument(0)
            Success(saved!!)
        }.`when`(factions).save(anyArg())
        doAnswer { invocation ->
            invocation.getArgument<Runnable>(1).run()
            mock(BukkitTask::class.java)
        }.`when`(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable::class.java))
        command = MfFactionFlagSetCommand(plugin)
    }

    @Test
    fun factionCannotIncreaseItsOwnEntitlement() {
        command.onCommand(sender, mock(Command::class.java), "f", arrayOf("maxEmbassyChunks", "8"))

        assertNull(saved)
        verify(scheduler, never()).runTaskAsynchronously(eq(plugin), any(Runnable::class.java))
    }

    @Test
    fun staffCanUpgradeAnotherRealmWithoutFactionRoleAuthority() {
        `when`(sender.hasPermission("mf.force.flag")).thenReturn(true)

        command.onCommand(sender, mock(Command::class.java), "f", arrayOf("PeerRealm", "maxEmbassyChunks", "8"))

        assertEquals(8, saved?.flags?.valuesByName?.get("maxEmbassyChunks"))
    }

    @Test
    fun staffPermissionIsRecheckedWhenQueuedWriteRuns() {
        `when`(sender.hasPermission("mf.force.flag")).thenReturn(true, false)

        command.onCommand(sender, mock(Command::class.java), "f", arrayOf("PeerRealm", "maxEmbassyChunks", "8"))

        assertNull(saved)
    }
}
