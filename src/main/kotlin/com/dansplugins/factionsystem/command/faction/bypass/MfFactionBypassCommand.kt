package com.dansplugins.factionsystem.command.faction.bypass

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
import java.util.logging.Level

class MfFactionBypassCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.bypass")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionBypassNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionBypassNotAPlayer"]}")
            return true
        }

        if (args.isNotEmpty() && !args[0].equals("warnings", ignoreCase = true)) {
            sender.sendMessage("$RED${plugin.language["CommandFactionBypassUsage"]}")
            return true
        }
        if (args.size > 2 || (args.size == 2 && !args[1].equals("on", true) && !args[1].equals("off", true))) {
            sender.sendMessage("$RED${plugin.language["CommandFactionBypassUsage"]}")
            return true
        }

        val playerService = plugin.services.playerService
        val mfPlayer = playerService.getPlayer(sender) ?: MfPlayer(plugin, sender)
        if (args.size == 1) {
            sender.sendMessage("$GREEN${warningStatus(mfPlayer)}")
            return true
        }

        val updatedPlayer = if (args.size == 2) {
            mfPlayer.copy(isBypassWarningMuted = args[1].equals("off", true))
        } else {
            mfPlayer.copy(isBypassEnabled = !mfPlayer.isBypassEnabled)
        }
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val saved = playerService.save(updatedPlayer).onFailure {
                    plugin.logger.log(Level.SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                    plugin.server.scheduler.runTask(
                        plugin,
                        Runnable {
                            if (sender.isOnline) {
                                sender.sendMessage("$RED${plugin.language["CommandFactionBypassFailedToSavePlayer"]}")
                            }
                        }
                    )
                    return@Runnable
                }
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        if (args.size == 2 || saved.isBypassEnabled) {
                            // Re-enabling warnings or bypass gives an immediate reminder on the next protected action.
                            plugin.resetTerritoryBypassWarning(sender.uniqueId)
                        }
                        if (sender.isOnline) {
                            val message = if (args.size == 2) {
                                warningStatus(saved)
                            } else if (saved.isBypassEnabled) {
                                plugin.language["CommandFactionBypassEnabled"]
                            } else {
                                plugin.language["CommandFactionBypassDisabled"]
                            }
                            sender.sendMessage("$GREEN$message")
                        }
                    }
                )
            }
        )
        return true
    }

    private fun warningStatus(player: MfPlayer): String {
        val key = if (player.isBypassWarningMuted) {
            "CommandFactionBypassWarningsOff"
        } else {
            "CommandFactionBypassWarningsOn"
        }
        return plugin.language[key]
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("mf.bypass")) return emptyList()
        return when (args.size) {
            1 -> listOf("warnings").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("warnings", true)) {
                listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }
}
