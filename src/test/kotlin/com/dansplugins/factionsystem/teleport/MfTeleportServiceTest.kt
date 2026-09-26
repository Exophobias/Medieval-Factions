package com.dansplugins.factionsystem.teleport

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.lang.Language
import org.bukkit.ChatColor.RED
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MfTeleportServiceTest {
    private val now = Instant.parse("2026-09-26T20:00:00Z")
    private val destination = mock(Location::class.java)

    @Test
    fun successfulTeleportStartsPerPlayerCooldownAndBlocksReuse() {
        val plugin = plugin(delaySeconds = 0)
        val first = player()
        val second = player()
        `when`(first.bukkit.teleport(destination)).thenReturn(true)
        `when`(second.bukkit.teleport(destination)).thenReturn(true)
        val service = MfTeleportService(plugin, at(now))

        service.teleport(first.bukkit, destination, "Arrived")

        val (key, timestamp) = first.stored.entries.single()
        assertEquals("faction_home_last_teleport_at", key.key)
        assertEquals(now.toEpochMilli(), timestamp)
        service.teleport(first.bukkit, destination, "Arrived")
        verify(first.bukkit, times(1)).teleport(destination)
        verify(first.bukkit).sendMessage("${RED}Wait 30:00")

        service.teleport(second.bukkit, destination, "Arrived")
        verify(second.bukkit).teleport(destination)
        assertEquals(now.toEpochMilli(), second.stored.values.single())
    }

    @Test
    fun failedTeleportDoesNotStartCooldownAndCanBeRetried() {
        val plugin = plugin(delaySeconds = 0)
        val traveler = player()
        `when`(traveler.bukkit.teleport(destination)).thenReturn(false, true)
        val service = MfTeleportService(plugin, at(now))

        service.teleport(traveler.bukkit, destination, "Arrived")
        assertTrue(traveler.stored.isEmpty())

        service.teleport(traveler.bukkit, destination, "Arrived")
        verify(traveler.bukkit, times(2)).teleport(destination)
        verify(traveler.bukkit, times(1)).sendMessage("Arrived")
        assertEquals(now.toEpochMilli(), traveler.stored.values.single())
    }

    @Test
    fun canceledWarmupDoesNotStartCooldown() {
        val plugin = plugin(delaySeconds = 5)
        val traveler = player()
        `when`(traveler.bukkit.teleport(destination)).thenReturn(true)
        val scheduled = schedule(plugin, traveler.bukkit)
        val service = MfTeleportService(plugin, at(now))

        service.teleport(traveler.bukkit, destination, "Arrived")
        assertEquals(1, scheduled.size)
        assertTrue(traveler.stored.isEmpty())

        service.cancelTeleportation(traveler.bukkit)
        verify(scheduled[0].task).cancel()
        assertTrue(traveler.stored.isEmpty())
        verify(traveler.bukkit, never()).teleport(destination)

        service.teleport(traveler.bukkit, destination, "Arrived")
        assertEquals(2, scheduled.size)
        scheduled[1].runnable.run()
        verify(traveler.bukkit).teleport(destination)
        assertEquals(now.toEpochMilli(), traveler.stored.values.single())
    }

    @Test
    fun delayedArrivalStartsCooldownOnlyAfterTaskRunsAndItSurvivesReconnect() {
        val plugin = plugin(delaySeconds = 5)
        val traveler = player()
        `when`(traveler.bukkit.teleport(destination)).thenReturn(true)
        val scheduled = schedule(plugin, traveler.bukkit)
        val service = MfTeleportService(plugin, at(now))

        service.teleport(traveler.bukkit, destination, "Arrived")
        assertTrue(traveler.stored.isEmpty())
        scheduled.single().runnable.run()
        assertEquals(now.toEpochMilli(), traveler.stored.values.single())

        val reconnected = player(traveler.bukkit.uniqueId, traveler.stored)
        val beforeExpiry = MfTeleportService(plugin, at(now.plus(Duration.ofMinutes(29))))
        beforeExpiry.teleport(reconnected.bukkit, destination, "Arrived")
        verify(reconnected.bukkit, never()).teleport(destination)
        assertEquals(1, scheduled.size, "a blocked use must not queue another warmup")

        val afterExpiry = MfTeleportService(plugin, at(now.plus(Duration.ofMinutes(30))))
        afterExpiry.teleport(reconnected.bukkit, destination, "Arrived")
        assertEquals(2, scheduled.size)
    }

    private fun at(instant: Instant) = Clock.fixed(instant, ZoneOffset.UTC)

    private fun plugin(delaySeconds: Int): MedievalFactions {
        val plugin = mock(MedievalFactions::class.java)
        val config = mock(FileConfiguration::class.java)
        val language = mock(Language::class.java)
        `when`(plugin.name).thenReturn("MedievalFactions")
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(language)
        `when`(config.getInt("factions.factionHomeTeleportDelay")).thenReturn(delaySeconds)
        `when`(config.getInt("factions.factionHomeCooldownMinutes", 30)).thenReturn(30)
        `when`(language["CommandFactionHomeCooldown", "30:00"]).thenReturn("Wait 30:00")
        return plugin
    }

    private data class Traveler(val bukkit: Player, val stored: MutableMap<NamespacedKey, Long>)

    private fun player(
        id: UUID = UUID.randomUUID(),
        stored: MutableMap<NamespacedKey, Long> = mutableMapOf()
    ): Traveler {
        val bukkit = mock(Player::class.java)
        val data = mock(PersistentDataContainer::class.java)
        `when`(bukkit.uniqueId).thenReturn(id)
        `when`(bukkit.persistentDataContainer).thenReturn(data)
        `when`(data.get(any(NamespacedKey::class.java), eq(PersistentDataType.LONG))).thenAnswer {
            stored[it.getArgument<NamespacedKey>(0)]
        }
        doAnswer {
            stored[it.getArgument<NamespacedKey>(0)] = it.getArgument<Long>(2)
            null
        }.`when`(data).set(any(NamespacedKey::class.java), eq(PersistentDataType.LONG), anyLong())
        return Traveler(bukkit, stored)
    }

    private data class Scheduled(val runnable: Runnable, val task: BukkitTask)

    private fun schedule(plugin: MedievalFactions, online: Player): MutableList<Scheduled> {
        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        val scheduled = mutableListOf<Scheduled>()
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.getPlayer(online.uniqueId)).thenReturn(online)
        `when`(scheduler.runTaskLater(eq(plugin), any(Runnable::class.java), eq(100L))).thenAnswer {
            val task = mock(BukkitTask::class.java)
            scheduled += Scheduled(it.getArgument(1), task)
            task
        }
        return scheduled
    }
}
