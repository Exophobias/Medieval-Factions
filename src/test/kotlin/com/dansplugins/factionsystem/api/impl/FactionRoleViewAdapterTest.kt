package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionPermission
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.flag.MfFlag
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Covers the API-to-internal permission mapping in [FactionRoleViewAdapter].
 *
 * The compiler already proves the mapping is total, because the `when` over [FactionPermission] is
 * exhaustive and will not build with a constant missing. What it cannot prove is that each constant
 * is mapped to the *right* internal permission: mapping MANAGE_SHOPS to `permissions.setHome` by
 * copy-paste compiles perfectly and hands a shopkeeper's authority to whoever may set the home.
 *
 * These tests close that hole by giving every internal permission a distinct identity and asserting
 * that granting exactly one of them lights up exactly one API constant.
 */
class FactionRoleViewAdapterTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var faction: MfFaction

    /**
     * One internal permission per API constant, named identically to it.
     *
     * The names do not have to match MF's real ones, and for SET_COAT_OF_ARMS they do not: MF spells
     * that one `SET_FLAG(coatofarms)`. All the fixture needs is a distinct identity per constant, since
     * the adapter never reads these names and only follows its own `when`, so a mis-wired branch still
     * shows up as the wrong constant answering.
     */
    private val internals = FactionPermission.values().associateWith { MfFactionPermission(it.name, it.name, false) }

    private fun internalFor(permission: FactionPermission) = internals.getValue(permission)

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val permissions = mock(MfFactionPermissions::class.java)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        // MfFactionRole resolves its stored names back to permissions through parse().
        internals.forEach { (apiPermission, internal) ->
            `when`(permissions.parse(apiPermission.name)).thenReturn(internal)
        }

        // Every accessor the adapter's `when` can reach. Listed one by one on purpose: if a future
        // constant is added and mapped to an accessor that is already spoken for, the uniqueness
        // assertion below fails rather than the fixture quietly agreeing with the mistake.
        `when`(permissions.disband).thenReturn(internalFor(FactionPermission.DISBAND))
        `when`(permissions.changeName).thenReturn(internalFor(FactionPermission.CHANGE_NAME))
        `when`(permissions.changeDescription).thenReturn(internalFor(FactionPermission.CHANGE_DESCRIPTION))
        `when`(permissions.changePrefix).thenReturn(internalFor(FactionPermission.CHANGE_PREFIX))
        `when`(permissions.setDefaultRole).thenReturn(internalFor(FactionPermission.SET_DEFAULT_ROLE))
        `when`(permissions.createRole).thenReturn(internalFor(FactionPermission.CREATE_ROLE))
        `when`(permissions.claim).thenReturn(internalFor(FactionPermission.CLAIM))
        `when`(permissions.unclaim).thenReturn(internalFor(FactionPermission.UNCLAIM))
        `when`(permissions.setHome).thenReturn(internalFor(FactionPermission.SET_HOME))
        `when`(permissions.kick).thenReturn(internalFor(FactionPermission.KICK))
        `when`(permissions.approveApp).thenReturn(internalFor(FactionPermission.APPROVE_APP))
        `when`(permissions.denyApp).thenReturn(internalFor(FactionPermission.DENY_APP))
        `when`(permissions.declareWar).thenReturn(internalFor(FactionPermission.DECLARE_WAR))
        `when`(permissions.makePeace).thenReturn(internalFor(FactionPermission.MAKE_PEACE))
        `when`(permissions.manageShops).thenReturn(internalFor(FactionPermission.MANAGE_SHOPS))
        // The arms gate is MF's parameterised SET_FLAG permission over one fixed flag, so the fixture
        // has to supply the flag as well as the permission.
        val flags = mock(MfFlags::class.java)
        `when`(plugin.flags).thenReturn(flags)
        val coatOfArms = MfFlag.string("coatofarms", "")
        `when`(flags.coatOfArms).thenReturn(coatOfArms)
        `when`(permissions.setFlag(coatOfArms)).thenReturn(internalFor(FactionPermission.SET_COAT_OF_ARMS))

        faction = mock(MfFaction::class.java)
    }

    private fun roleGranting(vararg granted: FactionPermission, name: String = "Test") =
        MfFactionRole(plugin, MfFactionRoleId.generate(), name, granted.associate { it.name to true })

    private fun view(role: MfFactionRole) = FactionRoleViewAdapter(plugin, faction, role)

    /**
     * The uniqueness property, stated as a test. Granting one internal permission must answer true for
     * exactly one API constant and false for all the others.
     */
    @Test
    fun eachApiPermissionMapsToItsOwnInternalPermission() {
        FactionPermission.values().forEach { permission ->
            val view = view(roleGranting(permission))
            assertTrue(
                view.hasPermission(permission),
                "$permission was granted but the adapter did not report it"
            )
            FactionPermission.values().filter { it != permission }.forEach { other ->
                assertFalse(
                    view.hasPermission(other),
                    "granting $permission also reported $other, so the two share an internal mapping"
                )
            }
        }
    }

    @Test
    fun manageShopsIsDeniedByDefault() {
        assertFalse(view(roleGranting()).hasPermission(FactionPermission.MANAGE_SHOPS))
    }

    /**
     * The reason PatriamEconomy asks MF at all instead of keeping its own list of who may trade. A
     * faction can rename a role to anything, so the name is not evidence of anything; the grant is.
     */
    @Test
    fun aRoleCalledMerchantHoldsNoShopAuthorityByVirtueOfTheName() {
        val view = view(roleGranting(name = "Merchant"))

        assertFalse(view.hasPermission(FactionPermission.MANAGE_SHOPS))
    }

    /**
     * MF resolves an unset permission against the faction's own defaults before the permission's
     * built-in default, so a faction that opens trade to everybody must be honoured.
     */
    @Test
    fun manageShopsFallsBackToTheFactionDefault() {
        `when`(faction.defaultPermissions).thenReturn(mapOf(internalFor(FactionPermission.MANAGE_SHOPS) to true))

        assertTrue(view(roleGranting()).hasPermission(FactionPermission.MANAGE_SHOPS))
    }

    /** A faction-wide default must not leak across permissions. */
    @Test
    fun aFactionDefaultOnAnotherPermissionDoesNotOpenTheShops() {
        `when`(faction.defaultPermissions).thenReturn(mapOf(internalFor(FactionPermission.SET_HOME) to true))

        val view = view(roleGranting())

        assertTrue(view.hasPermission(FactionPermission.SET_HOME))
        assertFalse(view.hasPermission(FactionPermission.MANAGE_SHOPS))
    }

    @Test
    fun theRoleNameIsCarriedThroughUnchanged() {
        assertTrue(view(roleGranting(name = "Guildmaster")).name == "Guildmaster")
    }

    // --- the arms gate ---

    @Test
    fun theArmsGateIsDeniedByDefault() {
        assertFalse(view(roleGranting()).hasPermission(FactionPermission.SET_COAT_OF_ARMS))
    }

    /**
     * The delegation the constant exists for: a role that has been granted it may set the arms, and
     * granting it says nothing about any other authority. That is the whole improvement on gating arms
     * behind DISBAND or CHANGE_PREFIX, which is what a consumer had to do while no purpose-built
     * permission existed.
     */
    @Test
    fun aRoleGrantedTheArmsGateHoldsNothingElse() {
        val view = view(roleGranting(FactionPermission.SET_COAT_OF_ARMS, name = "Herald"))

        assertTrue(view.hasPermission(FactionPermission.SET_COAT_OF_ARMS))
        assertFalse(view.hasPermission(FactionPermission.DISBAND))
        assertFalse(view.hasPermission(FactionPermission.CHANGE_PREFIX))
    }

    /** And the reverse: the terminal authority does not carry the arms with it. */
    @Test
    fun aRoleThatMayDisbandTheFactionDoesNotTherebyHoldTheArms() {
        assertFalse(view(roleGranting(FactionPermission.DISBAND)).hasPermission(FactionPermission.SET_COAT_OF_ARMS))
    }

    /** A faction may open the arms to every role at once, as it may any other permission. */
    @Test
    fun theArmsGateFallsBackToTheFactionDefault() {
        `when`(faction.defaultPermissions)
            .thenReturn(mapOf(internalFor(FactionPermission.SET_COAT_OF_ARMS) to true))

        assertTrue(view(roleGranting()).hasPermission(FactionPermission.SET_COAT_OF_ARMS))
    }
}
