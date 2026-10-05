package com.dansplugins.factionsystem.command.power.set

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

class MfPowerSetCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(plugin.language.locale))

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.power.set") && !sender.hasPermission("mf.force.power")) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetNoPermission"]}")
            return true
        }
        if (args.size != 2) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetUsage"]}")
            return true
        }
        val target = plugin.knownPowerPlayer(sender, args[0]) ?: return true
        val power = args[1].toDoubleOrNull()
        if (power == null || !power.isFinite()) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetInvalidPowerMustBeNumber"]}")
            return true
        }
        val minPower = plugin.config.getDouble("players.minPower")
        if (power < minPower) {
            sender.sendMessage("$RED${plugin.language["CommandPowerSetInvalidPowerTooLow", decimalFormat.format(minPower)]}")
            return true
        }
        plugin.editPowerPlayer(
            sender,
            target,
            edit = { it.copy(power = power, powerAtLogout = power) },
            success = {
                plugin.language[
                    "CommandPowerSetSuccess", target.name ?: plugin.language["UnknownPlayer"],
                    decimalFormat.format(it.power), decimalFormat.format(it.effectiveMaxPower(plugin))
                ]
            }
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ) = when {
        !sender.hasPermission("mf.power.set") && !sender.hasPermission("mf.force.power") -> emptyList()
        else -> plugin.powerPlayerSuggestions(args)
    }
}
