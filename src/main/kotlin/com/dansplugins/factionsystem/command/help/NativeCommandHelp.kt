package com.dansplugins.factionsystem.command.help

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionSubcommandHelpLine
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender

/** Permission metadata mirrors the native handlers; each inner group contains OR alternatives. */
internal object NativeCommandHelp {
    data class Entry(
        val command: String,
        val description: String,
        val permissions: List<List<String>>,
                     val staff: Boolean = false,
        val container: Boolean = false,
        val debug: Boolean = false
    ) {
        fun allowed(sender: CommandSender) = permissions.all { group -> group.any(sender::hasPermission) }
    }

    fun catalog(plugin: MedievalFactions): List<Entry> {
        fun description(key: String, fallback: String = "Manage faction settings") =
            plugin.language["CommandFactionHelp$key"].substringAfter(" - ", fallback).replace("''", "'")
        fun row(
            key: String,
            command: String,
            permissions: String,
            staff: Boolean = false,
                fallback: String = "Manage faction settings"
        ) = Entry(
            command,
            description(key, fallback),
            permissions.split('&').filter(String::isNotBlank).map { it.split('|') },
            staff
        )
        fun container(key: String, command: String, staff: Boolean = false) =
            Entry(command, description(key), emptyList(), staff, container = true)
        return listOf(
            container("Accessors", "/accessors"),
            row("AccessorsAdd", "/accessors add [x y z] <player>", "mf.accessors.add|mf.grantaccess"),
            row("AccessorsList", "/accessors list [x y z]", "mf.accessors.list|mf.checkaccess"),
            row("AccessorsRemove", "/accessors remove [x y z] <player>", "mf.accessors.remove|mf.revokeaccess"),
            container("Duel", "/duel"),
            row("DuelAccept", "/duel accept <player>", "mf.duel"),
            row("DuelCancel", "/duel cancel <player>", "mf.duel"),
            row("DuelChallenge", "/duel challenge <player>", "mf.duel"),
            row("FactionAlly", "/f ally <faction>", "mf.ally"),
            row("FactionAutoclaim", "/f claim auto", "mf.claim.auto&mf.autoclaim"),
            row("FactionBonusPower", "/f bonuspower <faction> <power>", "mf.force.bonuspower", true),
            row("FactionBreakAlliance", "/f breakalliance <faction>", "mf.breakalliance"),
            row("FactionBypass", "/f bypass", "mf.bypass", true),
            row("FactionBypass", "/f bypass warnings [on|off]", "mf.bypass", true, "Control territory warnings in bypass mode"),
            row("FactionChat", "/f chat [faction|vassals|allies]", "mf.chat"),
            row("FactionChatHistory", "/f chat history [page]", "mf.chat&mf.chat.history"),
            row("FactionCheckClaim", "/f claim check", "mf.claim.check|mf.checkclaim"),
            row("FactionClaim", "/f claim [radius]", "mf.claim.circle|mf.claim"),
            row("FactionClaim", "/f claim circle [radius]", "mf.claim.circle|mf.claim"),
            row("FactionClaimFill", "/f claim fill", "mf.claim.fill|mf.claimfill"),
            row("FactionCreate", "/f create <name>", "mf.create"),
            row("FactionDeclareIndependence", "/f declareindependence", "mf.declareindependence"),
            row("FactionDeclareWar", "/f declarewar <faction>", "mf.declarewar"),
            row("FactionDeclineVassalization", "/f declinevassalization <faction>", "mf.declinevassalization"),
            row("FactionDisband", "/f disband", "mf.disband"),
            row("FactionDisband", "/f disband <faction>", "mf.disband&mf.disband.others", true),
            row("Embassy", "/f embassy offer <realm> [chunks]", "mf.embassy", false, "Offer connected loaded land while standing in your claim"),
            row("Embassy", "/f embassy accept", "mf.embassy", false, "Accept the offer here within seven days"),
            row("Embassy", "/f embassy decline", "mf.embassy", false, "Decline the pending offer here"),
            row("Embassy", "/f embassy revoke [plot]", "mf.embassy", false, "End a hosted agreement with fourteen peaceful days to clear"),
            row("Embassy", "/f embassy release [plot]", "mf.embassy", false, "Release a held agreement and recover your goods"),
            row("Embassy", "/f embassy finish", "mf.embassy", false, "Finish clearing the embassy here"),
            row("Embassy", "/f embassy seize [plot]", "mf.embassy", false, "Seize embassy property when native conquest rules permit"),
            row("Embassy", "/f embassy passage [plot]", "mf.embassy", false, "Allow fourteen days for recovery from this plot"),
            row("Embassy", "/f embassy info [plot]", "mf.embassy", false, "Read plot details; plot addresses use world UUID:chunkX,chunkZ"),
            row("Embassy", "/f embassy list", "mf.embassy", false, "List your realm offers, plots and allowance"),
            container("FactionFlag", "/f flag"),
            row("FactionFlagList", "/f flag list [page]", "mf.flag.list"),
            row("FactionFlagList", "/f flag list <faction> [page]", "mf.flag.list&mf.force.flag", true),
            row("FactionFlagSet", "/f flag set <flag> <value>", "mf.flag.set"),
            row("FactionFlagSet", "/f flag set <faction> <flag> <value>", "mf.flag.set&mf.force.flag", true),
            row("FactionFlagSet", "/f flag set <faction> maxEmbassyChunks <value>", "mf.flag.set&mf.force.flag", true, "Set a realm embassy allowance"),
            row("FactionGrantIndependence", "/f grantindependence <vassal>", "mf.grantindependence"),
            row("FactionHeir", "/f heir <player>", "mf.heir"),
            row("FactionHelp", "/f help [page]", "mf.help"),
            row("FactionHome", "/f home", "mf.home"),
            row("FactionInfo", "/f info", "mf.info"),
            row("FactionInfo", "/f info <faction|player>", "mf.info&mf.info.other"),
            row("FactionInvite", "/f invite <player>", "mf.invite"),
            row("FactionInvoke", "/f invoke <ally> <enemy>", "mf.invoke"),
            row("FactionJoin", "/f join <faction>", "mf.join"),
            row("FactionKick", "/f kick <member>", "mf.kick"),
            row("FactionKick", "/f kick <faction> <member>", "mf.kick&mf.force.kick", true),
            container("FactionLaw", "/f law"),
            row("FactionLawAdd", "/f law add <law>", "mf.law.add&mf.addlaw"),
            row("FactionLawList", "/f law list [faction] [page]", "mf.law.list&mf.laws"),
            row("FactionLawRemove", "/f law remove <id>", "mf.law.remove&mf.removelaw"),
            row("FactionLawEdit", "/f law edit <id> <law>", "mf.law.edit", false, "Edit a faction law"),
            row("FactionLawMove", "/f law move <id> <position>", "mf.law.move", false, "Move a faction law"),
            row("FactionLeave", "/f leave", "mf.leave"),
            row("FactionList", "/f list [page]", "mf.list"),
            row("FactionMakePeace", "/f makepeace <faction>", "mf.makepeace"),
            row("FactionMap", "/f map [normal|diplomatic]", "mf.map"),
            row("FactionMembers", "/f members [faction]", "mf.members"),
            row("FactionPower", "/f power", "mf.power"),
            row("FactionPower", "/f power <faction|player>", "mf.power&mf.power.view.other"),
            row("PowerFill", "/f fillpower <player>", "mf.power.fill|mf.force.power", true),
            row("PowerSet", "/f setpower <player> <power>", "mf.power.set|mf.force.power", true),
            row("PowerSetMax", "/f setmaxpower <player> <max|reset>", "mf.power.setmax|mf.force.power", true),
            Entry("/f relationship", "View faction relationships", emptyList(), container = true),
            container("FactionRelationship", "/f relationship", true),
            row("FactionRelationshipAdd", "/f relationship add <faction1> <faction2> <type>", "mf.relationship.add", true),
            row("FactionRelationshipRemove", "/f relationship remove <faction1> <faction2> <type>", "mf.relationship.remove", true),
            row("FactionRelationshipView", "/f relationship view <faction1> <faction2>", "mf.relationship.view"),
            container("FactionRole", "/f role"),
            row("FactionRoleCreate", "/f role create <name>", "mf.role.create"),
            row("FactionRoleDelete", "/f role delete <name>", "mf.role.delete"),
            row("FactionRoleList", "/f role list [page]", "mf.role.list"),
            row("FactionRoleRename", "/f role rename <role> <name>", "mf.role.rename"),
            row("FactionRoleSet", "/f role set <player> <role>", "mf.role.set"),
            row("FactionRoleSetDefault", "/f role setdefault <role>", "mf.role.setdefault"),
            row("FactionRoleSetPermission", "/f role setpermission <role> <permission> <allow|deny|default>", "mf.role.setpermission"),
            row("FactionRoleView", "/f role view <role> [page]", "mf.role.view"),
            container("FactionSet", "/f set"),
            row("FactionSetDescription", "/f set description <description>", "mf.desc"),
            row("FactionSetName", "/f set name <name>", "mf.rename"),
            row("FactionSetPrefix", "/f set prefix <prefix>", "mf.prefix"),
            row("FactionSetDisplayName", "/f set displayname <name>", "mf.displayname", false, "Set your faction display name"),
            row("FactionSetDisplayName", "/f set displayname --faction <faction> <name>", "mf.admin&mf.admin.displayname", true, "Set another faction display name"),
            row("FactionSetHome", "/f sethome", "mf.sethome"),
            row("FactionSwearFealty", "/f swearfealty <faction>", "mf.swearfealty"),
            row("FactionTransfer", "/f transfer <player>", "mf.transfer"),
            row("FactionAutounclaim", "/f unclaim auto", "mf.unclaim.auto&mf.autounclaim"),
            row("FactionAddons", "/f addons", "mf.addons", staff = true),
            row("FactionForceRole", "/f role <faction> <command>", "mf.force.role&mf.role.create|mf.role.delete|mf.role.rename|mf.role.list|mf.role.view|mf.role.set|mf.role.setdefault|mf.role.setpermission", staff = true),
            row("FactionUnclaim", "/f unclaim [radius]", "mf.unclaim"),
            row("FactionUnclaimAll", "/f unclaimall", "mf.unclaimall"),
            row("FactionVassalize", "/f vassalize <faction>", "mf.vassalize"),
            row("FactionWho", "/f who <player>", "mf.who"),
            row("FactionVersion", "/f version", "mf.version"),
            container("Gate", "/gate"),
            row("GateCancel", "/gate cancel", "mf.gate"),
            row("GateCreate", "/gate create", "mf.gate"),
            row("GateRemove", "/gate remove", "mf.gate"),
            row("Lock", "/lock", "mf.lock"),
            row("Lock", "/lock cancel", "mf.lock", false, "Cancel block locking"),
            container("Power", "/power", true),
            row("PowerSet", "/power set <player> <power>", "mf.power.set|mf.force.power", true),
            row("PowerFill", "/power fill <player>", "mf.power.fill|mf.force.power", true),
            row("PowerSetMax", "/power setmax <player> <max|reset>", "mf.power.setmax|mf.force.power", true),
            row("Unlock", "/unlock", "mf.unlock"),
            row("Unlock", "/unlock cancel", "mf.unlock", false, "Cancel block unlocking"),
            row("AddMember", "/f addmember <player> <faction>", "mf.force.addmember|mf.force.join", true),
            row("ShowApps", "/f showapps", "mf.showapps"),
            row("Apply", "/f apply <faction>", "mf.apply"),
            row("ApproveApp", "/f approveapp <player>", "mf.approveapp"),
            row("DenyApp", "/f denyapp <player>", "mf.denyapp"),
            row("Dpc", "/f dpc optin", "mf.dpc", true, "Enable the community API integration"),
            row("Dpc", "/f dpc optout", "mf.dpc", true, "Disable the community API integration"),
            row("Dpc", "/f dpc reminder <on|off>", "mf.dpc", true, "Control login reminders"),
            row("Dpc", "/f dpc shareip <on|off>", "mf.dpc", true, "Control server address sharing"),
            row("Dpc", "/f dpc discord <link|clear>", "mf.dpc", true, "Change the community Discord link"),
            container("Admin", "/f admin", true),
            row("AdminCreate", "/f admin create <name>", "mf.admin&mf.admin.create", true),
            row("AdminMakeLeaderless", "/f admin makeleaderless <faction>", "mf.admin&mf.admin.makeleaderless", true),
            row("AdminSetLeader", "/f admin setleader <player> <faction>", "mf.admin&mf.admin.setleader", true),
            row("AdminProtection", "/f admin protection <faction> <field> <value>", "mf.admin&mf.admin.protection", true, "Manage native siege protection for a faction"),
            row("Migrate", "/f migrate toJson", "mf.migrate", true, "Migrate database data to JSON when native integration guards permit"),
            row("Migrate", "/f migrate toDatabase", "mf.migrate", true, "Migrate JSON data to the configured database"),
            row("FactionApprove", "/f approve <id>", "mf.approve", true),
            row("FactionDeny", "/f deny <id>", "mf.approve", true),
            row("FactionPendingActions", "/f pendingactions", "mf.approve", true)
        ) + if (plugin.config.getBoolean("dev.enableDevCommands")) {
            listOf(
            Entry("/f dev generate <count>", "Generate development factions", listOf(listOf("mf.admin")), true, debug = true),
            Entry("/f dev powercycle", "Run a development power cycle", listOf(listOf("mf.admin")), true, debug = true)
        )
        } else {
            emptyList()
        }
    }

