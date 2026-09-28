package com.dansplugins.factionsystem.faction.flag

import com.dansplugins.factionsystem.MedievalFactions
import java.math.BigDecimal

class MfFlagValues(
    private val plugin: MedievalFactions,
    val valuesByName: Map<String, Any?> = mutableMapOf()
) {
    val values: Map<MfFlag<out Any>, Any?>
        get() = valuesByName.toList().map { (key, value) -> plugin.flags.get<MfFlag<out Any>>(key) to value }
            .filter { (key, _) -> key != null }
            .associate { (key, value) -> key!! to value }
    operator fun <T : Any> get(flag: MfFlag<T>): T {
        if (!valuesByName.containsKey(flag.name)) return flag.defaultValue
        val stored = valuesByName[flag.name]
        // Gson reads JSON numbers in flag maps as Double. Require an exact integral conversion.
        if (flag.type == Int::class) {
            val integer = if (stored is Number) {
                runCatching { BigDecimal(stored.toString()).intValueExact() }.getOrNull()
            } else {
                null
            }
            require(integer != null) { "Invalid integer faction flag ${flag.name}" }
            @Suppress("UNCHECKED_CAST")
            return integer as T
        }
        return stored as? T ?: flag.defaultValue
    }
    operator fun <T : Any> plus(flagValue: Pair<MfFlag<T>, T>): MfFlagValues = MfFlagValues(plugin, valuesByName + (flagValue.first.name to flagValue.second))
    operator fun plus(flagValues: Map<MfFlag<out Any>, Any?>): MfFlagValues = MfFlagValues(plugin, valuesByName + flagValues.mapKeys { (key, _) -> key.name })
}
