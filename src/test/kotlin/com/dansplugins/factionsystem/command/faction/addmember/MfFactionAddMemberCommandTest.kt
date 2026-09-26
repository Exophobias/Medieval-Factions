package com.dansplugins.factionsystem.command.faction.addmember

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.TestUtils
import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.MedievalFactionsApi
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import net.md_5.bungee.api.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfFactionAddMemberCommandTest {
    private val testUtils = TestUtils()

    private lateinit var fixture: TestUtils.CommandTestFixture
    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var playerService: MfPlayerService
    private lateinit var language: Language
    private lateinit var config: FileConfiguration
    private lateinit var server: Server
    private lateinit var api: MedievalFactionsApi
    private lateinit var uut: MfFactionAddMemberCommand

    @BeforeEach
    fun setUp() {
        fixture = testUtils.createCommandTestFixture()
        plugin = mock(MedievalFactions::class.java)
        mockServices()
        mockLanguageSystem()
        mockConfig()
        mockServer()
        api = mock(MedievalFactionsApi::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger("MfFactionAddMemberCommandTest"))
        uut = MfFactionAddMemberCommand(plugin, api)
    }

    @Test
    fun testOnCommand_senderWithoutPermission() {
        // prepare
        val sender = fixture.sender
        val command = fixture.command
        `when`(sender.hasPermission("mf.force.addmember")).thenReturn(false)
        `when`(sender.hasPermission("mf.force.join")).thenReturn(false)
        `when`(language["CommandFactionAddMemberNoPermission"]).thenReturn("No permission")

        // execute
        val result = uut.onCommand(sender, command, "label", arrayOf())

        // verify
        assertTrue(result)
        verify(sender).sendMessage("${ChatColor.RED}No permission")
    }

    @Test
    fun testOnCommand_senderNotAPlayer() {
        // prepare
        val sender = fixture.sender
        val command = fixture.command
        `when`(sender.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberNotAPlayer"]).thenReturn("Not a player")

        // execute
        val result = uut.onCommand(sender, command, "label", arrayOf())

        // verify
        assertTrue(result)
        verify(sender).sendMessage("${ChatColor.RED}Not a player")
    }

    @Test
    fun testOnCommand_noArgumentsProvided() {
        // prepare
        val player = fixture.player
        val command = fixture.command
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberUsage"]).thenReturn("Usage")

        // execute
        val result = uut.onCommand(player, command, "label", arrayOf())

        // verify
        assertTrue(result)
        verify(player).sendMessage("${ChatColor.RED}Usage")
    }

    @Test
    fun testOnCommand_invalidTargetFaction() {
        // prepare
        val player = fixture.player
        val command = fixture.command
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberInvalidTargetFaction"]).thenReturn("No such faction")

        val targetPlayer = mockOfflinePlayer("targetPlayerName")
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(testUtils.createPlayerId(), name = "targetPlayerName"))
        `when`(factionService.getFaction("NonExistentFaction")).thenReturn(null)

        // execute
        val result = uut.onCommand(player, command, "label", arrayOf("targetPlayerName", "NonExistentFaction"))

        // verify
        assertTrue(result)
        verify(player).sendMessage("${ChatColor.RED}No such faction")
    }

    @Test
    fun testOnCommand_targetFactionFull_playerHasNoCurrentFaction() {
        // prepare
        val player = fixture.player
        val command = fixture.command
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberTargetFactionFull"]).thenReturn("Faction full")
        `when`(config.getInt("factions.maxMembers")).thenReturn(1)

        val targetPlayerId = testUtils.createPlayerId()
        val targetPlayer = mockOfflinePlayer("targetPlayerName")
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(targetPlayerId, name = "targetPlayerName"))

        val targetFaction = mockFaction("TargetFaction", memberCount = 1)
        `when`(factionService.getFaction("TargetFaction")).thenReturn(targetFaction)
        `when`(factionService.getFaction(targetPlayerId)).thenReturn(null)

        // execute
        val result = uut.onCommand(player, command, "label", arrayOf("targetPlayerName", "TargetFaction"))

        // verify
        assertTrue(result)
        verify(player).sendMessage("${ChatColor.RED}Faction full")
        verify(factionService, never()).save(anyFaction())
    }

    /**
     * Regression test for the target-faction-full check running after the player was already
     * removed from their current faction. If the full check is not performed before the removal,
     * this test fails because factionService.save is invoked to remove the player from currentFaction.
     */
    @Test
    fun testOnCommand_targetFactionFull_doesNotRemovePlayerFromCurrentFaction() {
        // prepare
        val player = fixture.player
        val command = fixture.command
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberTargetFactionFull"]).thenReturn("Faction full")
        `when`(config.getInt("factions.maxMembers")).thenReturn(1)

        val targetPlayerId = testUtils.createPlayerId()
        val targetPlayer = mockOfflinePlayer("targetPlayerName")
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(targetPlayerId, name = "targetPlayerName"))

        val targetFaction = mockFaction("TargetFaction", memberCount = 1)
        `when`(factionService.getFaction("TargetFaction")).thenReturn(targetFaction)

        val currentFaction = mockFaction("CurrentFaction", memberCount = 1)
        `when`(factionService.getFaction(targetPlayerId)).thenReturn(currentFaction)

        // execute: force flag so the removal branch would otherwise be reached directly
        val result = uut.onCommand(player, command, "label", arrayOf("targetPlayerName", "TargetFaction", "-f"))

        // verify
        assertTrue(result)
        verify(player).sendMessage("${ChatColor.RED}Faction full")
        verify(factionService, never()).save(anyFaction())
    }

    @Test
    fun testOnCommand_sameFactionRefusesWithoutTransferOrDuplicateMember() {
        val player = fixture.player
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberTargetPlayerAlreadyInFaction"]).thenReturn("Already there")
        val targetPlayerId = testUtils.createPlayerId()
        val targetPlayer = mockOfflinePlayer("targetPlayerName")
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(targetPlayerId, name = "targetPlayerName"))
        val faction = mockFaction("TargetFaction", memberIds = listOf(targetPlayerId))
        `when`(factionService.getFaction("TargetFaction")).thenReturn(faction)
        `when`(factionService.getFaction(targetPlayerId)).thenReturn(faction)

        assertTrue(uut.onCommand(player, fixture.command, "label", arrayOf("targetPlayerName", "TargetFaction", "-f")))

        verify(player).sendMessage("${org.bukkit.ChatColor.RED}Already there")
        verifyNoInteractions(api)
        verify(factionService, never()).save(anyFaction())
    }

    @Test
    fun testOnCommand_forcedMoveUsesTransferApiForFinalSourceMember() {
        val player = fixture.player
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        val targetUuid = UUID.randomUUID()
        val targetPlayerId = MfPlayerId(targetUuid.toString())
        val targetPlayer = mockOfflinePlayer("targetPlayerName", targetUuid)
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(targetPlayerId, name = "targetPlayerName"))
        val source = mockFaction("CurrentFaction", memberIds = listOf(targetPlayerId))
        val destination = mockFaction("TargetFaction", memberCount = 0)
        `when`(factionService.getFaction("TargetFaction")).thenReturn(destination)
        `when`(factionService.getFaction(targetPlayerId)).thenReturn(source)
        `when`(factionService.getFaction(destination.id)).thenReturn(destination)
        `when`(api.transferMembers(FactionId(source.id.value), FactionId(destination.id.value), listOf(targetUuid)))
            .thenReturn(ApiResult.success())
        `when`(language["FactionNewMemberNotificationTitle", "targetPlayerName"]).thenReturn("Joined")
        `when`(language["FactionNewMemberNotificationBody", "targetPlayerName"]).thenReturn("Joined body")
        `when`(language["CommandFactionAddMemberSuccess", "TargetFaction"]).thenReturn("Success")

        assertTrue(uut.onCommand(player, fixture.command, "label", arrayOf("targetPlayerName", "TargetFaction", "-f")))

        verify(api).transferMembers(FactionId(source.id.value), FactionId(destination.id.value), listOf(targetUuid))
        verify(factionService, never()).save(anyFaction())
        verify(destination).sendMessage("Joined", "Joined body")
    }

    @Test
    fun testOnCommand_failedTransferDoesNotAnnounceArrival() {
        val player = fixture.player
        `when`(player.hasPermission("mf.force.addmember")).thenReturn(true)
        `when`(language["CommandFactionAddMemberFailedToTransfer"]).thenReturn("Check rosters")
        val targetUuid = UUID.randomUUID()
        val targetPlayerId = MfPlayerId(targetUuid.toString())
        val targetPlayer = mockOfflinePlayer("targetPlayerName", targetUuid)
        `when`(playerService.getPlayer(targetPlayer)).thenReturn(MfPlayer(targetPlayerId, name = "targetPlayerName"))
        val source = mockFaction("CurrentFaction", memberIds = listOf(targetPlayerId))
        val destination = mockFaction("TargetFaction", memberCount = 0)
        `when`(factionService.getFaction("TargetFaction")).thenReturn(destination)
        `when`(factionService.getFaction(targetPlayerId)).thenReturn(source)
        `when`(api.transferMembers(FactionId(source.id.value), FactionId(destination.id.value), listOf(targetUuid)))
            .thenReturn(ApiResult.failure("Failed after departure"))

        assertTrue(uut.onCommand(player, fixture.command, "label", arrayOf("targetPlayerName", "TargetFaction", "-f")))

        verify(player).sendMessage("${org.bukkit.ChatColor.RED}Check rosters")
        verify(factionService, never()).save(anyFaction())
        verify(destination, never()).sendMessage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())
    }

    // Helper functions

    /**
     * Mockito's [ArgumentMatchers.any] returns null, which trips Kotlin's null-check on the
     * non-nullable [MfFaction] parameter of [MfFactionService.save] before the matcher is
     * registered, corrupting Mockito's matcher stack for subsequent tests. This generic
     * indirection avoids the compiler inserting that check.
     */
    private fun <T> anyFaction(): T {
        ArgumentMatchers.any<MfFaction>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun mockOfflinePlayer(name: String, uuid: UUID = UUID.randomUUID()): OfflinePlayer {
        val offlinePlayer = mock(OfflinePlayer::class.java)
        `when`(offlinePlayer.hasPlayedBefore()).thenReturn(true)
        `when`(offlinePlayer.uniqueId).thenReturn(uuid)
        `when`(server.getOfflinePlayer(name)).thenReturn(offlinePlayer)
        return offlinePlayer
    }

    private fun mockFaction(
        name: String,
        memberCount: Int = 0,
        memberIds: List<MfPlayerId> = (1..memberCount).map { testUtils.createPlayerId() }
    ): MfFaction {
        val faction = mock(MfFaction::class.java)
        `when`(faction.id).thenReturn(MfFactionId.generate())
        `when`(faction.name).thenReturn(name)
        val members = memberIds.map {
            MfFactionMember(it, mock(MfFactionRole::class.java))
        }
        `when`(faction.members).thenReturn(members)
        val roles = mock(MfFactionRoles::class.java)
        `when`(roles.default).thenReturn(mock(MfFactionRole::class.java))
        `when`(faction.roles).thenReturn(roles)
        return faction
    }

    private fun mockServices() {
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)

        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)

        playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
    }

    private fun mockLanguageSystem() {
        language = mock(Language::class.java)
        `when`(plugin.language).thenReturn(language)
    }

    private fun mockConfig() {
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
    }

    private fun mockServer() {
        server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
    }
}
