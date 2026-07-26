package com.dansplugins.factionsystem.command.faction.heir

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level.SEVERE

/**
 * Names who inherits the faction if its head departs: /faction heir [player], or /faction heir none.
 *
 * A nomination rather than an appointment, so it needs no confirmation step: it changes nothing until
 * the head is actually gone, and running it again replaces it. That is the whole difference between
 * this and /faction transfer, which hands the title over immediately and therefore does confirm.
 *
 * Only the recorded head may nominate, for the same reason only they may transfer: this is their
 * succession to decide, not that of anyone who happens to hold the right to disband.
 */
class MfFactionHeirCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.heir")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirNotAPlayer"]}")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirUsage"]}")
            return true
        }
        val targetName = args.joinToString(" ")
        val clearing = targetName.equals("none", ignoreCase = true)
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(mfPlayer.id)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirMustBeInAFaction"]}")
                    return@Runnable
                }
                if (faction.primaryOwnerId != mfPlayer.id) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirNotPrimaryOwner"]}")
                    return@Runnable
                }
                val heirId = if (clearing) {
                    null
                } else {
                    val target = plugin.server.offlinePlayers.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    val targetMfPlayer = target?.let(playerService::getPlayer)
                    if (targetMfPlayer == null || faction.members.none { it.playerId == targetMfPlayer.id }) {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirTargetNotAMember", targetName]}")
                        return@Runnable
                    }
                    if (targetMfPlayer.id == mfPlayer.id) {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirCannotNominateSelf"]}")
                        return@Runnable
                    }
                    targetMfPlayer.id
                }
                factionService.save(faction.copy(heirId = heirId)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                if (heirId == null) {
                    sender.sendMessage("$GREEN${plugin.language["CommandFactionHeirCleared"]}")
                } else {
                    sender.sendMessage("$GREEN${plugin.language["CommandFactionHeirSuccess", targetName]}")
                }
            }
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        if (sender !is Player) return emptyList()
        val playerService = plugin.services.playerService
        val faction = playerService.getPlayer(sender)?.let { plugin.services.factionService.getFaction(it.id) }
            ?: return emptyList()
        val prefix = args.lastOrNull()?.lowercase() ?: ""
        return (faction.members.mapNotNull { playerService.getPlayer(it.playerId)?.name } + "none")
            .filter { it.lowercase().startsWith(prefix) }
    }
}
