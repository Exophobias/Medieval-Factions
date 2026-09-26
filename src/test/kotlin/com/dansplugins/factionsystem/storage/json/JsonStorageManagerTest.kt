package com.dansplugins.factionsystem.storage.json

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.failure.UnreadableJsonFileException
import com.google.gson.Gson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger

class JsonStorageManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var plugin: MedievalFactions
    private lateinit var storageManager: JsonStorageManager

    data class TestData(val name: String, val value: Int)

    private val empty = TestData("", 0)

    @BeforeEach
    fun setup() {
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger("TestLogger"))
        storageManager = JsonStorageManager(plugin, tempDir.toString())
    }

    @AfterEach
    fun cleanup() {
        // Clean up temp files
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `test write and read JSON file`() {
        val fileName = "test.json"
        val testData = TestData("test", 42)

        storageManager.writeJsonFile(fileName, testData)
        val result = storageManager.readJsonFile(fileName, TestData::class.java)

        assertNotNull(result)
        assertEquals("test", result?.name)
        assertEquals(42, result?.value)
    }

    @Test
    fun `test read non-existent file returns null`() {
        val result = storageManager.readJsonFile("nonexistent.json", TestData::class.java)
        assertNull(result)
    }

    @Test
    fun `test fileExists returns correct result`() {
        val fileName = "exists.json"
        assertFalse(storageManager.fileExists(fileName))

        storageManager.writeJsonFile(fileName, TestData("test", 1))
        assertTrue(storageManager.fileExists(fileName))
    }

    @Test
    fun `test delete JSON file`() {
        val fileName = "delete.json"
        storageManager.writeJsonFile(fileName, TestData("test", 1))
        assertTrue(storageManager.fileExists(fileName))

        val deleted = storageManager.deleteJsonFile(fileName)
        assertTrue(deleted)
        assertFalse(storageManager.fileExists(fileName))
    }

    @Test
    fun `test delete non-existent file returns false`() {
        val deleted = storageManager.deleteJsonFile("nonexistent.json")
        assertFalse(deleted)
    }

    @Test
    fun `test backup JSON file`() {
        val fileName = "backup.json"
        storageManager.writeJsonFile(fileName, TestData("test", 1))

        val backed = storageManager.backupJsonFile(fileName)
        assertTrue(backed)

        val backupDir = File(storageManager.getStorageDirectory(), "backups")
        assertTrue(backupDir.exists())
        assertTrue(backupDir.listFiles()?.any { it.name.startsWith("backup.json.") } ?: false)
    }

    @Test
    fun `test backup non-existent file returns false`() {
        val backed = storageManager.backupJsonFile("nonexistent.json")
        assertFalse(backed)
    }

    @Test
    fun `test getStorageDirectory returns correct directory`() {
        val dir = storageManager.getStorageDirectory()
        assertEquals(tempDir.toFile().absolutePath, dir.absolutePath)
    }

    @Test
    fun `test readJsonFileAsString returns content`() {
        val fileName = "string.json"
        storageManager.writeJsonFile(fileName, TestData("test", 1))

        val content = storageManager.readJsonFileAsString(fileName)
        assertNotNull(content)
        assertTrue(content!!.contains("test"))
        assertTrue(content.contains("1"))
    }

    @Test
    fun `test readJsonFileAsString for non-existent file returns null`() {
        val content = storageManager.readJsonFileAsString("nonexistent.json")
        assertNull(content)
    }

    @Test
    fun `test listJsonFiles returns JSON files`() {
        storageManager.writeJsonFile("file1.json", TestData("test1", 1))
        storageManager.writeJsonFile("file2.json", TestData("test2", 2))

        // Create a non-JSON file
        File(storageManager.getStorageDirectory(), "other.txt").writeText("test")

        val files = storageManager.listJsonFiles()
        assertEquals(2, files.size)
        assertTrue(files.contains("file1.json"))
        assertTrue(files.contains("file2.json"))
    }

    @Test
    fun `test concurrent writes to same file`() {
        val fileName = "concurrent.json"
        val threads = (1..10).map { index ->
            Thread {
                storageManager.writeJsonFile(fileName, TestData("test$index", index))
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // File should exist and contain valid data
        val result = storageManager.readJsonFile(fileName, TestData::class.java)
        assertNotNull(result)
    }

    @Test
    fun `test loadJsonData returns the empty value only for a missing file`() {
        assertEquals(empty, loadTestData("missing.json"))
        assertFalse(storageManager.isUnreadable("missing.json"))

        val blankFile = File(storageManager.getStorageDirectory(), "blank.json")
        blankFile.writeText("   \n")

        assertThrows<UnreadableJsonFileException> { loadTestData("blank.json") }
        assertTrue(storageManager.isUnreadable("blank.json"))
        assertThrows<UnreadableJsonFileException> {
            storageManager.writeJsonFile("blank.json", TestData("written", 1))
        }
        assertEquals("   \n", blankFile.readText())
        assertEquals(listOf("blank.json"), tempDir.toFile().listFiles()?.map { it.name })
    }

    @Test
    fun `test loadJsonData fails closed without changing source bytes or creating a backup`() {
        val fileName = "unreadable.json"
        val file = File(storageManager.getStorageDirectory(), fileName)
        val originalBytes = "{ this is not json\r\n".toByteArray(Charsets.UTF_8)
        file.writeBytes(originalBytes)

        var producedEmpty = false
        assertThrows<UnreadableJsonFileException> {
            storageManager.loadJsonData(fileName, "test data", Gson(), TestData::class.java) {
                producedEmpty = true
                empty
            }
        }
        assertFalse(producedEmpty, "an invalid file must not be reported as empty data")
        assertTrue(storageManager.isUnreadable(fileName))
        assertEquals(listOf(fileName), tempDir.toFile().listFiles()?.map { it.name })

        assertThrows<UnreadableJsonFileException> { storageManager.writeJsonFile(fileName, TestData("over", 1)) }
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test
    fun `test loadJsonData treats a file parsing to nothing as unreadable`() {
        val fileName = "null.json"
        File(storageManager.getStorageDirectory(), fileName).writeText("null")

        assertThrows<UnreadableJsonFileException> { loadTestData(fileName) }
        assertTrue(storageManager.isUnreadable(fileName))
        assertEquals(listOf(fileName), tempDir.toFile().listFiles()?.map { it.name })
    }

    @Test
    fun `test loadJsonData allows writes again once the file can be read`() {
        val fileName = "repaired.json"
        val file = File(storageManager.getStorageDirectory(), fileName)
        file.writeText("{ this is not json")
        assertThrows<UnreadableJsonFileException> { loadTestData(fileName) }
        assertTrue(storageManager.isUnreadable(fileName))

        file.writeText("""{"name":"repaired","value":7}""")

        assertEquals(TestData("repaired", 7), loadTestData(fileName))
        assertFalse(storageManager.isUnreadable(fileName))
        storageManager.writeJsonFile(fileName, TestData("written", 8))
        assertEquals(TestData("written", 8), loadTestData(fileName))
    }

    @Test
    fun `test loadJsonData creates no backups for separate failures`() {
        val fileName = "twice.json"
        val file = File(storageManager.getStorageDirectory(), fileName)

        file.writeText("first corruption")
        assertThrows<UnreadableJsonFileException> { loadTestData(fileName) }
        file.writeText("""{"name":"repaired","value":1}""")
        assertEquals(TestData("repaired", 1), loadTestData(fileName))
        file.writeText("second corruption")
        assertThrows<UnreadableJsonFileException> { loadTestData(fileName) }

        assertEquals("second corruption", file.readText())
        assertEquals(listOf(fileName), tempDir.toFile().listFiles()?.map { it.name })
    }

    @Test
    fun `test loadJsonData creates no backups on repeated failed reads`() {
        val fileName = "repeated.json"
        File(storageManager.getStorageDirectory(), fileName).writeText("{ this is not json")

        repeat(5) { assertThrows<UnreadableJsonFileException> { loadTestData(fileName) } }

        assertEquals(listOf(fileName), tempDir.toFile().listFiles()?.map { it.name })
    }

    @Test
    fun `test readJsonFile prevents writes after failed parsing`() {
        val fileName = "direct-read.json"
        val file = File(storageManager.getStorageDirectory(), fileName)
        file.writeText("{ invalid json")

        assertThrows<Exception> { storageManager.readJsonFile(fileName, TestData::class.java) }
        assertTrue(storageManager.isUnreadable(fileName))
        assertThrows<UnreadableJsonFileException> { storageManager.writeJsonFile(fileName, TestData("over", 1)) }
        assertEquals("{ invalid json", file.readText())
        assertEquals(listOf(fileName), tempDir.toFile().listFiles()?.map { it.name })
    }

    private fun loadTestData(fileName: String): TestData =
        storageManager.loadJsonData(fileName, "test data", Gson(), TestData::class.java) { empty }
}
