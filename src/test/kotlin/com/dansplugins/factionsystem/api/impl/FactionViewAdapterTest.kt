package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionHierarchyView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionPermission
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Covers the faction-authority accessors on [FactionViewAdapter].
 *
 * The roles here are real [MfFactionRole]s with real permission maps, not mocks, so the assertions
 * exercise MF's own permission resolution rather than a stubbed answer. That is the point of the
 * accessor: the tests must fail if the adapter ever decides authority from the role name.
 */
class FactionViewAdapterTest {

    private val disband = MfFactionPermission("DISBAND", "Disband the faction", false)
    private val kick = MfFactionPermission("KICK", "Kick a member", false)

    private lateinit var plugin: MedievalFactions
    private lateinit var faction: MfFaction

    private val leaderId = UUID.randomUUID()
    private val officerId = UUID.randomUUID()
    private val commonerId = UUID.randomUUID()

    private fun leaderRole() = role("Owner", mapOf("DISBAND" to true, "KICK" to true))
    private fun officerRole() = role("Officer", mapOf("KICK" to true))
    private fun commonerRole() = role("Member", emptyMap())

    private fun role(name: String, permissions: Map<String, Boolean?>) =
        MfFactionRole(plugin, MfFactionRoleId.generate(), name, permissions)

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val permissions = mock(MfFactionPermissions::class.java)
        `when`(plugin.factionPermissions).thenReturn(permissions)
        `when`(permissions.disband).thenReturn(disband)
        `when`(permissions.kick).thenReturn(kick)
        `when`(permissions.parse("DISBAND")).thenReturn(disband)
        `when`(permissions.parse("KICK")).thenReturn(kick)

