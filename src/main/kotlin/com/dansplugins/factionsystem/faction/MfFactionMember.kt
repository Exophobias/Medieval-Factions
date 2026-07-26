package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId

data class MfFactionMember(
    @get:JvmName("getPlayerId")
    val playerId: MfPlayerId,
    val role: MfFactionRole,
    /**
     * When this player joined, as epoch milliseconds.
     *
     * Exists only so succession can order candidates by standing. MF had no record of it, and a
     * database hands back rows in primary-key order, so without this "the longest-standing member"
     * would resolve to "whichever uuid sorts first". Defaulted so that every existing construction
     * site keeps meaning "joining now", and carried through role changes by copying the member rather
     * than rebuilding it.
     */
    val joinedAt: Long = System.currentTimeMillis()
)

fun MfPlayer.withRole(role: MfFactionRole) = MfFactionMember(this.id, role)
