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

    /** Providers see the action, which is what lets them refuse container access specifically. */
    @Test
    fun `the action is passed through to the provider`() {
        val seen = mutableListOf<ClaimAction>()
        registry.register { _, _, _, _, _, action ->
            seen.add(action)
            action != ClaimAction.CONTAINER
        }
        assertTrue(ask(ClaimAction.BREAK))
        assertFalse(ask(ClaimAction.CONTAINER), "a provider must be able to refuse chest access")
        assertEquals(listOf(ClaimAction.BREAK, ClaimAction.CONTAINER), seen)
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
