package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import org.bukkit.World
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The override registry sits on MedievalFactions' anti-grief path, so its failure modes matter more
 * than its happy path. Each test here pins a property that would be a security or stability bug if
 * it regressed.
 */
class ClaimOverrideRegistryTest {

    private lateinit var registry: ClaimOverrideRegistry
    private lateinit var world: World
    private val player: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        registry = ClaimOverrideRegistry(Logger.getLogger("test"))
        world = mock(World::class.java)
    }

    private fun ask(action: ClaimAction = ClaimAction.BREAK) =
        registry.allows(player, world, 10, 64, 10, action)

    /** No providers is the normal case on any server that has not installed one. */
    @Test
    fun `empty registry permits nothing`() {
        assertTrue(registry.isEmpty())
        assertFalse(ask())
    }

    @Test
    fun `a provider that permits is honoured`() {
        registry.register { _, _, _, _, _, _ -> true }
        assertTrue(ask())
    }

    @Test
    fun `a provider that declines leaves the answer alone`() {
        registry.register { _, _, _, _, _, _ -> false }
        assertFalse(ask())
    }

    /** Any single provider permitting is enough; they are alternatives, not a quorum. */
    @Test
    fun `one permitting provider among several is enough`() {
        registry.register { _, _, _, _, _, _ -> false }
        registry.register { _, _, _, _, _, _ -> true }
        assertTrue(ask())
    }

    /**
     * UNKNOWN comes only from the legacy chunk-level protection call, which has no block position.
     * Consulting a provider there would grant it the entire 16x16 chunk -- an unscoped exception,
     * which is exactly what the action parameter exists to prevent.
     */
    @Test
    fun `unknown action never consults providers`() {
        var consulted = false
        registry.register { _, _, _, _, _, _ ->
            consulted = true
            true
        }
        assertFalse(ask(ClaimAction.UNKNOWN))
        assertFalse(consulted, "a provider must not even be asked about an unscoped action")
    }

    /**
     * Providers see the action, which is what lets them scope a grant to what was actually asked.
     *
     * CONTAINER is still refused, but no longer by the provider: it never reaches one, so only BREAK
     * is recorded as seen. The provider's own refusal is now belt and braces on the same attack.
     */
    @Test
    fun `the action is passed through to the provider`() {
        val seen = mutableListOf<ClaimAction>()
        registry.register { _, _, _, _, _, action ->
            seen.add(action)
            action != ClaimAction.CONTAINER
        }
        assertTrue(ask(ClaimAction.BREAK))
        assertFalse(ask(ClaimAction.CONTAINER), "a provider must be able to refuse chest access")
        assertEquals(listOf(ClaimAction.BREAK), seen)
    }

    /**
     * Section 7.3 invariant 2: CONTAINER, DAMAGE and EXPLODE are hard-excluded inside MF, before the
     * registry consults anyone, so the guarantee is structural rather than conventional.
     *
     * A provider refusing them itself is not enough. That is a convention a third-party plugin can
     * fail to follow, and a whitelist in a first-party one can be widened by mistake. Chest access,
     * PvP inside somebody else's claim, and blowing up somebody else's walls are all grants far
     * larger than any land exception intends, so no answer a provider gives can produce them.
     */
    @Test
    fun `container damage and explode never reach a provider`() {
        val seen = mutableListOf<ClaimAction>()
        registry.register { _, _, _, _, _, action ->
            seen.add(action)
            true
        }
        assertFalse(ask(ClaimAction.CONTAINER))
        assertFalse(ask(ClaimAction.DAMAGE))
        assertFalse(ask(ClaimAction.EXPLODE))
        assertEquals(emptyList(), seen, "a provider must not even be asked to open a chest")
    }

    /** The exclusion is exactly three actions wide; it must not quietly disable the whole SPI. */
    @Test
    fun `the actions an exception is for still reach a provider`() {
        registry.register { _, _, _, _, _, _ -> true }
        assertTrue(ask(ClaimAction.BUILD))
        assertTrue(ask(ClaimAction.BREAK))
        assertTrue(ask(ClaimAction.INTERACT))
        assertTrue(ask(ClaimAction.DOOR))
        assertTrue(ask(ClaimAction.BUCKET))
    }

    /** The position is passed through, so an exception can be a few blocks rather than a chunk. */
    @Test
    fun `the block position is passed through to the provider`() {
        var seenX = -1
        var seenY = -1
        var seenZ = -1
        registry.register { _, _, x, y, z, _ ->
            seenX = x
            seenY = y
            seenZ = z
            false
        }
        registry.allows(player, world, 100, 70, -200, ClaimAction.BUILD)
        assertEquals(100, seenX)
        assertEquals(70, seenY)
        assertEquals(-200, seenZ)
    }

    /**
     * A third-party plugin throwing must not take territory protection down with it. The provider is
     * dropped for the session and the answer falls back to MF's own decision.
     */
    @Test
    fun `a throwing provider is contained and disabled`() {
        var calls = 0
        registry.register { _, _, _, _, _, _ ->
            calls++
            throw IllegalStateException("provider is broken")
        }
        assertFalse(ask(), "a thrown exception must read as no opinion, not as permission")
        assertFalse(ask())
        assertFalse(ask())
        assertEquals(1, calls, "a broken provider should be asked once, then skipped")
    }

    /**
     * The same containment for an [Error], which is the failure that actually happens in the field.
     *
     * A provider compiled against a class the installed jar no longer has throws
     * NoClassDefFoundError, which is an Error and not an Exception. Caught only as Exception it
     * walks out of allows, out of isOverridden, out of the listener's `if`, and the listener's
     * cancel never runs -- territory protection fails OPEN for every player on that path. Note that
     * reintroducing the defect fails this test by THROWING rather than by asserting, which is the
     * shape the live defect had.
     */
    @Test
    fun `a provider throwing an Error is contained`() {
        var calls = 0
        registry.register { _, _, _, _, _, _ ->
            calls++
            throw NoClassDefFoundError("a class this provider was built against is gone")
        }
        assertFalse(ask(), "an Error must read as no opinion, not as permission")
        assertFalse(ask(), "and must keep reading that way once the provider is poisoned")
        assertEquals(1, calls, "a provider that threw an Error should be asked once, then skipped")
    }

    /** A broken provider must not stop a working one from being heard. */
    @Test
    fun `a throwing provider does not silence the others`() {
        registry.register { _, _, _, _, _, _ -> throw IllegalStateException("broken") }
        registry.register { _, _, _, _, _, _ -> true }
        assertTrue(ask())
        assertTrue(ask())
    }

    @Test
    fun `unregister removes a provider`() {
        val provider = ClaimOverrideProvider { _, _, _, _, _, _ -> true }
        registry.register(provider)
        assertTrue(ask())
        registry.unregister(provider)
        assertTrue(registry.isEmpty())
        assertFalse(ask())
    }

    @Test
    fun `registering the same provider twice is a no-op`() {
        var calls = 0
        val provider = ClaimOverrideProvider { _, _, _, _, _, _ ->
            calls++
            false
        }
        registry.register(provider)
        registry.register(provider)
        ask()
        assertEquals(1, calls, "a provider registered twice must only be consulted once")
    }

    @Test
    fun `unregistering something never registered is harmless`() {
        registry.unregister { _, _, _, _, _, _ -> true }
        assertTrue(registry.isEmpty())
    }
}
