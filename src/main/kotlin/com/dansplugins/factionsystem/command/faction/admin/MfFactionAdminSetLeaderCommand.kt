package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.dropFirst
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.logging.Level.SEVERE

class MfFactionAdminSetLeaderCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.admin.setleader")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderNoPermission"]}")
            return true
        }

        if (args.size < 2) {
            sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderUsage"]}")
            return true
        }

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                // Get target player
                val targetPlayer = plugin.server.getOfflinePlayer(args[0])
                if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderInvalidTargetPlayer"]}")
                    return@Runnable
                }

                val playerService = plugin.services.playerService
                val targetMfPlayer = playerService.getPlayer(targetPlayer)
                    ?: playerService.save(MfPlayer(plugin, targetPlayer)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }

                val factionService = plugin.services.factionService

                // Get target faction. Resolved BEFORE the membership guard below, because the
                // guard has to know which faction is being asked for.
                val targetFaction = factionService.getFaction(args.dropFirst().joinToString(" "))
                if (targetFaction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderInvalidTargetFaction"]}")
                    return@Runnable
                }

                // Refuse only if they belong to a DIFFERENT faction. It used to refuse anybody who
                // belonged to any faction at all, which made this command unable to do the one job
                // V9 hands it: every faction that existed before V9 has a null primary_owner_id,
                // deliberately not backfilled, and the migration says "operators appoint a head with
                // /f admin setleader". The obvious head of such a faction is a member of it, so the
                // guard always fired -- leaving every pre-existing faction on the server permanently
                // headless, with /f transfer, /f heir, succession and setPrimaryOwner all refusing
                // in turn because none of them will act without a recorded owner.
                val currentFaction = factionService.getFaction(targetMfPlayer.id)
                val alreadyAMember = currentFaction != null && currentFaction.id == targetFaction.id
                if (currentFaction != null && !alreadyAMember) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderTargetPlayerAlreadyInFaction"]}")
                    return@Runnable
                }

                // A member being promoted is not a member being added, so a full faction must not
                // block seating a head on it -- which would otherwise leave the largest factions,
                // the ones most in need of a head, as the ones that cannot be given one.
                val maxMembers = plugin.config.getInt("factions.maxMembers")
                if (!alreadyAMember && maxMembers > 0 && targetFaction.members.size >= maxMembers) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderTargetFactionFull"]}")
                    return@Runnable
                }

                // Find the top role. Selected by the authority it carries, not by the name "Owner",
                // which the faction itself can rename: a faction that renamed its top role used to
                // land here and be told it had none, and one that renamed a junior role to "Owner"
                // could have had the appointed leader handed that junior role instead.
                val ownerRole = targetFaction.roles.leaderRole
                if (ownerRole == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderNoOwnerRole"]}")
                    return@Runnable
                }

                // Add player as the owner. This is the one command that reassigns the recorded head
                // of a faction; nothing a faction can do to itself moves it.
                // PROMOTE IN PLACE when they are already a member, rather than appending. Two
                // MfFactionMember rows for one player make MfFaction.getRole -- a singleOrNull --
                // return null, so the appointed head would hold the seat with no role at all.
                val members = if (alreadyAMember) {
                    targetFaction.members.map {
                        if (it.playerId == targetMfPlayer.id) MfFactionMember(targetMfPlayer.id, ownerRole) else it
                    }
                } else {
                    targetFaction.members + MfFactionMember(targetMfPlayer.id, ownerRole)
                }
                val updatedFaction = factionService.save(
                    targetFaction.copy(
                        members = members,
                        invites = targetFaction.invites.filter { it.playerId != targetMfPlayer.id },
                        primaryOwnerId = targetMfPlayer.id
                    )
                ).onFailure {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderFailedToSaveFaction"]}")
                    plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                    return@Runnable
                }

                val targetName = targetMfPlayer.name ?: plugin.language["CommandFactionAdminSetLeaderUnknownPlayer"]
                updatedFaction.sendMessage(
                    plugin.language["FactionNewLeaderNotificationTitle", targetName],
                    plugin.language["FactionNewLeaderNotificationBody", targetName]
                )
                sender.sendMessage(
                    "$GREEN${plugin.language["CommandFactionAdminSetLeaderSuccess", targetName, targetFaction.name]}"
                )

                try {
                    factionService.cancelAllApplicationsForPlayer(targetMfPlayer)
                } catch (e: Exception) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionAdminSetLeaderFailedToCancelApplications"]}")
                    plugin.logger.log(SEVERE, "Failed to cancel applications: ${e.message}", e)
                }
            }
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {
        val factionService = plugin.services.factionService
        return when {
            args.isEmpty() ->
                plugin.server.onlinePlayers
                    .mapNotNull { it.name }
            args.size == 1 ->
                plugin.server.onlinePlayers
                    .filter { it.name?.lowercase()?.startsWith(args[0].lowercase()) == true }
                    .mapNotNull { it.name }
            args.size == 2 ->
                factionService.factions
                    .filter { it.name.lowercase().startsWith(args[1].lowercase()) }
                    .map(MfFaction::name)
            else -> emptyList()
        }
    }
}
