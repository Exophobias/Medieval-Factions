package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfChunkPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfDemesne
import com.dansplugins.factionsystem.exception.WorldClaimBlockedException
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.ChatMessageType.ACTION_BAR
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor.RED
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Vehicle
import org.bukkit.inventory.InventoryHolder
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.plugin.EventExecutor
import java.util.UUID
import java.util.WeakHashMap
import java.util.logging.Level.SEVERE

class PlayerMoveListener(private val plugin: MedievalFactions) : Listener {
    private val embassyEntry = EmbassyEntryGuard(plugin)
    private val embassyBoundary = EmbassyBoundary(plugin)
    private val lastEntryWarning = WeakHashMap<Player, Long>()
    private val relocatingEntities = HashSet<UUID>()

    /** Paper's cancellable living-entity movement event is required for mobile embassy storage. */
    fun registerStorageMountMovement() {
        val type = try {
            Class.forName("io.papermc.paper.event.entity.EntityMoveEvent", false,
                plugin.javaClass.classLoader).asSubclass(Event::class.java)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException(
                "Embassy storage mount protection requires Paper's EntityMoveEvent; " +
                    "run the supported Paper server before enabling embassy offers.", failure)
        }
        registerStorageMountMovement(type)
    }

    internal fun registerStorageMountMovement(type: Class<out Event>) {
        try {
            require(Cancellable::class.java.isAssignableFrom(type))
            val entityMethod = type.getMethod("getEntity")
            val fromMethod = type.getMethod("getFrom")
            val toMethod = type.getMethod("getTo")
            require(LivingEntity::class.java.isAssignableFrom(entityMethod.returnType))
            require(Location::class.java.isAssignableFrom(fromMethod.returnType))
            require(Location::class.java.isAssignableFrom(toMethod.returnType))
            plugin.server.pluginManager.registerEvent(type, this, EventPriority.LOWEST,
                EventExecutor { _, event ->
                    if (!type.isInstance(event)) return@EventExecutor
                    onEmbassyLivingMove(entityMethod.invoke(event) as LivingEntity,
                        fromMethod.invoke(event) as Location, toMethod.invoke(event) as Location) {
                        (event as Cancellable).isCancelled = true
                    }
                }, plugin, true)
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Paper's EntityMoveEvent could not be registered for embassy storage mount " +
                    "protection; embassy offers must stay disabled until the runtime is compatible.",
                failure)
        }
    }

    internal fun onEmbassyLivingMove(entity: LivingEntity, from: Location, to: Location,
                                      cancel: () -> Unit) {
        if (entity.uniqueId in relocatingEntities) return
        if (entity !is InventoryHolder && playerPassengers(entity).isEmpty()) return
        guardEntityDestination(entity, from, to, cancel)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEmbassyMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (from.x == to.x && from.y == to.y && from.z == to.z && from.world == to.world) return
        if (!embassyEntry.isEmbassy(to) || !embassyEntry.isDenied(event.player, to)) return
        if (!embassyEntry.sameProtectedArea(from, to)) {
            event.isCancelled = true
        } else {
            // An agreement, faction change or end of war can leave someone inside. Move them out
            // on their first movement; movement toward the outside is always allowed.
            val exit = embassyEntry.exit(event.player, from)
            if (exit != null) event.setTo(exit)
            // If no safe relocation exists, allow walking inside toward the boundary. The
            // once-per-second sweep keeps retrying, and movement out remains unrestricted.
        }
        warnEntry(event.player)
    }

    /** Called once per second by the plugin, including for stationary occupants and login. */
    fun sweepEmbassyOccupants() {
        for (player in plugin.server.onlinePlayers) {
            val location = player.location
            if (!embassyEntry.isEmbassy(location) || !embassyEntry.isDenied(player, location)) continue
            if (embassyEntry.relocate(player, location)) warnEntry(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (embassyEntry.isEmbassy(event.vehicle.location) &&
            embassyEntry.isDenied(player, event.vehicle.location)) {
            event.isCancelled = true
            warnEntry(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityMount(event: EntityMountEvent) {
        val player = event.entity as? Player ?: return
        if (embassyEntry.isRelocating(player) || event.mount.uniqueId in relocatingEntities) return
        if (embassyEntry.isEmbassy(event.mount.location) &&
            embassyEntry.isDenied(player, event.mount.location)) {
            event.isCancelled = true
            warnEntry(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val player = event.exited as? Player ?: return
        if (embassyEntry.isRelocating(player) || event.vehicle.uniqueId in relocatingEntities) return
        if (embassyEntry.isEmbassy(event.vehicle.location) &&
            embassyEntry.isDenied(player, event.vehicle.location)) {
            event.isCancelled = true
            warnEntry(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        if (embassyEntry.isRelocating(player) || event.dismounted.uniqueId in relocatingEntities) return
        if (embassyEntry.isEmbassy(event.dismounted.location) &&
            embassyEntry.isDenied(player, event.dismounted.location)) {
            event.isCancelled = true
            warnEntry(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val vehicle = event.vehicle
        if (vehicle.uniqueId in relocatingEntities) return
        if (storageCrossesBoundary(vehicle, event.from, event.to)) {
            rollbackVehicle(vehicle, event.from)
            return
        }
        if (!embassyEntry.isEmbassy(event.to)) return
        val denied = playerPassengers(vehicle)
            .filter { embassyEntry.isDenied(it, event.to) }
        if (denied.isEmpty()) return
        if (embassyEntry.sameProtectedArea(event.from, event.to)) {
            denied.forEach { player ->
                if (embassyEntry.relocate(player, event.to)) warnEntry(player)
            }
        } else {
            rollbackVehicle(vehicle, event.from)
            denied.forEach(::warnEntry)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehiclePortal(event: EntityPortalEvent) {
        guardEntityDestination(event.entity, event.from, event.to) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleTeleport(event: EntityTeleportEvent) {
        guardEntityDestination(event.entity, event.from, event.to) {
            event.isCancelled = true
        }
    }

    private fun storageCrossesBoundary(entity: Entity, from: Location, to: Location): Boolean {
        val source = EmbassyBoundary.Point.of(from) ?: return false
        val destination = EmbassyBoundary.Point.of(to) ?: return false
        if (!embassyBoundary.crosses(source, destination)) return false
        return (listOf(entity) + passengerTree(entity)).any { carried ->
            carried !is Player && carried is InventoryHolder && (carried !is LivingEntity ||
                carried.inventory.contents.orEmpty().any {
                    it != null && it.amount > 0 && it.type != Material.AIR
                })
        }
    }

    private fun passengerTree(entity: Entity): List<Entity> {
        val passengers = ArrayList<Entity>()
        val remaining = java.util.ArrayDeque<Entity>(entity.passengers)
        val seen = HashSet<Entity>()
        while (remaining.isNotEmpty()) {
            val passenger = remaining.removeFirst()
            if (!seen.add(passenger)) continue
            passengers.add(passenger)
            remaining.addAll(passenger.passengers)
        }
        return passengers
    }

    private fun playerPassengers(entity: Entity): List<Player> =
        passengerTree(entity).filterIsInstance<Player>()

    private fun rollbackVehicle(vehicle: Vehicle, from: org.bukkit.Location) {
        relocatingEntities.add(vehicle.uniqueId)
        try {
            if (vehicle.teleport(from)) return
            // Bukkit can refuse teleporting a vehicle carrying passengers. Remove them under
            // the relocation guard, move players to an admitted destination, then restore stock.
            val passengers = passengerTree(vehicle)
            val guardedIds = passengers.map { it.uniqueId }.filter { it !in relocatingEntities }
            relocatingEntities.addAll(guardedIds)
            try {
                vehicle.eject()
                passengers.filterNot { it is Player }.forEach { it.eject() }
                passengers.filterIsInstance<Player>().forEach { player ->
                    embassyEntry.relocateTo(player, from)
                }
                passengers.filterNot { it is Player }.forEach { it.teleport(from) }
                vehicle.teleport(from)
            } finally {
                relocatingEntities.removeAll(guardedIds.toSet())
            }
        } finally {
            relocatingEntities.remove(vehicle.uniqueId)
        }
    }

    private fun guardEntityDestination(entity: Entity, from: Location, to: Location?, cancel: () -> Unit) {
        if (to == null) return
        if (entity.uniqueId in relocatingEntities) return
        if (storageCrossesBoundary(entity, from, to)) {
            cancel()
            return
        }
        if (!embassyEntry.isEmbassy(to)) return
        val denied = playerPassengers(entity)
            .filter { embassyEntry.isDenied(it, to) }
        if (denied.isEmpty()) return
        cancel()
        denied.forEach(::warnEntry)
    }

    private fun warnEntry(player: Player) {
        val now = System.currentTimeMillis()
        if (now - (lastEntryWarning[player] ?: 0L) < 2000) return
        lastEntryWarning[player] = now
        player.sendMessage("${RED}Only the guest faction may enter this embassy.")
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.chunk == to.chunk) return
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val claimService = plugin.services.claimService
                val newChunkClaim = claimService.getClaim(to.chunk)
                val oldChunkClaim = claimService.getClaim(from.chunk)
                val factionService = plugin.services.factionService
                val newChunkFaction = newChunkClaim?.let { factionService.getFaction(it.factionId) }
                val playerService = plugin.services.playerService
                val mfPlayer = playerService.getPlayer(event.player)
                    ?: playerService.save(MfPlayer(plugin, event.player)).onFailure {
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                val playerFaction = factionService.getFaction(mfPlayer.id)
                if (playerFaction != null) {
                    if (newChunkFaction == null && playerFaction.autoclaim) {
                        val playerRole = playerFaction.getRole(mfPlayer.id) ?: return@Runnable
                        val claimPermissionValue = playerRole.getPermissionValue(plugin.factionPermissions.claim) ?: return@Runnable
                        if (!claimPermissionValue || !event.player.hasPermission("mf.claim")) {
                            return@Runnable
                        }
                        if (claimService.isClaimingBlockedInWorld(to.world!!)) {
                            return@Runnable
                        }
                        if (plugin.config.getBoolean("factions.limitLand") &&
                            !MfDemesne.mayClaim(claimService.getClaimCount(playerFaction.id), 1, playerFaction.power, MfDemesne.Settings.from(plugin.config))
                        ) {
                            event.player.sendMessage("$RED${plugin.language["AutoclaimPowerLimitReached"]}")
                            val updatedFaction = factionService.save(playerFaction.copy(autoclaim = false)).onFailure {
                                plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                                return@Runnable
                            }
                            updatedFaction.sendMessage(
                                plugin.language["AutoclaimDisabledNotificationTitle"],
                                plugin.language["AutoclaimDisabledNotificationBody"]
                            )
                            return@Runnable
                        }
                        if (plugin.config.getBoolean("factions.contiguousClaims") &&
                            !claimService.isClaimAdjacent(playerFaction.id, *listOfNotNull(to.world?.let { MfChunkPosition(it.uid, to.chunk.x, to.chunk.z) }).toTypedArray()) &&
                            claimService.hasClaims(playerFaction.id)
                        ) {
                            event.player.sendMessage("$RED${plugin.language["CommandFactionClaimNotContiguous"]}")
                            val updatedFaction = factionService.save(playerFaction.copy(autoclaim = false)).onFailure {
                                plugin.logger.log(SEVERE, "Failed to save faction: ${it.reason.message}", it.reason.cause)
                                return@Runnable
                            }
                            updatedFaction.sendMessage(
                                plugin.language["AutoclaimDisabledNotificationTitle"],
                                plugin.language["AutoclaimDisabledNotificationBody"]
                            )
                            return@Runnable
                        }
                        claimService.save(MfClaimedChunk(to.chunk, playerFaction.id)).onFailure {
                            when (it.reason.cause) {
                                is WorldClaimBlockedException -> {
                                    // Silently skip autoclaim if world is blocked
                                    return@Runnable
                                }
                                else -> {
                                    plugin.logger.log(SEVERE, "Failed to save chunk claim: ${it.reason.message}", it.reason.cause)
                                    return@Runnable
                                }
                            }
                        }
                    }
                }
                if (newChunkClaim?.factionId?.value == oldChunkClaim?.factionId?.value) return@Runnable
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        val title = if (newChunkFaction != null) {
                            "${ChatColor.of(newChunkFaction.flags[plugin.flags.color])}${newChunkFaction.displayName}"
                        } else {
                            "${ChatColor.of(plugin.config.getString("wilderness.color"))}${plugin.language["Wilderness"]}"
                        }

                        val subtitle = if (newChunkFaction != null) {
                            "${ChatColor.of(newChunkFaction.flags[plugin.flags.color])}${newChunkFaction.description}"
                        } else {
                            null
                        }
                        if (plugin.config.getBoolean("factions.titleTerritoryIndicator")) {
                            event.player.resetTitle()
                            event.player.sendTitle(
                                title,
                                subtitle,
                                plugin.config.getInt("factions.titleTerritoryFadeInLength"),
                                plugin.config.getInt("factions.titleTerritoryDuration"),
                                plugin.config.getInt("factions.titleTerritoryFadeOutLength")
                            )
                        }
                        if (plugin.config.getBoolean("factions.actionBarTerritoryIndicator")) {
                            event.player.spigot().sendMessage(ACTION_BAR, *TextComponent.fromLegacyText(title))
                        }
                    }
                )
            }
        )
    }
}
