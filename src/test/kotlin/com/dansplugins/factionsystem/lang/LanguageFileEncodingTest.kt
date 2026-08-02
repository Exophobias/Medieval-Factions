package com.dansplugins.factionsystem.lang

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies the encoding integrity of the shipped language file.
 *
 * The plugin ships English (en_US) only. New keys are appended to the file by raw byte append,
 * so this test guards against a bad append leaving invalid UTF-8 or corruption markers behind.
 */
class LanguageFileEncodingTest {

    private val langDirectory = File("src/main/resources/lang")

    @Test
    fun testOnlyEnglishLanguageFileIsShipped() {
        // "lang", not "lang_". The shipped bundle is the ROOT one -- lang.properties -- so that
        // every locale has somewhere to fall back to; a de_DE server with only lang_en_US on the
        // classpath throws MissingResourceException out of Language's constructor and the plugin
        // does not enable. Filtering on "lang_" silently matched nothing after the rename, which
        // made this test pass its own emptiness rather than the thing it is here to check.
        val langFiles = langDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("lang") && file.name.endsWith(".properties")
        } ?: fail("Language directory not found or empty")

        assertTrue(
            langFiles.map { it.name }.sorted() == listOf("lang.properties"),
            "Expected lang.properties -- the ROOT bundle every locale falls back to -- to be the only language file, found: " +
                langFiles.joinToString { it.name }
        )
    }

    @Test
    fun testEnglishFileIsValidUtf8AndUncorrupted() {
        val englishFile = File(langDirectory, "lang.properties")
        assertTrue(englishFile.exists(), "lang.properties not found")

        val content = String(englishFile.readBytes(), StandardCharsets.UTF_8)

        assertTrue(content.isNotEmpty(), "lang.properties should not be empty")

        // U+FFFD only appears if the bytes are not valid UTF-8.
        assertTrue(
            !content.contains('\uFFFD'),
            "lang.properties contains invalid UTF-8 byte sequences."
        )

        // Corruption from a mis-encoded edit shows up as literal <E3>-style escapes.
        assertTrue(
            !Regex("<[EF][0-9A-F]{1,2}>").containsMatchIn(content),
            "lang_en_US.properties contains corrupted character sequences."
        )
    }
}
