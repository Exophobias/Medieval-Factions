package com.dansplugins.factionsystem.claim

import org.bukkit.configuration.file.FileConfiguration
import kotlin.math.floor
import kotlin.math.max

/**
 * How much power a faction needs to hold a given amount of land.
 *
 * MedievalFactions' rule is flat: one power point buys one chunk, for ever. That makes the marginal
 * cost of a chunk the same whether you hold three or three hundred, so the largest faction on a
 * server is the one for whom expansion is cheapest relative to what it already controls, and the
 * only thing slowing it down is how fast it can recruit.
 *
 * This makes each chunk cost slightly more than the last. The effect is a soft ceiling that a realm
 * approaches rather than a hard one it hits: the first hundred chunks cost about what they always
 * did, and the five hundredth costs several times as much.
 *
 * ## The default changes nothing
 *
 * With `increment` at `0.0` -- the shipped value -- every chunk costs exactly one power and this is
 * arithmetically identical to the old rule. That is deliberate: land is the most load-bearing number
 * on a running server, and a plugin update that silently redrew every faction's border would be
 * indefensible. An operator turns it on by choosing a figure.
 *
 * ## Nothing is ever taken away
 *
 * Lowering a faction's allowance below what it already holds does not unclaim anything, here or
 * anywhere else in MF. A faction over its allowance simply cannot claim more until it grows or gives
 * land up, which is the same thing that happens today when a member leaves and takes their power
 * with them.
 */
object MfDemesne {

    /**
     * @param freeChunks how many chunks still cost exactly one power each. Below this the curve does
     *                   not apply at all, so a small faction is untouched by it
     * @param step       how many chunks share a price before it rises again. Larger is gentler
     * @param increment  how much the per-chunk price rises at each step. `0.0` is the old flat rule
     */
    data class Settings(val freeChunks: Int, val step: Int, val increment: Double) {

        /** Whether the curve does anything at all. */
        val flat: Boolean get() = increment <= 0.0

        companion object {

            /** The old rule exactly, and the shipped default. */
            @JvmField
            val FLAT = Settings(0, 1, 0.0)

            @JvmStatic
            fun from(config: FileConfiguration): Settings {
                if (!config.getBoolean("factions.demesneCurve.enabled", false)) return FLAT
                return Settings(
                    max(0, config.getInt("factions.demesneCurve.freeChunks", 64)),
                    max(1, config.getInt("factions.demesneCurve.step", 32)),
                    max(0.0, config.getDouble("factions.demesneCurve.increment", 0.25))
                )
            }
        }
    }

    /**
     * The power needed to hold [chunks] chunks.
     *
     * Closed form rather than a loop, because [maxChunks] is consulted from `PlayerMoveListener` on
     * every chunk a player with autoclaim on walks into. A loop over a realm-sized demesne there
     * would run hundreds of iterations per movement event.
     *
     * The i-th chunk beyond [Settings.freeChunks] costs `1 + increment * (1 + (i - 1) / step)`, so
     * chunks are grouped into bands of `step` that each cost one `increment` more than the last.
     */
    @JvmStatic
    fun powerFor(chunks: Int, settings: Settings): Double {
        if (chunks <= 0) return 0.0
        if (settings.flat) return chunks.toDouble()
        val free = minOf(chunks, settings.freeChunks)
        val beyond = chunks - free
        if (beyond <= 0) return free.toDouble()

        val step = settings.step
        // The sum of floor(j / step) for j in 0 until beyond, in closed form. `bands` counts how
        // many complete bands fit; `remainder` is the partial one on the end.
        val bands = beyond / step
        val remainder = beyond % step
        val bandedSum = step.toLong() * bands * (bands - 1) / 2 + bands.toLong() * remainder

        return free + beyond + settings.increment * (beyond + bandedSum)
    }

    /**
     * The most chunks [power] can hold.
     *
     * Binary search rather than an inverted formula: the forward function is a sum of floors, so its
     * inverse has no clean closed form, and a search over a range bounded by [power] is a couple of
     * dozen iterations at any realistic size. [powerFor] is monotonic in `chunks`, which is what
     * makes the search valid.
     */
    @JvmStatic
    fun maxChunks(power: Double, settings: Settings): Int {
        if (power <= 0.0) return 0
        if (settings.flat) return floor(power).toInt()
        // powerFor(n) >= n for every n, so the answer cannot exceed the flat allowance.
        var low = 0
        var high = floor(power).toInt()
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (powerFor(middle, settings) <= power) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Whether a faction holding [held] chunks may take [wanted] more.
     *
     * The one call the three claim gates make, so the comparison lives in one place rather than
     * being spelled out three times as `held + wanted > power` was.
     */
    @JvmStatic
    fun mayClaim(held: Int, wanted: Int, power: Double, settings: Settings): Boolean =
        powerFor(held + wanted, settings) <= power
}
