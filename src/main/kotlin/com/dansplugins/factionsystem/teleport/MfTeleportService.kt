package com.dansplugins.factionsystem.teleport

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.ChatColor
import org.bukkit.ChatColor.GRAY
import org.bukkit.ChatColor.RED
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.time.Clock
import java.util.*

class MfTeleportService(private val plugin: MedievalFactions, private val clock: Clock = Clock.systemUTC()) {

    private val tasks = mutableMapOf<UUID, BukkitTask>()
    private val lastHomeTeleportKey = NamespacedKey(plugin, "faction_home_last_teleport_at")

    fun teleport(player: Player, location: Location, message: String? = null) {
        val cooldownSeconds = remainingCooldownSeconds(player)
        if (cooldownSeconds > 0) {
            val remaining = "${cooldownSeconds / 60}:${(cooldownSeconds % 60).toString().padStart(2, '0')}"
            player.sendMessage("$RED${plugin.language["CommandFactionHomeCooldown", remaining]}")
            return
        }

        val teleportDelay = plugin.config.getInt("factions.factionHomeTeleportDelay")
        if (teleportDelay <= 0) {
            completeTeleport(player, location, message)
            return
        }
        val uuid = player.uniqueId
        tasks[uuid]?.cancel()
        player.sendMessage("$GRAY${plugin.language["Teleporting", teleportDelay.toString()]}")
        val task = plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                tasks.remove(uuid)
                val playerToTeleport = plugin.server.getPlayer(uuid)
                if (playerToTeleport != null) {
                    completeTeleport(playerToTeleport, location, message)
                }
            },
            teleportDelay * 20L
        )
        tasks[uuid] = task
    }

    private fun completeTeleport(player: Player, location: Location, message: String?) {
        if (!player.teleport(location)) {
            player.sendMessage("$RED${plugin.language["CommandFactionHomeTeleportFailed"]}")
            return
        }
        if (plugin.config.getInt("factions.factionHomeCooldownMinutes", 30) > 0) {
            player.persistentDataContainer.set(lastHomeTeleportKey, PersistentDataType.LONG, clock.millis())
        }
        if (message != null) player.sendMessage(message)
    }

    private fun remainingCooldownSeconds(player: Player): Long {
        val cooldownMinutes = plugin.config.getInt("factions.factionHomeCooldownMinutes", 30)
        if (cooldownMinutes <= 0) return 0
        val lastTeleport = player.persistentDataContainer.get(lastHomeTeleportKey, PersistentDataType.LONG)
            ?: return 0
        val cooldownMillis = cooldownMinutes.toLong() * 60_000L
        val elapsed = (clock.millis() - lastTeleport).coerceAtLeast(0L)
        if (elapsed >= cooldownMillis) return 0
        return (cooldownMillis - elapsed + 999L) / 1_000L
    }

    fun cancelTeleportation(player: Player) {
        val task = tasks[player.uniqueId]
        if (task != null) {
            task.cancel()
            tasks.remove(player.uniqueId)
            player.sendMessage("${ChatColor.RED}${plugin.language["TeleportationCancelled"]}")
        }
    }
}
