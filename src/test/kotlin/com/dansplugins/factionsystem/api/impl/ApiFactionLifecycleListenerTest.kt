package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionCreateEvent
import com.dansplugins.factionsystem.api.event.FactionDisbandedEvent
import com.dansplugins.factionsystem.api.event.FactionJoinEvent
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import com.dansplugins.factionsystem.event.faction.FactionJoinEvent as MfFactionJoinEvent

class ApiFactionLifecycleListenerTest {

    @Test
    fun createEventKeepsAnExactInitialRosterSnapshotAndOldConstructor() {
        val founder = UUID.randomUUID()
        val ids = mutableListOf(founder.toString(), "malformed-initial-id")
        val event = FactionCreateEvent(FactionId("new"), "New", founder, ids, true)
        ids.clear()
        assertEquals(listOf(founder.toString(), "malformed-initial-id"), event.memberIdValues)
        assertEquals(listOf(founder.toString()),
            FactionCreateEvent(FactionId("old"), "Old", founder, true).memberIdValues)
    }

    @Test
    fun cancelledStableJoinGateVetoesAnExistingFactionJoin() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        val manager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(manager)
        var observed: FactionJoinEvent? = null
        doAnswer { invocation ->
            val event = invocation.getArgument(0, Event::class.java)
            if (event is FactionJoinEvent) {
                observed = event
                event.isCancelled = true
            }
            null
        }.`when`(manager).callEvent(any(Event::class.java))
        val internal = MfFactionJoinEvent(MfFactionId("realm"), MfPlayerId("malformed-player-id"), true)

        ApiFactionLifecycleListener(plugin).onFactionJoin(internal)

        assertTrue(internal.isCancelled)
        assertEquals("realm", observed?.factionId?.value)
        assertEquals("malformed-player-id", observed?.playerIdValue)
        assertTrue(observed?.isAsynchronous == true)
    }

    @Test
    fun committedFactionDeletionPublishesStableDisbandOnMainTurn() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        val manager = mock(PluginManager::class.java)
        val task = mock(BukkitTask::class.java)
        var scheduled: Runnable? = null
        val fired = mutableListOf<Event>()
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.pluginManager).thenReturn(manager)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                scheduled = invocation.getArgument(1, Runnable::class.java)
                task
            }
        doAnswer { invocation ->
            fired += invocation.getArgument(0, Event::class.java)
            null
        }.`when`(manager).callEvent(any(Event::class.java))
        val listener = ApiFactionLifecycleListener(plugin)

        listener.onFactionDeleted(FactionDeletedEvent(MfFactionId("gone"), true))

        assertEquals(emptyList<Event>(), fired)
        scheduled!!.run()
        val disbanded = fired.filterIsInstance<FactionDisbandedEvent>().single()
        assertEquals("gone", disbanded.faction.value)
    }
}
