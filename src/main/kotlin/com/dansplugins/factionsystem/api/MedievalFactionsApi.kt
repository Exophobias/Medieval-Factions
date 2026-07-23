package com.dansplugins.factionsystem.api

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
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

    fun getFaction(name: String): FactionView?

    fun getFactionByPlayer(playerId: UUID): FactionView?

    /** The faction that owns the given chunk, or null if it is unclaimed. */
    fun getFactionAt(chunk: Chunk): FactionView?

    /** The claim covering the given chunk, or null if it is unclaimed. */
    fun getClaimAt(chunk: Chunk): ClaimView?

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
