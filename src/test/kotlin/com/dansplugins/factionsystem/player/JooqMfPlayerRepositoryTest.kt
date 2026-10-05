package com.dansplugins.factionsystem.player

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.sql.DriverManager
import java.util.UUID

class JooqMfPlayerRepositoryTest {
    @Test
    fun staleSaveCannotOverwriteNewerPlayerState() {
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()};DATABASE_TO_UPPER=false").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            dsl.execute(
                "create table mf_player (id varchar(36) primary key, version integer not null, " +
                    "name varchar(255), power double not null, power_at_logout double not null, " +
                    "bypass_enabled boolean not null, bypass_warning_muted boolean not null default false, " +
                    "max_power_override double, chat_channel varchar(16))"
            )
            val repository = JooqMfPlayerRepository(mock(MedievalFactions::class.java), dsl)
            val first = repository.upsert(MfPlayer(MfPlayerId(UUID.randomUUID().toString()), power = 10.0))
            val updated = repository.upsert(first.copy(power = 15.0))
            assertThrows(OptimisticLockingFailureException::class.java) {
                repository.upsert(first.copy(powerAtLogout = first.power))
            }
            assertEquals(updated, repository.getPlayer(first.id))
            assertEquals(first.version + 1, updated.version)
        }
    }

    @Test
    fun existingPlayersKeepWarningsAndMutePreferenceRoundTripsAfterMigration() {
        val url = "jdbc:h2:mem:player-warning-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        val migration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
        migration.target(MigrationVersion.fromVersion("906")).load().migrate()
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        DriverManager.getConnection(url, "sa", "").use { connection ->
            DSL.using(connection, SQLDialect.H2).execute(
                "insert into mf_player (id, version, name, power, power_at_logout, bypass_enabled) values (?, 1, 'Legacy', 10, 10, true)",
                playerId.value
            )
        }

        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val repository = JooqMfPlayerRepository(mock(MedievalFactions::class.java), DSL.using(connection, SQLDialect.H2))
            val legacy = repository.getPlayer(playerId)!!
            assertFalse(legacy.isBypassWarningMuted)

            val muted = repository.upsert(legacy.copy(isBypassWarningMuted = true))
            assertTrue(muted.isBypassWarningMuted)
            assertTrue(repository.getPlayer(playerId)!!.isBypassWarningMuted)

            val unmuted = repository.upsert(muted.copy(isBypassWarningMuted = false))
            assertFalse(unmuted.isBypassWarningMuted)
            assertFalse(repository.getPlayer(playerId)!!.isBypassWarningMuted)
        }
    }

    @Test
    fun existingPlayerPowerIsPreservedAndMaximumOverrideDefaultsToNullAfterMigration() {
        val url = "jdbc:h2:mem:player-max-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .target(MigrationVersion.fromVersion("908")).load().migrate()
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        DriverManager.getConnection(url, "sa", "").use { connection ->
            DSL.using(connection, SQLDialect.H2).execute(
                "insert into mf_player (id, version, name, power, power_at_logout, bypass_enabled, bypass_warning_muted) " +
                    "values (?, 7, 'Legacy', 40, 35, true, true)",
                playerId.value
            )
        }

        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val repository = JooqMfPlayerRepository(mock(MedievalFactions::class.java), DSL.using(connection, SQLDialect.H2))
            val legacy = repository.getPlayer(playerId)!!
            assertNull(legacy.maxPowerOverride)
            assertEquals(7, legacy.version)
            assertEquals(40.0, legacy.power)
            assertEquals(35.0, legacy.powerAtLogout)
            assertTrue(legacy.isBypassEnabled)
            assertTrue(legacy.isBypassWarningMuted)

            val customized = repository.upsert(legacy.copy(maxPowerOverride = 50.0))
            assertEquals(customized, repository.getPlayer(playerId))
            assertEquals(50.0, customized.maxPowerOverride)
        }
    }
}
