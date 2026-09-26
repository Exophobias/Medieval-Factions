package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent

class BlockBurnListener(private val plugin: MedievalFactions) : Listener {

    @EventHandler
    fun onBlockBurn(event: BlockBurnEvent) {
        val gateService = plugin.services.gateService
        val block = event.block
        if (AdminFactionProtectionChecks.denies(plugin, block.location, AdminFactionProtection.FIRE_SPREAD)) {
            event.isCancelled = true
            return
        }

        val blockPosition = MfBlockPosition.fromBukkitBlock(block)

        // if block is part of a gate, cancel the event
        if (gateService.getGatesAt(blockPosition).isNotEmpty() || gateService.getGatesByTrigger(blockPosition).isNotEmpty()) {
            event.isCancelled = true
        }
    }
}
