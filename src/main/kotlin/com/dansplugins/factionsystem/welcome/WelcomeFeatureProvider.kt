package com.dansplugins.factionsystem.welcome

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.help.NativeCommandHelp
import com.dansplugins.factionsystem.player.MfPlayerId
import com.github.exophobias.patriamwelcome.api.HubFeature
import com.github.exophobias.patriamwelcome.api.HubFeatureProvider
import com.github.exophobias.patriamwelcome.api.HubFeatureView
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority

/** Native current-member view. No foreign rosters, member names, presence or database writes. */
class WelcomeFeatureProvider(private val owner: MedievalFactions) : HubFeatureProvider {
    companion object {
        @JvmStatic
        fun register(owner: MedievalFactions): AutoCloseable {
            val provider = WelcomeFeatureProvider(owner)
            owner.server.servicesManager.register(HubFeatureProvider::class.java, provider, owner, ServicePriority.Normal)
            return AutoCloseable { owner.server.servicesManager.unregister(HubFeatureProvider::class.java, provider) }
        }
    }

    override fun key() = "realms"

    private fun help(player: Player) = NativeCommandHelp.visible(player, NativeCommandHelp.catalog(owner))

    override fun availableTo(player: Player): Boolean =
        Bukkit.isPrimaryThread() && owner.isEnabled && owner.servicesOrNull != null && player.isOnline &&
            owner.server.getPlayer(player.uniqueId) === player && help(player).isNotEmpty()

    override fun open(player: Player, view: HubFeatureView) {
        if (!availableTo(player) || !view.active()) {
            view.unavailable("Realm pages are unavailable.")
            return
        }
        view.show(project(player))
    }

    internal fun project(player: Player): HubFeature {
        val rows = mutableListOf<HubFeature.Row>()
        val sections = mutableListOf<HubFeature.Section>()
        val actions = mutableListOf<HubFeature.Action>()
        val services = owner.services
        val playerId = MfPlayerId(player.uniqueId.toString())
        val faction = services.factionService.getFaction(playerId)
        val role = faction?.getRole(playerId)
        val readable = player.hasPermission("mf.info") && (
            player.hasPermission("mf.info.other") ||
                (faction != null && role?.hasPermission(faction, owner.factionPermissions.viewInfo) == true)
        )
        if (faction != null && role != null && readable) {
            rows += HubFeature.Row("Realm", faction.displayName)
            if (faction.name != faction.displayName) rows += HubFeature.Row("Canonical name", faction.name)
            rows += HubFeature.Row("Your role", role.name)
            rows += HubFeature.Row("Members", faction.members.size.toString())
            if (faction.description.isNotBlank()) sections += HubFeature.Section("Description", listOf(faction.description))
            actions += HubFeature.Action("Realm details", "Inspect your current realm", "f info", "mf.info")
        } else {
            rows += HubFeature.Row("Current realm", if (faction == null) "No current realm membership" else "Realm details are not permitted")
        }
        // These statistics belong to /f power, independently of /f info's role gate.
        if (faction != null && player.hasPermission("mf.power")) {
            rows += HubFeature.Row("Claimed chunks", services.claimService.getClaimCount(faction.id).toString())
            rows += HubFeature.Row("Realm power", faction.power.toString())
            rows += HubFeature.Row("Maximum power", faction.maxPower.toString())
        }
        if (player.hasPermission("mf.power")) {
            services.playerService.getPlayer(playerId)?.let { rows += HubFeature.Row("Your power", it.power.toString()) }
            actions += HubFeature.Action("Your power", "Read your current power through its owner command", "f power", "mf.power")
        }
        if (player.hasPermission("mf.list")) actions += HubFeature.Action("Realm list", "Read the permitted public realm list", "f list", "mf.list")
        // The owner has already resolved all AND/OR groups. Empty DTO permission adds no weaker alternative.
        val entries = help(player).filterNot { it.container }.map {
            HubFeature.Help(it.command.removePrefix("/"), org.bukkit.ChatColor.stripColor(it.description) ?: it.description, "", it.staff)
        }
        return HubFeature("Realms", "Your current realm and permitted native command help. Commands with placeholders require their shown arguments.", rows, sections, entries, actions)
    }
}
