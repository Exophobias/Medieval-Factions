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
 */
interface MedievalFactionsApi {

    // --- Reads ---

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

    fun setHome(faction: FactionId, location: Location): ApiResult

    fun claim(faction: FactionId, chunk: Chunk): ApiResult

    fun unclaim(chunk: Chunk): ApiResult

    /** Ends any war between the two factions by removing the war relationship in both directions. */
    fun forcePeace(faction: FactionId, otherFaction: FactionId): ApiResult

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
