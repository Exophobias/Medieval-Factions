package com.dansplugins.factionsystem.command.faction.dev

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionDevCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val factionDevGenerateCommand = MfFactionDevGenerateCommand(plugin)
    private val factionDevPowerCycleCommand = MfFactionDevPowerCycleCommand(plugin)

    private val generateAliases = listOf("generate")
    private val powerCycleAliases = listOf("powercycle")

    private val subcommands = generateAliases + powerCycleAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f dev")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in generateAliases -> NativeCommandHelp.canUse(sender, "/f dev generate", helpEntries())
        in powerCycleAliases -> NativeCommandHelp.canUse(sender, "/f dev powercycle", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!plugin.config.getBoolean("dev.enableDevCommands") || !sender.hasPermission("mf.admin")) {
            NativeCommandHelp.deny(sender); return true
        }

        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f dev", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in generateAliases -> factionDevGenerateCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in powerCycleAliases -> factionDevPowerCycleCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f dev", emptyArray(), helpEntries())
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
            in generateAliases -> factionDevGenerateCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in powerCycleAliases -> factionDevPowerCycleCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
