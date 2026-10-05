package com.dansplugins.factionsystem.command.faction.role

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MfFactionRoleCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    private val factionRoleViewCommand = MfFactionRoleViewCommand(plugin)
    private val factionRoleSetPermissionCommand = MfFactionRoleSetPermissionCommand(plugin)
    private val factionRoleListCommand = MfFactionRoleListCommand(plugin)
    private val factionRoleSetCommand = MfFactionRoleSetCommand(plugin)
    private val factionRoleCreateCommand = MfFactionRoleCreateCommand(plugin)
    private val factionRoleDeleteCommand = MfFactionRoleDeleteCommand(plugin)
    private val factionRoleRenameCommand = MfFactionRoleRenameCommand(plugin)
    private val factionRoleSetDefaultCommand = MfFactionRoleSetDefaultCommand(plugin)

    private val viewAliases = listOf("view", plugin.language["CmdFactionRoleView"])
    private val setPermissionAliases = listOf("setpermission", plugin.language["CmdFactionRoleSetPermission"])
    private val listAliases = listOf("list", plugin.language["CmdFactionRoleList"])
    private val setAliases = listOf("set", plugin.language["CmdFactionRoleSet"])
    private val createAliases = listOf("create", "add", plugin.language["CmdFactionRoleCreate"])
    private val deleteAliases = listOf("delete", "remove", plugin.language["CmdFactionRoleDelete"])
    private val renameAliases = listOf("rename", "setname", plugin.language["CmdFactionRoleRename"])
    private val setDefaultAliases = listOf("setdefault", plugin.language["CmdFactionRoleSetDefault"])

    private val subcommands = viewAliases +
        setPermissionAliases +
        listAliases +
        setAliases +
        createAliases +
        deleteAliases +
        renameAliases +
        setDefaultAliases

    private fun helpEntries() = NativeCommandHelp.forRoot(NativeCommandHelp.catalog(plugin), "f role")
    private fun permitted(sender: CommandSender, alias: String): Boolean = when (alias.lowercase()) {
        in viewAliases -> NativeCommandHelp.canUse(sender, "/f role view", helpEntries())
        in setPermissionAliases -> NativeCommandHelp.canUse(sender, "/f role setpermission", helpEntries())
        in listAliases -> NativeCommandHelp.canUse(sender, "/f role list", helpEntries())
        in setAliases -> NativeCommandHelp.canUse(sender, "/f role set", helpEntries())
        in createAliases -> NativeCommandHelp.canUse(sender, "/f role create", helpEntries())
        in deleteAliases -> NativeCommandHelp.canUse(sender, "/f role delete", helpEntries())
        in renameAliases -> NativeCommandHelp.canUse(sender, "/f role rename", helpEntries())
        in setDefaultAliases -> NativeCommandHelp.canUse(sender, "/f role setdefault", helpEntries())
        "help" -> helpEntries().any { it.allowed(sender) && !it.container }
        else -> false
    }
    private fun visibleSubcommands(sender: CommandSender) = (listOf("help") + subcommands)
        .distinct().filter { permitted(sender, it) }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.firstOrNull()?.equals("help", true) == true) {
            NativeCommandHelp.render(sender, "f role", args.drop(if (args.isEmpty()) 0 else 1).toTypedArray(), helpEntries())
            return true
        }

        return when (args.firstOrNull()?.lowercase()) {
            in viewAliases -> factionRoleViewCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setPermissionAliases -> factionRoleSetPermissionCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> factionRoleListCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setAliases -> factionRoleSetCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in createAliases -> factionRoleCreateCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in deleteAliases -> factionRoleDeleteCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in renameAliases -> factionRoleRenameCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            in setDefaultAliases -> factionRoleSetDefaultCommand.onCommand(sender, command, label, args.drop(1).toTypedArray())
            else -> {
                NativeCommandHelp.unknown(sender)
                NativeCommandHelp.render(sender, "f role", emptyArray(), helpEntries())
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
            in viewAliases -> factionRoleViewCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setPermissionAliases -> factionRoleSetPermissionCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in listAliases -> factionRoleListCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setAliases -> factionRoleSetCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in createAliases -> factionRoleCreateCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in deleteAliases -> factionRoleDeleteCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in renameAliases -> factionRoleRenameCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            in setDefaultAliases -> factionRoleSetDefaultCommand.onTabComplete(sender, command, label, args.drop(1).toTypedArray())
            else -> emptyList()
        }
    }
}
