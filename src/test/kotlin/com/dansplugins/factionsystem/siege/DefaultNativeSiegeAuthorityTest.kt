package com.dansplugins.factionsystem.siege

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.SiegeClaim
import com.dansplugins.factionsystem.api.SiegeMember
import com.dansplugins.factionsystem.api.SiegeNativeExpectation
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class DefaultNativeSiegeAuthorityTest {
    @Test fun ownClaimCustodyLeaseRequiresFreshOwnershipAndMembershipWithoutInventingWar() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        val services = mock(Services::class.java)
        val factions = mock(MfFactionService::class.java)
        val claims = mock(MfClaimService::class.java)
        `when`(plugin.isEnabled).thenReturn(true)
        `when`(plugin.server).thenReturn(server)
        `when`(server.isPrimaryThread).thenReturn(true)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.claimService).thenReturn(claims)
        val actor = UUID.randomUUID(); val world = UUID.randomUUID()
        val realm = MfFactionId("friendly")
        val faction = mock(MfFaction::class.java)
        `when`(faction.id).thenReturn(realm)
        `when`(factions.getFaction(MfPlayerId(actor.toString()))).thenReturn(faction)
        `when`(claims.getClaim(world, 0, 0)).thenReturn(MfClaimedChunk(world, 0, 0, realm))
        val authority = DefaultNativeSiegeAuthority(plugin)
        val expectation = SiegeNativeExpectation(authority.epoch, emptyList(), listOf(SiegeClaim(world, 0, 0, realm.value)), listOf(SiegeMember(actor, realm.value)))
        requireNotNull(authority.tryAcquireEffectLease(expectation)).use {
            assertTrue(authority.isAvailable())
            assertThrows(IllegalStateException::class.java) { NativeFenceRegistry.get(plugin).mutate {} }
        }
        `when`(factions.getFaction(MfPlayerId(actor.toString()))).thenReturn(null)
        assertNull(authority.tryAcquireEffectLease(expectation))
        `when`(factions.getFaction(MfPlayerId(actor.toString()))).thenReturn(faction)
        `when`(claims.getClaim(world, 0, 0)).thenReturn(MfClaimedChunk(world, 0, 0, MfFactionId("new-owner")))
        assertNull(authority.tryAcquireEffectLease(expectation))
        assertNull(authority.tryAcquireEffectLease(SiegeNativeExpectation(authority.epoch, emptyList(), emptyList())))
    }
}
