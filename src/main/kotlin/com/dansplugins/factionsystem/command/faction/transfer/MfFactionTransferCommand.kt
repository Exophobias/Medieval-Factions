package com.dansplugins.factionsystem.command.faction.transfer

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level.SEVERE
import net.md_5.bungee.api.ChatColor as SpigotChatColor

/**
 * Hands the faction to another member: /faction transfer [player].
 *
 * The deliberate counterpart to succession. Succession fires when the head is already gone and has to
 * guess; this is the head choosing, while still present, and it therefore overrides everything.
 *
 * Only the recorded head may run it, not merely someone holding the right to disband, because this is
 * a question of identity rather than capability - a faction may have several members who can dissolve
 * it, and none of them is entitled to hand the title away.
 *
 * Confirmation follows MF's existing idiom from /faction join: the bare command prints a clickable
 * Confirm that reruns it with -f. Nothing happens without the flag, so the title cannot change on a
 * single mistyped name.
 */
class MfFactionTransferCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.transfer")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionTransferNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionTransferNotAPlayer"]}")
            return true
        }
        val confirmed = args.lastOrNull() == "-f"
        val nameArgs = if (confirmed) args.dropLast(1) else args.toList()
        if (nameArgs.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionTransferUsage"]}")
            return true
        }
        val targetName = nameArgs.joinToString(" ")
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandFactionTransferFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(mfPlayer.id)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionTransferMustBeInAFaction"]}")
                    return@Runnable
                }
                if (faction.primaryOwnerId != mfPlayer.id) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionTransferNotPrimaryOwner"]}")
                    return@Runnable
                }
                val target = plugin.server.offlinePlayers.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                val targetMfPlayer = target?.let(playerService::getPlayer)
                val targetMember = targetMfPlayer?.let { candidate ->
                    faction.members.singleOrNull { it.playerId == candidate.id }
                }
                if (targetMfPlayer == null || targetMember == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionTransferTargetNotAMember", targetName]}")
                    return@Runnable
                }
                if (targetMfPlayer.id == mfPlayer.id) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionTransferCannotTransferToSelf"]}")
                    return@Runnable
                }
                if (!confirmed) {
                    confirmTransfer(sender, targetMfPlayer.name ?: targetName)
                    return@Runnable
                }
                // The successor is given the top role as well as the title, matching what
                // /f admin setleader does: a head who cannot act is not a head. The outgoing head
                // keeps whatever role they had, since MF has no notion of demotion and the new head
                // can adjust it with /f role set.
                val topRole = faction.roles.leaderRole
                val updatedFaction = factionService.save(
                    faction.copy(
                        members = if (topRole == null) {
                            faction.members
                        } else {
                            faction.members.map { member ->
                                if (member.playerId == targetMfPlayer.id) member.copy(role = topRole) else member
                            }
                        },
                        primaryOwnerId = targetMfPlayer.id,
                        heirId = null
                    )
                ).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionTransferFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                val successorName = targetMfPlayer.name ?: targetName
                updatedFaction.sendMessage(
                    plugin.language["FactionNewLeaderNotificationTitle", successorName],
                    plugin.language["FactionNewLeaderNotificationBody", successorName]
                )
                sender.sendMessage("$GREEN${plugin.language["CommandFactionTransferSuccess", successorName]}")
            }
        )
        return true
    }

    private fun confirmTransfer(player: Player, targetName: String) {
        player.sendMessage("$RED${plugin.language["CommandFactionTransferConfirm", targetName]}")
        player.spigot().sendMessage(
            TextComponent(
                plugin.language["CommandFactionTransferConfirmButton"]
            ).apply {
                color = SpigotChatColor.GREEN
                hoverEvent = HoverEvent(SHOW_TEXT, Text(plugin.language["CommandFactionTransferConfirmButtonHover", targetName]))
                clickEvent = ClickEvent(RUN_COMMAND, "/faction transfer $targetName -f")
            }
        )
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> = memberNames(sender).filter { it.lowercase().startsWith(args.lastOrNull()?.lowercase() ?: "") }

    private fun memberNames(sender: CommandSender): List<String> {
        if (sender !is Player) return emptyList()
        val playerService = plugin.services.playerService
        val faction = playerService.getPlayer(sender)?.let { plugin.services.factionService.getFaction(it.id) }
            ?: return emptyList()
        return faction.members.mapNotNull { playerService.getPlayer(it.playerId)?.name }
    }
}
