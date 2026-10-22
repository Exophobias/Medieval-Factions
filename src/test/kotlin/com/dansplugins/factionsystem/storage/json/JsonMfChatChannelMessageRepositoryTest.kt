package com.dansplugins.factionsystem.storage.json

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.chat.MfChatChannelMessage
import com.dansplugins.factionsystem.chat.MfFactionChatChannel
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.player.MfPlayerId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

class JsonMfChatChannelMessageRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var plugin: MedievalFactions
    private lateinit var storageManager: JsonStorageManager
    private lateinit var repository: JsonMfChatChannelMessageRepository

    @BeforeEach
    fun setup() {
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger("TestLogger"))
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        storageManager = JsonStorageManager(plugin, tempDir.toString())
        repository = JsonMfChatChannelMessageRepository(plugin, storageManager)
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test insert and retrieve chat message`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        val message = MfChatChannelMessage(
            Instant.now(),
            playerId,
            factionId,
            MfFactionChatChannel.FACTION,
            "Test message"
        )

        repository.insert(message)
        val messages = repository.getChatChannelMessages(factionId)

        assertEquals(1, messages.size)
        assertEquals("Test message", messages[0].message)
        assertEquals(playerId, messages[0].playerId)
    }

    @Test
    fun `test getChatChannelMessages returns empty list for unknown faction`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val messages = repository.getChatChannelMessages(factionId)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `test insert multiple messages for same faction`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())

        for (i in 1..5) {
            val message = MfChatChannelMessage(
                Instant.now(),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                "Message $i"
            )
            repository.insert(message)
        }

        val messages = repository.getChatChannelMessages(factionId)
        assertEquals(5, messages.size)
    }

    @Test
    fun `test message limit enforcement - keeps only 1000 messages per faction`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())

        val start = Instant.parse("2026-01-01T00:00:00Z")
        // Seed real persisted history at the cap, then exercise the repository's boundary write.
        // Rewriting the entire JSON file 1050 times adds Windows sharing-denial exposure without
        // adding cap coverage; JsonStorageManagerTest separately exercises file-write concurrency.
        val history = (1..1000).map { i ->
            MfChatChannelMessage(
                start.plusMillis(i.toLong()),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                "Message $i"
            )
        }.toMutableList()
        storageManager.writeJsonFile(
            "chat_messages.json",
            JsonMfChatChannelMessageRepository.ChatMessageData(history)
        )
        repository.insert(
            MfChatChannelMessage(
                start.plusMillis(1001),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                "Message 1001"
            )
        )

        val messages = repository.getChatChannelMessages(factionId)
        assertEquals(1000, messages.size)
        assertFalse(messages.any { it.message == "Message 1" })
        assertEquals((2..1001).map { "Message $it" }, messages.map { it.message })
    }

    @Test
    fun `test multiple factions have independent message limits`() {
        val faction1 = MfFactionId(UUID.randomUUID().toString())
        val faction2 = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        val start = Instant.parse("2026-01-01T00:00:00Z")
        // A real file containing 1000 messages for each faction proves that the cap is per faction,
        // including when the total already exceeds 1000, with only the relevant boundary writes.
        val history = listOf(faction1, faction2).flatMapIndexed { factionIndex, faction ->
            (1..1000).map { i ->
                MfChatChannelMessage(
                    start.plusMillis(i.toLong()),
                    playerId,
                    faction,
                    MfFactionChatChannel.FACTION,
                    "Faction${factionIndex + 1} Message $i"
                )
            }
        }.toMutableList()
        storageManager.writeJsonFile(
            "chat_messages.json",
            JsonMfChatChannelMessageRepository.ChatMessageData(history)
        )
        repository.insert(
            MfChatChannelMessage(
                start.plusMillis(1001),
                playerId,
                faction1,
                MfFactionChatChannel.FACTION,
                "Faction1 Message 1001"
            )
        )
        assertEquals(
            (2..1001).map { "Faction1 Message $it" },
            repository.getChatChannelMessages(faction1).map { it.message }
        )
        assertEquals(
            (1..1000).map { "Faction2 Message $it" },
            repository.getChatChannelMessages(faction2).map { it.message }
        )

        repository.insert(
            MfChatChannelMessage(
                start.plusMillis(1001),
                playerId,
                faction2,
                MfFactionChatChannel.FACTION,
                "Faction2 Message 1001"
            )
        )
        assertEquals(
            (2..1001).map { "Faction1 Message $it" },
            repository.getChatChannelMessages(faction1).map { it.message }
        )
        assertEquals(
            (2..1001).map { "Faction2 Message $it" },
            repository.getChatChannelMessages(faction2).map { it.message }
        )
    }

    @Test
    fun `test getChatChannelMessageCount returns correct count`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())

        for (i in 1..10) {
            repository.insert(
                MfChatChannelMessage(
                    Instant.now(),
                    playerId,
                    factionId,
                    MfFactionChatChannel.FACTION,
                    "Message $i"
                )
            )
        }

        val count = repository.getChatChannelMessageCount(factionId)
        assertEquals(10, count)
    }

    @Test
    fun `test getChatChannelMessages with limit and offset`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())

        // Insert 20 messages
        for (i in 1..20) {
            repository.insert(
                MfChatChannelMessage(
                    Instant.now().plusMillis(i.toLong()),
                    playerId,
                    factionId,
                    MfFactionChatChannel.FACTION,
                    "Message $i"
                )
            )
        }

        // Get messages with limit and offset
        val messages = repository.getChatChannelMessages(factionId, limit = 5, offset = 5)

        assertEquals(5, messages.size)
    }

    @Test
    fun `test messages with special characters`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        val specialMessage = "Hello™ 世界 😀 \"quoted\" 'text' <html>"

        repository.insert(
            MfChatChannelMessage(
                Instant.now(),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                specialMessage
            )
        )

        val messages = repository.getChatChannelMessages(factionId)
        assertEquals(1, messages.size)
        assertEquals(specialMessage, messages[0].message)
    }

    @Test
    fun `test empty message string`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())

        repository.insert(
            MfChatChannelMessage(
                Instant.now(),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                ""
            )
        )

        val messages = repository.getChatChannelMessages(factionId)
        assertEquals(1, messages.size)
        assertEquals("", messages[0].message)
    }

    @Test
    fun `test very long message`() {
        val factionId = MfFactionId(UUID.randomUUID().toString())
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        val longMessage = "x".repeat(10000)

        repository.insert(
            MfChatChannelMessage(
                Instant.now(),
                playerId,
                factionId,
                MfFactionChatChannel.FACTION,
                longMessage
            )
        )

        val messages = repository.getChatChannelMessages(factionId)
        assertEquals(1, messages.size)
        assertEquals(10000, messages[0].message.length)
    }
}
