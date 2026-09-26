package com.dansplugins.factionsystem.command.faction.approve

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.approval.MfApprovalRequestId
import com.dansplugins.factionsystem.approval.MfApprovalRequestType.ALLY
import com.dansplugins.factionsystem.approval.MfApprovalRequestType.VASSALIZE
import com.dansplugins.factionsystem.approval.MfApprovalRequestType.WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.logging.Level.SEVERE

class MfFactionApproveCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.approve")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionApproveNoPermission"]}")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("$RED${plugin.language["CommandFactionApproveUsage"]}")
            return true
        }
        val requestId = MfApprovalRequestId(args[0])
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val approvalService = plugin.services.approvalRequestService
                val request = approvalService.getRequest(requestId)
                if (request == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionApproveRequestNotFound"]}")
                    return@Runnable
                }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(request.factionId)
                val target = factionService.getFaction(request.targetId)
                if (faction == null || target == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionApproveFactionNotFound"]}")
                    approvalService.removeRequest(requestId)
                    return@Runnable
                }
                val factionRelationshipService = plugin.services.factionRelationshipService
                when (request.type) {
                    WAR -> {
                        factionRelationshipService.ensureWarPair(faction.id, target.id, faction.id)
                            .onFailure {
                                sender.sendMessage("$RED${plugin.language["CommandFactionApproveFailedToSaveRelationship"]}")
                                plugin.logger.log(SEVERE, "Failed to save faction relationship: ${it.reason.message}", it.reason.cause)
                                return@Runnable
                            }
                        sender.sendMessage("$GREEN${plugin.language["CommandFactionApproveWarSuccess", faction.displayName, target.displayName]}")
                        plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                faction.sendMessage(
                                    plugin.language["FactionAtWarNotificationTitle", target.displayName],
                                    plugin.language["FactionAtWarNotificationBody", target.displayName]
                                )
                                target.sendMessage(
                                    plugin.language["FactionAtWarNotificationTitle", faction.displayName],
                                    plugin.language["FactionAtWarNotificationBody", faction.displayName]
                                )
                                plugin.server.onlinePlayers.filter { onlinePlayer ->
                                    (faction.members + target.members).none { member -> member.playerId.toBukkitPlayer().uniqueId == onlinePlayer.uniqueId }
                                }.forEach { onlinePlayer -> onlinePlayer.sendMessage("$RED${plugin.language["FactionDeclaredWar", faction.displayName, target.displayName]}") }
                            }
                        )
                    }
                    ALLY -> {
                        factionRelationshipService.save(
                            MfFactionRelationship(factionId = faction.id, targetId = target.id, type = MfFactionRelationshipType.ALLY)
                        ).onFailure {
                            sender.sendMessage("$RED${plugin.language["CommandFactionApproveFailedToSaveRelationship"]}")
                            plugin.logger.log(SEVERE, "Failed to save faction relationship: ${it.reason.message}", it.reason.cause)
                            return@Runnable
                        }
                        val reverseRelationships = factionRelationshipService.getRelationships(target.id, faction.id)
                        if (reverseRelationships.any { it.type == MfFactionRelationshipType.ALLY }) {
                            sender.sendMessage("$GREEN${plugin.language["CommandFactionApproveAllySuccess", faction.displayName, target.displayName]}")
                            plugin.server.scheduler.runTask(
                                plugin,
                                Runnable {
                                    faction.sendMessage(
                                        plugin.language["FactionAllyNotificationTitle", target.displayName],
                                        plugin.language["FactionAllyNotificationBody", target.displayName]
                                    )
                                    target.sendMessage(
                                        plugin.language["FactionAllyNotificationTitle", faction.displayName],
                                        plugin.language["FactionAllyNotificationBody", faction.displayName]
                                    )
                                }
                            )
                        } else {
                            sender.sendMessage("$GREEN${plugin.language["CommandFactionApproveAllyRequestSuccess", faction.displayName, target.displayName]}")
                            plugin.server.scheduler.runTask(
                                plugin,
                                Runnable {
                                    val requesterName = request.requesterId.toBukkitPlayer().name ?: plugin.language["UnknownPlayer"]
                                    faction.sendMessage(
                                        plugin.language["FactionAllyRequestSentNotificationTitle", requesterName, target.displayName],
                                        plugin.language["FactionAllyRequestSentNotificationBody", requesterName, target.displayName]
                                    )
                                    target.sendMessage(
                                        plugin.language["FactionAllyRequestReceivedNotificationTitle", requesterName, faction.displayName],
                                        plugin.language["FactionAllyRequestReceivedNotificationBody", requesterName, faction.displayName]
                                    )
                                }
                            )
                        }
                    }
                    VASSALIZE -> {
                        factionRelationshipService.save(
                            MfFactionRelationship(factionId = faction.id, targetId = target.id, type = MfFactionRelationshipType.VASSAL)
                        ).onFailure {
                            sender.sendMessage("$RED${plugin.language["CommandFactionApproveFailedToSaveRelationship"]}")
                            plugin.logger.log(SEVERE, "Failed to save faction relationship: ${it.reason.message}", it.reason.cause)
                            return@Runnable
                        }
                        sender.sendMessage("$GREEN${plugin.language["CommandFactionApproveVassalizeSuccess", faction.displayName, target.displayName]}")
                        plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                faction.sendMessage(
                                    plugin.language["FactionVassalizationRequestSentNotificationTitle", target.displayName],
                                    plugin.language["FactionVassalizationRequestSentNotificationBody", target.displayName]
                                )
                                target.sendMessage(
                                    plugin.language["FactionVassalizationRequestReceivedNotificationTitle", faction.displayName],
                                    plugin.language["FactionVassalizationRequestReceivedNotificationBody", faction.displayName]
                                )
                            }
                        )
                    }
                }
                approvalService.removeRequest(requestId)
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
        return when {
            args.isEmpty() || args.size == 1 -> {
                val approvalService = plugin.services.approvalRequestService
                val ids = approvalService.getAllRequests().map { it.id.value }
                if (args.isEmpty()) ids else ids.filter { it.lowercase().startsWith(args[0].lowercase()) }
            }
            else -> emptyList()
        }
    }
}
