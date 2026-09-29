package com.dansplugins.factionsystem.notification

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.lang.Language
import org.bukkit.ChatColor.RED
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.TimeUnit

class TerritoryBypassWarningsTest {
    private lateinit var warnings: TerritoryBypassWarnings
    private var now = 0L
    private val message = "${RED}Bypassed faction territory protection."

    @BeforeEach
    fun setUp() {
        now = 0L
        val plugin = mock(MedievalFactions::class.java)
        val language = mock(Language::class.java, { invocation ->
            if (invocation.method.name == "get") {
                "Bypassed faction territory protection."
            } else {
                RETURNS_DEFAULTS.answer(invocation)
            }
        })
        `when`(plugin.language).thenReturn(language)
        warnings = TerritoryBypassWarnings(plugin) { now }
    }

    @Test
    fun `first notice is sent and repeated attempts do not extend cooldown`() {
        val player = player()

        warnings.notify(player, false)
        now = TimeUnit.SECONDS.toNanos(30)
        warnings.notify(player, false)
        now = TimeUnit.SECONDS.toNanos(59)
        warnings.notify(player, false)
        verify(player, times(1)).sendMessage(message)

        now = TimeUnit.SECONDS.toNanos(60)
        warnings.notify(player, false)
        verify(player, times(2)).sendMessage(message)
    }

    @Test
    fun `players have independent cooldowns`() {
        val first = player()
        val second = player()

        warnings.notify(first, false)
        now = TimeUnit.SECONDS.toNanos(1)
        warnings.notify(second, false)
        warnings.notify(first, false)

        verify(first, times(1)).sendMessage(message)
        verify(second, times(1)).sendMessage(message)
    }

    @Test
    fun `muted attempts do not send or advance the cooldown`() {
        val player = player()

        warnings.notify(player, true)
        warnings.notify(player, false)
        now = TimeUnit.SECONDS.toNanos(30)
        warnings.notify(player, true)
        now = TimeUnit.SECONDS.toNanos(59)
        warnings.notify(player, false)
        verify(player, times(1)).sendMessage(message)

        now = TimeUnit.SECONDS.toNanos(60)
        warnings.notify(player, false)
        verify(player, times(2)).sendMessage(message)
    }

    @Test
    fun `reset and forget allow a fresh notice`() {
        val player = player()

        warnings.notify(player, false)
        warnings.reset(player.uniqueId)
        warnings.notify(player, false)
        warnings.forget(player.uniqueId)
        warnings.notify(player, false)

        verify(player, times(3)).sendMessage(message)
    }

    private fun player(): Player = mock(Player::class.java).also {
        `when`(it.uniqueId).thenReturn(UUID.randomUUID())
    }
}
