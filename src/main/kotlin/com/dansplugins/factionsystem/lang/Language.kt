package com.dansplugins.factionsystem.lang

import com.dansplugins.factionsystem.MedievalFactions
import java.io.File
import java.net.URLClassLoader
import java.text.MessageFormat
import java.util.*

class Language(plugin: MedievalFactions, private val language: String) {

    private val resourceBundles: List<ResourceBundle>
    val locale: Locale = Locale.forLanguageTag(language)

    init {
        val languageFolder = File(plugin.dataFolder, "lang")
        if (!languageFolder.exists()) {
            languageFolder.mkdirs()
        }

        // The BASE bundle, not lang_en_US, and the distinction is the whole fix.
        //
        // The fork ships English only. With lang_en_US as the only file there is no ROOT candidate,
        // so ResourceBundle.getBundle("lang", de_DE, ...) walks lang_de_DE, lang_de, the JVM default
        // locale's candidates, and then finds nothing -- MissingResourceException, thrown out of a
        // getBundle call that is NOT inside the try below, which means MedievalFactions fails to
        // enable outright on a German host. Naming the file `lang` makes it the ROOT bundle, which
        // every locale falls back to.
        //
        // Servers that customised their own plugins/MedievalFactions/lang/lang_en_US.properties keep
        // working and keep winning for en_US, because a locale-specific bundle still beats the base.
        // They now also inherit any key their old copy lacks instead of rendering
        // "Missing translation for ...", because that file's parent chain ends at this one.
        val filenames = listOf("lang")

        filenames.forEach { filename ->
            val filepath = "lang/$filename.properties"
            val file = File(plugin.dataFolder, filepath)
            if (!file.exists()) {
                plugin.saveResource(filepath, false)
            }
        }

        val externalUrls = arrayOf(languageFolder.toURI().toURL())
        val externalClassLoader = URLClassLoader(externalUrls)

        // Both loads are guarded now. The base bundle above should make the external one always
        // resolvable, but "should" is doing a lot of work in a constructor whose exceptions abort
        // onEnable: an operator who deletes the file after first run, or a folder the server cannot
        // read, would otherwise take the entire plugin down over a translation file. Falling back to
        // the bundle inside the jar is always better than not starting.
        val externalResourceBundle = try {
            ResourceBundle.getBundle("lang", locale, externalClassLoader)
        } catch (e: MissingResourceException) {
            plugin.logger.warning(
                "Could not read a language bundle from " + languageFolder +
                    "; falling back to the one inside the jar. (" + e.message + ")"
            )
            null
        }
        val internalResourceBundle = try {
            ResourceBundle.getBundle("lang", locale)
        } catch (e: MissingResourceException) {
            null
        }
        resourceBundles = listOfNotNull(externalResourceBundle, internalResourceBundle)
    }

    operator fun get(key: String, vararg params: String) =
        resourceBundles.firstNotNullOfOrNull { resourceBundle ->
            try {
                MessageFormat.format(resourceBundle.getString(key), *params)
            } catch (exception: MissingResourceException) {
                null
            }
        } ?: "Missing translation for $language: $key"
}
