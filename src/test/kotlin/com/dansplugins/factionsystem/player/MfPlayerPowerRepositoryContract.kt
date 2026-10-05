package com.dansplugins.factionsystem.player

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import java.util.UUID

/** The SQL and JSON stores must apply the same power rules to the same player snapshots. */
abstract class MfPlayerPowerRepositoryContract {
    private lateinit var plugin: MedievalFactions
    private lateinit var repository: MfPlayerRepository

    protected fun configurePowerRepository(plugin: MedievalFactions, repository: MfPlayerRepository) {
        this.plugin = plugin
        this.repository = repository
        val config = YamlConfiguration()
        config.set("players.minPower", 0.0)
        config.set("players.maxPower", 20.0)
        config.set("players.hoursToReachMaxPower", 4.0)
        config.set("players.hoursToReachMinPower", 4.0)
        `when`(plugin.config).thenReturn(config)
    }

    protected abstract fun reopenPowerRepository(): MfPlayerRepository

    private fun player(power: Double, maxPower: Double? = null, logoutPower: Double = power) = MfPlayer(
        id = MfPlayerId(UUID.randomUUID().toString()),
        power = power,
        powerAtLogout = logoutPower,
        maxPowerOverride = maxPower
    )

    @Test
    fun `max power overrides survive insert update clear and reopening without changing current power`() {
        val initial = repository.upsert(player(40.0, 50.0).copy(isBypassEnabled = true, isBypassWarningMuted = true))
        assertEquals(initial, reopenPowerRepository().getPlayer(initial.id))

        val lowered = repository.upsert(initial.copy(maxPowerOverride = 10.0))
        val persisted = reopenPowerRepository().getPlayer(lowered.id)!!
        assertEquals(10.0, persisted.maxPowerOverride)
        assertEquals(40.0, persisted.power)
        assertEquals(40.0, persisted.powerAtLogout)
        assertTrue(persisted.isBypassEnabled)
        assertTrue(persisted.isBypassWarningMuted)

        val cleared = repository.upsert(persisted.copy(maxPowerOverride = null))
        val reloaded = reopenPowerRepository().getPlayer(cleared.id)!!
        assertNull(reloaded.maxPowerOverride)
        assertEquals(20.0, reloaded.effectiveMaxPower(plugin))
        assertEquals(40.0, reloaded.power)
    }

    @Test
    fun `online regeneration reaches each effective maximum and preserves online surplus and offline players`() {
        val normal = repository.upsert(player(0.0))
        val lower = repository.upsert(player(0.0, 5.0))
        val higher = repository.upsert(player(0.0, 50.0))
        val surplus = repository.upsert(player(40.0))
        val offline = repository.upsert(player(25.0, 50.0))
        val onlineIds = listOf(normal.id, lower.id, higher.id, surplus.id)

        // Sixteen fifteen-minute updates cover the configured four-hour regeneration period.
        repeat(16) { repository.increaseOnlinePlayerPower(onlineIds) }

        assertEquals(20.0, repository.getPlayer(normal.id)!!.power, 0.000001)
        assertEquals(5.0, repository.getPlayer(lower.id)!!.power, 0.000001)
        assertEquals(50.0, repository.getPlayer(higher.id)!!.power, 0.000001)
        assertEquals(surplus, repository.getPlayer(surplus.id))
        assertEquals(offline, repository.getPlayer(offline.id))
    }

    @Test
    fun `a player above the global maximum still regenerates toward their higher personal maximum`() {
        val higher = repository.upsert(player(25.0, 50.0))

        repository.increaseOnlinePlayerPower(listOf(higher.id))

        val regenerated = repository.getPlayer(higher.id)!!
        assertTrue(regenerated.power > 25.0)
        assertTrue(regenerated.power <= 50.0)
        assertEquals(50.0, regenerated.maxPowerOverride)
        assertEquals(higher.version + 1, regenerated.version)
    }

    @Test
    fun `offline decay excludes online IDs and removes surplus gradually even after lowering its maximum`() {
        val online = repository.upsert(player(40.0, 50.0))
        val defaultSurplus = repository.upsert(player(40.0))
        val loweredMaximum = repository.upsert(player(40.0, 10.0))
        val floor = repository.upsert(player(0.0, 10.0, logoutPower = 40.0))

        repository.decreaseOfflinePlayerPower(listOf(online.id))

        assertEquals(online, repository.getPlayer(online.id))
        assertEquals(floor, repository.getPlayer(floor.id))
        // At the first quarter hour of a four-hour decay, 40 * (1 - (1 / 16)^4) remains.
        val expectedPower = 39.9993896484375
        for (snapshot in listOf(defaultSurplus, loweredMaximum)) {
            val decayed = repository.getPlayer(snapshot.id)!!
            assertEquals(expectedPower, decayed.power, 0.000001)
            assertEquals(40.0, decayed.powerAtLogout)
            assertEquals(snapshot.maxPowerOverride, decayed.maxPowerOverride)
            assertEquals(snapshot.version + 1, decayed.version)
        }
    }

    @Test
    fun `an empty online list decays all offline players to the floor over the configured period`() {
        val surplus = repository.upsert(player(40.0, 10.0))
        var previousPower = surplus.power

        repeat(16) {
            repository.decreaseOfflinePlayerPower(emptyList())
            val current = repository.getPlayer(surplus.id)!!.power
            assertTrue(current <= previousPower + 0.000001)
            assertTrue(current >= 0.0)
            previousPower = current
        }

        assertEquals(0.0, previousPower, 0.000001)
        assertEquals(40.0, reopenPowerRepository().getPlayer(surplus.id)!!.powerAtLogout)
    }
}
