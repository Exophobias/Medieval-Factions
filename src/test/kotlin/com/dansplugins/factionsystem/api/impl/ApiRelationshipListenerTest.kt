package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.event.FactionWarEndedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreatedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeletedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.ArrayDeque

class ApiRelationshipListenerTest {

    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var listener: ApiRelationshipListener
    private val scheduled = ArrayDeque<Runnable>()
    private val fired = mutableListOf<Event>()

    @BeforeEach
    fun setUp() {
        scheduled.clear()
        fired.clear()

        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val factionService = mock(MfFactionService::class.java)
        relationshipService = mock(MfFactionRelationshipService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(factionService.factions).thenReturn(emptyList())

        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        val task = mock(BukkitTask::class.java)
        val pluginManager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                scheduled.addLast(invocation.getArgument(1, Runnable::class.java))
                task
            }
        doAnswer { invocation ->
            fired.add(invocation.getArgument(0, Event::class.java))
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))

        listener = ApiRelationshipListener(plugin)
    }

    @Test
    fun committedMirrorsEmitOneStartWithTheCausalInitiator() {
        val first = war("new-realm", "neighbour")
        val mirror = war("neighbour", "new-realm")
        val invoker = MfFactionId("invoking-ally")

        listener.onRelationshipCreated(RelationshipCreatedEvent(first, invoker, false))
        listener.onRelationshipCreated(RelationshipCreatedEvent(mirror, invoker, false))
        runScheduled()

        val starts = fired.filterIsInstance<FactionWarStartedEvent>()
        assertEquals(1, starts.size)
        assertEquals("invoking-ally", starts.single().initiatingFaction.value)
        assertEquals("new-realm", starts.single().faction.value)
        assertEquals("neighbour", starts.single().otherFaction.value)
    }

    @Test
    fun createThenDeleteBeforeMainTurnStillEmitsStartThenEnd() {
        val relationship = war("new-realm", "neighbour")
        `when`(
            relationshipService.getRelationships(
                relationship.factionId,
                relationship.targetId
            )
        ).thenReturn(emptyList())
        `when`(
            relationshipService.getRelationships(
                relationship.targetId,
                relationship.factionId
            )
        ).thenReturn(emptyList())

        listener.onRelationshipCreated(
            RelationshipCreatedEvent(
                relationship,
                relationship.factionId,
                false
            )
        )
        listener.onRelationshipDeleted(RelationshipDeletedEvent(relationship, false))
        runScheduled()

        assertEquals(
            listOf(FactionWarStartedEvent::class, FactionWarEndedEvent::class),
            fired.map { it::class },
            "post-commit transitions must survive both changes happening before the main relay"
        )
    }

    private fun war(holder: String, target: String): MfFactionRelationship =
        MfFactionRelationship(
            factionId = MfFactionId(holder),
            targetId = MfFactionId(target),
            type = AT_WAR
        )

    private fun runScheduled() {
        while (scheduled.isNotEmpty()) {
            scheduled.removeFirst().run()
        }
    }
}
