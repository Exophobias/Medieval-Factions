package com.dansplugins.factionsystem.command.faction.embassy

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyService.ChunkPos
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.Success
import org.bukkit.ChatColor.AQUA
import org.bukkit.ChatColor.GRAY
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.Chunk
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryHolder
import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs

/**
 * An embassy is a mutually accepted tenancy of connected host-owned chunks. Offering and accepting
 * require standing at the parcel so both sides can inspect it. Withdrawal and conquest decisions
 * may use a plot address because the landholder cannot enter an active embassy.
 */
class MfFactionEmbassyCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {
    private val verbs = listOf("offer", "accept", "decline", "revoke", "release", "finish", "seize", "passage", "info", "list")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.embassy")) {
            sender.sendMessage("${RED}You cannot use embassy commands.")
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("${RED}Embassy commands must be used at the plot in game.")
            return true
        }
        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            sender.sendMessage("$AQUA/f embassy offer <realm> [chunks], accept, decline, revoke, release, finish, seize, passage, info, list")
            sender.sendMessage("${GRAY}Stand in the plot to offer or accept. Revoke/release/seize/passage/info can name <world UUID>:<chunkX>,<chunkZ>.")
            sender.sendMessage("${GRAY}Use 4 for four connected chunks in your land, starting here; the default is 1. Offers last 7 days; clearing lasts 14 peaceful days.")
            return true
        }
        val factionService = plugin.services.factionService
        val actor = MfPlayerId(sender.uniqueId.toString())
        val faction = factionService.getFaction(actor)
        if (faction == null) {
            sender.sendMessage("${RED}You must belong to a realm to manage an embassy.")
            return true
        }
        val role = faction.getRole(actor)
        val verb = args[0].lowercase()
        val parcel = sender.location.chunk
        val selected = if (verb in setOf("revoke", "release", "seize", "passage", "info") && args.size > 1) {
            parseLocation(args.drop(1).joinToString(" ")) ?: run {
                sender.sendMessage("${RED}Use a plot from /f embassy list: <world UUID>:<chunkX>,<chunkZ>.")
                return true
            }
        } else {
            Plot(parcel.world.uid, parcel.x, parcel.z)
        }
        val embassies = plugin.services.embassyService
        if (verb == "list") {
            val rows = embassies.listFor(faction.id)
            runCatching { embassies.chunkLimit(faction.id) }.onSuccess { limit ->
                sender.sendMessage("${AQUA}Embassy chunks: hosting ${embassies.hostedChunkCount(faction.id)}/$limit, holding ${embassies.heldChunkCount(faction.id)}/$limit; offers and recovery count.")
            }.onFailure {
                sender.sendMessage("${RED}Your saved embassy allowance is invalid; staff must correct maxEmbassyChunks.")
            }
            if (rows.isEmpty()) {
                sender.sendMessage("${GRAY}Your realm has no embassy offers or plots.")
            } else {
                sender.sendMessage("${AQUA}Embassies for ${faction.displayName}:")
                rows.forEach { sender.sendMessage("${GRAY}${describe(it)}") }
            }
            return true
        }
        val existing = embassies.getAt(selected.worldId, selected.x, selected.z)
        if (verb == "info") {
            sender.sendMessage(
                existing?.let { "${AQUA}${describe(it)}" }
                ?: "${GRAY}There is no embassy offer or plot in this chunk."
            )
            return true
        }
        val hostVerb = verb == "offer" || verb == "revoke"
        val guestVerb = verb == "accept" || verb == "decline" || verb == "release" || verb == "finish"
        val conquerorVerb = verb == "seize" || verb == "passage"
        if (!hostVerb && !guestVerb && !conquerorVerb) {
            sender.sendMessage("${RED}Unknown embassy action. Use /f embassy help.")
            return true
        }
        val permission = if (hostVerb || conquerorVerb) plugin.factionPermissions.unclaim else plugin.factionPermissions.claim
        if (role?.hasPermission(faction, permission) != true) {
            sender.sendMessage("${RED}Your faction role cannot sign or end this land agreement.")
            return true
        }
        if (verb == "offer") {
            val offerArgs = args.drop(1)
            if (offerArgs.isEmpty()) {
                sender.sendMessage("${RED}Use /f embassy offer <realm> [chunks] while standing in your claim.")
                return true
            }
            // Resolve the whole name first so realms whose names end in a number remain usable.
            val namedGuest = factionService.getFaction(offerArgs.joinToString(" "))
            val guest: MfFaction
            val count: Int
            if (namedGuest != null) {
                guest = namedGuest
                count = 1
            } else {
                guest = if (offerArgs.size > 1) {
                    factionService.getFaction(offerArgs.dropLast(1).joinToString(" "))
                    ?: run {
                        sender.sendMessage("${RED}Name another existing realm as the guest.")
                        return true
                    }
                } else {
                    sender.sendMessage("${RED}Name another existing realm as the guest.")
                    return true
                }
                count = offerArgs.last().takeIf { it.matches(Regex("[0-9]+")) }
                    ?.toIntOrNull()?.takeIf { it > 0 } ?: run {
                    sender.sendMessage("${RED}Use a positive chunk count, for example /f embassy offer <realm> 4.")
                    return true
                }
            }
            if (guest.id == faction.id) {
                sender.sendMessage("${RED}Name another existing realm as the guest.")
                return true
            }
            val allowance = runCatching { minOf(embassies.chunkLimit(faction.id), embassies.chunkLimit(guest.id)) }
                .getOrElse {
                    sender.sendMessage("${RED}A saved embassy allowance is invalid; staff must correct maxEmbassyChunks.")
                    return true
                }
            if (count > allowance) {
                sender.sendMessage("${RED}This offer exceeds a realm's embassy allowance of $allowance chunks.")
                return true
            }
            val positions = connectedHostChunks(parcel, faction.id, count) ?: run {
                sender.sendMessage("${RED}There are not $count connected, loaded, available chunks in your realm's land starting here. Load adjoining claims or choose another starting chunk.")
                return true
            }
            val chunks = inspectableChunks(sender, parcel, positions) ?: return true
            val occupied = chunks.firstOrNull { !emptyOfInventories(it) }
            if (occupied != null) {
                sender.sendMessage("${RED}Clear every chest, hopper and other inventory from chunk ${occupied.x},${occupied.z} before offering it.")
                return true
            }
            val result = embassies.offerArea(faction.id, guest.id, parcel.world.uid, positions)
            report(sender, result) { granted ->
                sender.sendMessage("${GREEN}Offered ${granted.size} chunk(s) to ${guest.displayName} for 7 days.")
                sender.sendMessage("${GRAY}Selected chunks: ${positions.joinToString("; ") { "${it.x},${it.z}" }}. Review them with /f embassy list.")
                guest.sendMessage("Embassy offer", "${faction.displayName} offered ${granted.size} chunk(s) at ${location(granted.first())}. Visit an offered chunk and use /f embassy accept within 7 days.")
            }
            return true
        }
        if (existing == null) {
            sender.sendMessage("${RED}There is no embassy agreement in this chunk.")
            return true
        }
        if ((hostVerb && existing.hostId != faction.id) || (guestVerb && existing.guestId != faction.id) ||
            (conquerorVerb && existing.conquerorId != faction.id)
        ) {
            sender.sendMessage("${RED}Your realm is not the authority for this embassy action.")
            return true
        }
        val other = factionService.getFaction(
            if (hostVerb || conquerorVerb) {
            existing.guestId
        } else {
            existing.conquerorId ?: existing.hostId
        }
        )
        when (verb) {
            "accept" -> {
                val pending = embassies.pendingOfferAt(parcel.world.uid, parcel.x, parcel.z)
                if (pending.isEmpty()) {
                    sender.sendMessage("${RED}There is no pending embassy offer in this chunk.")
                    return true
                }
                val positions = pending.map { ChunkPos(it.chunkX, it.chunkZ) }
                val chunks = inspectableChunks(sender, parcel, positions) ?: return true
                val area = positions.map { it.x to it.z }.toSet()
                if (parcel.world.players.any { occupant ->
                        (occupant.location.chunk.x to occupant.location.chunk.z) in area &&
                            factionService.getFaction(MfPlayerId(occupant.uniqueId.toString()))?.id != faction.id &&
                            !(
                                occupant.hasPermission("mf.bypass") &&
                                plugin.services.playerService.getPlayer(occupant)?.isBypassEnabled == true
                            )
                    }
                ) {
                    sender.sendMessage("${RED}Everyone outside the guest realm must leave all offered chunks before acceptance.")
                    return true
                }
                val occupied = chunks.firstOrNull { !emptyOfInventories(it) }
                if (occupied != null) {
                    sender.sendMessage("${RED}The host must remove every physical inventory from chunk ${occupied.x},${occupied.z} before acceptance.")
                    return true
                }
                report(sender, embassies.acceptArea(faction.id, parcel.world.uid, parcel.x, parcel.z)) {
                    sender.sendMessage("${GREEN}Accepted ${it.size} embassy chunk(s). Your realm may now build and store goods here.")
                    other?.sendMessage("Embassy accepted", "${faction.displayName} accepted ${it.size} embassy chunk(s) at ${location(it.first())}.")
                }
            }
            "decline" -> report(sender, embassies.decline(faction.id, selected.worldId, selected.x, selected.z)) {
                sender.sendMessage("${GREEN}Offer declined. Any existing active embassy remains in place.")
                other?.sendMessage("Embassy offer declined", "${faction.displayName} declined the pending offer at ${location(existing)}.")
            }
            "release" -> report(sender, embassies.release(faction.id, selected.worldId, selected.x, selected.z)) {
                sender.sendMessage("${GREEN}${if (it == null) "Offer declined." else "Embassy entered 14 days of clearing. No new construction is allowed."}")
                other?.sendMessage("Embassy released", "${faction.displayName} ended the agreement at ${location(existing)}.")
            }
            "revoke" -> report(sender, embassies.revoke(faction.id, selected.worldId, selected.x, selected.z)) {
                sender.sendMessage("${GREEN}${if (it == null) "Offer cancelled." else "Embassy entered 14 days of clearing. The guest may recover its goods."}")
                other?.sendMessage("Embassy revoked", "${faction.displayName} ended the agreement at ${location(existing)}; the guest has 14 days to clear it.")
            }
            "finish" -> report(sender, embassies.finish(faction.id, parcel.world.uid, parcel.x, parcel.z)) {
                sender.sendMessage("${GREEN}Embassy cleared and returned to the current landholder.")
                other?.sendMessage("Embassy cleared", "${faction.displayName} finished clearing ${location(existing)}.")
            }
            "seize", "passage" -> report(
                sender,
                embassies.chooseConquest(
                faction.id,
                    selected.worldId,
                    selected.x,
                    selected.z,
                    verb == "passage"
            )
            ) {
                if (it == null) {
                    sender.sendMessage("${GREEN}Embassy property seized; your realm now controls the chunk.")
                    other?.sendMessage("Embassy seized", "${faction.displayName} seized former embassy property at ${location(existing)}.")
                } else {
                    sender.sendMessage("${GREEN}The guest has 14 days to recover its property at ${location(it)}.")
                    other?.sendMessage("Embassy passage", "${faction.displayName} granted 14 days to clear ${location(it)}.")
                }
            }
        }
        return true
    }

    private fun emptyOfInventories(chunk: Chunk): Boolean =
        chunk.tileEntities.none { it is InventoryHolder } && chunk.entities.none { it is InventoryHolder }

    /** Grow through side-adjacent eligible claims only, preferring a compact area near the player. */
    private fun connectedHostChunks(chunk: Chunk, hostId: MfFactionId, count: Int): List<ChunkPos>? {
        val start = ChunkPos(chunk.x, chunk.z)
        fun dx(position: ChunkPos) = position.x.toLong() - start.x
        fun dz(position: ChunkPos) = position.z.toLong() - start.z
        val frontier = PriorityQueue(
            compareBy<ChunkPos> { maxOf(abs(dx(it)), abs(dz(it))) }
            .thenBy { (if (dx(it) < 0) 1 else 0) + (if (dz(it) < 0) 1 else 0) }
            .thenBy { abs(dx(it)) + abs(dz(it)) }.thenBy { it.z }.thenBy { it.x }
        )
        val seen = mutableSetOf(start)
        val selected = mutableListOf<ChunkPos>()
        frontier.add(start)
        while (frontier.isNotEmpty() && selected.size < count) {
            val position = frontier.remove()
            if (plugin.services.claimService.getClaim(chunk.world.uid, position.x, position.z)?.factionId != hostId ||
                (position != start && !chunk.world.isChunkLoaded(position.x, position.z)) ||
                plugin.services.embassyService.getAt(chunk.world.uid, position.x, position.z) != null
            ) {
                    continue
                }
            selected.add(position)
            if (selected.size == count) break
            val neighbors = listOfNotNull(
                if (position.x < Int.MAX_VALUE) ChunkPos(position.x + 1, position.z) else null,
                if (position.z < Int.MAX_VALUE) ChunkPos(position.x, position.z + 1) else null,
                if (position.x > Int.MIN_VALUE) ChunkPos(position.x - 1, position.z) else null,
                if (position.z > Int.MIN_VALUE) ChunkPos(position.x, position.z - 1) else null
            )
            neighbors.filter { seen.add(it) }.forEach(frontier::add)
        }
        return selected.takeIf { it.size == count }
    }

    /** Inspect only loaded chunks; a large offer must not synchronously generate distant terrain. */
    private fun inspectableChunks(sender: Player, parcel: Chunk, positions: List<ChunkPos>): List<Chunk>? {
        val chunks = mutableListOf<Chunk>()
        for (position in positions) {
            if (position.x == parcel.x && position.z == parcel.z) {
                chunks.add(parcel)
            } else {
                if (!parcel.world.isChunkLoaded(position.x, position.z)) {
                    sender.sendMessage("${RED}Load chunk ${position.x},${position.z} before offering or accepting this area.")
                    return null
                }
                chunks.add(parcel.world.getChunkAt(position.x, position.z))
            }
        }
        return chunks
    }

    private fun location(row: MfEmbassy): String = "${row.worldId}:${row.chunkX},${row.chunkZ}"

    private data class Plot(val worldId: UUID, val x: Int, val z: Int)

    private fun parseLocation(text: String): Plot? {
        val colon = text.lastIndexOf(':')
        if (colon < 0) return null
        val world = runCatching { UUID.fromString(text.substring(0, colon)) }.getOrNull() ?: return null
        val coordinates = text.substring(colon + 1).split(',')
        if (coordinates.size != 2) return null
        return Plot(
            world,
            coordinates[0].toIntOrNull() ?: return null,
            coordinates[1].toIntOrNull() ?: return null
        )
    }

    private fun describe(row: MfEmbassy): String {
        val factions = plugin.services.factionService
        val host = factions.getFaction(row.hostId)?.displayName ?: row.hostId.value
        val guest = factions.getFaction(row.guestId)?.displayName ?: row.guestId.value
        val conqueror = row.conquerorId?.let { factions.getFaction(it)?.displayName ?: it.value }
        val recovery = row.status == MfEmbassyStatus.CLEARING || row.status == MfEmbassyStatus.CONQUEST_PASSAGE
        val deadline = if (recovery && row.pausedAt != null && row.deadlineAt != null) {
            val remaining = Duration.ofMillis((row.deadlineAt - row.pausedAt).coerceAtLeast(0L))
            ", paused by war (${remaining.toDays()}d ${remaining.toHoursPart()}h of peaceful retrieval remaining)"
        } else {
            row.deadlineAt?.let { ", until ${Instant.ofEpochMilli(it)}" } ?: ""
        }
        val holder = conqueror?.let { ", current landholder $it" } ?: ""
        return "${row.status} $host → $guest at ${location(row)}$holder$deadline"
    }

    private inline fun <T> report(sender: Player, result: Result4k<T, com.dansplugins.factionsystem.failure.ServiceFailure>, onSuccess: (T) -> Unit) {
        when (result) {
            is Success -> onSuccess(result.value)
            is Failure -> sender.sendMessage("${RED}${result.reason.message}")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("mf.embassy")) return emptyList()
        return when {
            args.size == 1 -> verbs.filter { it.startsWith(args[0].lowercase()) }
            args.size == 2 && args[0].equals("offer", ignoreCase = true) ->
                plugin.services.factionService.factions.map(MfFaction::name).filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }
}
