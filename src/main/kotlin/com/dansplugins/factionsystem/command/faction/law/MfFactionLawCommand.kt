package com.dansplugins.factionsystem.command.faction.law

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionLawCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val factionLawAddCommand = MfFactionLawAddCommand(plugin)
    private val factionLawEditCommand = MfFactionLawEditCommand(plugin)
    private val factionLawMoveCommand = MfFactionLawMoveCommand(plugin)
    private val factionLawRemoveCommand = MfFactionLawRemoveCommand(plugin)
    private val factionLawListCommand = MfFactionLawListCommand(plugin)

    private val addAliases = listOf("add", plugin.language["CmdFactionLawAdd"])
    private val editAliases = listOf("edit", plugin.language["CmdFactionLawEdit"])
    private val moveAliases = listOf("move", plugin.language["CmdFactionLawMove"])
    private val removeAliases = listOf("remove", plugin.language["CmdFactionLawRemove"])
    private val listAliases = listOf("list", plugin.language["CmdFactionLawList"])

    private val subcommands = addAliases + editAliases + moveAliases + removeAliases + listAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f law")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in addAliases -> NativeCommandHelp.canUse(sender, "/f law add", helpEntries())
        in editAliases -> NativeCommandHelp.canUse(sender, "/f law edit", helpEntries())
        in moveAliases -> NativeCommandHelp.canUse(sender, "/f law move", helpEntries())
        in removeAliases -> NativeCommandHelp.canUse(sender, "/f law remove", helpEntries())
        in listAliases -> NativeCommandHelp.canUse(sender, "/f law list", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f law", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in addAliases -> factionLawAddCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in editAliases -> factionLawEditCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in moveAliases -> factionLawMoveCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> factionLawRemoveCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> factionLawListCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f law", emptyArray(), helpEntries())
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
            in addAliases -> factionLawAddCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> factionLawRemoveCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> factionLawListCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
