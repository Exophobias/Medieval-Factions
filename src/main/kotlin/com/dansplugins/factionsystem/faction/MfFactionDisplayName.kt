package com.dansplugins.factionsystem.faction

/** Plain text label shown to players. Faction names and IDs remain the lookup keys. */
object MfFactionDisplayName {
    const val MAX_LENGTH = 64

    fun isValid(value: String): Boolean =
        value.length in 1..MAX_LENGTH &&
            value.first() != ' ' && value.last() != ' ' &&
            value.all { it.isLetterOrDigit() || it == ' ' || it in "-'’_.,()" }
}
