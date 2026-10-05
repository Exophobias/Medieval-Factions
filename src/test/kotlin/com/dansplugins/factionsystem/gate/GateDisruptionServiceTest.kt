package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.service.Services
import com.dansplugins.factionsystem.siege.DefaultNativeSiegeAuthority
import com.dansplugins.factionsystem.siege.NativeFenceRegistry
import dev.forkhandles.result4k.Failure
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.h2.jdbcx.JdbcDataSource
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID
import java.util.logging.Logger

class GateDisruptionServiceTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var server: Server
    private lateinit var repository: JooqMfGateRepository
    private lateinit var original: MfGate
    private lateinit var service: MfGateService
    private val operation = UUID.randomUUID()

    @BeforeEach fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.isEnabled).thenReturn(true)
        `when`(plugin.logger).thenReturn(Logger.getAnonymousLogger())
        `when`(server.scheduler).thenReturn(mock(BukkitScheduler::class.java))
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getStringList("gates.restrictedBlocks")).thenReturn(emptyList())
        val source = JdbcDataSource().apply { setURL("jdbc:h2:mem:prepared-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1") }
        val dsl = DSL.using(source, SQLDialect.H2)
        dsl.execute("""create table mf_gate(id varchar(36) primary key,version integer,faction_id varchar(36),world_id varchar(36),
            min_x integer,min_y integer,min_z integer,max_x integer,max_y integer,max_z integer,
            trigger_x integer,trigger_y integer,trigger_z integer,material varchar(64),status varchar(32))""")
        dsl.execute(requireNotNull(javaClass.getResource("/com/dansplugins/factionsystem/db/migration/V908__Durable_gate_disruption.sql")).readText())
        repository = spy(JooqMfGateRepository(plugin, dsl))
        val p = MfBlockPosition(UUID.randomUUID(), 0, 64, 0)
        original = repository.upsert(MfGate(plugin, factionId = MfFactionId("realm"), area = MfCuboidArea(p,p), trigger = p, material = Material.IRON_BARS))
        service = freshService()
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.gateService).thenReturn(service)
    }
    private fun freshService() = MfGateService(plugin, repository, mock(MfGateCreationContextRepository::class.java))
    private fun prepare() = service.prepareDisruption(operation, original.id, original.version)
    private fun activate(): MfGate {
        `when`(server.isPrimaryThread).thenReturn(true)
        return requireNotNull(NativeFenceRegistry.get(plugin).tryAcquire()).use {
            service.activateDisruption(operation, original.id, original.version)
        }
    }

    @Test fun durablePreparationHasNoActuatorEffectAndMainActivationNeverCallsRepository() {
        assertEquals(original, prepare())
        assertEquals(original, repository.getGate(original.id))
        assertEquals("PREPARED", repository.getGateDisruption(operation)?.phase)
        assertFalse(service.actuatorCurrent(original))
        assertTrue(service.getGatesByStatus(MfGateStatus.CLOSED).isEmpty())
        assertTrue(service.save(original) is Failure)
        assertTrue(service.delete(original.id) is Failure)
        assertThrows(IllegalStateException::class.java) { service.blockFactionDeletion(original.factionId) }
        `when`(server.isPrimaryThread).thenReturn(true)
        assertThrows(IllegalStateException::class.java) { service.activateDisruption(operation,original.id,original.version) }
        clearInvocations(repository)
        val activated = activate()
        verifyNoInteractions(repository)
        assertEquals(MfGateStatus.DISRUPTED, activated.status)
        assertEquals(original.version+1, activated.version)
        assertEquals("ACTIVATED", service.disruptionMetadata(original.id)?.second)
        assertThrows(IllegalArgumentException::class.java) { service.cancelDisruption(operation,original.id,original.version) }
        `when`(server.isPrimaryThread).thenReturn(false)
        assertEquals(activated, service.commitDisruption(operation,original.id,original.version))
        assertEquals(activated, repository.getGate(original.id))
        assertEquals("COMMITTED", repository.getGateDisruption(operation)?.phase)
        assertEquals(operation.toString() to "COMMITTED", service.disruptionMetadata(original.id))
        assertEquals(activated, service.commitDisruption(operation,original.id,original.version))
    }

    @Test fun restartQuarantinesUnactivatedPreparationUntilExplicitObservedBeforeReview() {
        prepare()
        service = freshService()
        assertEquals("QUARANTINED", service.disruptionMetadata(original.id)?.second)
        assertFalse(service.actuatorCurrent(original))
        assertThrows(IllegalArgumentException::class.java) { activate() }
        assertThrows(IllegalArgumentException::class.java) { service.cancelDisruption(operation,original.id,original.version) }
        requireNotNull(NativeFenceRegistry.get(plugin).tryAcquire()).use { service.beginDisruptionReview(operation,original.id,original.version) }
        assertEquals(original, service.finishDisruptionReview(operation,original.id,original.version,false))
        assertEquals("CANCELLED", repository.getGateDisruption(operation)?.phase)
        assertTrue(service.actuatorCurrent(original))
        assertNull(service.disruptionMetadata(original.id))
        assertThrows(IllegalArgumentException::class.java) { prepare() }
    }

    @Test fun crashAfterActivationCannotReplayAndObservedAfterReviewCommitsOnlyNativeActuator() {
        prepare(); val activated = activate()
        assertEquals(original, repository.getGate(original.id))
        service = freshService()
        assertEquals(original, service.getGatesAt(original.trigger).single())
        assertEquals("QUARANTINED", service.disruptionMetadata(original.id)?.second)
        requireNotNull(NativeFenceRegistry.get(plugin).tryAcquire()).use { service.beginDisruptionReview(operation,original.id,original.version) }
        assertEquals(activated, service.finishDisruptionReview(operation,original.id,original.version,true))
        assertEquals(activated, repository.getGate(original.id))
        assertFalse(service.actuatorCurrent(activated))
    }

    @Test fun normalCurrentRuntimeCancellationAcknowledgesBeforeReleasingNativeReservation() {
        prepare()
        assertEquals(original, service.cancelDisruption(operation,original.id,original.version))
        assertEquals("CANCELLED", repository.getGateDisruption(operation)?.phase)
        assertNull(service.disruptionMetadata(original.id))
        assertEquals(original, repository.getGate(original.id))
        assertTrue(service.actuatorCurrent(original))
    }

    @Test fun durablePreparationBindsOriginalFootprintOwnerMaterialAndRevision() {
        prepare()
        assertEquals(original, repository.getGateDisruption(operation)?.original)
        val changed = listOf(
            original.copy(version = original.version + 1),
            original.copy(factionId = MfFactionId("different-owner")),
            original.copy(material = Material.OAK_FENCE),
            original.copy(area = MfCuboidArea(original.area.minPosition, original.area.maxPosition.copy(x = 1)))
        )
        changed.forEach { proposed ->
            assertThrows(IllegalArgumentException::class.java) { repository.finishDisruption(operation,proposed,true) }
        }
        assertEquals(original, repository.getGate(original.id))
        assertEquals("PREPARED", repository.getGateDisruption(operation)?.phase)
    }

    @Test fun lostCompletionAcknowledgementQuarantinesAndPoisonsAdmissionWithoutRestoringActuator() {
        prepare(); val activated = activate()
        doAnswer { invocation -> invocation.callRealMethod(); throw IllegalStateException("Lost commit acknowledgement") }
            .`when`(repository).finishDisruption(operation,original,true)
        assertThrows(IllegalStateException::class.java) { service.commitDisruption(operation,original.id,original.version) }
        assertEquals(activated, service.getGatesAt(original.trigger).single())
        assertEquals("QUARANTINED", service.disruptionMetadata(original.id)?.second)
        assertFalse(NativeFenceRegistry.get(plugin).isReadable())
        assertEquals("COMMITTED", repository.getGateDisruption(operation)?.phase)
        val restarted = freshService()
        assertEquals(activated, restarted.getGatesAt(original.trigger).single())
        assertEquals(operation.toString() to "COMMITTED", restarted.disruptionMetadata(original.id))
    }

    @Test fun publicNativeRecoveryRejectsMissingRealOnlineStaffPermissionAndLegacyUnfencedWrite() {
        `when`(server.isPrimaryThread).thenReturn(true)
        val authority = DefaultNativeSiegeAuthority(plugin)
        val actor = UUID.randomUUID()
        assertFalse(authority.reviewGateDisruption(actor,operation,original.id.value,original.version,false).join().isSuccess)
        val player = mock(Player::class.java)
        `when`(server.getPlayer(actor)).thenReturn(player)
        `when`(player.isOnline).thenReturn(true)
        assertFalse(authority.reviewGateDisruption(actor,operation,original.id.value,original.version,false).join().isSuccess)
        assertFalse(authority.disruptGate(operation,original.id.value,original.version).isSuccess)
    }
}
