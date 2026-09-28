package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.MercenaryCombatProvider
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.duel.MfDuel
import com.dansplugins.factionsystem.duel.MfDuelId
import com.dansplugins.factionsystem.duel.MfDuelService
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Hanging
import org.bukkit.entity.Donkey
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.bukkit.plugin.ServicesManager
import java.util.UUID

class EntityDamageByEntityListenerTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var services: Services
    private lateinit var playerService: MfPlayerService
    private lateinit var factionService: MfFactionService
    private lateinit var duelService: MfDuelService
    private lateinit var claimService: MfClaimService
    private lateinit var embassyService: MfEmbassyService
    private lateinit var config: FileConfiguration
    private lateinit var uut: EntityDamageByEntityListener

    private lateinit var damager: Player
    private lateinit var damaged: Player
    private lateinit var damagerMfPlayer: MfPlayer
    private lateinit var damagedMfPlayer: MfPlayer
    private lateinit var event: EntityDamageByEntityEvent

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        services = mock(Services::class.java)
        playerService = mock(MfPlayerService::class.java)
        factionService = mock(MfFactionService::class.java)
        duelService = mock(MfDuelService::class.java)
        claimService = mock(MfClaimService::class.java)
        embassyService = mock(MfEmbassyService::class.java)
        config = mock(FileConfiguration::class.java)

        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(playerService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.duelService).thenReturn(duelService)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.embassyService).thenReturn(embassyService)
        `when`(plugin.config).thenReturn(config)

        // Default: pvp allowed for factionless
        `when`(config.getBoolean("pvp.enabledForFactionlessPlayers")).thenReturn(true)

        damager = mock(Player::class.java)
        damaged = mock(Player::class.java)
        val damagerId = UUID.randomUUID()
        val damagedId = UUID.randomUUID()
        `when`(damager.uniqueId).thenReturn(damagerId)
        `when`(damaged.uniqueId).thenReturn(damagedId)

        damagerMfPlayer = mock(MfPlayer::class.java)
        damagedMfPlayer = mock(MfPlayer::class.java)
        `when`(damagerMfPlayer.id).thenReturn(MfPlayerId(damagerId.toString()))
        `when`(damagedMfPlayer.id).thenReturn(MfPlayerId(damagedId.toString()))

        `when`(playerService.getPlayer(damager)).thenReturn(damagerMfPlayer)
        `when`(playerService.getPlayer(damaged)).thenReturn(damagedMfPlayer)

        event = mock(EntityDamageByEntityEvent::class.java)
        `when`(event.damager).thenReturn(damager)
        `when`(event.entity).thenReturn(damaged)

        uut = EntityDamageByEntityListener(plugin)
    }

    @Test
    fun onEntityDamage_BothInSameDuel_ShouldNotCancel() {
        val duelId = MfDuelId("shared-duel")
        val duel = mock(MfDuel::class.java)
        `when`(duel.id).thenReturn(duelId)

        `when`(duelService.getDuel(damagerMfPlayer.id)).thenReturn(duel)
        `when`(duelService.getDuel(damagedMfPlayer.id)).thenReturn(duel)

        uut.onEntityDamageByEntity(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun onEntityDamage_DamagerInDuelButDamagedIsNot_ShouldCancel() {
        val duel = mock(MfDuel::class.java)
        `when`(duel.id).thenReturn(MfDuelId("damager-duel"))

        `when`(duelService.getDuel(damagerMfPlayer.id)).thenReturn(duel)
        `when`(duelService.getDuel(damagedMfPlayer.id)).thenReturn(null)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun onEntityDamage_DamagedInDuelButDamagerIsNot_ShouldCancel() {
        val duel = mock(MfDuel::class.java)
        `when`(duel.id).thenReturn(MfDuelId("damaged-duel"))

        `when`(duelService.getDuel(damagerMfPlayer.id)).thenReturn(null)
        `when`(duelService.getDuel(damagedMfPlayer.id)).thenReturn(duel)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun onEntityDamage_InDifferentDuels_ShouldCancel() {
        val duel1 = mock(MfDuel::class.java)
        val duel2 = mock(MfDuel::class.java)
        `when`(duel1.id).thenReturn(MfDuelId("duel-1"))
        `when`(duel2.id).thenReturn(MfDuelId("duel-2"))

        `when`(duelService.getDuel(damagerMfPlayer.id)).thenReturn(duel1)
        `when`(duelService.getDuel(damagedMfPlayer.id)).thenReturn(duel2)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun onEntityDamage_NeitherInDuel_ShouldNotCancelDueToDuel() {
        `when`(duelService.getDuel(damagerMfPlayer.id)).thenReturn(null)
        `when`(duelService.getDuel(damagedMfPlayer.id)).thenReturn(null)

        uut.onEntityDamageByEntity(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun mercenaryAllowPermitsCrossFactionMeleeWithoutWar() {
        crossFactionWithProvider(MercenaryCombatProvider.Decision.ALLOW)
        `when`(config.getBoolean("pvp.warRequiredForPlayersOfDifferentFactions")).thenReturn(true)

        uut.onEntityDamageByEntity(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun mercenaryDenyBlocksCrossFactionProjectileDamageBeforeWarLookup() {
        crossFactionWithProvider(MercenaryCombatProvider.Decision.DENY)
        val projectile = mock(Projectile::class.java)
        `when`(projectile.shooter).thenReturn(damager)
        `when`(event.damager).thenReturn(projectile)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun hostCannotBreakEmbassyArmorStandFromOutside() {
        val world = mock(World::class.java)
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val armorStand = mock(ArmorStand::class.java)
        val location = Location(world, 16.5, 70.0, 0.5)
        val worldId = UUID.randomUUID()
        `when`(world.uid).thenReturn(worldId)
        `when`(world.getChunkAt(1, 0)).thenReturn(chunk)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(armorStand.location).thenReturn(location)
        `when`(event.entity).thenReturn(armorStand)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BREAK))
            .thenReturn(EmbassyAccessDecision.DENY)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun guestCanBreakOwnEmbassyArmorStandWithoutHostMobFlag() {
        val world = mock(World::class.java)
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val armorStand = mock(ArmorStand::class.java)
        val location = Location(world, 16.5, 70.0, 0.5)
        val worldId = UUID.randomUUID()
        `when`(world.uid).thenReturn(worldId)
        `when`(world.getChunkAt(1, 0)).thenReturn(chunk)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(armorStand.location).thenReturn(location)
        `when`(event.entity).thenReturn(armorStand)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BREAK))
            .thenReturn(EmbassyAccessDecision.GRANT)

        uut.onEntityDamageByEntity(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun actorlessDispenserProjectileCannotDamageEmbassyProperty() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        val armorStand = mock(ArmorStand::class.java)
        val projectile = mock(Projectile::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(armorStand.location).thenReturn(Location(world, 16.5, 70.0, 0.5))
        `when`(event.entity).thenReturn(armorStand)
        `when`(event.damager).thenReturn(projectile)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun hostProjectileCannotKillGuestStorageDonkeyFromOutside() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val donkey = mock(Donkey::class.java)
        val projectile = mock(Projectile::class.java)
        val location = Location(world, 16.5, 70.0, 0.5)
        `when`(world.uid).thenReturn(worldId)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(donkey.location).thenReturn(location)
        `when`(projectile.shooter).thenReturn(damager)
        `when`(event.entity).thenReturn(donkey)
        `when`(event.damager).thenReturn(projectile)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BREAK))
            .thenReturn(EmbassyAccessDecision.DENY)

        uut.onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun actorlessProjectileDamageToPlayerUsesOrdinaryCombatRules() {
        val projectile = mock(Projectile::class.java)
        `when`(event.damager).thenReturn(projectile)

        uut.onEntityDamageByEntity(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun actorlessProjectileCannotBreakEmbassyItemFrame() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        val frame = mock(Hanging::class.java)
        val projectile = mock(Projectile::class.java)
        val breakEvent = mock(HangingBreakByEntityEvent::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(frame.location).thenReturn(Location(world, 16.5, 70.0, 0.5))
        `when`(breakEvent.entity).thenReturn(frame)
        `when`(breakEvent.remover).thenReturn(projectile)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)

        uut.onHangingBreak(breakEvent)

        verify(breakEvent).isCancelled = true
    }

    @Test
    fun guestCannotPlaceItemFrameDuringClearing() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val frame = mock(Hanging::class.java)
        val location = Location(world, 16.5, 70.0, 0.5)
        val place = mock(HangingPlaceEvent::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(frame.location).thenReturn(location)
        `when`(place.entity).thenReturn(frame)
        `when`(place.player).thenReturn(damager)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BUILD))
            .thenReturn(EmbassyAccessDecision.DENY)

        uut.onHangingPlace(place)

        verify(place).isCancelled = true
    }

    @Test
    fun hostCannotPlaceArmorStandInsideActiveEmbassy() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val stand = mock(ArmorStand::class.java)
        val location = Location(world, 16.5, 70.0, 0.5)
        val place = mock(EntityPlaceEvent::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(world.getChunkAt(location)).thenReturn(chunk)
        `when`(stand.location).thenReturn(location)
        `when`(place.entity).thenReturn(stand)
        `when`(place.player).thenReturn(damager)
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassyService.isParcelProtectionActive(worldId, 1, 0)).thenReturn(true)
        `when`(embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BUILD))
            .thenReturn(EmbassyAccessDecision.DENY)

        uut.onEntityPlace(place)

        verify(place).isCancelled = true
    }

    private fun crossFactionWithProvider(decision: MercenaryCombatProvider.Decision) {
        val attackingFaction = mock(MfFaction::class.java)
        val defendingFaction = mock(MfFaction::class.java)
        `when`(attackingFaction.id).thenReturn(MfFactionId("attacker"))
        `when`(defendingFaction.id).thenReturn(MfFactionId("victim"))
        `when`(factionService.getFaction(damagerMfPlayer.id)).thenReturn(attackingFaction)
        `when`(factionService.getFaction(damagedMfPlayer.id)).thenReturn(defendingFaction)

        val server = mock(Server::class.java)
        val manager = mock(ServicesManager::class.java)
        val provider = MercenaryCombatProvider { _, _ -> decision }
        `when`(plugin.server).thenReturn(server)
        `when`(server.servicesManager).thenReturn(manager)
        `when`(manager.load(MercenaryCombatProvider::class.java)).thenReturn(provider)
    }
}
