package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.ChatColor.YELLOW
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.logging.Level.SEVERE

/** Views or changes protection in a staff-designated faction's claims. */
class MfFactionAdminProtectionCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.admin.protection")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionNoPermission"]}")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionUsage"]}")
            return true
        }

        val setting = if (args.size >= 3) AdminFactionProtection.fromCommandName(args[args.lastIndex - 1]) else null
        val mode = if (setting != null) args.last().lowercase() else null
        if (setting != null && mode !in listOf("on", "off", "reset")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionUsage"]}")
            return true
        }
        val factionName = (if (setting == null) args.toList() else args.dropLast(2)).joinToString(" ")
        if (factionName.isBlank()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionUsage"]}")
            return true
        }

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(factionName)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionUnknownFaction"]}")
                    return@Runnable
                }
                if (!faction.adminLeaderless) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionNotAdminFaction"]}")
                    return@Runnable
                }

                if (setting == null) {
                    sender.sendMessage("$YELLOW${plugin.language["CommandFactionAdminProtectionStatus", faction.name]}")
                    AdminFactionProtection.entries.forEach { flag ->
                        val value = if (flag.isAllowed(faction)) "on (normal server rules)" else "off (blocked)"
                        val suffix = if (flag.isExplicit(faction)) "" else " [inherited]"
                        sender.sendMessage("$YELLOW${flag.commandName}: $value$suffix")
                    }
                    return@Runnable
                }

                val allowed = when (mode) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
                factionService.save(setting.withAllowed(plugin, faction, allowed)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminProtectionSaveFailed"]}")
                    plugin.logger.log(SEVERE, "Failed to save admin faction protection: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                val result = when (mode) {
                    "on" -> plugin.language["CommandFactionAdminProtectionValueOn"]
                    "off" -> plugin.language["CommandFactionAdminProtectionValueOff"]
                    else -> plugin.language["CommandFactionAdminProtectionValueReset"]
                }
                sender.sendMessage("$GREEN${plugin.language["CommandFactionAdminProtectionSuccess", faction.name, setting.commandName, result]}")
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
        if (!sender.hasPermission("mf.admin.protection") || args.isEmpty()) return emptyList()
        val factions = plugin.services.factionService.factions.filter { it.adminLeaderless }
        val completeFaction = factions.firstOrNull { it.name.equals(args.dropLast(1).joinToString(" "), ignoreCase = true) }
        if (completeFaction != null) {
            return AdminFactionProtection.entries.map { it.commandName }
                .filter { it.startsWith(args.last().lowercase()) }
        }
        val setting = AdminFactionProtection.fromCommandName(args.getOrNull(args.lastIndex - 1) ?: "")
        val factionName = args.dropLast(2).joinToString(" ")
        if (setting != null && factions.any { it.name.equals(factionName, ignoreCase = true) }) {
            return listOf("on", "off", "reset").filter { it.startsWith(args.last().lowercase()) }
        }
        val prefix = args.joinToString(" ").lowercase()
        return factions.map { it.name }.filter { it.lowercase().startsWith(prefix) }
    }
}
