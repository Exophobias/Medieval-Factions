package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.event.FactionDisbandedEvent
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ApiFactionLifecycleListenerTest {

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
