package com.dansplugins.factionsystem.command.power.setmax

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.power.editPowerPlayer
import com.dansplugins.factionsystem.command.power.knownPowerPlayer
import com.dansplugins.factionsystem.command.power.powerPlayerSuggestions
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

class MfPowerSetMaxCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(plugin.language.locale))

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.power.setmax") && !sender.hasPermission("mf.force.power")) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetMaxNoPermission"]}")
            return true
        }
        if (args.size != 2) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetMaxUsage"]}")
            return true
        }
        val target = plugin.knownPowerPlayer(sender, args[0]) ?: return true
        val reset = args[1].equals("reset", ignoreCase = true)
        val maximum = if (reset) null else args[1].toDoubleOrNull()
        if (!reset && (maximum == null || !maximum.isFinite() || maximum <= 0 || maximum < plugin.config.getDouble("players.minPower"))) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetMaxInvalidMaximum", decimalFormat.format(plugin.config.getDouble("players.minPower"))]}")
            return true
        }
        plugin.editPowerPlayer(
            sender,
            target,
            edit = { it.copy(maxPowerOverride = maximum) },
            success = {
                plugin.language[
                    if (reset) "CommandPowerSetMaxResetSuccess" else "CommandPowerSetMaxSuccess",
                    target.name ?: plugin.language["UnknownPlayer"], decimalFormat.format(it.effectiveMaxPower(plugin)),
                    decimalFormat.format(it.power)
                ]
            }
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>) = when {
        !sender.hasPermission("mf.power.setmax") && !sender.hasPermission("mf.force.power") -> emptyList()
        args.size == 2 -> listOf("reset").filter { it.startsWith(args[1], ignoreCase = true) }
        else -> plugin.powerPlayerSuggestions(args)
    }
}
