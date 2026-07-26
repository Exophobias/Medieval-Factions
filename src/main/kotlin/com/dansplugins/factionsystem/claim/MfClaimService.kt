package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import com.dansplugins.factionsystem.area.MfChunkPosition
import com.dansplugins.factionsystem.event.faction.FactionClaimEvent
import com.dansplugins.factionsystem.event.faction.FactionUnclaimEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.exception.WorldClaimBlockedException
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.resultFrom
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.ChatMessageType.ACTION_BAR
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MfClaimService(private val plugin: MedievalFactions, private val repository: MfClaimedChunkRepository) {

    private data class ClaimKey(val worldId: UUID, val x: Int, val z: Int) {
        constructor(claimedChunk: MfClaimedChunk) : this(claimedChunk.worldId, claimedChunk.x, claimedChunk.z)
    }

    private val claimsByKey: MutableMap<ClaimKey, MfClaimedChunk> = ConcurrentHashMap()

    // Secondary index: faction id -> the set of that faction's claim keys. Maintained alongside
    // claimsByKey at every mutation point (init/save/delete/deleteAll) so that per-faction reads
    // (count/existence/list) cost O(claims owned by the faction) instead of O(all claims on the server).
    private val claimKeysByFaction: MutableMap<MfFactionId, MutableSet<ClaimKey>> = ConcurrentHashMap()

    init {
        plugin.logger.info("Loading claims...")
        val startTime = System.currentTimeMillis()
        claimsByKey.putAll(repository.getClaims().associateBy { ClaimKey(it.worldId, it.x, it.z) })
        claimsByKey.values.forEach(::indexClaim)
        plugin.logger.info("${claimsByKey.size} claims loaded (${System.currentTimeMillis() - startTime}ms)")
    }

    fun getClaim(worldId: UUID, x: Int, z: Int): MfClaimedChunk? = claimsByKey[ClaimKey(worldId, x, z)]
    fun getClaim(world: World, x: Int, z: Int): MfClaimedChunk? = getClaim(world.uid, x, z)
    fun getClaim(chunk: Chunk): MfClaimedChunk? = getClaim(chunk.world, chunk.x, chunk.z)
    fun getClaim(chunkPosition: MfChunkPosition): MfClaimedChunk? = getClaim(chunkPosition.worldId, chunkPosition.x, chunkPosition.z)

    fun isClaimingBlockedInWorld(worldName: String): Boolean {
        val blockedWorlds = plugin.config.getStringList("factions.blockedClaimWorlds")
        return blockedWorlds.contains(worldName)
    }

    fun isClaimingBlockedInWorld(world: World): Boolean {
        return isClaimingBlockedInWorld(world.name)
    }

    @JvmName("getClaimsByFactionId")
    fun getClaims(factionId: MfFactionId): List<MfClaimedChunk> =
        claimKeysByFaction[factionId]?.mapNotNull(claimsByKey::get) ?: emptyList()

    /** O(1) count of the chunks a faction has claimed. Prefer this over `getClaims(factionId).size`. */
    fun getClaimCount(factionId: MfFactionId): Int = claimKeysByFaction[factionId]?.size ?: 0

    /** O(1) check for whether a faction has any claims. Prefer this over `getClaims(factionId).isNotEmpty()`. */
    fun hasClaims(factionId: MfFactionId): Boolean = !claimKeysByFaction[factionId].isNullOrEmpty()

    /** Third-party exceptions to territory protection. See [ClaimOverrideRegistry]. */
    val claimOverrides = ClaimOverrideRegistry(plugin.logger)

    /**
     * Whether a registered [ClaimOverrideProvider] grants [playerId] an exception at this exact
     * block for this exact [action].
     *
     * ## Why this is a separate call rather than an argument to [isInteractionAllowed]
     *
     * The obvious design is a wider overload of the protection check itself. It was tried and
     * rejected, for two reasons that only became visible once the existing suite ran against it.
     *
     * First, **it silently weakened the tests.** MF's listener tests mock `MfClaimService`, so
     * every stub of `isInteractionAllowed` stops intercepting the moment listeners call a different
     * overload. Mockito then returns `false` for the unstubbed one — which happens to be what most
     * of those stubs already said, so the majority of the suite kept passing while no longer
     * exercising anything. Only the handful of `thenReturn(true)` cases failed. A change that leaves
     * a protection suite green for the wrong reason is worse than one that breaks it loudly.
     *
     * Second, it hid the additive property. Written as a separate condition at each call site, the
     * shape is `denied by MF && not overridden`, and it is obvious by inspection that an override
     * can only widen permission and never narrow it. Buried inside the check, that guarantee
     * depends on reading the implementation.
     *
     * ## Ordering does not matter here, and that is the point
     *
     * Because this is consulted only when MF has already decided to deny, it does not matter that
     * MF's own logic returns early for a factionless player on its third line. The exception is
     * evaluated independently, so a factionless priest hosted on another group's land is reached —
     * which a provider loop appended inside the existing method would never have managed.
     *
     * @return true only when some provider affirmatively permits it
     */
    fun isOverridden(
        playerId: MfPlayerId,
        world: World,
        x: Int,
        y: Int,
        z: Int,
        action: ClaimAction
    ): Boolean {
        // The common server registers no providers at all; this keeps the hot path free.
        if (claimOverrides.isEmpty()) {
            return false
        }
        val playerUuid = runCatching { UUID.fromString(playerId.value) }.getOrNull() ?: return false
        return claimOverrides.allows(playerUuid, world, x, y, z, action)
    }

    @JvmName("isInteractionAllowedForPlayerInChunk")
    fun isInteractionAllowed(playerId: MfPlayerId, claim: MfClaimedChunk): Boolean {
        val factionService = plugin.services.factionService
        val playerFaction = factionService.getFaction(playerId) ?: return false
        if (claim.factionId == playerFaction.id) return true
        val claimFaction = factionService.getFaction(claim.factionId) ?: return true
        val relationshipService = plugin.services.factionRelationshipService
        val vassals = relationshipService.getVassalTree(claim.factionId)
        if (claimFaction.flags[plugin.flags.vassalageTreeCanInteractWithLand] && vassals.contains(playerFaction.id)) return true
        val lieges = relationshipService.getLiegeChain(claim.factionId)
        if (claimFaction.flags[plugin.flags.liegeChainCanInteractWithLand] && lieges.contains(playerFaction.id)) return true
        val allies = relationshipService.getRelationships(claim.factionId, MfFactionRelationshipType.ALLY).map { it.targetId }
        if (claimFaction.flags[plugin.flags.alliesCanInteractWithLand] && allies.contains(playerFaction.id)) return true
        val atWar = relationshipService.getRelationships(claim.factionId, MfFactionRelationshipType.AT_WAR).map { it.targetId }
        if (plugin.config.getBoolean("pvp.enableWartimeBlockDestruction") && atWar.contains(playerFaction.id)) return true
        return false
    }

    /**
     * Checks if ladder placement is allowed in enemy territory during wartime.
     * This is used to allow players to place ladders for sieges when their faction is at war with the territory owner.
     *
     * @param playerId The ID of the player attempting to place a ladder
     * @param claim The claimed chunk where the player is attempting the action
     * @param isLadder Whether the item being placed is a ladder
     * @return true if ladder placement should be allowed, false otherwise
     */
    fun isWartimeLadderPlacementAllowed(playerId: MfPlayerId, claim: MfClaimedChunk, isLadder: Boolean): Boolean {
        if (!isLadder) return false
        if (!plugin.config.getBoolean("factions.laddersPlaceableInEnemyFactionTerritory")) return false

        val factionService = plugin.services.factionService
        val playerFaction = factionService.getFaction(playerId) ?: return false
        val claimFactionId = claim.factionId

        val relationshipService = plugin.services.factionRelationshipService
        return relationshipService.getFactionsAtWarWith(playerFaction.id).contains(claimFactionId)
    }

    /**
     * Checks if placing a block of the given material is allowed in enemy territory during wartime
     * because it appears in the `factions.wartimePlaceableBlocks` config list.
     *
     * @param playerId The ID of the player attempting to place the block
     * @param claim The claimed chunk where the player is attempting the action
     * @param material The material of the block being placed
     * @return true if placement should be allowed, false otherwise
     */
    fun isWartimePlaceableBlock(playerId: MfPlayerId, claim: MfClaimedChunk, material: Material): Boolean =
        isWartimeBlockActionAllowed(playerId, claim, material, "factions.wartimePlaceableBlocks")

    /**
     * Checks if breaking a block of the given material is allowed in enemy territory during wartime
     * because it appears in the `factions.wartimeBreakableBlocks` config list.
     *
     * @param playerId The ID of the player attempting to break the block
     * @param claim The claimed chunk where the player is attempting the action
     * @param material The material of the block being broken
     * @return true if breaking should be allowed, false otherwise
     */
    fun isWartimeBreakableBlock(playerId: MfPlayerId, claim: MfClaimedChunk, material: Material): Boolean =
        isWartimeBlockActionAllowed(playerId, claim, material, "factions.wartimeBreakableBlocks")

    /**
     * Checks if interacting with a block of the given material is allowed in enemy territory during wartime
     * because it appears in the `factions.wartimeInteractableBlocks` config list.
     *
     * @param playerId The ID of the player attempting to interact with the block
     * @param claim The claimed chunk where the player is attempting the action
     * @param material The material of the block being interacted with
     * @return true if interaction should be allowed, false otherwise
     */
    fun isWartimeInteractableBlock(playerId: MfPlayerId, claim: MfClaimedChunk, material: Material): Boolean =
        isWartimeBlockActionAllowed(playerId, claim, material, "factions.wartimeInteractableBlocks")

    private fun isWartimeBlockActionAllowed(playerId: MfPlayerId, claim: MfClaimedChunk, material: Material, configKey: String): Boolean {
        val blocks = plugin.config.getStringList(configKey).mapTo(HashSet()) { it.uppercase() }
        if (material.name !in blocks) return false
        val factionService = plugin.services.factionService
        val playerFaction = factionService.getFaction(playerId) ?: return false
        val relationshipService = plugin.services.factionRelationshipService
        return relationshipService.getFactionsAtWarWith(playerFaction.id).contains(claim.factionId)
    }

    // Checks whether a set of chunks has at least one chunk that is adjacent to an existing claim. Works across multiple worlds.
    fun isClaimAdjacent(id: MfFactionId, vararg chunks: MfChunkPosition): Boolean {
        return chunks.any { chunk ->
            getClaim(chunk.worldId, chunk.x - 1, chunk.z)?.factionId == id ||
                getClaim(chunk.worldId, chunk.x + 1, chunk.z)?.factionId == id ||
                getClaim(chunk.worldId, chunk.x, chunk.z - 1)?.factionId == id ||
                getClaim(chunk.worldId, chunk.x, chunk.z + 1)?.factionId == id
        }
    }

    fun save(claim: MfClaimedChunk) = resultFrom {
        val world = plugin.server.getWorld(claim.worldId)
        if (world != null && isClaimingBlockedInWorld(world)) {
            throw WorldClaimBlockedException("Claims are not allowed in this world")
        }
        val factionService = plugin.services.factionService
        val faction = factionService.getFaction(claim.factionId).let(::requireNotNull)
        val event = FactionClaimEvent(claim.factionId, claim, !plugin.server.isPrimaryThread)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) throw EventCancelledException("Event cancelled")
        val result = repository.upsert(event.claim)
        val previousClaim = claimsByKey.put(ClaimKey(result), result)
        if (previousClaim == null) {
            indexClaim(result)
        } else if (previousClaim.factionId != result.factionId) {
            unindexClaim(previousClaim)
            indexClaim(result)
        }
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val world = plugin.server.getWorld(event.claim.worldId)
                if (world != null) {
                    val players = world.players.filter { it.location.chunk.x == claim.x && it.location.chunk.z == claim.z }
                    if (players.isNotEmpty()) {
                        plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                players.forEach { player ->
                                    val title = "${ChatColor.of(faction.flags[plugin.flags.color])}${faction.name}"
                                    val subtitle = "${ChatColor.of(faction.flags[plugin.flags.color])}${faction.description}"
                                    if (plugin.config.getBoolean("factions.titleTerritoryIndicator")) {
                                        player.resetTitle()
                                        player.sendTitle(
                                            title,
                                            subtitle,
                                            plugin.config.getInt("factions.titleTerritoryFadeInLength"),
                                            plugin.config.getInt("factions.titleTerritoryDuration"),
                                            plugin.config.getInt("factions.titleTerritoryFadeOutLength")
                                        )
                                    }
                                    if (plugin.config.getBoolean("factions.actionBarTerritoryIndicator")) {
                                        player.spigot().sendMessage(ACTION_BAR, *TextComponent.fromLegacyText(title))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        )
        val mapService = plugin.services.mapService
        if (mapService != null && !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")) {
            plugin.server.scheduler.runTask(
                plugin,
                Runnable {
                    mapService.scheduleUpdateClaims(faction)
                }
            )
        }
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    fun delete(claim: MfClaimedChunk) = resultFrom {
        val event = FactionUnclaimEvent(claim.factionId, claim, !plugin.server.isPrimaryThread)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) throw EventCancelledException("Event cancelled")
        val result = repository.delete(event.claim.worldId, event.claim.x, event.claim.z)
        val removedClaim = claimsByKey.remove(ClaimKey(event.claim))
        if (removedClaim != null) {
            unindexClaim(removedClaim)
        }
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val world = plugin.server.getWorld(event.claim.worldId)
                if (world != null) {
                    val players = world.players.filter { it.location.chunk.x == claim.x && it.location.chunk.z == claim.z }
                    if (players.isNotEmpty()) {
                        players.forEach { player ->
                            val title =
                                "${ChatColor.of(plugin.config.getString("wilderness.color"))}${plugin.language["Wilderness"]}"
                            if (plugin.config.getBoolean("factions.titleTerritoryIndicator")) {
                                player.resetTitle()
                                player.sendTitle(
                                    title,
                                    null,
                                    plugin.config.getInt("factions.titleTerritoryFadeInLength"),
                                    plugin.config.getInt("factions.titleTerritoryDuration"),
                                    plugin.config.getInt("factions.titleTerritoryFadeOutLength")
                                )
                            }
                            if (plugin.config.getBoolean("factions.actionBarTerritoryIndicator")) {
                                player.spigot().sendMessage(ACTION_BAR, *TextComponent.fromLegacyText(title))
                            }
                        }
                    }
                }
            }
        )
        val mapService = plugin.services.mapService
        if (mapService != null) {
            val factionService = plugin.services.factionService
            val faction = factionService.getFaction(claim.factionId)
            if (faction != null && !plugin.config.getBoolean("dynmap.onlyRenderTerritoriesUponStartup")) {
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        mapService.scheduleUpdateClaims(faction)
                    }
                )
            }
        }
        val lockService = plugin.services.lockService
        lockService.unloadLockedBlocks(claim)
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmName("deleteAllClaimsByFactionId")
    fun deleteAllClaims(factionId: MfFactionId) = resultFrom {
        val result = repository.deleteAll(factionId)
        val claimsToDelete = claimsByKey.filterValues { it.factionId == factionId }
        claimsToDelete.forEach { (key, value) ->
            if (claimsByKey.remove(key, value)) {
                unindexClaim(value)
            }
        }
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    private fun indexClaim(claim: MfClaimedChunk) {
        claimKeysByFaction.computeIfAbsent(claim.factionId) { ConcurrentHashMap.newKeySet() }.add(ClaimKey(claim))
    }

    private fun unindexClaim(claim: MfClaimedChunk) {
        claimKeysByFaction.computeIfPresent(claim.factionId) { _, keys ->
            keys.remove(ClaimKey(claim))
            if (keys.isEmpty()) null else keys
        }
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }
}
