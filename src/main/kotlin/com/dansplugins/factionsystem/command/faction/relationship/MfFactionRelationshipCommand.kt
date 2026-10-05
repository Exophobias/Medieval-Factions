package com.dansplugins.factionsystem.command.faction.relationship

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionRelationshipCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val factionRelationshipViewCommand = MfFactionRelationshipViewCommand(plugin)
    private val factionRelationshipAddCommand = MfFactionRelationshipAddCommand(plugin)
    private val factionRelationshipRemoveCommand = MfFactionRelationshipRemoveCommand(plugin)

    private val viewAliases = listOf("view", plugin.language["CmdFactionRelationshipView"])
    private val addAliases = listOf("add", "create", plugin.language["CmdFactionRelationshipAdd"])
    private val removeAliases = listOf("remove", "delete", plugin.language["CmdFactionRelationshipRemove"])

    private val subcommands = viewAliases + addAliases + removeAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f relationship")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in viewAliases -> NativeCommandHelp.canUse(sender, "/f relationship view", helpEntries())
        in addAliases -> NativeCommandHelp.canUse(sender, "/f relationship add", helpEntries())
        in removeAliases -> NativeCommandHelp.canUse(sender, "/f relationship remove", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f relationship", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in viewAliases -> factionRelationshipViewCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in addAliases -> factionRelationshipAddCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> factionRelationshipRemoveCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f relationship", emptyArray(), helpEntries())
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
            in viewAliases -> factionRelationshipViewCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in addAliases -> factionRelationshipAddCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> factionRelationshipRemoveCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