    fun extension(line: FactionSubcommandHelpLine): Entry = Entry(
        line.text.substringBefore(" - "),
        line.text.substringAfter(" - ", "Faction extension command"),
        listOfNotNull(line.permission?.takeIf(String::isNotBlank), line.additionalPermission?.takeIf(String::isNotBlank))
            .map { listOf(it) },
                line.staff
    )

    fun forRoot(entries: List<Entry>, root: String): List<Entry> =
        if (root == "f") entries else entries.filter { it.command.startsWith("/$root ") || (it.command == "/$root" && !it.container) }

    fun canUse(sender: CommandSender, prefix: String, entries: List<Entry>): Boolean = entries.any {
        !it.container && (it.command == prefix || it.command.startsWith("$prefix ")) && it.allowed(sender)
    }

    fun visible(sender: CommandSender, entries: List<Entry>): List<Entry> = entries.filter { entry ->
        entry.allowed(sender) && (
            !entry.container || entries.any { child ->
            !child.container && child.staff == entry.staff && child.command.startsWith("${entry.command} ") && child.allowed(sender)
        }
        )
    }

    fun deny(sender: CommandSender) { sender.sendMessage("§cYou may not use that.") }
    fun unknown(sender: CommandSender) { sender.sendMessage("§cUnknown subcommand.") }

