package com.dansplugins.factionsystem.player

import com.dansplugins.factionsystem.MedievalFactions
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class JooqMfPlayerPowerRepositoryTest : MfPlayerPowerRepositoryContract() {
    private lateinit var connection: Connection
    private lateinit var dsl: DSLContext
    private lateinit var plugin: MedievalFactions

    @BeforeEach
    fun setup() {
        val url = "jdbc:h2:mem:player-power-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()
        connection = DriverManager.getConnection(url, "sa", "")
        dsl = DSL.using(connection, SQLDialect.H2)
        plugin = mock(MedievalFactions::class.java)
        configurePowerRepository(plugin, JooqMfPlayerRepository(plugin, dsl))
    }

    @AfterEach
    fun cleanup() {
        connection.close()
    }

    override fun reopenPowerRepository(): MfPlayerRepository = JooqMfPlayerRepository(plugin, dsl)
}
