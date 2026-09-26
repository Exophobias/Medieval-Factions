package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.duel.MfDuelService
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.potion.MfPotionService
import com.dansplugins.factionsystem.service.Services
import com.dansplugins.factionsystem.teleport.MfTeleportService
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.potion.PotionData
import org.bukkit.potion.PotionType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class AdminFactionProtectionTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var services: Services
    private lateinit var claimService: MfClaimService
    private lateinit var factionService: MfFactionService
    private lateinit var duelService: MfDuelService
    private lateinit var teleportService: MfTeleportService
    private lateinit var potionService: MfPotionService
    private lateinit var playerService: MfPlayerService
    private lateinit var location: Location
    private lateinit var faction: MfFaction

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        services = mock(Services::class.java)
        claimService = mock(MfClaimService::class.java)
        factionService = mock(MfFactionService::class.java)
        duelService = mock(MfDuelService::class.java)
        teleportService = mock(MfTeleportService::class.java)
        potionService = mock(MfPotionService::class.java)
        playerService = mock(MfPlayerService::class.java)
        location = mock(Location::class.java)
        val chunk = mock(Chunk::class.java)
        val factionId = MfFactionId.generate()
        `when`(plugin.services).thenReturn(services)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.duelService).thenReturn(duelService)
        `when`(services.teleportService).thenReturn(teleportService)
        `when`(services.potionService).thenReturn(potionService)
        `when`(services.playerService).thenReturn(playerService)
        `when`(location.chunk).thenReturn(chunk)
        `when`(claimService.getClaim(chunk)).thenReturn(MfClaimedChunk(UUID.randomUUID(), 0, 0, factionId))
        faction = mock(MfFaction::class.java)
        `when`(faction.adminLeaderless).thenReturn(true)
        `when`(factionService.getFaction(factionId)).thenReturn(faction)
    }

    private fun flags(vararg entries: Pair<String, Boolean>) {
        `when`(faction.flags).thenReturn(MfFlagValues(plugin, mapOf(*entries)))
    }

    private fun player(): Player = mock(Player::class.java).also { `when`(it.location).thenReturn(location) }

    @Test
    fun adminPvpOffBlocksPlayerHitsBeforeDuelRules() {
        flags(AdminFactionProtection.PVP.storageKey to false)
        val event = mock(EntityDamageByEntityEvent::class.java)
        val victim = player()
        val attacker = player()
        `when`(event.entity).thenReturn(victim)
        `when`(event.damager).thenReturn(attacker)

        EntityDamageByEntityListener(plugin).onEntityDamageByEntity(event)

        verify(event).isCancelled = true
        verifyNoInteractions(duelService)
    }

    @Test
    fun adminPvpOffBlocksPlayerShotProjectiles() {
        flags(AdminFactionProtection.PVP.storageKey to false)
        val victim = player()
        val shooter = player()
        val projectile = mock(Projectile::class.java)
        `when`(projectile.shooter).thenReturn(shooter)
        val event = mock(EntityDamageByEntityEvent::class.java)
        `when`(event.entity).thenReturn(victim)
        `when`(event.damager).thenReturn(projectile)

        EntityDamageByEntityListener(plugin).onEntityDamageByEntity(event)

        verify(event).isCancelled = true
    }

    @Test
    fun adminPvpOffRemovesHarmfulLingeringPotionEffectsBeforeDuelRules() {
        flags(AdminFactionProtection.PVP.storageKey to false)
        val cloud = mock(AreaEffectCloud::class.java)
        val potionData = mock(PotionData::class.java)
        `when`(cloud.basePotionData).thenReturn(potionData)
        `when`(potionData.type).thenReturn(PotionType.POISON)
        val thrower = player()
        `when`(potionService.getLingeringPotionEffectThrower(cloud)).thenReturn(thrower)
        val victim = player()
        val affected = mutableListOf<LivingEntity>(victim)
        val event = mock(AreaEffectCloudApplyEvent::class.java)
        `when`(event.entity).thenReturn(cloud)
        `when`(event.affectedEntities).thenReturn(affected)

        AreaEffectCloudApplyListener(plugin).onAreaEffectCloudApply(event)

        assertTrue(affected.isEmpty())
        verifyNoInteractions(duelService)
    }

    @Test
    fun mixedLingeringPotionStillProtectsLaterVictim() {
        flags(AdminFactionProtection.PVP.storageKey to false)
        val cloud = mock(AreaEffectCloud::class.java)
        val potionData = mock(PotionData::class.java)
        `when`(cloud.basePotionData).thenReturn(potionData)
        `when`(potionData.type).thenReturn(PotionType.POISON)
        val thrower = player()
        `when`(potionService.getLingeringPotionEffectThrower(cloud)).thenReturn(thrower)
        val outside = mock(Player::class.java)
        val outsideLocation = mock(Location::class.java)
        val outsideChunk = mock(Chunk::class.java)
        `when`(outsideLocation.chunk).thenReturn(outsideChunk)
        `when`(outside.location).thenReturn(outsideLocation)
        val protectedVictim = player()
        val affected = mutableListOf<LivingEntity>(outside, protectedVictim)
        val event = mock(AreaEffectCloudApplyEvent::class.java)
        `when`(event.entity).thenReturn(cloud)
        `when`(event.affectedEntities).thenReturn(affected)
        val mfPlayer = mock(MfPlayer::class.java)
        `when`(mfPlayer.id).thenReturn(MfPlayerId(UUID.randomUUID().toString()))
        `when`(playerService.getPlayer(thrower)).thenReturn(mfPlayer)
        `when`(playerService.getPlayer(outside)).thenReturn(mfPlayer)
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("pvp.enabledForFactionlessPlayers")).thenReturn(true)

        AreaEffectCloudApplyListener(plugin).onAreaEffectCloudApply(event)

        assertTrue(affected.contains(outside))
        assertFalse(affected.contains(protectedVictim))
    }

    @Test
    fun adminPlayerDamageOffBlocksEnvironmentalDamageBeforeTeleportBookkeeping() {
        flags(AdminFactionProtection.PLAYER_DAMAGE.storageKey to false)
        val event = mock(EntityDamageEvent::class.java)
        val victim = player()
        `when`(event.entity).thenReturn(victim)

        EntityDamageListener(plugin).onEntityDamage(event)

        verify(event).isCancelled = true
        verifyNoInteractions(teleportService)
    }

    @Test
    fun adminMobSpawningOffBlocksPeacefulCustomSpawnsDespiteGlobalRules() {
        flags(AdminFactionProtection.MOB_SPAWNING.storageKey to false)
        val event = mock(CreatureSpawnEvent::class.java)
        `when`(event.location).thenReturn(location)
        `when`(event.entity).thenReturn(mock(LivingEntity::class.java))
        `when`(event.spawnReason).thenReturn(CreatureSpawnEvent.SpawnReason.CUSTOM)

        CreatureSpawnListener(plugin).onCreatureSpawn(event)

        verify(event).isCancelled = true
    }

    @Test
    fun unsetRulesAndOrdinaryFactionsKeepNormalBehavior() {
        flags(
            AdminFactionProtection.PVP.storageKey to false,
            AdminFactionProtection.PLAYER_DAMAGE.storageKey to false,
            AdminFactionProtection.MOB_SPAWNING.storageKey to false
        )
        `when`(faction.adminLeaderless).thenReturn(false)
        val event = mock(EntityDamageByEntityEvent::class.java)
        val victim = player()
        val attacker = player()
        `when`(event.entity).thenReturn(victim)
        `when`(event.damager).thenReturn(attacker)
        assertFalse(AdminFactionProtectionChecks.deniesDamage(plugin, event))
        assertFalse(AdminFactionProtectionChecks.denies(plugin, location, AdminFactionProtection.MOB_SPAWNING))

        `when`(faction.adminLeaderless).thenReturn(true)
        flags()
        assertFalse(AdminFactionProtectionChecks.deniesDamage(plugin, event))
        assertFalse(AdminFactionProtectionChecks.denies(plugin, location, AdminFactionProtection.MOB_SPAWNING))
        assertTrue(AdminFactionProtection.PVP.isAllowed(faction))
    }
}
