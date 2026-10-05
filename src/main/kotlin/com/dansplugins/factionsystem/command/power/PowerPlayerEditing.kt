package com.dansplugins.factionsystem.command.power

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import java.util.logging.Level

private data class PowerPlayerKey(val plugin: MedievalFactions, val playerId: MfPlayerId)
private data class PowerPlayerChange(
    val sender: CommandSender,
    val fallbackPlayer: MfPlayer,
    val edit: (MfPlayer) -> MfPlayer,
    val success: (MfPlayer) -> String
)
private val powerEditLock = Any()
private val powerEditQueues = mutableMapOf<PowerPlayerKey, ArrayDeque<PowerPlayerChange>>()

internal fun MedievalFactions.knownPowerPlayer(sender: CommandSender, name: String): OfflinePlayer? {
    val target = server.getOfflinePlayer(name)
    if (!target.isOnline && !target.hasPlayedBefore()) {
        sender.sendMessage("$RED${language["CommandPowerSetInvalidTarget"]}")
        return null
    }
    return target
}

internal fun MedievalFactions.editPowerPlayer(
    sender: CommandSender,
    target: OfflinePlayer,
    edit: (MfPlayer) -> MfPlayer,
    success: (MfPlayer) -> String
) {
    // Capture Bukkit-backed identity on the command thread. Both roots share the same ordered
    // queue, so a maximum change followed by a fill reads the newly saved maximum.
    val fallbackPlayer = MfPlayer(this, target)
    val key = PowerPlayerKey(this, fallbackPlayer.id)
    val startWorker = synchronized(powerEditLock) {
        val start = key !in powerEditQueues
        powerEditQueues.getOrPut(key) { ArrayDeque() }.addLast(PowerPlayerChange(sender, fallbackPlayer, edit, success))
        start
    }
    if (startWorker) {
        server.scheduler.runTaskAsynchronously(this, Runnable { drainPowerEdits(key) })
    }
}

private fun MedievalFactions.drainPowerEdits(key: PowerPlayerKey) {
    while (true) {
        val change = synchronized(powerEditLock) {
            val queue = powerEditQueues[key] ?: return
            if (queue.isEmpty()) {
                powerEditQueues.remove(key)
                null
            } else {
                queue.removeFirst()
            }
        } ?: return
        try {
            val playerService = services.playerService
            val player = playerService.getPlayer(key.playerId) ?: change.fallbackPlayer
            when (val result = playerService.save(change.edit(player))) {
                is Success -> server.scheduler.runTask(
                    this,
                    Runnable {
                    change.sender.sendMessage("$GREEN${change.success(result.value)}")
                }
                )
                is Failure -> {
                    logger.log(Level.SEVERE, "Failed to save player: ${result.reason.message}", result.reason.cause)
                    sendPowerSaveFailure(change.sender)
                }
            }
        } catch (exception: Exception) {
            // A failed write must not strand subsequent edits in this player's queue.
            logger.log(Level.SEVERE, "Failed to edit power for ${key.playerId.value}", exception)
            runCatching { sendPowerSaveFailure(change.sender) }
        }
    }
}

private fun MedievalFactions.sendPowerSaveFailure(sender: CommandSender) {
    server.scheduler.runTask(
        this,
        Runnable {
        sender.sendMessage("$RED${language["CommandPowerSetFailedToSaveTargetPlayer"]}")
    }
    )
}

internal fun MedievalFactions.powerPlayerSuggestions(args: Array<out String>): List<String> = when {
    args.size > 1 -> emptyList()
    else -> server.offlinePlayers.mapNotNull { it.name }
        .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
}
