package com.dansplugins.factionsystem.faction.permission

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks that every translation key the permission package asks for actually exists in the shipped
 * language file.
 *
 * A missing key does not crash anything. [com.dansplugins.factionsystem.lang.Language] returns the
 * string "Missing translation for en_US: <key>", so the failure is a permission that renders as a
 * diagnostic message in /f role setpermission and its tab completion, and it survives every test
 * that does not happen to look at the text. Registering a permission and forgetting its key is a
 * single-line mistake with no other signal, which is exactly the kind worth automating away.
 *
 * The keys are read out of the source rather than listed here, so a permission added later is
 * covered without anyone remembering to extend this test.
 */
class MfFactionPermissionLangKeyTest {

    private val permissionSources = File("src/main/kotlin/com/dansplugins/factionsystem/faction/permission")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }

    private val languageKeyPattern = Regex("""plugin\.language\["([^"]+)"""")

    private val translations: Properties = Properties().apply {
        File("src/main/resources/lang/lang_en_US.properties")
            .reader(StandardCharsets.UTF_8)
            .use { reader -> load(reader) }
    }

    @Test
    fun everyPermissionTranslationKeyIsPresentInTheLanguageFile() {
        val referenced = permissionSources
            .flatMap { file -> languageKeyPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()

        assertTrue(referenced.isNotEmpty(), "found no translation keys at all, the source scan is broken")

        val missing = referenced.filterNot(translations::containsKey)
        assertTrue(
            missing.isEmpty(),
            "permission translation keys with no entry in lang_en_US.properties: $missing"
        )
    }

    /**
     * MANAGE_SHOPS named explicitly, because it is the one permission MedievalFactions itself never
     * displays through its own commands and so the one most likely to lose its key unnoticed.
     */
    @Test
    fun manageShopsHasATranslation() {
        assertTrue(
            translations.containsKey("FactionPermissionManageShops"),
            "FactionPermissionManageShops is missing from lang_en_US.properties"
        )
    }
}
