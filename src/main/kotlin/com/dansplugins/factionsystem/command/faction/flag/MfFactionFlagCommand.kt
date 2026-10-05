package com.dansplugins.factionsystem.command.faction.flag

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionFlagCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val factionFlagListCommand = MfFactionFlagListCommand(plugin)
    private val factionFlagSetCommand = MfFactionFlagSetCommand(plugin)

    private val listAliases = listOf("list", plugin.language["CmdFactionFlagList"])
    private val setAliases = listOf("set", plugin.language["CmdFactionFlagSet"])

    private val subcommands = listAliases + setAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f flag")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in listAliases -> NativeCommandHelp.canUse(sender, "/f flag list", helpEntries())
        in setAliases -> NativeCommandHelp.canUse(sender, "/f flag set", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f flag", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in listAliases -> factionFlagListCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setAliases -> factionFlagSetCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f flag", emptyArray(), helpEntries())
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
            in listAliases -> factionFlagListCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setAliases -> factionFlagSetCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
