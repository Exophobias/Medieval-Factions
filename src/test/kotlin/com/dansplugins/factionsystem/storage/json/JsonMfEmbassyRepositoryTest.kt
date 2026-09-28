package com.dansplugins.factionsystem.storage.json

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.UnreadableJsonFileException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonMfEmbassyRepositoryTest {
    @TempDir lateinit var temporary: Path

    private fun repository(): JsonMfEmbassyRepository {
        val plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger("EmbassyJsonTest"))
        return JsonMfEmbassyRepository(JsonStorageManager(plugin, temporary.toString()))
    }

    private val row = MfEmbassy(
        UUID.randomUUID(), 4, -9, MfFactionId("host"), MfFactionId("guest"),
        MfEmbassyStatus.OFFERED, 1000, 1000, 2000
    )

    @Test
    fun `offer active and clearing survive reload`() {
        repository().upsert(row)
        assertEquals(row, repository().getAll().single())
        val active = row.copy(status = MfEmbassyStatus.ACTIVE, changedAt = 1100, deadlineAt = null)
        repository().upsert(active)
        assertEquals(active, repository().getAll().single())
        val clearing = active.copy(status = MfEmbassyStatus.CLEARING, changedAt = 1200, deadlineAt = 3000)
        repository().upsert(clearing)
        assertEquals(clearing, repository().getAll().single())
        repository().delete(row.worldId, row.chunkX, row.chunkZ)
        assertTrue(repository().getAll().isEmpty())
    }

    @Test
    fun `malformed stored status is not treated as an empty embassy list`() {
        repository().upsert(row)
        val file = temporary.resolve("embassies.json")
        Files.writeString(file, Files.readString(file).replace("OFFERED", "UNKNOWN"))
        assertFailsWith<UnreadableJsonFileException> { repository().getAll() }
        assertEquals("UNKNOWN", Files.readString(file).let { Regex("UNKNOWN").find(it)?.value })
    }

    @Test
    fun `area cells are rewritten in one batch and survive reload`() {
        val offered = listOf(row, row.copy(chunkX = row.chunkX + 1))
        repository().applyChanges(offered, emptyList())
        assertEquals(offered.toSet(), repository().getAll().toSet())
        val active = offered.map { it.copy(status = MfEmbassyStatus.ACTIVE, changedAt = 1100, deadlineAt = null) }
        repository().applyChanges(active, emptyList())
        assertEquals(active.toSet(), repository().getAll().toSet())
        repository().applyChanges(emptyList(), active)
        assertTrue(repository().getAll().isEmpty())
        Files.list(temporary).use { files -> assertEquals(listOf("embassies.json"), files.map { it.fileName.toString() }.toList()) }
    }

    @Test
    fun `missing root numeric fields and duplicate object keys refuse reads and writes unchanged`() {
        repository().upsert(row)
        val file = temporary.resolve("embassies.json")
        val valid = Files.readString(file)
        val invalid = listOf(
            "{}", "{\"embassies\":null}",
            valid.replace("\"chunkX\": 4,", ""),
            "{\"embassies\":[],\"embassies\":[]}",
            valid.replace("\"chunkX\": 4", "\"chunkX\":4,\"chunkX\":9"),
            valid.replace("\"createdAt\": 1000", "\"createdAt\":1.5")
        )
        invalid.forEach { original ->
            assertTrue(original != valid)
            Files.writeString(file, original)
            val repository = repository()
            assertFailsWith<UnreadableJsonFileException> { repository.getAll() }
            assertFailsWith<UnreadableJsonFileException> { repository.upsert(row) }
            assertFailsWith<UnreadableJsonFileException> { repository.delete(row.worldId, row.chunkX, row.chunkZ) }
            assertEquals(original, Files.readString(file))
        }
    }

}
