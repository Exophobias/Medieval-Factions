package com.dansplugins.factionsystem.command.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.lang.Language
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.util.Locale
import java.util.UUID

class MfFactionCaretakerDispatchTest {
    @Test fun `localized and rewritten command labels pass canonical protected verbs to authority`() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.servicesManager).thenReturn(mock(ServicesManager::class.java))
        `when`(plugin.config).thenReturn(mock(FileConfiguration::class.java))
        val translations = mapOf("CmdFactionTransfer" to "passer", "CmdFactionHeir" to "heritier",
            "CmdFactionKick" to "expulser", "CmdFactionRole" to "rang", "CmdFactionDisband" to "dissoudre")
        val language = mock(Language::class.java, Answer { invocation ->
            if (invocation.method.returnType == String::class.java)
                translations[invocation.arguments.firstOrNull()?.toString()] ?: invocation.arguments.firstOrNull()?.toString().orEmpty()
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(language.locale).thenReturn(Locale.FRENCH)
        `when`(plugin.language).thenReturn(language)
        val sender = mock(Player::class.java)
        val id = UUID.randomUUID()
        `when`(sender.uniqueId).thenReturn(id)
        val calls = mutableListOf<String>()
        doAnswer { invocation -> calls += invocation.getArgument<String>(1); "Caretaker refusal" }
            .`when`(plugin).patriamCommandRefusal(eq(id) ?: id, anyString())
        val router = MfFactionCommand(plugin)
        val command = mock(Command::class.java)
        val aliases = listOf("PASSER" to "transfer", "heritier" to "heir", "expulser" to "kick",
            "rang" to "role", "dissoudre" to "disband", "transfer" to "transfer")
        for ((index, entry) in aliases.withIndex()) {
            // Bukkit commands.yml expands an alias into this same root dispatch. Namespaced labels
            // and the rewritten alias label must not change which canonical verb is guarded.
            val label = listOf("f", "medievalfactions:mf", "shortcut")[index % 3]
            assertTrue(router.onCommand(sender, command, label, arrayOf(entry.first, "Target")))
        }
        assertEquals(aliases.map { it.second }, calls)
    }
}
