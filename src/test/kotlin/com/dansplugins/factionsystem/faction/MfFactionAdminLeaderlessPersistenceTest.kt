package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.plugin.PluginManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.sql.DriverManager
import java.util.UUID

class MfFactionAdminLeaderlessPersistenceTest {
    private fun databaseUrl() =
        "jdbc:h2:mem:admin-leaderless-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"

    private fun migrate(url: String, target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
        if (target != null) configuration.target(MigrationVersion.fromVersion(target))
        configuration.load().migrate()
    }

    @Test
    fun `migration keeps existing factions regular`() {
        val url = databaseUrl()
        migrate(url, "903")
        DriverManager.getConnection(url, "sa", "").use { connection ->
            DSL.using(connection, SQLDialect.H2).execute(
                """insert into mf_faction
                    (id, version, name, description, flags, bonus_power, autoclaim, roles,
                     default_role_id, default_permissions)
                    values (?, 1, 'Existing', '', '{}', 0, false, '[]', ?, '{}')""",
                "existing",
                "default"
            )
        }

        migrate(url)

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val marker = dsl.fetchValue(
                "select admin_leaderless from mf_faction where id = ?",
                "existing"
            ) as Boolean
            assertFalse(marker)
        }
    }

    @Test
    fun `Jooq insert update and read preserve staff leaderless marker`() {
        val url = databaseUrl()
        migrate(url)
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val dsl = DSL.using(connection, SQLDialect.H2)
            val plugin = mock(MedievalFactions::class.java)
            val manager = mock(PluginManager::class.java)
            mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<PluginManager> { Bukkit.getPluginManager() }.thenReturn(manager)
                `when`(manager.getPlugin("MedievalFactions")).thenReturn(plugin)

                val role = MfFactionRole(plugin, MfFactionRoleId.generate(), "Owner")
                val repository = JooqMfFactionRepository(plugin, dsl, Gson())
                val faction = MfFaction(
                    plugin = plugin,
                    name = "Staff Faction",
                    flags = MfFlagValues(plugin),
                    roles = MfFactionRoles(role.id, listOf(role)),
                    defaultPermissionsByName = emptyMap(),
                    adminLeaderless = true
                )

                val saved = repository.upsert(faction)
                assertTrue(saved.adminLeaderless)
                assertTrue(repository.getFaction(saved.id)!!.adminLeaderless)

                repository.upsert(saved.copy(adminLeaderless = false))
                assertFalse(repository.getFaction(saved.id)!!.adminLeaderless)
            }
        }
    }
}
