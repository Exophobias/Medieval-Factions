package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbassyEntryListenerTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var server: Server
    private lateinit var services: Services
    private lateinit var embassies: MfEmbassyService
    private lateinit var playerService: MfPlayerService
    private lateinit var world: World
    private lateinit var player: Player
    private var playerId = MfPlayerId("")
    private lateinit var moveListener: PlayerMoveListener
    private lateinit var teleportListener: PlayerTeleportListener

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        server = mock(Server::class.java)
        services = mock(Services::class.java)
        embassies = mock(MfEmbassyService::class.java)
        playerService = mock(MfPlayerService::class.java)
        world = mock(World::class.java)
        player = mock(Player::class.java)
        val uuid = UUID.randomUUID()
        playerId = MfPlayerId(uuid.toString())
        `when`(player.uniqueId).thenReturn(uuid)
        `when`(playerService.getPlayer(player)).thenReturn(MfPlayer(playerId))
        `when`(world.uid).thenReturn(UUID.randomUUID())
        `when`(world.minHeight).thenReturn(-64)
        `when`(world.maxHeight).thenReturn(320)
        val blocked = mock(Block::class.java)
        `when`(blocked.type).thenReturn(Material.STONE)
        `when`(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(blocked)
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.server).thenReturn(server)
        `when`(services.playerService).thenReturn(playerService)
        `when`(services.embassyService).thenReturn(embassies)
        `when`(embassies.hasActiveOrClearingEmbassy(world.uid, 1, 0)).thenReturn(true)
        `when`(embassies.isParcelProtectionActive(world.uid, 1, 0)).thenReturn(true)
        `when`(embassies.entryDecision(playerId, world.uid, 1, 0)).thenReturn(EmbassyAccessDecision.DENY)
        moveListener = PlayerMoveListener(plugin)
        teleportListener = PlayerTeleportListener(plugin)
    }

    private fun location(x: Double, z: Double = 8.5) = Location(world, x, 70.0, z)

    private fun safeWestExit() {
        val feet = mock(Block::class.java)
        val head = mock(Block::class.java)
        val floor = mock(Block::class.java)
        `when`(feet.isPassable).thenReturn(true)
        `when`(head.isPassable).thenReturn(true)
        `when`(floor.type).thenReturn(org.bukkit.Material.STONE)
        `when`(world.getBlockAt(15, 70, 8)).thenReturn(feet)
        `when`(world.getBlockAt(15, 71, 8)).thenReturn(head)
        `when`(world.getBlockAt(15, 69, 8)).thenReturn(floor)
    }

    @Test
    fun crossingIntoEmbassyIsCancelled() {
        val event = mock(PlayerMoveEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.from).thenReturn(location(15.5))
        `when`(event.to).thenReturn(location(16.5))

        moveListener.onEmbassyMove(event)

        verify(event).isCancelled = true
    }

    @Test
    fun ordinaryMovementOutOfEmbassyIsAllowed() {
        val event = mock(PlayerMoveEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.from).thenReturn(location(16.5))
        `when`(event.to).thenReturn(location(15.5))

        moveListener.onEmbassyMove(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun occupantAlreadyInsideIsRedirectedOnMovement() {
        safeWestExit()
        val event = mock(PlayerMoveEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.from).thenReturn(location(20.5))
        `when`(event.to).thenReturn(location(21.5))

        moveListener.onEmbassyMove(event)

        val destination = ArgumentCaptor.forClass(Location::class.java)
        verify(event).setTo(destination.capture())
        assertEquals(15, destination.value.blockX)
        verify(event, never()).isCancelled = true
    }

    @Test
    fun stationaryUnauthorizedOccupantIsEvictedBySweep() {
        safeWestExit()
        `when`(player.location).thenReturn(location(20.5))
        `when`(server.onlinePlayers).thenReturn(mutableListOf(player))
        `when`(player.teleport(org.mockito.ArgumentMatchers.any(Location::class.java))).thenReturn(true)

        moveListener.sweepEmbassyOccupants()

        val destination = ArgumentCaptor.forClass(Location::class.java)
        verify(player).teleport(destination.capture())
        assertEquals(15, destination.value.blockX)
    }

    @Test
    fun teleportAndPortalDestinationInsideEmbassyAreCancelled() {
        val event = mock(PlayerTeleportEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.to).thenReturn(location(16.5))

        teleportListener.onEmbassyTeleport(event)

        verify(event).isCancelled = true
    }

    @Test
    fun deniedRespawnIsMovedOutsideEmbassy() {
        safeWestExit()
        val event = mock(PlayerRespawnEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.respawnLocation).thenReturn(location(20.5))

        teleportListener.onEmbassyRespawn(event)

        val destination = ArgumentCaptor.forClass(Location::class.java)
        verify(event).respawnLocation = destination.capture()
        assertEquals(15, destination.value.blockX)
    }

    @Test
    fun deniedWorldSpawnFallsBackToPermittedServerWorldSpawn() {
        val blocked = mock(Block::class.java)
        `when`(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(blocked)
        `when`(world.spawnLocation).thenReturn(location(20.5))
        val otherWorld = mock(World::class.java)
        `when`(otherWorld.uid).thenReturn(UUID.randomUUID())
        `when`(otherWorld.minHeight).thenReturn(-64)
        `when`(otherWorld.maxHeight).thenReturn(320)
        val otherSpawn = Location(otherWorld, 100.5, 70.0, 100.5)
        `when`(otherWorld.spawnLocation).thenReturn(otherSpawn)
        `when`(server.worlds).thenReturn(mutableListOf(world, otherWorld))
        val feet = mock(Block::class.java)
        val head = mock(Block::class.java)
        val floor = mock(Block::class.java)
        `when`(feet.isPassable).thenReturn(true)
        `when`(head.isPassable).thenReturn(true)
        `when`(floor.type).thenReturn(org.bukkit.Material.STONE)
        `when`(otherWorld.getBlockAt(100, 70, 100)).thenReturn(feet)
        `when`(otherWorld.getBlockAt(100, 71, 100)).thenReturn(head)
        `when`(otherWorld.getBlockAt(100, 69, 100)).thenReturn(floor)

        val event = mock(PlayerRespawnEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.respawnLocation).thenReturn(location(20.5))
        teleportListener.onEmbassyRespawn(event)

        val destination = ArgumentCaptor.forClass(Location::class.java)
        verify(event).respawnLocation = destination.capture()
        assertEquals(otherWorld, destination.value.world)
        assertEquals(100, destination.value.blockX)
    }

    @Test
    fun unoccupiedStorageVehicleCannotBeMovedOutAndLooted() {
        val vehicle = mock(Vehicle::class.java, withSettings().extraInterfaces(InventoryHolder::class.java))
        val from = location(20.5)
        val event = mock(VehicleMoveEvent::class.java)
        `when`(vehicle.uniqueId).thenReturn(UUID.randomUUID())
        `when`(vehicle.teleport(from)).thenReturn(true)
        `when`(event.vehicle).thenReturn(vehicle)
        `when`(event.from).thenReturn(from)
        `when`(event.to).thenReturn(location(32.5))

        moveListener.onVehicleMove(event)

        verify(vehicle).teleport(from)
    }

    @Test
    fun failedPassengerVehicleRollbackDismountsAndRelocatesDeniedPlayer() {
        val vehicle = mock(Vehicle::class.java)
        val from = location(15.5)
        val event = mock(VehicleMoveEvent::class.java)
        `when`(vehicle.uniqueId).thenReturn(UUID.randomUUID())
        `when`(vehicle.passengers).thenReturn(mutableListOf<org.bukkit.entity.Entity>(player))
        `when`(vehicle.teleport(from)).thenReturn(false, true)
        `when`(player.isInsideVehicle).thenReturn(true)
        `when`(player.teleport(from)).thenReturn(true)
        `when`(event.vehicle).thenReturn(vehicle)
        `when`(event.from).thenReturn(from)
        `when`(event.to).thenReturn(location(16.5))

        moveListener.onVehicleMove(event)

        verify(vehicle).eject()
        verify(player).leaveVehicle()
        verify(player).teleport(from)
        verify(vehicle, times(2)).teleport(from)
    }

    private fun stockedMount(): LivingEntity {
        val mount = mock(LivingEntity::class.java, withSettings().extraInterfaces(InventoryHolder::class.java))
        val inventory = mock(Inventory::class.java)
        val item = mock(ItemStack::class.java)
        `when`(mount.uniqueId).thenReturn(UUID.randomUUID())
        `when`((mount as InventoryHolder).inventory).thenReturn(inventory)
        `when`(item.type).thenReturn(Material.DIAMOND)
        `when`(item.amount).thenReturn(1)
        `when`(inventory.contents).thenReturn(arrayOf(item))
        return mount
    }

    @Test
    fun stockedLivingMountCannotWanderOrBePushedOutsideParcel() {
        val mount = stockedMount()
        var cancelled = false

        moveListener.onEmbassyLivingMove(mount, location(20.5), location(32.5)) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun ordinaryBoatCannotCarryStockedMountAcrossParcelBoundary() {
        val boat = mock(Vehicle::class.java)
        val mount = stockedMount()
        val from = location(20.5)
        val event = mock(VehicleMoveEvent::class.java)
        `when`(boat.uniqueId).thenReturn(UUID.randomUUID())
        `when`(boat.passengers).thenReturn(mutableListOf<org.bukkit.entity.Entity>(mount))
        `when`(boat.teleport(from)).thenReturn(true)
        `when`(event.vehicle).thenReturn(boat)
        `when`(event.from).thenReturn(from)
        `when`(event.to).thenReturn(location(32.5))

        moveListener.onVehicleMove(event)

        verify(boat).teleport(from)
    }

    @Test
    fun guestMayRideOrdinaryBoatIntoEmbassyWithPersonalInventory() {
        `when`(embassies.entryDecision(playerId, world.uid, 1, 0)).thenReturn(EmbassyAccessDecision.GRANT)
        val boat = mock(Vehicle::class.java)
        val event = mock(VehicleMoveEvent::class.java)
        `when`(boat.uniqueId).thenReturn(UUID.randomUUID())
        `when`(boat.passengers).thenReturn(mutableListOf<org.bukkit.entity.Entity>(player))
        `when`(event.vehicle).thenReturn(boat)
        `when`(event.from).thenReturn(location(15.5))
        `when`(event.to).thenReturn(location(16.5))

        moveListener.onVehicleMove(event)

        verify(boat, never()).teleport(org.mockito.ArgumentMatchers.any(Location::class.java))
        verify(player, never()).teleport(org.mockito.ArgumentMatchers.any(Location::class.java))
    }

    @Test
    fun stockedLivingMountCannotTeleportOrPortalOutOfParcel() {
        val mount = stockedMount()
        val teleport = mock(EntityTeleportEvent::class.java)
        `when`(teleport.entity).thenReturn(mount)
        `when`(teleport.from).thenReturn(location(20.5))
        `when`(teleport.to).thenReturn(location(32.5))
        val portal = mock(EntityPortalEvent::class.java)
        `when`(portal.entity).thenReturn(mount)
        `when`(portal.from).thenReturn(location(20.5))
        `when`(portal.to).thenReturn(location(32.5))

        moveListener.onVehicleTeleport(teleport)
        moveListener.onVehiclePortal(portal)

        verify(teleport).isCancelled = true
        verify(portal).isCancelled = true
    }

    @Test
    fun internalAreaBorderAllowsMobileStorageButDeniesDifferentAgreement() {
        `when`(embassies.isParcelProtectionActive(world.uid, 2, 0)).thenReturn(true)
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(true)
        val mount = stockedMount()
        var cancelled = false

        moveListener.onEmbassyLivingMove(mount, location(20.5), location(32.5)) {
            cancelled = true
        }

        assertFalse(cancelled)
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(false)
        moveListener.onEmbassyLivingMove(mount, location(20.5), location(32.5)) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun unauthorizedInternalCellCrossingRedirectsOccupantOutOfConnectedArea() {
        safeWestExit()
        `when`(embassies.hasActiveOrClearingEmbassy(world.uid, 2, 0)).thenReturn(true)
        `when`(embassies.entryDecision(playerId, world.uid, 2, 0)).thenReturn(EmbassyAccessDecision.DENY)
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(true)
        val event = mock(PlayerMoveEvent::class.java)
        `when`(event.player).thenReturn(player)
        `when`(event.from).thenReturn(location(31.5))
        `when`(event.to).thenReturn(location(32.5))

        moveListener.onEmbassyMove(event)

        val destination = ArgumentCaptor.forClass(Location::class.java)
        verify(event).setTo(destination.capture())
        assertEquals(15, destination.value.blockX)
        verify(event, never()).isCancelled = true
    }

    @Test
    fun interiorAreaOccupantExitSearchUsesOuterPerimeter() {
        val cells = (0..2).flatMap { x ->
            (-1..1).map { z ->
            `when`(embassies.entryDecision(playerId, world.uid, x, z)).thenReturn(EmbassyAccessDecision.DENY)
            MfEmbassy(
                world.uid, x, z, MfFactionId("host"), MfFactionId("guest"),
                MfEmbassyStatus.ACTIVE, 1L, 2L, null
            )
        }
        }
        `when`(embassies.protectedAreaAt(world.uid, 1, 0)).thenReturn(cells)
        val feet = mock(Block::class.java)
        val head = mock(Block::class.java)
        val floor = mock(Block::class.java)
        `when`(feet.isPassable).thenReturn(true)
        `when`(head.isPassable).thenReturn(true)
        `when`(floor.type).thenReturn(Material.STONE)
        `when`(world.getBlockAt(48, 70, 8)).thenReturn(feet)
        `when`(world.getBlockAt(48, 71, 8)).thenReturn(head)
        `when`(world.getBlockAt(48, 69, 8)).thenReturn(floor)

        val exit = EmbassyEntryGuard(plugin).exit(player, location(24.5))

        assertEquals(48, exit!!.blockX)
    }

    @Test
    fun paperMoveRegistrationDispatchesCancellableStorageProtection() {
        val manager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(manager)
        val executor = ArgumentCaptor.forClass(EventExecutor::class.java)

        moveListener.registerStorageMountMovement(TestLivingMoveEvent::class.java)

        verify(manager).registerEvent(
            org.mockito.ArgumentMatchers.eq(TestLivingMoveEvent::class.java),
            org.mockito.ArgumentMatchers.eq(moveListener),
            org.mockito.ArgumentMatchers.eq(EventPriority.LOWEST),
            executor.capture(),
            org.mockito.ArgumentMatchers.eq(plugin),
            org.mockito.ArgumentMatchers.eq(true)
        )
        val event = TestLivingMoveEvent(stockedMount(), location(20.5), location(32.5))
        executor.value.execute(moveListener, event)
        assertTrue(event.isCancelled())
    }

    @Test
    fun incompatibleLivingMoveApiFailsWithActionableAvailabilityError() {
        val failure = assertFailsWith<IllegalStateException> {
            moveListener.registerStorageMountMovement(Event::class.java)
        }
        assertTrue(failure.message!!.contains("embassy offers must stay disabled"))
    }

    class TestLivingMoveEvent(
        private val entity: LivingEntity,
        private val from: Location,
                              private val to: Location
    ) : Event(), Cancellable {
        private var cancelled = false
        fun getEntity(): LivingEntity = entity
        fun getFrom(): Location = from
        fun getTo(): Location = to
        override fun isCancelled(): Boolean = cancelled
        override fun setCancelled(value: Boolean) { cancelled = value }
        override fun getHandlers(): HandlerList = handlers
        companion object { private val handlers = HandlerList() }
    }
}
