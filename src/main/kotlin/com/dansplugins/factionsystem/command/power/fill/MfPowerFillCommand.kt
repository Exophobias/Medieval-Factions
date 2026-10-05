package com.dansplugins.factionsystem.command.power.fill

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

class MfPowerFillCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val decimalFormat = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(plugin.language.locale))

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.power.fill") && !sender.hasPermission("mf.force.power")) {
            sender.sendMessage("$RED${plugin.language["CommandPowerFillNoPermission"]}")
            return true
        }
        if (args.size != 1) {
            sender.sendMessage("$RED${plugin.language["CommandPowerFillUsage"]}")
            return true
        }
        val target = plugin.knownPowerPlayer(sender, args[0]) ?: return true
        plugin.editPowerPlayer(
            sender,
            target,
            edit = {
                val maximum = it.effectiveMaxPower(plugin)
                it.copy(power = maximum, powerAtLogout = maximum)
            },
            success = {
                plugin.language[
                    "CommandPowerFillSuccess", target.name ?: plugin.language["UnknownPlayer"],
                    decimalFormat.format(it.power), decimalFormat.format(it.effectiveMaxPower(plugin))
                ]
            }
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>) =
        if (!sender.hasPermission("mf.power.fill") && !sender.hasPermission("mf.force.power")) {
            emptyList()
        } else {
            plugin.powerPlayerSuggestions(args)
        }
}
