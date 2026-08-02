package com.dansplugins.factionsystem.api

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

/**
 * The stable, in-JVM public API for MedievalFactions.
 *
 * This is the ONLY surface other plugins should bind to. Everything returned is either a Bukkit type
 * or an API-owned view/value type ([FactionView], [ClaimView], [FactionId], ...) — never an internal
 * `com.dansplugins.factionsystem.*` service or model. That decoupling is the whole point: when MF's
 * internals are refactored, only the API's adapter implementation changes, and dependent plugins keep
 * working without recompilation.
 *
 * Obtain an instance via Bukkit's ServicesManager (see [get]).
 *
 * ## Threading
 *
 * **Reads are safe from any thread. Writes block on the database and belong OFF the main thread.**
 *
 * Everything under "Reads" below is answered from `ConcurrentHashMap`s held in MF's services, so it
 * costs a map lookup and cannot stall anything. Everything under "Writes" performs synchronous JDBC
 * on **the thread that called it** — there is no internal dispatch that moves the write elsewhere.
 * MF's own command layer reflects this: it wraps service calls in `runTaskAsynchronously` at well
 * over a hundred sites, and this API is the same code underneath.
 *
 * ### What a main-thread write actually costs
 *
 * Enough to matter, and how much depends on the database:
 *
 * - **Saving one faction is not one statement.** The whole member, invite and application list is
 *   deleted and reinserted every time, with a read-back after each insert, inside one transaction —
 *   roughly `6 + 2 × (members + invites + applications)` statements. A forty-member faction is
 *   around eighty-six of them, whether you changed its name or its description.
 * - **Claiming one chunk is two** (an upsert and a read-back), and it is not transactional.
 * - **On the default embedded H2** those are in-process file writes and a main-thread call is merely
 *   wasteful. **On MySQL or MariaDB** — both fully supported — every statement is its own network
 *   round trip, because nothing is batched. The same call that was invisible on H2 becomes a
 *   multi-second freeze, and the failure arrives when an operator migrates the database rather than
 *   when anybody changed the code.
 *
 * So: do the write on an async task, and hop back with `runTask` for anything that touches the world
 * afterwards.
 *
 * ### Events do not follow the same rule as calls, and the exception matters
 *
 * Every event in the `event` package is delivered on the **main thread**, scheduled onto the next
 * tick, so a handler may touch the world freely — with one deliberate exception.
 * [event.FactionCreateEvent] is fired **inline and may be asynchronous**, because it is cancellable
 * and a veto has to reach MF before it persists anything. `/f create` runs its whole chain on an
 * async task, so in ordinary play that event *is* async.
 *
 * Two consequences for anything handling it. A handler must not assume it can touch the world. And
 * **the faction does not exist yet**: the event is fired before the row is written and before the
 * cache is populated, so [getFaction] with the new id answers `null` and any write keyed on it
 * fails. React to a creation *after* the fact by other means; use this event only to allow or refuse.
 *
 * [ClaimOverrideProvider] and [SuccessionPolicy] are the mirror image — they are consumer callbacks
 * that MF invokes **inline on whatever thread MF is on**, so they must be fast and must not block.
 *
 * ### Calling a write off the main thread runs other plugins' listeners there too
 *
 * Bukkit invokes listeners inline on the calling thread; the asynchronous flag on an event describes
 * it, it does not move it. So an off-thread [claim] runs every third-party `FactionClaimEvent`
 * listener on that thread, and a listener written on the assumption it is on the main thread will
 * misbehave. This is unavoidable and is why MF marks its own events honestly rather than pretending.
 *
 * ### Concurrency between two writers
 *
 * Faction writes carry an optimistic lock, so two callers racing on one faction produce a *failure*
 * for the loser rather than a silent overwrite; treat a failed [ApiResult] as possibly meaning
 * "somebody else got there first" and re-read before retrying. Claim writes have no version column
 * and so no such protection: two callers racing on the same chunk are last-writer-wins.
 *
 * ### Views are snapshots of identity and live for territory
 *
 * A [FactionView] freezes name, members and ownership at the moment you looked it up, but reads
 * claim count, wars and hierarchy live on every access. Holding one across ticks therefore mixes
 * stale identity with fresh territory. Re-fetch rather than caching a view.
 */
interface MedievalFactionsApi {

    // --- Reads ---
    //
    // In-memory, and safe from any thread. See the threading note on the interface.

    fun getFaction(id: FactionId): FactionView?

    /**
     * Look a faction up by its display name.
     *
     * Named distinctly from [getFaction] rather than overloading it: [FactionId] wraps a [String], so
     * an overload taking a bare `String` is trivially selected by accident when a caller has an id in
     * hand — a mistake that compiles cleanly and then silently returns null for every real faction.
     */
    fun getFactionByName(name: String): FactionView?

    fun getFactionByPlayer(playerId: UUID): FactionView?

    /** The faction that owns the given chunk, or null if it is unclaimed. */
    fun getFactionAt(chunk: Chunk): FactionView?

    /** The claim covering the given chunk, or null if it is unclaimed. */
    fun getClaimAt(chunk: Chunk): ClaimView?

    /**
     * Whether the chunk at the given chunk coordinates is claimed, **without loading the chunk**.
     *
     * [getFactionAt] and [getClaimAt] take a [Chunk], and obtaining one from a [Location] goes through
     * `Location.getChunk()`, which loads (and if necessary generates) the chunk. That is fine for the
     * occasional lookup but ruinous for callers that test many block positions per tick — territory
     * protection in a disaster/explosion plugin being the motivating case.
     *
     * This overload answers the only question such callers actually have, straight off the in-memory
     * claim index, so it costs one map lookup and never touches world state.
     */
    fun isClaimed(world: World, chunkX: Int, chunkZ: Int): Boolean

