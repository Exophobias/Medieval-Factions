package com.dansplugins.factionsystem.faction

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MfFactionDisplayNameTest {
    @Test
    fun `allows ordinary spaced names`() {
        assertTrue(MfFactionDisplayName.isValid("People of Olzhar"))
        assertTrue(MfFactionDisplayName.isValid("L'Étoile (North)"))
    }

    @Test
    fun `rejects formatting controls and placeholder syntax`() {
        for (invalid in listOf("", " Too much", "Too much ", "A\nB", "&cRed", "§cRed", "%server_online%", "\${player}", "<b>Realm</b>", "x".repeat(65))) {
            assertFalse(MfFactionDisplayName.isValid(invalid), invalid)
        }
    }
}
