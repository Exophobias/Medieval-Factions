package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.faction.MfFactionId
import dev.forkhandles.result4k.Failure
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

/**
 * Exercises the gate spatial indexes that back getGatesByTrigger (exact trigger position) and
 * getGatesAt (area containment via a chunk index). Covers a single-chunk gate, a gate whose area
 * spans multiple chunks, negative coordinates (chunk math via `shr 4`), and empty lookups.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfGateServiceTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var world: UUID
    private val factionId = MfFactionId("faction-a")

    private fun pos(x: Int, y: Int, z: Int) = MfBlockPosition(world, x, y, z)

    private fun gate(area: MfCuboidArea, trigger: MfBlockPosition) = MfGate(
        plugin = plugin,
        factionId = factionId,
        area = area,
        trigger = trigger,
        material = Material.IRON_BARS
    )

    @BeforeEach
    fun setUp() {
        world = UUID.randomUUID()
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getStringList("gates.restrictedBlocks")).thenReturn(emptyList())
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        // No-op scheduler so the init-time restricted-block review task never runs.
        `when`(server.scheduler).thenReturn(mock(BukkitScheduler::class.java))
    }

    private fun serviceWith(vararg gates: MfGate): MfGateService {
        val gateRepo = mock(MfGateRepository::class.java)
        `when`(gateRepo.getGates()).thenReturn(gates.toList())
        return MfGateService(plugin, gateRepo, mock(MfGateCreationContextRepository::class.java))
    }

    @Test
    fun getGatesByTriggerMatchesExactTriggerBlock() {
        val gate = gate(MfCuboidArea(pos(0, 60, 0), pos(3, 70, 3)), trigger = pos(5, 64, 5))
        val service = serviceWith(gate)

        assertEquals(listOf(gate), service.getGatesByTrigger(pos(5, 64, 5)))
        // A neighbouring block is not the trigger.
        assertTrue(service.getGatesByTrigger(pos(6, 64, 5)).isEmpty())
    }

    @Test
    fun getGatesAtFindsGateForBlockInsideItsArea() {
        val gate = gate(MfCuboidArea(pos(0, 60, 0), pos(3, 70, 3)), trigger = pos(5, 64, 5))
        val service = serviceWith(gate)

        assertEquals(listOf(gate), service.getGatesAt(pos(2, 65, 2)))
        // Inside the gate's chunk but outside the cuboid -> filtered out by area.contains.
        assertTrue(service.getGatesAt(pos(8, 65, 8)).isEmpty())
        // A different chunk with no gates.
        assertTrue(service.getGatesAt(pos(100, 65, 100)).isEmpty())
    }

    @Test
    fun getGatesAtHandlesAreaSpanningMultipleChunks() {
        // Area spans chunks (1,1) through (2,2).
        val gate = gate(MfCuboidArea(pos(18, 60, 18), pos(34, 70, 34)), trigger = pos(20, 64, 20))
        val service = serviceWith(gate)

        // Block in chunk (2,2), inside the cuboid.
        assertEquals(listOf(gate), service.getGatesAt(pos(33, 65, 33)))
        // Block in chunk (1,1) which the area's bounding box touches, but outside the cuboid itself.
        assertTrue(service.getGatesAt(pos(17, 65, 17)).isEmpty())
    }

    @Test
    fun getGatesAtHandlesNegativeCoordinates() {
        // x=-20 is chunk -2 (-20 shr 4). Verifies chunk keying matches for negatives.
        val gate = gate(MfCuboidArea(pos(-20, 60, -20), pos(-18, 70, -18)), trigger = pos(-19, 64, -19))
        val service = serviceWith(gate)

        assertEquals(listOf(gate), service.getGatesAt(pos(-19, 65, -19)))
        assertTrue(service.getGatesAt(pos(-1, 65, -1)).isEmpty())
    }

    @Test
    fun factionDeleteFenceRejectsNewGateWrites() {
        val gateRepo = mock(MfGateRepository::class.java)
        `when`(gateRepo.getGates()).thenReturn(emptyList())
        val service = MfGateService(plugin, gateRepo, mock(MfGateCreationContextRepository::class.java))
        val gate = gate(MfCuboidArea(pos(0, 60, 0), pos(3, 70, 3)), trigger = pos(5, 64, 5))
        service.blockFactionDeletion(factionId)

        try {
            assertTrue(service.save(gate) is Failure)
            verify(gateRepo, never()).upsert(gate)
        } finally {
            service.unblockFactionDeletion(factionId)
        }
    }
}
