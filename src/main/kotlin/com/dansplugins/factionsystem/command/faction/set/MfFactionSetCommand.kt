package com.dansplugins.factionsystem.command.faction.set

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import com.dansplugins.factionsystem.command.faction.set.description.MfFactionSetDescriptionCommand
import com.dansplugins.factionsystem.command.faction.set.displayname.MfFactionSetDisplayNameCommand
import com.dansplugins.factionsystem.command.faction.set.name.MfFactionSetNameCommand
import com.dansplugins.factionsystem.command.faction.set.prefix.MfFactionSetPrefixCommand
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionSetCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val factionSetNameCommand = MfFactionSetNameCommand(plugin)
    private val factionSetDisplayNameCommand = MfFactionSetDisplayNameCommand(plugin)
    private val factionSetDescriptionCommand = MfFactionSetDescriptionCommand(plugin)
    private val factionSetPrefixCommand = MfFactionSetPrefixCommand(plugin)

    private val nameAliases = listOf("name", plugin.language["CmdFactionSetName"])
    private val displayNameAliases = listOf("displayname", plugin.language["CmdFactionSetDisplayName"])
    private val descriptionAliases = listOf("description", plugin.language["CmdFactionSetDescription"])
    private val prefixAliases = listOf("prefix", plugin.language["CmdFactionSetPrefix"])

    private val subcommands = nameAliases + displayNameAliases + descriptionAliases + prefixAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f set")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in nameAliases -> NativeCommandHelp.canUse(sender, "/f set name", helpEntries())
        in displayNameAliases -> NativeCommandHelp.canUse(sender, "/f set displayname", helpEntries())
        in descriptionAliases -> NativeCommandHelp.canUse(sender, "/f set description", helpEntries())
        in prefixAliases -> NativeCommandHelp.canUse(sender, "/f set prefix", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f set", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in nameAliases -> factionSetNameCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in displayNameAliases -> factionSetDisplayNameCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in descriptionAliases -> factionSetDescriptionCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in prefixAliases -> factionSetPrefixCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f set", emptyArray(), helpEntries())
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
            in nameAliases -> factionSetNameCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in displayNameAliases -> factionSetDisplayNameCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in descriptionAliases -> factionSetDescriptionCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in prefixAliases -> factionSetPrefixCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
