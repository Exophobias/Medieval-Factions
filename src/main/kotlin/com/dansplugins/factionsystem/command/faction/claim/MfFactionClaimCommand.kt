package com.dansplugins.factionsystem.command.faction.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionClaimCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val factionClaimAutoCommand = MfFactionClaimAutoCommand(plugin)
    private val factionClaimFillCommand = MfFactionClaimFillCommand(plugin)
    private val factionClaimCheckCommand = MfFactionClaimCheckCommand(plugin)
    private val factionClaimCircleCommand = MfFactionClaimCircleCommand(plugin)

    private val autoAliases = listOf("auto", plugin.language["CmdFactionClaimAuto"])
    private val fillAliases = listOf("fill", plugin.language["CmdFactionClaimFill"])
    private val checkAliases = listOf("check", plugin.language["CmdFactionClaimCheck"])
    private val circleAliases = listOf("circle", plugin.language["CmdFactionClaimCircle"])

    private val subcommands = autoAliases + fillAliases + checkAliases + circleAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f claim")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in autoAliases -> NativeCommandHelp.canUse(sender, "/f claim auto", helpEntries())
        in fillAliases -> NativeCommandHelp.canUse(sender, "/f claim fill", helpEntries())
        in checkAliases -> NativeCommandHelp.canUse(sender, "/f claim check", helpEntries())
        in circleAliases -> NativeCommandHelp.canUse(sender, "/f claim circle", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f claim", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in autoAliases -> factionClaimAutoCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in fillAliases -> factionClaimFillCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in checkAliases -> factionClaimCheckCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in circleAliases -> factionClaimCircleCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                if (args.isEmpty() || args.first().toIntOrNull() != null) {
                    factionClaimCircleCommand.onCommand(sender, command, label, args)
                } else {
                    NativeCommandHelp.unknown(sender)
                    NativeCommandHelp.render(sender, "f claim", emptyArray(), helpEntries())
                    true
                }
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
        else -> emptyList()
    }
}
