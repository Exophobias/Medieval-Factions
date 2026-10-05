package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.ChatColor.YELLOW
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionAdminCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val adminCreateCommand = MfFactionAdminCreateCommand(plugin)
    private val adminMakeLeaderlessCommand = MfFactionAdminMakeLeaderlessCommand(plugin)
    private val adminProtectionCommand = MfFactionAdminProtectionCommand(plugin)
    private val adminSetLeaderCommand = MfFactionAdminSetLeaderCommand(plugin)

    private val createAliases = listOf("create", plugin.language["CmdFactionAdminCreate"])
    private val makeLeaderlessAliases = listOf("makeleaderless", plugin.language["CmdFactionAdminMakeLeaderless"])
    private val protectionAliases = listOf("protection", plugin.language["CmdFactionAdminProtection"])
    private val setLeaderAliases = listOf("setleader", plugin.language["CmdFactionAdminSetLeader"])

    private val subcommands = createAliases + makeLeaderlessAliases + setLeaderAliases + protectionAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f admin")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in createAliases -> NativeCommandHelp.canUse(sender, "/f admin create", helpEntries())
        in makeLeaderlessAliases -> NativeCommandHelp.canUse(sender, "/f admin makeleaderless", helpEntries())
        in protectionAliases -> NativeCommandHelp.canUse(sender, "/f admin protection", helpEntries())
        in setLeaderAliases -> NativeCommandHelp.canUse(sender, "/f admin setleader", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f admin", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        if (!sender.hasPermission("mf.admin")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminNoPermission"]}")
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in createAliases -> adminCreateCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in makeLeaderlessAliases -> adminMakeLeaderlessCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in protectionAliases -> adminProtectionCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setLeaderAliases -> adminSetLeaderCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f admin", emptyArray(), helpEntries())
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
        !sender.hasPermission("mf.admin") -> emptyList()
        args.firstOrNull()?.equals("help", true) == true -> NativeCommandHelp.helpTabs(sender, args.drop(1).toTypedArray(), helpEntries())
        args.size > 1 && !permitted(sender, args.first()) -> emptyList()
        args.isEmpty() -> visibleSubcommands(sender)
        args.size == 1 -> visibleSubcommands(sender).filter { it.startsWith(args[0].lowercase()) }
        else -> when (args.first().lowercase()) {
            in createAliases -> adminCreateCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in makeLeaderlessAliases -> adminMakeLeaderlessCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in protectionAliases -> adminProtectionCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setLeaderAliases -> adminSetLeaderCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
