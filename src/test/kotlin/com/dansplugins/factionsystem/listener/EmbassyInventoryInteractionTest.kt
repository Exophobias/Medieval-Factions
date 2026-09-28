package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import com.dansplugins.factionsystem.lang.Language
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import java.util.UUID

class EmbassyInventoryInteractionTest {
    private lateinit var listener: InventoryClickListener
    private lateinit var embassy: MfEmbassyService
    private lateinit var player: Player
    private lateinit var top: Inventory
    private lateinit var bottom: Inventory
    private lateinit var view: InventoryView
    private lateinit var parcel: MfEmbassy
    private lateinit var claim: MfClaimedChunk
    private var actorId = MfPlayerId("")
    private lateinit var world: World
    private lateinit var chunk: Chunk

    @BeforeEach
    fun setUp() {
        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val playerService = mock(MfPlayerService::class.java)
        val claimService = mock(MfClaimService::class.java)
        val factionService = mock(MfFactionService::class.java)
        embassy = mock(MfEmbassyService::class.java)
        player = mock(Player::class.java)
        top = mock(Inventory::class.java)
        bottom = mock(Inventory::class.java)
        view = mock(InventoryView::class.java)
        world = mock(World::class.java)
        chunk = mock(Chunk::class.java)
        claim = mock(MfClaimedChunk::class.java)
        parcel = mock(MfEmbassy::class.java)
        val worldId = UUID.randomUUID()
        val hostId = MfFactionId("host")
        actorId = MfPlayerId(UUID.randomUUID().toString())
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.language).thenReturn(mock(Language::class.java))
        `when`(services.playerService).thenReturn(playerService)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.embassyService).thenReturn(embassy)
        `when`(playerService.getPlayer(player)).thenReturn(MfPlayer(actorId))
        `when`(world.uid).thenReturn(worldId)
        `when`(view.topInventory).thenReturn(top)
        `when`(view.bottomInventory).thenReturn(bottom)
        `when`(top.size).thenReturn(27)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(claim.worldId).thenReturn(worldId)
        `when`(claim.x).thenReturn(1)
        `when`(claim.z).thenReturn(0)
        `when`(claim.factionId).thenReturn(hostId)
        val host = mock(MfFaction::class.java)
        `when`(host.name).thenReturn("Host")
        `when`(factionService.getFaction(hostId)).thenReturn(host)
        `when`(embassy.getAt(worldId, 1, 0)).thenReturn(parcel)
        `when`(embassy.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(embassy.access(actorId, claim, ClaimAction.CONTAINER)).thenReturn(EmbassyAccessDecision.GRANT)
        `when`(parcel.status).thenReturn(MfEmbassyStatus.CLEARING)
        listener = InventoryClickListener(plugin)
    }

    private fun entityInventory() {
        val holder = mock(InventoryHolder::class.java, withSettings().extraInterfaces(Entity::class.java))
        val location = Location(world, 16.5, 70.0, 0.5)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`((holder as Entity).location).thenReturn(location)
        `when`(top.holder).thenReturn(holder)
    }

    @Test
    fun dragIntoPreopenedEntityInventoryIsDeniedDuringClearing() {
        entityInventory()
        val event = mock(InventoryDragEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.rawSlots).thenReturn(setOf(0, 1))

        listener.onInventoryDrag(event)

        verify(event).isCancelled = true
    }

    @Test
    fun shiftClickDepositIntoEntityInventoryIsDeniedDuringClearing() {
        entityInventory()
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(bottom)
        `when`(event.action).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY)

        listener.onInventoryClick(event)

        verify(event).isCancelled = true
    }

    @Test
    fun guestCanWithdrawFromEntityInventoryDuringClearing() {
        entityInventory()
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(top)
        `when`(event.action).thenReturn(InventoryAction.PICKUP_ALL)

        listener.onInventoryClick(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun unknownClickActionFailsClosedDuringClearing() {
        entityInventory()
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(bottom)
        `when`(event.action).thenReturn(InventoryAction.UNKNOWN)

        listener.onInventoryClick(event)

        verify(event).isCancelled = true
    }

    @Test
    fun creativeSlotWriteCannotRestockDuringClearing() {
        entityInventory()
        val event = mock(InventoryCreativeEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.rawSlot).thenReturn(0)

        listener.onCreativeInventory(event)

        verify(event).isCancelled = true
    }

    @Test
    fun hotbarSwapCannotDepositExistingStockDuringClearing() {
        entityInventory()
        val playerInventory = mock(PlayerInventory::class.java)
        val stock = mock(ItemStack::class.java)
        `when`(player.inventory).thenReturn(playerInventory)
        `when`(stock.type).thenReturn(Material.STONE)
        `when`(stock.amount).thenReturn(1)
        `when`(playerInventory.getItem(2)).thenReturn(stock)
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(top)
        `when`(event.action).thenReturn(InventoryAction.HOTBAR_SWAP)
        `when`(event.click).thenReturn(ClickType.NUMBER_KEY)
        `when`(event.hotbarButton).thenReturn(2)

        listener.onInventoryClick(event)

        verify(event).isCancelled = true
    }

    @Test
    fun emptyOffhandCanWithdrawDuringClearing() {
        entityInventory()
        val playerInventory = mock(PlayerInventory::class.java)
        val empty = mock(ItemStack::class.java)
        `when`(player.inventory).thenReturn(playerInventory)
        `when`(empty.type).thenReturn(Material.AIR)
        `when`(playerInventory.itemInOffHand).thenReturn(empty)
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(top)
        `when`(event.action).thenReturn(InventoryAction.HOTBAR_SWAP)
        `when`(event.click).thenReturn(ClickType.SWAP_OFFHAND)

        listener.onInventoryClick(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun hostPreopenedPhysicalAnvilRemainsDeniedAfterLeavingAcceptedParcel() {
        val location = Location(world, 16.5, 70.0, 0.5)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(top.location).thenReturn(location)
        `when`(parcel.status).thenReturn(MfEmbassyStatus.ACTIVE)
        `when`(embassy.access(actorId, claim, ClaimAction.INTERACT)).thenReturn(EmbassyAccessDecision.DENY)
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(top)
        `when`(event.action).thenReturn(InventoryAction.PICKUP_ALL)

        listener.onInventoryClick(event)

        verify(event).isCancelled = true
        verify(embassy).access(actorId, claim, ClaimAction.INTERACT)
    }

    @Test
    fun personalCraftingAndLocationlessVirtualMenusRemainAvailable() {
        val event = mock(InventoryClickEvent::class.java)
        `when`(event.whoClicked).thenReturn(player)
        `when`(event.inventory).thenReturn(top)
        `when`(event.view).thenReturn(view)
        `when`(event.clickedInventory).thenReturn(top)
        `when`(event.action).thenReturn(InventoryAction.UNKNOWN)
        `when`(top.holder).thenReturn(player)

        listener.onInventoryClick(event)

        `when`(top.holder).thenReturn(null)
        listener.onInventoryClick(event)
        verify(event, never()).isCancelled = true
    }
}
