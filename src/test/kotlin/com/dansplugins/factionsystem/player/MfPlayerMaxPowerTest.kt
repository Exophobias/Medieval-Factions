package com.dansplugins.factionsystem.player

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class MfPlayerMaxPowerTest {
    @Test
    fun `legacy players track configuration while explicit maximums survive configuration changes`() {
        val plugin = mock(MedievalFactions::class.java)
        val config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        config.set("players.maxPower", 20.0)
        val legacy = MfPlayer(MfPlayerId(UUID.randomUUID().toString()))
        val custom = legacy.copy(power = 40.0, maxPowerOverride = 50.0)

        assertNull(legacy.maxPowerOverride)
        assertEquals(20.0, legacy.effectiveMaxPower(plugin))
        assertEquals(50.0, custom.effectiveMaxPower(plugin))

        config.set("players.maxPower", 30.0)
        assertEquals(30.0, legacy.effectiveMaxPower(plugin))
        assertEquals(50.0, custom.effectiveMaxPower(plugin))
        assertEquals(40.0, custom.copy(maxPowerOverride = 10.0).power)
        assertEquals(50.0, custom.copy(power = 35.0).maxPowerOverride)
    }

    @Test
    fun `offline player constructor records the override and independent current and logout power`() {
        val offline = mock(OfflinePlayer::class.java)
        val uuid = UUID.randomUUID()
        `when`(offline.uniqueId).thenReturn(uuid)
        `when`(offline.name).thenReturn("Custom")

        val player = MfPlayer(offline, power = 40.0, powerOnLogout = 35.0, maxPowerOverride = 50.0)

        assertEquals(MfPlayerId(uuid.toString()), player.id)
        assertEquals("Custom", player.name)
        assertEquals(40.0, player.power)
        assertEquals(35.0, player.powerAtLogout)
        assertEquals(50.0, player.maxPowerOverride)
    }
}
