package com.dansplugins.factionsystem.command.power

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import com.dansplugins.factionsystem.command.power.fill.MfPowerFillCommand
import com.dansplugins.factionsystem.command.power.set.MfPowerSetCommand
import com.dansplugins.factionsystem.command.power.setmax.MfPowerSetMaxCommand
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfPowerCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val powerSetCommand = MfPowerSetCommand(plugin)
    private val powerFillCommand = MfPowerFillCommand(plugin)
    private val powerSetMaxCommand = MfPowerSetMaxCommand(plugin)

    private val setAliases = listOf("set", plugin.language["CmdPowerSet"])
    private val fillAliases = listOf("fill")
    private val setMaxAliases = listOf("setmax")

    private val subcommands = setAliases + fillAliases + setMaxAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "power")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in setAliases -> NativeCommandHelp.canUse(sender, "/power set", helpEntries())
        in fillAliases -> NativeCommandHelp.canUse(sender, "/power fill", helpEntries())
        in setMaxAliases -> NativeCommandHelp.canUse(sender, "/power setmax", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "power", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in setAliases -> powerSetCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in fillAliases -> powerFillCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setMaxAliases -> powerSetMaxCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "power", emptyArray(), helpEntries())
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ) = when {
        args.firstOrNull()?.equals("help", true) == true -> NativeCommandHelp.helpTabs(sender, args.drop(1).toTypedArray(), helpEntries())
        args.size > 1 && !permitted(sender, args.first()) -> emptyList()
        args.isEmpty() -> visibleSubcommands(sender)
        args.size == 1 -> visibleSubcommands(sender).filter { it.startsWith(args[0].lowercase()) }
        else -> when (args.first().lowercase()) {
            in setAliases -> powerSetCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in fillAliases -> powerFillCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setMaxAliases -> powerSetMaxCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
