package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.exception.NoSuccessorException
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.configuration.file.FileConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Covers the recorded head of a faction: who it is, what cannot move it, and who inherits it.
 *
 * The role sets here are MF's real defaults, built over a real [MfFactionPermissions] and [MfFlags],
 * so "the Owner role" is the same object the plugin hands a founder rather than a stand-in shaped to
 * suit the test. That matters, because the thing under test is precisely whether authority is read
 * from a real permission map or from a name.
 *
 * Disbanding is absent on purpose: it deletes the faction row outright, so there is no owner record
 * left to reconcile and nothing to inherit.
 */
class MfFactionPrimaryOwnerTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration

    private val founderId = MfPlayerId(UUID.randomUUID().toString())
    private val elderId = MfPlayerId(UUID.randomUUID().toString())
    private val youngerId = MfPlayerId(UUID.randomUUID().toString())
    private val commonerId = MfPlayerId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)
    }

    private fun allowLeaderlessFactions(allowed: Boolean) {
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(allowed)
    }

    private fun defaultRoles() = MfFactionRoles.defaults(plugin, MfFactionId.generate())

    private fun MfFactionRoles.renaming(from: String, to: String) = MfFactionRoles(
        defaultRoleId,
        roles.map { role -> if (role.name == from) role.copy(name = to) else role }
    )

    private fun newFaction(
        roles: MfFactionRoles = defaultRoles(),
        extraMembers: List<MfFactionMember> = emptyList(),
        heirId: MfPlayerId? = null
    ) = MfFaction(
        plugin,
        name = "Test Faction",
        roles = roles,
        members = listOf(MfFactionMember(founderId, roles.leaderRole ?: roles.default, joinedAt = 1_000)) + extraMembers,
        primaryOwnerId = founderId,
        heirId = heirId
    )

    /** Removes a member the way both /f leave and /f kick do: by rewriting the member list. */
    private fun MfFaction.without(playerId: MfPlayerId) =
        copy(members = members.filter { it.playerId != playerId })

    @Test
    fun leaderRoleIsTheRoleGrantedTheRightToDisband() {
        val roles = defaultRoles()

        val leader = roles.leaderRole

        assertNotNull(leader)
        assertEquals("Owner", leader!!.name)
        assertEquals(true, leader.getPermissionValue(plugin.factionPermissions.disband))
    }

    /** /f role rename used to be enough to hide the top role from MF's own lookup. */
    @Test
    fun renamingTheTopRoleDoesNotHideIt() {
        val roles = defaultRoles().renaming("Owner", "Monarch")

        assertEquals("Monarch", roles.leaderRole?.name)
        assertThrows(NoSuchElementException::class.java) { roles.single { it.name == "Owner" } }
    }

    /**
     * The latent crash. Two roles named "Owner" is a state any faction can reach with /f role rename,
     * and `single {}` throws on it rather than choosing.
     */
    @Test
    fun aSecondRoleNamedOwnerDoesNotDisplaceTheTopRole() {
        val roles = defaultRoles()
        val disguised = roles.renaming("Member", "Owner")

        assertEquals(roles.leaderRole?.id, disguised.leaderRole?.id)
        assertThrows(IllegalArgumentException::class.java) { disguised.single { it.name == "Owner" } }
    }

    /** A role set that grants the right to nobody has no top role, rather than a fabricated one. */
    @Test
    fun aRoleSetGrantingNoAuthorityHasNoTopRole() {
        val commoner = MfFactionRole(plugin, MfFactionRoleId.generate(), "Owner", emptyMap())

        assertNull(MfFactionRoles(commoner.id, listOf(commoner)).leaderRole)
    }

    /** What /f create records. */
    @Test
    fun founderIsRecordedAsThePrimaryOwner() {
        assertEquals(founderId, newFaction().primaryOwnerId)
    }

    @Test
    fun renamingRolesDoesNotChangeThePrimaryOwner() {
        val faction = newFaction()

        val renamed = faction.copy(roles = faction.roles.renaming("Owner", "Peasant"))

        assertEquals(founderId, renamed.primaryOwnerId)
        assertEquals(founderId, renamed.withPrimaryOwnerSuccession().primaryOwnerId)
    }

    /** The forgery the field exists to stop: relabelling a junior role does not promote its holder. */
    @Test
    fun aMemberWhoseRoleIsRenamedOwnerIsNotThePrimaryOwner() {
        val disguised = defaultRoles().renaming("Member", "Owner")

        val faction = newFaction(
            roles = disguised,
            extraMembers = listOf(MfFactionMember(commonerId, disguised.default, joinedAt = 2_000))
        )

        assertEquals("Owner", faction.getRole(commonerId)?.name)
        assertEquals(founderId, faction.primaryOwnerId)
        assertNotEquals(commonerId, faction.primaryOwnerId)
    }

    /** What /f transfer and /f admin setleader record. Identity moves; the outgoing head keeps their role. */
    @Test
    fun handingTheFactionOverTransfersThePrimaryOwner() {
        val faction = newFaction()
        val topRole = faction.roles.leaderRole!!

        val transferred = faction.copy(
            members = faction.members + MfFactionMember(elderId, topRole, joinedAt = 2_000),
            primaryOwnerId = elderId
        ).withPrimaryOwnerSuccession()

        assertEquals(elderId, transferred.primaryOwnerId)
        assertNotEquals(founderId, transferred.primaryOwnerId)
        assertEquals(topRole.id, transferred.getRole(founderId)?.id)
    }

    @Test
    fun leavingHandsTheFactionToTheLongestStandingCoLeader() {
        val roles = defaultRoles()
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(youngerId, roles.leaderRole!!, joinedAt = 3_000),
                MfFactionMember(elderId, roles.leaderRole!!, joinedAt = 2_000),
                MfFactionMember(commonerId, roles.default, joinedAt = 1_500)
            )
        )

        assertEquals(elderId, faction.without(founderId).withPrimaryOwnerSuccession().primaryOwnerId)
    }

    @Test
    fun beingKickedHandsTheFactionToTheLongestStandingCoLeader() {
        val roles = defaultRoles()
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(elderId, roles.leaderRole!!, joinedAt = 2_000),
                MfFactionMember(youngerId, roles.leaderRole!!, joinedAt = 3_000)
            )
        )

        // A kick is the same rewrite of the member list, done by somebody else.
        assertEquals(elderId, faction.without(founderId).withPrimaryOwnerSuccession().primaryOwnerId)
    }

    /** An explicit nomination outranks standing and outranks holding the right to disband. */
    @Test
    fun aNominatedHeirInheritsAheadOfAnyCoLeader() {
        val roles = defaultRoles()
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(elderId, roles.leaderRole!!, joinedAt = 2_000),
                MfFactionMember(commonerId, roles.default, joinedAt = 9_000)
            ),
            heirId = commonerId
        )

        val inherited = faction.without(founderId).withPrimaryOwnerSuccession()

        assertEquals(commonerId, inherited.primaryOwnerId)
        // Spent on use, so the new head is not left with themselves nominated.
        assertNull(inherited.heirId)
    }

    @Test
    fun anHeirWhoLeavesFirstIsForgotten() {
        val roles = defaultRoles()
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(elderId, roles.leaderRole!!, joinedAt = 2_000),
                MfFactionMember(commonerId, roles.default, joinedAt = 3_000)
            ),
            heirId = commonerId
        )

        val afterHeirLeaves = faction.without(commonerId).withPrimaryOwnerSuccession()

        assertNull(afterHeirLeaves.heirId)
        assertEquals(founderId, afterHeirLeaves.primaryOwnerId)
        assertEquals(elderId, afterHeirLeaves.without(founderId).withPrimaryOwnerSuccession().primaryOwnerId)
    }

    /** No co-leader to promote, so the most authoritative role available inherits instead. */
    @Test
    fun withNoCoLeaderTheMostAuthoritativeRoleInherits() {
        val roles = defaultRoles()
        val officer = roles.getRole("Officer")!!
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(commonerId, roles.default, joinedAt = 2_000),
                MfFactionMember(elderId, officer, joinedAt = 3_000)
            )
        )

        assertEquals(elderId, faction.without(founderId).withPrimaryOwnerSuccession().primaryOwnerId)
    }

    /** Equal authority, so standing decides, and standing is a recorded join time rather than row order. */
    @Test
    fun equalAuthorityIsBrokenByStanding() {
        val roles = defaultRoles()
        val faction = newFaction(
            roles = roles,
            extraMembers = listOf(
                MfFactionMember(youngerId, roles.default, joinedAt = 5_000),
                MfFactionMember(elderId, roles.default, joinedAt = 4_000)
            )
        )

        assertEquals(elderId, faction.without(founderId).withPrimaryOwnerSuccession().primaryOwnerId)
    }

    /** Leaderless is the last resort, and only where the server has allowed it. */
    @Test
    fun anEmptiedFactionIsLeaderlessWhereTheConfigPermitsIt() {
        allowLeaderlessFactions(true)

        val emptied = newFaction().copy(members = emptyList()).withPrimaryOwnerSuccession()

        assertNull(emptied.primaryOwnerId)
    }

    @Test
    fun anEmptiedFactionIsRefusedWhereTheConfigForbidsLeaderlessFactions() {
        allowLeaderlessFactions(false)

        val emptied = newFaction().copy(members = emptyList())

        assertThrows(NoSuccessorException::class.java) { emptied.withPrimaryOwnerSuccession() }
    }

    /**
     * A faction that never had a head is not a failed succession, so it must stay savable whatever
     * the config says. Otherwise turning the option off would strand every faction created while it
     * was on.
     */
    @Test
    fun aFactionWithNoRecordedHeadIsLeftAloneEitherWay() {
        allowLeaderlessFactions(false)

        val headless = newFaction().copy(members = emptyList(), primaryOwnerId = null)

        assertNull(headless.withPrimaryOwnerSuccession().primaryOwnerId)
    }

    @Test
    fun anotherMemberLeavingDoesNotChangeThePrimaryOwner() {
        val faction = newFaction(
            extraMembers = listOf(MfFactionMember(commonerId, defaultRoles().default, joinedAt = 2_000))
        )

        assertEquals(founderId, faction.without(commonerId).withPrimaryOwnerSuccession().primaryOwnerId)
    }
}
