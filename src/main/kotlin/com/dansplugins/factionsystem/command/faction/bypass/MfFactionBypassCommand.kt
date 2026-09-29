package com.dansplugins.factionsystem.command.faction.bypass

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Level

class MfFactionBypassCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private data class PendingChange(val fallbackPlayer: MfPlayer, val muteWarnings: Boolean?)

    private class PlayerQueue {
        val changes = ArrayDeque<PendingChange>()
        var workerScheduled = false
    }

    private val queueLock = Any()
    private val queuesByPlayer = mutableMapOf<UUID, PlayerQueue>()

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

        if (args.size == 1) {
            val mfPlayer = plugin.services.playerService.getPlayer(sender) ?: MfPlayer(plugin, sender)
            sender.sendMessage("$GREEN${warningStatus(mfPlayer)}")
            return true
        }

        // Capture Bukkit-backed identity on the command thread; the worker only reads MF's cache.
        val playerId = sender.uniqueId
        val change = PendingChange(MfPlayer(plugin, sender), args.getOrNull(1)?.equals("off", true))
        val startWorker = synchronized(queueLock) {
            val queue = queuesByPlayer.getOrPut(playerId) { PlayerQueue() }
            queue.changes.addLast(change)
            if (queue.workerScheduled) {
                false
            } else {
                queue.workerScheduled = true
                true
            }
        }
        if (startWorker) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { drainPlayerQueue(playerId) })
        }
        return true
    }

    private fun drainPlayerQueue(playerId: UUID) {
        while (true) {
            val change = synchronized(queueLock) {
                val queue = queuesByPlayer[playerId] ?: return
                if (queue.changes.isEmpty()) {
                    queuesByPlayer.remove(playerId)
                    null
                } else {
                    queue.changes.removeFirst()
                }
            } ?: return

            try {
                val playerService = plugin.services.playerService
                val current = playerService.getPlayer(change.fallbackPlayer.id) ?: change.fallbackPlayer
                val updated = if (change.muteWarnings == null) {
                    current.copy(isBypassEnabled = !current.isBypassEnabled)
                } else {
                    current.copy(isBypassWarningMuted = change.muteWarnings)
                }
                when (val result = playerService.save(updated)) {
                    is Success -> sendSuccess(playerId, change, result.value)
                    is Failure -> {
                        plugin.logger.log(Level.SEVERE, "Failed to save player: ${result.reason.message}", result.reason.cause)
                        sendSaveFailure(playerId)
                    }
                }
            } catch (exception: Exception) {
                // One failed write must not strand later commands in this player's queue.
                plugin.logger.log(Level.SEVERE, "Failed to process bypass command for $playerId", exception)
                runCatching { sendSaveFailure(playerId) }
            }
        }
    }

    private fun sendSuccess(playerId: UUID, change: PendingChange, saved: MfPlayer) {
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                if (change.muteWarnings != null || saved.isBypassEnabled) {
                    // Re-enabling warnings or bypass gives an immediate reminder on the next protected action.
                    plugin.resetTerritoryBypassWarning(playerId)
                }
                val player = plugin.server.getPlayer(playerId)
                if (player?.isOnline == true) {
                    val message = if (change.muteWarnings != null) {
                        warningStatus(saved)
                    } else if (saved.isBypassEnabled) {
                        plugin.language["CommandFactionBypassEnabled"]
                    } else {
                        plugin.language["CommandFactionBypassDisabled"]
                    }
                    player.sendMessage("$GREEN$message")
                }
            }
        )
    }

    private fun sendSaveFailure(playerId: UUID) {
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val player = plugin.server.getPlayer(playerId)
                if (player?.isOnline == true) {
                    player.sendMessage("$RED${plugin.language["CommandFactionBypassFailedToSavePlayer"]}")
                }
            }
        )
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
