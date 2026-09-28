package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.faction.MfFactionId
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JooqMfEmbassyRepositoryTest {
    @Test
    fun `conquest recovery survives claim transfer and historical host deletion until true unclaim`() {
        val url = "jdbc:h2:mem:embassy-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val host = MfFactionId("host")
            val guest = MfFactionId("guest")
            val conqueror = MfFactionId("conqueror")
            listOf(host, guest, conqueror).forEach { faction ->
                dsl.execute(
                    """insert into mf_faction
                    (id, version, name, description, flags, bonus_power, autoclaim, roles,
                     default_role_id, default_permissions)
                    values (?, 1, ?, '', '{}', 0, false, '[]', 'default', '{}')""",
                    faction.value,
                    faction.value
                )
            }
            val world = UUID.randomUUID()
            val claims = JooqMfClaimedChunkRepository(dsl)
            val embassies = JooqMfEmbassyRepository(dsl)
            claims.upsert(MfClaimedChunk(world, 2, -3, host))
            val active = MfEmbassy(
                world, 2, -3, host, guest, MfEmbassyStatus.ACTIVE,
                createdAt = 1_000, changedAt = 1_000, deadlineAt = null
            )
            embassies.upsert(active)

            claims.upsert(MfClaimedChunk(world, 2, -3, conqueror))
            assertEquals(listOf(active), embassies.getAll())
            val pending = active.copy(
                status = MfEmbassyStatus.CONQUEST_DECISION,
                changedAt = 2_000,
                deadlineAt = 2_000 + MfEmbassyService.CONQUEST_DECISION_MILLIS,
                conquerorId = conqueror,
                pausedAt = 3_000
            )
            embassies.upsert(pending)
            assertEquals(listOf(pending), embassies.getAll())

            dsl.execute("delete from mf_faction where id = ?", host.value)
            assertEquals(listOf(pending), embassies.getAll())
            claims.delete(world, 2, -3)
            assertTrue(embassies.getAll().isEmpty())
        }
    }

    @Test
    fun `area repository rolls back every cell when one write fails`() {
        val url = "jdbc:h2:mem:embassy-area-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val host = MfFactionId("host")
            val guest = MfFactionId("guest")
            listOf(host, guest).forEach { faction ->
                dsl.execute(
                    """insert into mf_faction
                    (id, version, name, description, flags, bonus_power, autoclaim, roles,
                     default_role_id, default_permissions)
                    values (?, 1, ?, '', '{}', 0, false, '[]', 'default', '{}')""",
                    faction.value,
                    faction.value
                )
            }
            val world = UUID.randomUUID()
            val claims = JooqMfClaimedChunkRepository(dsl)
            val embassies = JooqMfEmbassyRepository(dsl)
            val first = MfEmbassy(
                world, 0, 0, host, guest, MfEmbassyStatus.ACTIVE,
                createdAt = 1000, changedAt = 1000, deadlineAt = null, offerSize = 2
            )
            val second = first.copy(chunkX = 1)
            claims.upsert(MfClaimedChunk(world, 0, 0, host))
            claims.upsert(MfClaimedChunk(world, 1, 0, host))
            embassies.applyChanges(listOf(first, second), emptyList())
            assertEquals(setOf(first, second), embassies.getAll().toSet())
            val clearing = first.copy(status = MfEmbassyStatus.CLEARING, changedAt = 2000, deadlineAt = 3000)
            val unclaimed = clearing.copy(chunkX = 2)
            assertFailsWith<Exception> {
                embassies.applyChanges(listOf(clearing, unclaimed), listOf(second))
            }
            assertEquals(setOf(first, second), embassies.getAll().toSet())
        }
    }
}