    private fun component(vararg parts: Pair<String, ChatColor>): TextComponent = TextComponent().apply {
        parts.forEach { (text, colour) -> addExtra(TextComponent(text).apply { color = colour }) }
    }
    private fun pages(size: Int) = (size + 7) / 8

    fun render(sender: CommandSender, root: String, args: Array<out String>, entries: List<Entry>) {
        val available = visible(sender, entries)
        val ordinary = available.filterNot(Entry::staff)
        val staff = available.filter(Entry::staff).sortedBy(Entry::debug)
        val directStaff = args.firstOrNull()?.equals("staff", true) == true
        if (available.isEmpty() || (directStaff && staff.isEmpty())) { deny(sender); return }
        val ordinaryPages = pages(ordinary.size)
        val staffPages = pages(staff.size)
        val total = ordinaryPages + staffPages
        val number = (args.getOrNull(if (directStaff) 1 else 0)?.toIntOrNull() ?: 1)
            .coerceIn(1, if (directStaff) staffPages else total)
        val page = if (directStaff) ordinaryPages + number else number
        val staffSection = page > ordinaryPages
        val sectionPage = if (staffSection) page - ordinaryPages else page
        val section = if (staffSection) staff else ordinary
        val header = component(
            "---------- " to ChatColor.DARK_GRAY,
            ("Medieval Factions" + if (staffSection) " Staff" else "") to ChatColor.GREEN
        )
        if (total > 1) {
            header.addExtra(
                component(
                    " (" to ChatColor.DARK_GRAY,
                    "$page" to ChatColor.WHITE,
                "/" to ChatColor.GRAY,
                    "$total" to ChatColor.WHITE,
                    ")" to ChatColor.DARK_GRAY
                )
            )
        }
        header.addExtra(component(" ----------" to ChatColor.DARK_GRAY))
        sender.spigot().sendMessage(header)
        section.drop((sectionPage - 1) * 8).take(8).forEach { entry ->
            sender.spigot().sendMessage(
                component(
                    entry.command to ChatColor.GREEN,
                " - " to ChatColor.DARK_GRAY,
                    entry.description to ChatColor.GRAY
                )
            )
        }
        sender.spigot().sendMessage(
            if (page < total) {
            component(
                "---------- " to ChatColor.DARK_GRAY,
            "/$root help ${page + 1}" to ChatColor.GREEN,
                " for more " to ChatColor.GRAY,
            "----------" to ChatColor.DARK_GRAY
            )
        } else {
            component("----------------------------------------" to ChatColor.DARK_GRAY)
        }
        )
    }

    fun helpTabs(sender: CommandSender, args: Array<out String>, entries: List<Entry>): List<String> {
        val available = visible(sender, entries)
        val staffPages = pages(available.count(Entry::staff))
        val total = pages(available.count { !it.staff }) + staffPages
        val directStaff = args.firstOrNull()?.equals("staff", true) == true
        if ((directStaff && staffPages == 0) || args.size > (if (directStaff) 2 else 1)) return emptyList()
        val options = if (directStaff) {
            (1..staffPages).map(Int::toString)
        } else {
            (1..total).map(Int::toString) + if (staffPages > 0) listOf("staff") else emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty().lowercase()) }
    }
}
