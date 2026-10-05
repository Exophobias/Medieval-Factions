package com.dansplugins.factionsystem.command.accessors

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import com.dansplugins.factionsystem.command.accessors.add.MfAccessorsAddCommand
import com.dansplugins.factionsystem.command.accessors.list.MfAccessorsListCommand
import com.dansplugins.factionsystem.command.accessors.remove.MfAccessorsRemoveCommand
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfAccessorsCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val accessorsAddCommand = MfAccessorsAddCommand(plugin)
    private val accessorsRemoveCommand = MfAccessorsRemoveCommand(plugin)
    private val accessorsListCommand = MfAccessorsListCommand(plugin)

    private val addAliases = listOf(
        plugin.language["CmdAccessorsAdd"],
        "add",
        "a"
    )

    private val removeAliases = listOf(
        plugin.language["CmdAccessorsRemove"],
        "remove",
        "rm",
        "r"
    )

    private val listAliases = listOf(
        plugin.language["CmdAccessorsList"],
        "list",
        "ls",
        "l"
    )

    private val subcommands = addAliases + removeAliases + listAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "accessors")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in addAliases -> NativeCommandHelp.canUse(sender, "/accessors add", helpEntries())
        in removeAliases -> NativeCommandHelp.canUse(sender, "/accessors remove", helpEntries())
        in listAliases -> NativeCommandHelp.canUse(sender, "/accessors list", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "accessors", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args[0].lowercase()) {
            in addAliases -> accessorsAddCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> accessorsRemoveCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> accessorsListCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "accessors", emptyArray(), helpEntries())
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
            in addAliases -> accessorsAddCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> accessorsRemoveCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> accessorsListCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
