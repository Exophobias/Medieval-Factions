package com.dansplugins.factionsystem.command.faction.help

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionSubcommandHelpLine
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionHelpCommand(
    private val plugin: MedievalFactions,
    private val extensionHelpLines: (CommandSender) -> List<FactionSubcommandHelpLine> = { emptyList() }
) : CommandExecutor, TabCompleter {
    private fun entries(sender: CommandSender) = NativeCommandHelp.catalog(plugin) +
        extensionHelpLines(sender).map(NativeCommandHelp::extension)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.help")) { NativeCommandHelp.deny(sender); return true }
        NativeCommandHelp.render(sender, "f", args, entries(sender))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>) =
        if (!sender.hasPermission("mf.help")) emptyList() else NativeCommandHelp.helpTabs(sender, args, entries(sender))
}
