package com.dansplugins.factionsystem.claim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The demesne curve, which redraws every faction's borders when it is switched on.
 *
 * The property that matters most is the first one: with the shipped settings this is arithmetically
 * identical to the old flat rule. Everything else here is a curve nobody has played yet and can be
 * retuned; that one is a promise that installing this version changes nothing until an operator
 * chooses to change it.
 */
class MfDemesneTest {

    private val curve = MfDemesne.Settings(freeChunks = 64, step = 32, increment = 0.25)

    @Test
    @DisplayName("the shipped settings are the old flat rule, exactly")
    fun `flat settings are identical to the old rule`() {
        // One power buys one chunk, for ever, which is what MedievalFactions has always done. A
        // plugin update that silently redrew every border would be indefensible.
        for (chunks in 0..500) {
            assertEquals(chunks.toDouble(), MfDemesne.powerFor(chunks, MfDemesne.Settings.FLAT))
        }
        for (power in 0..500) {
            assertEquals(power, MfDemesne.maxChunks(power.toDouble(), MfDemesne.Settings.FLAT))
        }
        assertTrue(MfDemesne.Settings.FLAT.flat)
    }

    @Test
    @DisplayName("chunks below the free allowance cost exactly one power each")
    fun `a small faction is untouched`() {
        // The curve exists to slow the largest factions down, not to squeeze the smallest ones.
        for (chunks in 0..64) {
            assertEquals(
                chunks.toDouble(),
                MfDemesne.powerFor(chunks, curve),
                "chunk $chunks should still cost one power"
            )
        }
    }

    @Test
    @DisplayName("each chunk beyond the allowance costs more than the one before it")
    fun `the marginal cost rises`() {
        var previous = 1.0
        var seenAnIncrease = false
        for (chunks in 65..600) {
            val marginal = MfDemesne.powerFor(chunks, curve) - MfDemesne.powerFor(chunks - 1, curve)
            assertTrue(
                marginal >= previous - 1e-9,
                "the ${chunks}th chunk cost $marginal, less than the ${chunks - 1}th at $previous"
            )
            if (marginal > previous + 1e-9) seenAnIncrease = true
            previous = marginal
        }
        assertTrue(seenAnIncrease, "a curve that never rises is a flat rule with extra steps")
    }

    @Test
    @DisplayName("the closed form matches a plain loop, which is what it replaces")
    fun `the closed form is right`() {
        // powerFor is a sum of floors written in closed form, because maxChunks is consulted from
        // PlayerMoveListener on every chunk an autoclaiming player walks into and a loop there would
        // run hundreds of iterations per movement event. This is the loop it has to agree with.
        val shapes = listOf(curve, MfDemesne.Settings(0, 1, 1.0), MfDemesne.Settings(10, 5, 0.5))
        for (settings in shapes) {
            for (chunks in 0..300) {
                var expected = 0.0
                for (i in 1..chunks) {
                    expected += if (i <= settings.freeChunks) {
                        1.0
                    } else {
                        1.0 + settings.increment * (1 + (i - settings.freeChunks - 1) / settings.step)
                    }
                }
                assertEquals(
                    expected,
                    MfDemesne.powerFor(chunks, settings),
                    1e-9,
                    "$chunks chunks under $settings"
                )
            }
        }
    }

    @Test
    @DisplayName("maxChunks is the exact inverse: the allowance fits and one more does not")
    fun `maxChunks inverts powerFor`() {
        for (power in 1..800) {
            val allowed = MfDemesne.maxChunks(power.toDouble(), curve)
            assertTrue(
                MfDemesne.powerFor(allowed, curve) <= power,
                "$power power was said to hold $allowed chunks, which costs more than that"
            )
            assertTrue(
                MfDemesne.powerFor(allowed + 1, curve) > power,
                "$power power was said to hold only $allowed chunks, but one more would fit"
            )
        }
    }

    @Test
    @DisplayName("the allowance grows more slowly than power does, which is the entire point")
    fun `the allowance falls behind power`() {
        assertEquals(
            64,
            MfDemesne.maxChunks(64.0, curve),
            "still one for one at the free allowance"
        )
        assertTrue(MfDemesne.maxChunks(256.0, curve) < 256)
        assertTrue(MfDemesne.maxChunks(1024.0, curve) < 1024)

        // And doubling the power buys less than double the land.
        val small = MfDemesne.maxChunks(256.0, curve)
        val large = MfDemesne.maxChunks(512.0, curve)
        assertTrue(large < small * 2, "$large should be less than twice $small")
    }

    @Test
    @DisplayName("nothing is negative, and nothing overflows at an absurd size")
    fun `the edges hold`() {
        assertEquals(0.0, MfDemesne.powerFor(0, curve))
        assertEquals(0.0, MfDemesne.powerFor(-5, curve))
        assertEquals(0, MfDemesne.maxChunks(0.0, curve))
        assertEquals(0, MfDemesne.maxChunks(-1.0, curve))

        // The banded sum is computed in Long precisely so a realm-sized demesne cannot wrap an Int.
        assertTrue(MfDemesne.powerFor(1_000_000, curve) > 0.0)
    }

    @Test
    @DisplayName("mayClaim asks about the total, not the increment")
    fun `mayClaim considers what is already held`() {
        // The bug this shape avoids: checking whether the NEW chunks fit rather than whether the
        // total does, which would let a faction at its limit claim for ever one chunk at a time.
        assertTrue(MfDemesne.mayClaim(held = 60, wanted = 4, power = 64.0, settings = curve))
        assertFalse(MfDemesne.mayClaim(held = 64, wanted = 1, power = 64.0, settings = curve))
        assertFalse(MfDemesne.mayClaim(held = 1000, wanted = 1, power = 64.0, settings = curve))
    }

    @Test
    @DisplayName("a zero increment is treated as flat however the other values are set")
    fun `a zero increment is flat`() {
        val misconfigured = MfDemesne.Settings(freeChunks = 500, step = 7, increment = 0.0)

        assertTrue(misconfigured.flat)
        assertEquals(300.0, MfDemesne.powerFor(300, misconfigured))
    }
}
