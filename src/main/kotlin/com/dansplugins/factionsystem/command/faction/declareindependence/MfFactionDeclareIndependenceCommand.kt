package com.dansplugins.factionsystem.command.faction.declareindependence

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor
import org.bukkit.ChatColor.RED
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level.SEVERE

class MfFactionDeclareIndependenceCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.declareindependence")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceNoPermission"]}")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceNotAPlayer"]}")
            return true
        }
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(sender)
                    ?: playerService.save(MfPlayer(plugin, sender)).onFailure {
                        sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val factionService = plugin.services.factionService
                val faction = factionService.getFaction(mfPlayer.id)
                if (faction == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceMustBeInAFaction"]}")
                    return@Runnable
                }
                val role = faction.getRole(mfPlayer.id)
                if (role == null || !role.hasPermission(faction, plugin.factionPermissions.declareIndependence)) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceNoFactionPermission"]}")
                    return@Runnable
                }
                val factionRelationshipService = plugin.services.factionRelationshipService
                // We could probably use singleOrNull here, but I think firstOrNull is better in the case that a faction
                // does somehow manage to get multiple lieges, and it doesn't hurt the user experience in the normal case.
                val liegeRelationship = factionRelationshipService.getRelationships(faction.id, LIEGE).firstOrNull()
                if (liegeRelationship == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceNoLiege"]}")
                    return@Runnable
                }
                val liege = factionService.getFaction(liegeRelationship.targetId)
                if (liege == null) {
                    sender.sendMessage("$RED${plugin.language["CommandFactionDeclareIndependenceNoLiege"]}")
                    return@Runnable
                }
                val establishWar = !faction.flags[plugin.flags.isNeutral] &&
                    !liege.flags[plugin.flags.isNeutral]
                factionRelationshipService.breakOath(faction.id, liege.id, establishWar)
                    .onFailure {
                        val key = if (establishWar) {
                            "CommandFactionDeclareIndependenceFailedToSaveRelationship"
                        } else {
                            "CommandFactionDeclareIndependenceFailedToDeleteRelationshipWithLiege"
                        }
                        sender.sendMessage("$RED${plugin.language[key]}")
                        plugin.logger.log(SEVERE, "Failed to establish independence: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }

                if (establishWar) {
                    plugin.server.scheduler.runTask(
                        plugin,
                        Runnable {
                            faction.sendMessage(
                                plugin.language["FactionDeclaredIndependenceWarNotificationTitle", liege.displayName],
                                plugin.language["FactionDeclaredIndependenceWarNotificationBody", liege.displayName]
                            )
                            liege.sendMessage(
                                plugin.language["VassalDeclaredIndependenceWarNotificationTitle", faction.displayName],
                                plugin.language["VassalDeclaredIndependenceWarNotificationBody", faction.displayName]
                            )
                            plugin.server.onlinePlayers.filter { onlinePlayer ->
                                (faction.members + liege.members).none { member -> member.playerId.toBukkitPlayer().uniqueId == onlinePlayer.uniqueId }
                            }.forEach { onlinePlayer -> onlinePlayer.sendMessage("$RED${plugin.language["FactionDeclaredIndependenceWar", faction.displayName, liege.displayName]}") }
                        }
                    )
                } else {
                    plugin.server.scheduler.runTask(
                        plugin,
                        Runnable {
                            faction.sendMessage(
                                plugin.language["FactionDeclaredIndependenceNotificationTitle", liege.displayName],
                                plugin.language["FactionDeclaredIndependenceNotificationBody", liege.displayName]
                            )
                            liege.sendMessage(
                                plugin.language["VassalDeclaredIndependenceNotificationTitle", faction.displayName],
                                plugin.language["VassalDeclaredIndependenceNotificationBody", faction.displayName]
                            )
                            plugin.server.onlinePlayers.filter { onlinePlayer ->
                                (faction.members + liege.members).none { member -> member.playerId.toBukkitPlayer().uniqueId == onlinePlayer.uniqueId }
                            }.forEach { onlinePlayer -> onlinePlayer.sendMessage("$RED${plugin.language["FactionDeclaredIndependence", faction.displayName, liege.displayName]}") }
                        }
                    )
                }
                sender.sendMessage("${ChatColor.GREEN}${plugin.language["CommandFactionDeclareIndependenceSuccess", liege.displayName]}")
            }
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ) = emptyList<String>()
}
