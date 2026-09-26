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
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.sql.DriverManager
import java.util.UUID

class MfFactionDisplayNamePersistenceTest {
    @Test
    fun `SQL display name round trips without changing canonical lookup`() {
        val url = "jdbc:h2:mem:faction-display-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(url, "sa", "")
            .locations("classpath:com/dansplugins/factionsystem/db/migration")
            .load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            val plugin = mock(MedievalFactions::class.java)
            val manager = mock(PluginManager::class.java)
            mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<PluginManager> { Bukkit.getPluginManager() }.thenReturn(manager)
                `when`(manager.getPlugin("MedievalFactions")).thenReturn(plugin)
                val role = MfFactionRole(plugin, MfFactionRoleId.generate(), "Owner")
                val repository = JooqMfFactionRepository(plugin, DSL.using(connection, SQLDialect.H2), Gson())
                val original = MfFaction(
                    plugin = plugin,
                    name = "PeopleOfOlzhar",
                    flags = MfFlagValues(plugin),
                    roles = MfFactionRoles(role.id, listOf(role)),
                    defaultPermissionsByName = emptyMap()
                )
                val saved = repository.upsert(original)
                assertNull(saved.displayNameOverride)
                assertEquals("PeopleOfOlzhar", saved.displayName)

                val renamed = repository.upsert(saved.copy(displayNameOverride = "People of Olzhar"))
                assertEquals("People of Olzhar", renamed.displayName)
                assertEquals(renamed.id, repository.getFaction("PeopleOfOlzhar")?.id)
                assertNull(repository.getFaction("People of Olzhar"))

                val reset = repository.upsert(renamed.copy(displayNameOverride = null))
                assertNotNull(repository.getFaction(reset.id))
                assertEquals("PeopleOfOlzhar", repository.getFaction(reset.id)?.displayName)
            }
        }
    }
}