    /**
     * The claim covering the given chunk coordinates, **without loading the chunk**.
     *
     * The positional counterpart to [getClaimAt] for the same reason [isClaimed] exists: the
     * [Chunk]-typed overload forces a caller holding a [org.bukkit.Location] through
     * `Location.getChunk()`, which loads and if necessary generates the chunk.
     *
     * [isClaimed] answers only "is this claimed at all". Callers that need to know *whose* land it
     * is — to detect a change of owner, or to ask that faction for consent — had no cheap way to
     * find out. This closes that gap: it reads the same in-memory claim index, so it costs one map
     * lookup and never touches world state.
     */
    fun getClaimAt(world: World, chunkX: Int, chunkZ: Int): ClaimView?

    /**
     * The power level of the given player, or `0.0` if MedievalFactions has no record of them.
     *
     * Power is MF's per-player score that, summed across members, bounds how much land a group may
     * hold. Exposed because consumers that model sub-groups of a faction (settlements, fiefs) need the
     * same currency to size their own land allowances consistently with MF's.
     *
     * O(1): MF holds players in an in-memory map keyed by id, so this costs one lookup and never
     * touches the database. Returning `0.0` rather than null for an unknown player matches how MF's
     * own callers treat a missing record, and keeps summing over a member list allocation-free.
     */
    fun getPower(playerId: UUID): Double

    // --- Mutations ---
    //
    // BLOCKING JDBC on the calling thread. Call these off the main thread. See the threading
    // note on the interface for what one costs and why the database backend changes the answer.

    fun setHome(faction: FactionId, location: Location): ApiResult

    fun claim(faction: FactionId, chunk: Chunk): ApiResult

    fun unclaim(chunk: Chunk): ApiResult

    /** Ends any war between the two factions by removing the war relationship in both directions. */
    fun forcePeace(faction: FactionId, otherFaction: FactionId): ApiResult

    /**
     * Record [playerId] as the head of [faction], as though the previous head had handed it on.
     *
     * The write that makes an external government possible. Until this existed,
     * [FactionView.primaryOwnerId] was readable and movable only by MF itself — the founder at
     * creation, `/f transfer`, `/f admin setleader`, and succession — so a plugin that had decided
     * who should rule had no way to say so, and would have had to reach past this API into MF's
     * internal `MfFactionService` with an internal `MfFaction` to do it.
     *
     * Fails, changing nothing, if the faction does not exist or if [playerId] is not one of its
     * members. The membership requirement is not a convenience check: the head is the identity of a
     * House, several things key off it, and a head who is not in the faction would be reconciled
     * away by succession on the very next save anyway. Admit the player first if that is what you
     * mean.
     *
     * Succeeds silently when [playerId] is already the head, so a caller reconciling its own state
     * against MF's need not compare first.
     *
     * Fires [com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent] on success,
     * like any other change of head, so a caller must not assume it is the only observer.
     *
     * There is deliberately no counterpart that clears the seat. Whether a faction may exist without
     * a head is `factions.allowLeaderlessFactions`, which is the server owner's setting, and this
     * API does not offer a way around it.
     */
    fun setPrimaryOwner(faction: FactionId, playerId: UUID): ApiResult

    // --- Territory protection exceptions ---
    //
    // Registration is in-memory. The PROVIDER is called back inline on MF's own thread, from
    // block and entity listeners, so it must be fast and must never block.

    /**
     * Register a [ClaimOverrideProvider], letting this plugin grant narrow exceptions to territory
     * protection.
     *
     * Providers are **additive only** — they can turn one of MF's denials into a permission, never
     * the reverse. Read [ClaimOverrideProvider] before implementing one; in particular, refuse
     * [ClaimAction.CONTAINER] unless you genuinely intend to hand over every chest on the land.
     *
     * Registering the same instance twice is a no-op. Plugins should unregister on disable.
     */
    fun registerClaimOverrideProvider(provider: ClaimOverrideProvider)

    /** Remove a previously registered provider. Unknown providers are ignored. */
    fun unregisterClaimOverrideProvider(provider: ClaimOverrideProvider)

    // --- Succession ---

    /**
     * Register a [SuccessionPolicy], letting this plugin decide who inherits a faction whose head
     * has departed, in place of MedievalFactions' own order.
     *
     * Read [SuccessionPolicy] before implementing one; in particular it is consulted from inside a
     * save, so it must answer from memory and must not save anything itself.
     *
     * Registering the same instance twice is a no-op. Plugins should unregister on disable — a
     * policy left registered by a plugin that is no longer functioning will throw on the next
     * departure, and while that is contained, every faction it governs then falls back to MF's
     * order without anyone having decided that.
     */
    fun registerSuccessionPolicy(policy: SuccessionPolicy)

    /** Remove a previously registered policy. Unknown policies are ignored. */
    fun unregisterSuccessionPolicy(policy: SuccessionPolicy)

    companion object {
        /**
         * Convenience accessor: the registered API instance, or null if MedievalFactions is not
         * loaded. Equivalent to querying Bukkit's ServicesManager.
         */
        @JvmStatic
        fun get(): MedievalFactionsApi? =
            Bukkit.getServicesManager().getRegistration(MedievalFactionsApi::class.java)?.provider
    }
}
