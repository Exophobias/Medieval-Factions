package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.logging.Level.SEVERE

/** Designates an existing faction for admin-only leaderless preservation without changing its roster. */
class MfFactionAdminMakeLeaderlessCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.admin.makeleaderless")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminMakeLeaderlessNoPermission"]}")
            return true
        }
        if (!plugin.config.getBoolean("factions.allowLeaderlessFactions")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminMakeLeaderlessFeatureDisabled"]}")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminMakeLeaderlessUsage"]}")
            return true
        }

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val factionName = args.joinToString(" ")
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(factionName)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminMakeLeaderlessInvalidTargetFaction"]}")
                    return@Runnable
                }

                val updatedFaction = factionService.save(
                    faction.copy(primaryOwnerId = null, heirId = null, adminLeaderless = true)
                ).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminMakeLeaderlessFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                sender.sendMessage("$GREEN${plugin.language["CommandFactionAdminMakeLeaderlessSuccess", updatedFaction.name]}")
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
        if (!sender.hasPermission("mf.admin.makeleaderless")) return emptyList()
        if (args.isEmpty()) return emptyList()
        val prefix = args.joinToString(" ").lowercase()
        return plugin.services.factionService.factions
            .map { it.name }
            .filter { it.lowercase().startsWith(prefix) }
    }
}
