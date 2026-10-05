package com.dansplugins.factionsystem.command.duel

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.duel.accept.MfDuelAcceptCommand
import com.dansplugins.factionsystem.command.duel.cancel.MfDuelCancelCommand
import com.dansplugins.factionsystem.command.duel.challenge.MfDuelChallengeCommand
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfDuelCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val duelChallengeCommand = MfDuelChallengeCommand(plugin)
    private val duelAcceptCommand = MfDuelAcceptCommand(plugin)
    private val duelCancelCommand = MfDuelCancelCommand(plugin)

    private val challengeAliases = listOf("challenge", plugin.language["CmdDuelChallenge"])
    private val acceptAliases = listOf("accept", plugin.language["CmdDuelAccept"])
    private val cancelAliases = listOf("cancel", "decline", plugin.language["CmdDuelCancel"])

    private val subcommands = challengeAliases + acceptAliases + cancelAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "duel")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in challengeAliases -> NativeCommandHelp.canUse(sender, "/duel challenge", helpEntries())
        in acceptAliases -> NativeCommandHelp.canUse(sender, "/duel accept", helpEntries())
        in cancelAliases -> NativeCommandHelp.canUse(sender, "/duel cancel", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "duel", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in challengeAliases -> duelChallengeCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in acceptAliases -> duelAcceptCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in cancelAliases -> duelCancelCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "duel", emptyArray(), helpEntries())
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
            in challengeAliases -> duelChallengeCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in acceptAliases -> duelAcceptCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in cancelAliases -> duelCancelCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
