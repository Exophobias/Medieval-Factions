package com.dansplugins.factionsystem.api

import com.dansplugins.factionsystem.api.geometry.ChunkPos
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
     * Every chunk [faction] holds, grouped by the world it is in.
     *
     * The inverse of [getClaimAt]: that answers "who owns this chunk", this answers "what does this
     * faction own". Rendering a territory outline needs the second question and there was previously
     * no way to ask it short of walking every claim on the server.
     *
     * **Grouped by world because [ChunkPos] deliberately carries none.** A faction may hold land in
     * several worlds, and a boundary traced across a mixed set would be nonsense. Each value is
     * therefore exactly what [com.dansplugins.factionsystem.api.geometry.ChunkRingBuilder.buildPolygons]
     * takes, so the common caller is one lookup and one call per world with nothing to reshape.
     *
     * O(chunks owned by this faction), off the in-memory per-faction claim index. It never walks the
     * global claim set and never touches world state, so it is safe on the server thread. Worlds with
     * no claims are absent rather than mapped to an empty set, and an unknown faction gives an empty
     * map.
     *
     * The returned collections are snapshots and do not track later claims or unclaims. Re-read after
     * any of the claim lifecycle events rather than holding one and mutating it.
     */
    fun getClaimedChunks(faction: FactionId): Map<UUID, Set<ChunkPos>>

    /**
     * The chunks [faction] holds in one world, or an empty set if it holds none there.
     *
     * The overload to prefer when the caller is already per-world, which a web map marker set always
     * is: it skips grouping the faction's other worlds only to discard them.
     */
    fun getClaimedChunks(faction: FactionId, worldId: UUID): Set<ChunkPos>

    /**
     * How many chunks [faction] holds, across every world.
     *
     * O(1), off the same index. Prefer it to `getClaimedChunks(faction).values.sumOf { it.size }`,
     * which materialises every chunk to count them.
     */
    fun getClaimCount(faction: FactionId): Int

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

    /**
     * Claim a chunk by coordinates, **without loading it**.
     *
     * The write-side counterpart to [isClaimed] and the positional [getClaimAt], and it exists for
     * the same reason they do. The [Chunk]-typed overload forces a caller holding only coordinates
     * through `World.getChunkAt`, which **loads and if necessary generates** the chunk -- and doing
     * that off the main thread, which every write here is supposed to be on, is a synchronous load
     * from the wrong thread. A caller moving a large holding's land would generate all of it.
     *
     * MF stores claims by `(worldId, x, z)` internally and never needs the [Chunk] object, so this
     * is the shape the write always wanted.
     *
     * Fails if no world with that id is loaded. Everything else about it matches [claim], including
     * the events fired and the fact that it is not transactional.
     */
    fun claim(faction: FactionId, worldId: UUID, chunkX: Int, chunkZ: Int): ApiResult

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

    // --- Founding, dissolution, and moving people and land between factions ---
    //
    // The four calls below exist so that a plugin modelling a political event -- a secession, a
    // rebellion, a fief being raised into a realm of its own -- can carry it out without reaching
    // into MF's internal services. Each is a BLOCKING write like everything else in this section,
    // and each is heavier than the ones above it; read the individual notes.

    /**
     * Found a faction with [founderId] as its head, and return its new id.
     *
     * The same thing `/f create` does, minus the chat: the founder is admitted with the top default
     * role and recorded as [FactionView.primaryOwnerId], and the faction starts with no land, no
     * relationships and MF's default flags.
     *
     * Fails, creating nothing, if a faction of that name already exists, if the name is longer than
     * `factions.maxNameLength`, or if the name is blank.
     *
     * **A founder who is already in a faction is MOVED, not refused**, and the distinction is
     * load-bearing rather than a convenience. The motivating consumer is a secession -- somebody
     * taking part of a realm out of it -- and such a founder is by construction still a member of
     * the realm they are leaving at the moment the new one is founded. Refusing them meant this
     * could only be called for a player who already belonged nowhere, which is a player who does not
     * need a faction founded around them.
     *
     * **If the creation then fails, they are put back.** The creation fails routinely rather than
     * exceptionally: [event.FactionCreateEvent] is cancellable, and a server running a
     * founding-permit plugin vetoes it for anybody without a permit. Note that restoring membership
     * does not undo what other plugins did in response to the departure -- a fief moved on by a
     * consumer watching [event.FactionMemberLeftEvent] stays moved -- so a caller that cannot
     * tolerate that should establish the founder may create one before calling.
     *
     * They are removed from the old faction **before** the new one is written, because MF resolves a
     * player found in two factions to *neither* -- the lookup is a `singleOrNull` over every faction
     * -- so the window between the two writes must leave them factionless rather than doubly seated.
     * The departure runs MF's ordinary machinery: succession reseats the old faction if the founder
     * was its head, and [event.FactionMemberLeftEvent] is delivered, so a consumer holding sub-group
     * state (a fief, a settlement) reacts to it as it would to any other departure.
     *
     * A player record is created for [founderId] if MF has never seen them, exactly as `/f create`
     * does for a first-time founder.
     *
     * [event.FactionCreateEvent] is fired, and it is cancellable, so this can fail because another
     * plugin refused. Read the events note on this interface before writing a handler for it -- the
     * faction does not exist yet inside that event.
     */
    fun createFaction(name: String, founderId: UUID): ApiOutcome<FactionId>

    /**
     * Dissolve a faction, as `/f disband` does.
     *
     * **Its land is destroyed, not released to anybody.** Every claim it holds returns to wilderness,
     * and if you meant those chunks to end up somewhere else you must call [transferAllClaims] first;
     * afterwards is too late. The same goes for its members, who are left factionless.
     *
     * **This fires no per-chunk event**, neither MF's own nor [event.ClaimOwnerChangedEvent], because
     * a realm-sized faction can put thousands of chunks through it at once and scheduling an event
     * for each would stall a tick. A consumer tracking tenancy must treat
     * [event.FactionDisbandedEvent] as invalidating every chunk it believed that faction held.
     */
    fun disbandFaction(faction: FactionId): ApiResult

    /**
     * Move members from one faction to another, giving them the destination's default role.
     *
     * Both factions must exist. Duplicates and an empty collection are accepted and are no-ops.
     *
     * **Moving every member dissolves [from] rather than emptying it.** An empty faction cannot be
     * saved at all when `factions.allowLeaderlessFactions` is off, because succession runs on every
     * save and finds no successor -- so without this the ordinary case of a group returning home in
     * one call simply failed. This is what `/f leave` already does when the last member walks out.
     *
     * **Fails if none of the named players are members**, rather than reporting a vacuous success.
     * A caller that acts on the result -- disbanding the source, say -- must be able to tell
     * "everybody moved" from "nobody was there to move".
     *
     * **Ids that are no longer members of [from] are skipped, not refused.** This was all-or-nothing
     * and that was the wrong shape for every real caller: the motivating one moves a group recorded
     * minutes or days earlier, and any single member leaving in the meantime turned a routine move
     * into a failure that stranded everybody else -- or, for a caller that treated the move as
     * housekeeping and carried on, left the whole remaining group factionless. A caller that
     * genuinely needs all-or-nothing should compare the count it asked for against the membership it
     * finds afterwards.
     *
     * **Two writes, and a failure between them leaves the players factionless.** They are removed
     * from [from] first and admitted to [to] second, deliberately in that order: the other order
     * would put them in two factions at once during the window, and a player in two factions reads
     * as being in *none* everywhere in MF, which is both wrong and invisible. Factionless is wrong
     * too, but it is the state a player can be in legitimately, so it is the recoverable one. A
     * caller doing something it cares about should re-read afterwards and retry the second half.
     *
     * **Roles do not survive the move**, including the top one. A faction's roles belong to that
     * faction, and there is no general mapping between two factions' role sets; carrying a role
     * across by name would let a member arrive holding whatever authority the destination happens to
     * have given that name. If the arriving player is meant to lead, say so with [setPrimaryOwner]
     * and grant the role explicitly.
     *
     * Moving a faction's recorded head out of it does NOT leave the faction headless: MF's succession
     * runs inside the very save that removes them, so a new head is already seated and
     * [event.FactionPrimaryOwnerChangedEvent] already delivered by the time this returns. A caller
     * that wants the seat to end up somewhere specific should say so with [setPrimaryOwner]
     * afterwards rather than assume it is vacant. If you are moving everybody, disband instead.
     */
    fun transferMembers(from: FactionId, to: FactionId, playerIds: Collection<UUID>): ApiResult

    /**
     * Hand every chunk [from] holds to [to], and return how many moved.
     *
     * The land half of a conquest or a secession. Each chunk is re-owned individually, so
     * [event.ClaimOwnerChangedEvent] fires once per chunk with both owners, MF's own
     * `FactionClaimEvent` fires for each and **may cancel it**, and a partial transfer is therefore
     * a real outcome: the count returned is what actually moved, and it can be less than the count
     * [from] had. It is never a failure to move zero chunks from a faction that held none.
     *
     * **This is the most expensive call in this interface.** It costs two statements per chunk and
     * schedules a Bukkit task per chunk, so a faction holding a thousand chunks costs two thousand
     * statements -- on MySQL, two thousand network round trips. That is acceptable for a rebellion
     * resolving once, and it is not acceptable on a timer. There is no bulk shortcut, because the
     * per-chunk events are the entire reason a consumer can track tenancy at all.
     *
     * Stops at the first hard failure rather than continuing, so the count is a prefix of the work
     * and not a sample of it.
     */
    fun transferAllClaims(from: FactionId, to: FactionId): ApiOutcome<Int>

    /**
     * Break a faction's oath to its liege, leaving it sworn to nobody.
     *
     * The write behind a war of independence. [FactionHierarchyView] made vassalage readable, so a
     * plugin could see that a realm swore fealty and could derive a rank from it, but nothing could
     * move it -- `/f declareindependence` and `/f grantindependence` were the only routes and both
     * need the faction's own leader to type them. A government plugin that has just watched a vassal
     * win a war had no way to record the result.
     *
     * Removes every relationship row between the two, in both directions, which is what MF's own
     * command does. **It does not start a war**, deliberately: `/f declareindependence` couples the
     * two and then makes the war conditional on neutrality flags, so a caller that wanted the oath
     * broken got a war it may not have wanted and a caller that wanted the war got a silent no-op
     * when either side was neutral. These are two acts here, and a caller that wants both calls
     * [declareWar] as well.
     *
     * Fails, changing nothing, if the faction does not exist or swears to nobody.
     */
    fun renounceLiege(vassal: FactionId): ApiResult

    /**
     * Swear [vassal] to [liege], as `/f swearfealty` accepting a `/f vassalize` offer does, without
     * either faction having to agree.
     *
     * The counterpart of [renounceLiege], and needed for the same reason it is: a plugin that can
     * break an oath but not restore one can only ever destroy hierarchy. The motivating case is a war
     * of independence that the vassal <em>loses</em> -- the oath it broke has to go back, or losing
     * would cost a realm its whole position over an attempt, which is a far heavier penalty than
     * winning is a reward.
     *
     * Writes both rows: the vassal's liege row and the liege's vassal row. Fails, changing nothing,
     * if either faction does not exist, if they are the same faction, or if [vassal] already swears
     * to somebody -- a faction with two lieges is a state MF's own walk resolves by picking the
     * first, so it must not be creatable.
     *
     * **It does not check for a cycle**, and a caller that swears a liege to its own vassal will
     * produce one. MF's hierarchy walk is depth-bounded rather than cycle-safe, so the symptom is a
     * wrong depth rather than a hang, but it is still wrong. Callers move oaths they already know
     * the shape of; do not expose this to a command without checking.
     */
    fun swearFealty(vassal: FactionId, liege: FactionId): ApiResult

    /**
     * Put two factions at war, as `/f declarewar` does, without asking either of them.
     *
     * The mirror of [forcePeace], and the reason it exists: a plugin that can end a war it did not
     * start could not start one it intends to end. Fails if the two are already at war, if either
     * does not exist, or if they are the same faction.
     *
     * **[event.FactionWarStartedEvent] is fired from the relationship write, which happens BEFORE the
     * row is persisted**, so a consumer can see a war announced that then fails to save. It is fired
     * once per pair either way -- the bridge collapses the two rows into one event -- but "once on
     * success" would be a promise this cannot keep. A consumer that must not act on a war that did
     * not happen should re-read [FactionView.isAtWarWith] on the next tick.
     *
     * The same ordering means a retry after a failure is *silent*: the bridge has already recorded
     * the pair as warring, so the second attempt fires nothing.
     */
    fun declareWar(faction: FactionId, otherFaction: FactionId): ApiResult

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