        faction = mock(MfFaction::class.java)
    }

    /** Puts the given members on the faction and makes each one's role resolvable by player id. */
    private fun membership(vararg members: Pair<UUID, MfFactionRole>) {
        `when`(faction.members).thenReturn(
            members.map { (playerId, role) -> MfFactionMember(MfPlayerId(playerId.toString()), role) }
        )
        members.forEach { (playerId, role) ->
            `when`(faction.getRole(MfPlayerId(playerId.toString()))).thenReturn(role)
        }
    }

    private fun view() = FactionViewAdapter(plugin, faction)

    @Test
    fun leaderIsTheMemberWhoseRoleMayDisbandTheFaction() {
        membership(leaderId to leaderRole(), commonerId to commonerRole())

        val view = view()

        assertTrue(view.isLeader(leaderId))
        assertEquals(listOf(leaderId), view.leaderIds)
    }

    @Test
    fun ordinaryMemberIsNotTheLeader() {
        membership(leaderId to leaderRole(), commonerId to commonerRole())

        val view = view()

        assertFalse(view.isLeader(commonerId))
        assertFalse(view.leaderIds.contains(commonerId))
    }

    @Test
    fun nonMemberIsNotTheLeader() {
        membership(leaderId to leaderRole())

        val view = view()
        val stranger = UUID.randomUUID()

        assertNull(view.roleOf(stranger))
        assertFalse(view.isLeader(stranger))
        assertFalse(view.leaderIds.contains(stranger))
    }

    /**
     * The reason this accessor exists. Factions may rename their own roles, so a name test would let
     * a faction promote a junior member by relabelling their role. Authority must come from the
     * permission map, which renaming does not touch.
     */
    @Test
    fun renamedRoleCannotImpersonateTheHighestAuthority() {
        val impostor = role("Owner", mapOf("KICK" to true))
        membership(commonerId to impostor)

        val view = view()

        assertEquals("Owner", view.roleOf(commonerId)?.name)
        assertFalse(view.isLeader(commonerId))
        assertEquals(emptyList<UUID>(), view.leaderIds)
    }

    /** MF lets several members hold a role carrying the right, so the accessor must not assume one. */
    @Test
    fun aFactionMayHaveMoreThanOneLeader() {
        val coLeaderId = UUID.randomUUID()
        membership(
            leaderId to leaderRole(),
            coLeaderId to leaderRole(),
            commonerId to commonerRole()
        )

        assertEquals(listOf(leaderId, coLeaderId), view().leaderIds)
    }

    /** A faction whose last such member has left has none, rather than falling back to some member. */
    @Test
    fun aFactionMayHaveNoLeaderAtAll() {
        membership(commonerId to commonerRole())

        assertEquals(emptyList<UUID>(), view().leaderIds)
    }

    /** The "officer or above" case: a capability question, since MF has no rank to compare. */
    @Test
    fun roleAnswersPermissionQueries() {
        membership(officerId to officerRole(), commonerId to commonerRole())

        val view = view()

        assertTrue(view.roleOf(officerId)!!.hasPermission(FactionPermission.KICK))
        assertFalse(view.roleOf(officerId)!!.hasPermission(FactionPermission.DISBAND))
        assertFalse(view.roleOf(commonerId)!!.hasPermission(FactionPermission.KICK))
    }

    @Test
    fun primaryOwnerIdExposesTheRecordedHead() {
        `when`(faction.primaryOwnerId).thenReturn(MfPlayerId(leaderId.toString()))
        membership(leaderId to leaderRole(), commonerId to commonerRole())

        assertEquals(leaderId, view().primaryOwnerId)
    }

    @Test
    fun primaryOwnerIdIsNullWhenNoHeadIsRecorded() {
        membership(leaderId to leaderRole())

        assertNull(view().primaryOwnerId)
    }

    /**
     * The two accessors answer different questions and must not be derived from each other. A faction
     * may hand the title to someone whose role carries no authority, and may grant authority to
     * members who hold no title; both of those are ordinary states, not inconsistencies.
     */
    @Test
    fun theRecordedHeadAndTheCapabilityToLeadAreIndependent() {
        `when`(faction.primaryOwnerId).thenReturn(MfPlayerId(commonerId.toString()))
        membership(leaderId to leaderRole(), commonerId to commonerRole())

        val view = view()

        assertEquals(commonerId, view.primaryOwnerId)
        assertFalse(view.isLeader(commonerId))
        assertTrue(view.isLeader(leaderId))
        assertEquals(listOf(leaderId), view.leaderIds)
    }

    /** Renaming a role rewrites a name and nothing else, so it cannot reach the recorded head. */
    @Test
    fun renamingARoleDoesNotChangeTheRecordedHead() {
        `when`(faction.primaryOwnerId).thenReturn(MfPlayerId(leaderId.toString()))
        membership(leaderId to leaderRole(), commonerId to role("Owner", mapOf("KICK" to true)))

        assertEquals(leaderId, view().primaryOwnerId)
    }

    /**
     * MF resolves an unset permission against the faction's own defaults before the permission's
     * built-in default, so the view has to consult the faction and not just the role.
     */
    @Test
    fun unsetPermissionFallsBackToTheFactionDefault() {
        `when`(faction.defaultPermissions).thenReturn(mapOf(kick to true))
        membership(commonerId to commonerRole())

        assertTrue(view().roleOf(commonerId)!!.hasPermission(FactionPermission.KICK))
    }

    // --- hierarchy ---

    private val factionId = MfFactionId.generate()
    private val liegeId = MfFactionId.generate()
    private val vassalA = MfFactionId.generate()
    private val vassalB = MfFactionId.generate()

    /** Wires the faction's position into the relationship service the adapter reads it from. */
    private fun hierarchy(
        liege: MfFactionId? = null,
        vassals: List<MfFactionId> = emptyList(),
        depth: Int = 0,
        crownedVassals: List<MfFactionId> = emptyList()
    ) {
        `when`(faction.id).thenReturn(factionId)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        val relationshipService = mock(MfFactionRelationshipService::class.java)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(relationshipService.getLiege(factionId)).thenReturn(liege)
        `when`(relationshipService.getVassals(factionId)).thenReturn(vassals)
        `when`(relationshipService.getDepthBelowSovereign(factionId)).thenReturn(depth)
        `when`(relationshipService.getVassalsHoldingVassals(factionId)).thenReturn(crownedVassals)
    }

    @Test
    fun aFactionInNoHierarchyReportsItselfIndependent() {
        hierarchy()

        assertEquals(FactionHierarchyView.INDEPENDENT, view().hierarchy)
    }

    @Test
    fun theViewCarriesTheFactionsPositionAcross() {
        hierarchy(liege = liegeId, vassals = listOf(vassalA, vassalB), depth = 2, crownedVassals = listOf(vassalA))

        val hierarchy = view().hierarchy

        assertEquals(FactionId(liegeId.value), hierarchy.liege)
        assertEquals(listOf(FactionId(vassalA.value), FactionId(vassalB.value)), hierarchy.vassals)
        assertEquals(2, hierarchy.depthBelowSovereign)
        assertEquals(1, hierarchy.vassalsHoldingVassals)
        assertTrue(hierarchy.hasLiege)
        assertTrue(hierarchy.hasVassals)
    }

    /**
     * The adapter must not answer this from the vassal tree, which grows with the whole realm. Two
     * levels is all the emperor question needs and all it is allowed to cost.
     */
    @Test
    fun theHierarchyIsBuiltWithoutWalkingTheVassalTree() {
        hierarchy(vassals = listOf(vassalA, vassalB), crownedVassals = listOf(vassalA, vassalB))

        assertEquals(2, view().hierarchy.vassalsHoldingVassals)
        val relationshipService = plugin.services.factionRelationshipService
        verify(relationshipService, never()).getVassalTree(factionId)
        verify(relationshipService, never()).getLiegeChain(factionId)
    }
}
