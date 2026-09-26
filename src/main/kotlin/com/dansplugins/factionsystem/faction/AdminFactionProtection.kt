package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.flag.MfFlagValues

/** Staff-only territory settings stored in the faction's existing flags JSON. */
enum class AdminFactionProtection(val commandName: String, val storageKey: String) {
    PVP("pvp", "adminProtectionPvp"),
    PLAYER_DAMAGE("playerdamage", "adminProtectionPlayerDamage"),
    MOB_SPAWNING("mobspawning", "adminProtectionMobSpawning"),
    EXPLOSIONS("explosions", "adminProtectionExplosions"),
    FIRE_SPREAD("firespread", "adminProtectionFireSpread");

    /** A missing value preserves the behavior of factions created before these settings existed. */
    fun isAllowed(faction: MfFaction): Boolean =
        !faction.adminLeaderless || faction.flags.valuesByName[storageKey] != false

    fun isExplicit(faction: MfFaction): Boolean = faction.flags.valuesByName.containsKey(storageKey)

    /** Null removes the override, restoring the default of "on". */
    fun withAllowed(plugin: MedievalFactions, faction: MfFaction, allowed: Boolean?): MfFaction {
        val values = if (allowed == null) {
            faction.flags.valuesByName - storageKey
        } else {
            faction.flags.valuesByName + (storageKey to allowed)
        }
        return faction.copy(flags = MfFlagValues(plugin, values))
    }

    companion object {
        fun fromCommandName(name: String): AdminFactionProtection? =
            entries.firstOrNull { it.commandName.equals(name, ignoreCase = true) }
    }
}
