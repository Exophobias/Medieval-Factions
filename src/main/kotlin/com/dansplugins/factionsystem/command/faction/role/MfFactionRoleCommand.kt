package com.dansplugins.factionsystem.command.faction.role

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import com.dansplugins.factionsystem.command.splitLeadingArg
import com.dansplugins.factionsystem.faction.MfFactionId
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

        val first = args.firstOrNull()
        // A leading argument that is not a subcommand names another faction: /f role <faction> <subcommand> ...
        // A quoted leading argument is always a faction, so a faction named like a subcommand can still be given.
        if (first != null && !first.startsWith("\"") && first.lowercase() in subcommands) {
            return dispatch(sender, command, label, args, null)
        }
        if (first == null || !sender.hasPermission("mf.force.role")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionRoleUsage"]}")
            return true
        }
        val (factionName, remainingArgs) = args.splitLeadingArg() ?: run {
            sender.sendMessage("$RED${plugin.language["CommandFactionRoleUsage"]}")
            return true
        }
        // With mf.force.role, the named faction replaces the sender's own, and the force permission replaces that
        // faction's role-permission checks, matching how the other mf.force.* nodes are honoured. A name that does not
        // resolve is refused rather than falling back to the sender's own faction.
        val factionService = plugin.services.factionService
        val faction = factionService.getFaction(MfFactionId(factionName)) ?: factionService.getFaction(factionName)
        if (faction == null) {
            sender.sendMessage("$RED${plugin.language["CommandFactionRoleInvalidFaction", factionName]}")
            return true
        }
        if (remainingArgs.firstOrNull()?.lowercase() !in subcommands) {
            sender.sendMessage("$RED${plugin.language["CommandFactionRoleUsage"]}")
            return true
        }
        return dispatch(sender, command, label, remainingArgs, faction.id)
    }

    private fun dispatch(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
        forcedFactionId: MfFactionId?
    ): Boolean {
        val subcommandArgs = args.drop(1).toTypedArray()
        return when (args.first().lowercase()) {
            in viewAliases -> factionRoleViewCommand.execute(sender, subcommandArgs, forcedFactionId)
            in setPermissionAliases -> factionRoleSetPermissionCommand.execute(sender, subcommandArgs, forcedFactionId)
            in listAliases -> factionRoleListCommand.execute(sender, subcommandArgs, forcedFactionId)
            in setAliases -> factionRoleSetCommand.execute(sender, subcommandArgs, forcedFactionId)
            in createAliases -> factionRoleCreateCommand.execute(sender, subcommandArgs, forcedFactionId)
            in deleteAliases -> factionRoleDeleteCommand.execute(sender, subcommandArgs, forcedFactionId)
            in renameAliases -> factionRoleRenameCommand.execute(sender, subcommandArgs, forcedFactionId)
            in setDefaultAliases -> factionRoleSetDefaultCommand.execute(sender, subcommandArgs, forcedFactionId)
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

/**
 * The command prefix used for clickable buttons and return-to-page commands, so that an admin managing another faction
 * with mf.force.role stays on that faction. The faction is given by ID, which cannot be confused with a subcommand.
 */
internal fun roleCommandPrefix(forcedFactionId: MfFactionId?) =
    if (forcedFactionId == null) "faction role" else "faction role ${forcedFactionId.value}"
