package com.dansplugins.factionsystem.faction.flag

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.flag.MfFlags.Companion.COAT_OF_ARMS_MAX_LENGTH
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import org.bukkit.configuration.file.FileConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Covers the coatofarms flag: that it is registered, that MedievalFactions does not pretend to
 * understand its value, and that a faction founded today can delegate the right to change it.
 *
 * The last of those is the point of the whole thing and the reason it had to land before the database
 * held factions worth keeping. Both grant lists a faction needs are snapshots taken at creation, so a
 * faction older than the flag can never be given the permission by anybody, operator included. These
 * tests assert the grants are present on a freshly founded faction, which is the only situation in
 * which they ever can be.
 *
 * Built over the real [MfFlags] and [MfFactionPermissions] rather than stand-ins, for the same reason
 * [com.dansplugins.factionsystem.faction.MfFactionPrimaryOwnerTest] is: the thing under test is what
 * the plugin actually hands a founder.
 */
class MfCoatOfArmsFlagTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var flags: MfFlags

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        // Built into a local first. Constructing it inside the when() argument reads plugin.language
        // while that stubbing is still open, which Mockito rejects.
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)
    }

    /**
     * The name is all lowercase deliberately. It is what lets
     * %MedievalFactions_faction_flag_coatofarms% resolve, since PlaceholderAPI hands the expansion a
     * lowercased parameter, and it is what keeps the guarding permission typeable as
     * SET_FLAG(coatofarms).
     */
    @Test
    fun theFlagIsRegisteredUnderAnAllLowercaseName() {
        assertNotNull(flags.get<String>("coatofarms"))
        assertEquals("coatofarms", flags.coatOfArms.name)
        assertEquals(flags.coatOfArms.name.lowercase(), flags.coatOfArms.name)
    }

    /** A new faction bears no arms until somebody gives it some. */
    @Test
    fun theDefaultIsEmpty() {
        assertEquals("", flags.coatOfArms.defaultValue)
    }

    @Test
    fun theDefaultCanBeSetInTheConfig() {
        `when`(config.getString("factions.defaults.flags.coatofarms")).thenReturn("2CJW-634K-M")

        assertEquals("2CJW-634K-M", flags.coatOfArms.defaultValue)
    }

    /**
     * MF does not know the arms codec and must not learn it. A value it cannot parse is still a value
     * it must store, because the flag mirrors a decision taken in another plugin whose format may
     * change without MF being rebuilt.
     */
    @Test
    fun anythingWithinTheCeilingIsAccepted() {
        listOf("2CJW-634K-M", "208E-QWR", "", "not an arms code at all", "0")
            .forEach { value ->
                assertTrue(
                    flags.coatOfArms.validate(value) is MfFlagValidationSuccess,
                    "'$value' was refused, so MF is judging the value's format rather than its length"
                )
            }
    }

    @Test
    fun theCeilingItselfIsAccepted() {
        val atTheLimit = "A".repeat(COAT_OF_ARMS_MAX_LENGTH)

        assertTrue(flags.coatOfArms.validate(atTheLimit) is MfFlagValidationSuccess)
    }

    /** The one thing MF can judge on its own. This is about MF's storage, not about heraldry. */
    @Test
    fun anythingLongerThanTheCeilingIsRefused() {
        val overTheLimit = "A".repeat(COAT_OF_ARMS_MAX_LENGTH + 1)

        assertTrue(flags.coatOfArms.validate(overTheLimit) is MfFlagValidationFailure)
    }

    @Test
    fun thePermissionThatGuardsItIsNamedForTheFlag() {
        val permission = plugin.factionPermissions.setFlag(flags.coatOfArms)

        assertEquals("SET_FLAG(coatofarms)", permission.name)
        assertEquals(permission, plugin.factionPermissions.parse("SET_FLAG(coatofarms)"))
    }

    /**
     * The founding Owner may set the arms, for the same reason it may claim land: a new House should
     * not have to discover a permission it did not know it needed.
     */
    @Test
    fun aFoundingOwnerMaySetTheArms() {
        val faction = newFaction()
        val owner = faction.getRole("Owner")

        assertNotNull(owner)
        assertEquals(
            true,
            owner!!.getPermissionValue(plugin.factionPermissions.setFlag(flags.coatOfArms))
        )
    }

    /**
     * The delegation this exists for. A ruler must be able to hand "may change this House's arms" to
     * an officer through /f role setpermission, and that command requires the granter to hold
     * SET_ROLE_PERMISSION over the permission being granted.
     */
    @Test
    fun aFoundingOwnerMayDelegateTheArmsToAnotherRole() {
        val faction = newFaction()
        val owner = faction.getRole("Owner")
        val delegable = plugin.factionPermissions.setRolePermission(
            plugin.factionPermissions.setFlag(flags.coatOfArms)
        )

        assertNotNull(owner)
        assertEquals(true, owner!!.getPermissionValue(delegable))
    }

    /**
     * The second of the two creation-time snapshots described on the flag's registration. A faction's
     * default_permissions column is written once, from the permission list as it stands at creation,
     * and a faction whose column has no key for this permission has no route to one afterwards.
     */
    @Test
    fun theFactionsOwnDefaultsCarryAKeyForIt() {
        val faction = newFaction()

        assertTrue(
            faction.defaultPermissionsByName.containsKey("SET_FLAG(coatofarms)"),
            "a faction founded now must carry the key, or the permission is unreachable forever"
        )
        assertTrue(
            faction.defaultPermissionsByName.containsKey("SET_ROLE_PERMISSION(SET_FLAG(coatofarms))"),
            "without this key no role can ever be granted the arms permission"
        )
    }

    /** No role but Owner starts with it. Delegating it has to be a deliberate act. */
    @Test
    fun theOrdinaryRolesDoNotStartWithIt() {
        val faction = newFaction()
        val permission = plugin.factionPermissions.setFlag(flags.coatOfArms)

        listOf("Officer", "Member").forEach { roleName ->
            val role = faction.getRole(roleName)
            assertNotNull(role, "$roleName is missing from MF's default roles")
            assertEquals(
                false,
                role!!.hasPermission(faction, permission),
                "$roleName may set the arms without anybody having granted it"
            )
        }
    }

    private fun newFaction(): MfFaction {
        val factionId = MfFactionId.generate()
        return MfFaction(plugin, id = factionId, name = "Test Faction", roles = MfFactionRoles.defaults(plugin, factionId))
    }
}
