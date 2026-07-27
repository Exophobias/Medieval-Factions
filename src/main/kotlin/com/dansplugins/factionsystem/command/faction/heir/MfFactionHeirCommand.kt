package com.dansplugins.factionsystem.command.faction.heir

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level.SEVERE

/**
 * Names who inherits the faction if its head departs: /faction heir [player], or /faction heir none.
 *
 * A nomination rather than an appointment, so it needs no confirmation step: it changes nothing until
 * the head is actually gone, and running it again replaces it. That is the whole difference between
 * this and /faction transfer, which hands the title over immediately and therefore does confirm.
 *
 * Only the recorded head may nominate, for the same reason only they may transfer: this is their
 * succession to decide, not that of anyone who happens to hold the right to disband.
 *
 * The nominee is normally a member. It may instead be the head of a faction that has sworn fealty to
 * this one, which is the only case where the succession reaches outside the faction. A player belongs
 * to exactly one faction, so such an heir takes the greater realm by leaving their own, and that
 * departure fires their own faction's succession in turn. Naming one is deliberately no harder than
 * naming a member; what makes it a decision is what it does when it lands.
 */
class MfFactionHeirCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.heir")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirNotAPlayer"]}")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionHeirUsage"]}")
            return true
        }
        val targetName = args.joinToString(" ")
        val clearing = targetName.equals("none", ignoreCase = true)
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(mfPlayer.id)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirMustBeInAFaction"]}")
                    return@Runnable
                }
                if (faction.primaryOwnerId != mfPlayer.id) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirNotPrimaryOwner"]}")
                    return@Runnable
                }
                var vassalName: String? = null
                val heirId = if (clearing) {
                    null
                } else {
                    val target = plugin.server.offlinePlayers.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    val targetMfPlayer = target?.let(playerService::getPlayer)
                    if (targetMfPlayer == null) {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirTargetNotEligible", targetName]}")
                        return@Runnable
                    }
                    if (targetMfPlayer.id == mfPlayer.id) {
                        sender.sendMessage("$RED${plugin.language["CommandFactionHeirCannotNominateSelf"]}")
                        return@Runnable
                    }
                    if (!faction.isMember(targetMfPlayer.id)) {
                        val vassal = vassalLedBy(targetMfPlayer.id, faction)
                        if (vassal == null) {
                            sender.sendMessage("$RED${plugin.language["CommandFactionHeirTargetNotEligible", targetName]}")
                            return@Runnable
                        }
                        vassalName = vassal.name
                    }
                    targetMfPlayer.id
                }
                factionService.save(faction.copy(heirId = heirId)).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionHeirFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }
                val vassal = vassalName
                when {
                    heirId == null -> sender.sendMessage("$GREEN${plugin.language["CommandFactionHeirCleared"]}")
                    vassal != null -> sender.sendMessage("$GREEN${plugin.language["CommandFactionHeirSuccessVassalLeader", targetName, vassal]}")
                    else -> sender.sendMessage("$GREEN${plugin.language["CommandFactionHeirSuccess", targetName]}")
                }
            }
        )
        return true
    }

    /**
     * The faction the given player heads, if it has sworn fealty to [liege]; null otherwise.
     *
     * Checked here so a nomination that could never take effect is refused at the point it is made
     * rather than silently ignored years later. It is checked again at the succession itself, because
     * a vassal can declare independence or replace its own head in between and neither of those tells
     * this command anything.
     */
    private fun vassalLedBy(playerId: MfPlayerId, liege: MfFaction): MfFaction? {
        val ledFaction = plugin.services.factionService.getFaction(playerId) ?: return null
        if (ledFaction.primaryOwnerId != playerId) return null
        if (plugin.services.factionRelationshipService.getLiege(ledFaction.id) != liege.id) return null
        return ledFaction
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        if (sender !is Player) return emptyList()
        val playerService = plugin.services.playerService
        val faction = playerService.getPlayer(sender)?.let { plugin.services.factionService.getFaction(it.id) }
            ?: return emptyList()
        val prefix = args.lastOrNull()?.lowercase() ?: ""
        val vassalLeaders = plugin.services.factionRelationshipService.getVassals(faction.id)
            .mapNotNull { plugin.services.factionService.getFaction(it)?.primaryOwnerId }
            .mapNotNull { playerService.getPlayer(it)?.name }
        return (faction.members.mapNotNull { playerService.getPlayer(it.playerId)?.name } + vassalLeaders + "none")
            .filter { it.lowercase().startsWith(prefix) }
    }
}
