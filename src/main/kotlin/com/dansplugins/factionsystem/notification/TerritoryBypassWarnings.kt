package com.dansplugins.factionsystem.notification

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.ChatColor.RED
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Limits territory bypass notices across all protection listeners for each player. */
class TerritoryBypassWarnings(
    private val plugin: MedievalFactions,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val lastNoticeNanos = ConcurrentHashMap<UUID, Long>()

    fun notify(player: Player, isMuted: Boolean) {
        if (isMuted) return

        val now = nanoTime()
        var shouldSend = false
        lastNoticeNanos.compute(player.uniqueId) { _, previous ->
            if (previous == null || now - previous >= NOTICE_INTERVAL_NANOS) {
                shouldSend = true
                now
            } else {
                previous
            }
        }
        if (shouldSend) player.sendMessage("$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
    }

    fun forget(playerId: UUID) {
        lastNoticeNanos.remove(playerId)
    }

    fun reset(playerId: UUID) {
        lastNoticeNanos.remove(playerId)
    }

    private companion object {
        val NOTICE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60)
    }
}
