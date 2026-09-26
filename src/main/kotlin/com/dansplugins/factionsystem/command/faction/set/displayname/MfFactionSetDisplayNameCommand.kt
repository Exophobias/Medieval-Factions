package com.dansplugins.factionsystem.command.faction.set.displayname

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.unquote
import com.dansplugins.factionsystem.faction.MfFactionDisplayName
import com.dansplugins.factionsystem.faction.MfFactionId
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

/** Changes only the label shown to players, leaving the canonical faction name intact. */
class MfFactionSetDisplayNameCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val parts = args.unquote()
        val targeting = parts.firstOrNull() == "--faction"
        if (targeting && (!sender.hasPermission("mf.admin") || !sender.hasPermission("mf.admin.displayname"))) {
            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameNoPermission"]}")
            return true
        }
        if (!targeting && !sender.hasPermission("mf.displayname")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameNoPermission"]}")
            return true
        }
        if (!targeting && sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameNotAPlayer"]}")
            return true
        }
        if (parts.size < if (targeting) 3 else 1) {
            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameUsage"]}")
            return true
        }
        val requested = parts.drop(if (targeting) 2 else 0).joinToString(" ")
        val reset = requested.equals("reset", ignoreCase = true)
        if (!reset && !MfFactionDisplayName.isValid(requested)) {
            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameInvalid", MfFactionDisplayName.MAX_LENGTH.toString()]}")
            return true
        }

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val factionService = plugin.services.factionService
                val mfPlayer = if (targeting) {
                    null
                } else {
                    val player = sender as Player
                    plugin.services.playerService.getPlayer(player)
                        ?: plugin.services.playerService.save(MfPlayer(plugin, player)).onFailure {
                            sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameFailedToSavePlayer"]}")
                            plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                            return@Runnable
                        }
                }
                val faction = if (targeting) {
                    factionService.getFaction(MfFactionId(parts[1])) ?: factionService.getFaction(parts[1])
                } else {
                    factionService.getFaction(requireNotNull(mfPlayer).id)
                }
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameFactionNotFound"]}")
                    return@Runnable
                }
                if (!targeting) {
                    val role = faction.getRole(requireNotNull(mfPlayer).id)
                    if (role?.hasPermission(faction, plugin.factionPermissions.changeName) != true) {
                        sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameNoFactionPermission"]}")
                        return@Runnable
                    }
                }
                val override = if (reset || requested == faction.name) null else requested
                val saved = factionService.save(faction.copy(displayNameOverride = override)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionSetDisplayNameFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                sender.sendMessage("$GREEN${plugin.language["CommandFactionSetDisplayNameSuccess", saved.displayName]}")
                if (!targeting && sender is Player) {
                    plugin.server.scheduler.runTask(plugin, Runnable { sender.performCommand("faction info") })
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
        val parts = args.unquote()
        if (parts.size == 1) {
            val choices = mutableListOf("reset")
            if (sender.hasPermission("mf.admin") && sender.hasPermission("mf.admin.displayname")) choices += "--faction"
            return choices.filter { it.startsWith(parts[0], ignoreCase = true) }
        }
        if (parts.firstOrNull() == "--faction" && parts.size == 2) {
            return plugin.services.factionService.factions.map { it.name }
                .filter { it.startsWith(parts[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
