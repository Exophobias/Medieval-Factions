package com.dansplugins.factionsystem.command.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.gate.cancel.MfGateCancelCommand
import com.dansplugins.factionsystem.command.gate.create.MfGateCreateCommand
import com.dansplugins.factionsystem.command.gate.remove.MfGateRemoveCommand
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfGateCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val gateCreateCommand = MfGateCreateCommand(plugin)
    private val gateRemoveCommand = MfGateRemoveCommand(plugin)
    private val gateCancelCommand = MfGateCancelCommand(plugin)

    private val createAliases = listOf("create", "new", plugin.language["CmdGateCreate"])
    private val removeAliases = listOf("remove", "delete", plugin.language["CmdGateRemove"])
    private val cancelAliases = listOf("cancel", plugin.language["CmdGateCancel"])

    private val subcommands = createAliases + removeAliases + cancelAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "gate")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in createAliases -> NativeCommandHelp.canUse(sender, "/gate create", helpEntries())
        in removeAliases -> NativeCommandHelp.canUse(sender, "/gate remove", helpEntries())
        in cancelAliases -> NativeCommandHelp.canUse(sender, "/gate cancel", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "gate", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in createAliases -> gateCreateCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> gateRemoveCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in cancelAliases -> gateCancelCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "gate", emptyArray(), helpEntries())
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
            in createAliases -> gateCreateCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in removeAliases -> gateRemoveCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in cancelAliases -> gateCancelCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
