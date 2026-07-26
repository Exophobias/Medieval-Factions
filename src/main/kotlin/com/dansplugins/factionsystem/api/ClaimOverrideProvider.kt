package com.dansplugins.factionsystem.api

import org.bukkit.World
import java.util.UUID

/**
 * Lets another plugin grant a narrow exception to MedievalFactions' territory protection.
 *
 * The motivating case is a faction agreeing to host something on its own land — a religion's holy
 * site, a market, a chartered guild hall — where the landholder wants a specific group to be able to
 * build and interact inside a small area without being given the run of the whole claim.
 *
 * ## This is deliberately additive only
 *
 * A provider can say **yes** where MF would have said no. It can never say **no** where MF would
 * have said yes. Territory protection is MF's core anti-grief guarantee, and a third-party plugin
 * that could revoke it — through a bug, a bad config, or simply by being disabled at the wrong
 * moment — would make that guarantee unreliable in a way server owners could not diagnose. Denial
 * stays MF's alone.
 *
 * ## Implementations must be fast and must not block
 *
 * This is consulted on the hot protection path, potentially many times per tick during an explosion.
 * Implementations should answer from memory. Do not touch the database, do not load chunks, and do
 * not synchronise on anything a scheduler task might hold.
 *
 * ## [ClaimAction.CONTAINER], [ClaimAction.DAMAGE] and [ClaimAction.EXPLODE] cannot be granted
 *
 * These three are refused by MedievalFactions before any provider is consulted, so a provider
 * cannot grant them however it answers. Providers should still refuse them explicitly; the
 * exclusion is belt and braces on the same attack.
 *
 * CONTAINER is the reason [ClaimAction] exists at all. MF routes inventory clicks through the same
 * decision as block breaks, so a provider that ignores the action and returns true would hand out
 * chest access along with the right to place a block. Almost no legitimate use case wants that, and
 * the ones that think they do usually want a dedicated container instead.
 *
 * ## Failure is contained
 *
 * A provider that throws anything at all, including an [Error] such as the [NoClassDefFoundError] a
 * provider built against a since-changed class produces, is caught, logged once, and treated as "no
 * opinion". A broken third-party plugin must not be able to break protection for the whole server in
 * either direction.
 *
 * @since the Patriam fork
 */
fun interface ClaimOverrideProvider {

    /**
     * Whether this provider grants [playerId] permission for [action] at the given block position.
     *
     * Called only after MF has already determined that its own rules would deny the action, so a
     * provider never needs to reimplement MF's logic — returning false simply leaves MF's answer
     * standing.
     *
     * @param playerId the acting player
     * @param world the world the block is in
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @param action what is being attempted; never [ClaimAction.UNKNOWN], and never
     * [ClaimAction.CONTAINER], [ClaimAction.DAMAGE] or [ClaimAction.EXPLODE], all of which MF
     * refuses without asking
     * @return true to permit, false to express no opinion
     */
    fun allows(playerId: UUID, world: World, x: Int, y: Int, z: Int, action: ClaimAction): Boolean
}
