package com.dansplugins.factionsystem.faction.flag

import com.dansplugins.factionsystem.MedievalFactions
import java.awt.Color
import kotlin.random.Random

class MfFlags(
    private val plugin: MedievalFactions,
    private val flags: MutableList<MfFlag<out Any>> = mutableListOf(
        MfFlag.boolean(
            plugin,
            "alliesCanInteractWithLand",
            plugin.config.getBoolean("factions.defaults.flags.alliesCanInteractWithLand")
        ),
        MfFlag.boolean(
            plugin,
            "vassalageTreeCanInteractWithLand",
            plugin.config.getBoolean("factions.defaults.flags.vassalageTreeCanInteractWithLand")
        ),
        MfFlag.boolean(
            plugin,
            "neutral",
            plugin.config.getBoolean("factions.defaults.flags.neutral")
        ),
        MfFlag.string(
            "color",
            {
                val default = plugin.config.getString("factions.defaults.flags.color") ?: "random"
                if (default == "random") {
                    val color = Color.getHSBColor(Random.nextFloat(), 0.7f + (Random.nextFloat() * 0.3f), 0.3f + (Random.nextFloat() * 0.7f))
                    String.format("#%02x%02x%02x", color.red, color.green, color.blue)
                } else {
                    default
                }
            },
            { value ->
                if (!value.matches(Regex("#[A-Fa-f0-9]{6}"))) {
                    return@string MfFlagValidationFailure(plugin.language["FactionFlagColorValidationFailure"])
                }
                return@string MfFlagValidationSuccess
            }
        ),
        MfFlag.boolean(
            plugin,
            "allowFriendlyFire",
            plugin.config.getBoolean("factions.defaults.flags.allowFriendlyFire")
        ),
        MfFlag.boolean(
            plugin,
            "acceptBonusPower",
            plugin.config.getBoolean("factions.defaults.flags.acceptBonusPower")
        ),
        MfFlag.boolean(
            plugin,
            "enableMobProtection",
            plugin.config.getBoolean("factions.defaults.flags.enableMobProtection")
        ),
        MfFlag.boolean(
            plugin,
            "liegeChainCanInteractWithLand",
            plugin.config.getBoolean("factions.defaults.flags.liegeChainCanInteractWithLand")
        ),
        MfFlag.boolean(
            plugin,
            "protectVillagerTrade",
            plugin.config.getBoolean("factions.defaults.flags.protectVillagerTrade")
        ),
        // A faction's coat of arms, held as whatever opaque identifier the plugin that owns heraldry
        // issues. MedievalFactions does nothing with it: no MF command reads it, no MF listener
        // consults it, and it exists so that a House's arms live on the House rather than in a
        // second per-faction record kept beside MF's. PatriamHeraldry, through PatriamMFAddon, is
        // the consumer.
        //
        // DELIBERATELY NOT VALIDATED AS AN ARMS CODE. MF does not know the codec and must not learn
        // it: the flag is a mirror of a decision taken elsewhere, and a validator here would refuse
        // codes that a later version of that codec issues perfectly legally. The length ceiling is
        // the one thing MF can judge honestly on its own, and it is about MF's own storage rather
        // than about heraldry, since the value goes into the faction's flags JSON column, is echoed
        // back in chat on /f flag set, and is printed by /f flag list.
        //
        // The name is lowercase on purpose, twice over. It is what makes
        // %MedievalFactions_faction_flag_coatofarms% resolve, and it is what makes the permission
        // that guards it read SET_FLAG(coatofarms) rather than a camelCase spelling nobody can type
        // reliably into /f role setpermission.
        //
        // DO NOT REMOVE IT ON THE GROUNDS THAT MF DOES NOT USE IT. Re-adding it later is a database
        // migration, not a one-line edit, because a faction that already exists can never have the
        // permission to set it granted to any of its roles:
        //
        //  - MfFactionRoles.defaults walks this list once, at creation, and gives the Owner role a
        //    SET_FLAG(x) grant for every flag registered at that moment, plus the matching
        //    SET_ROLE_PERMISSION(SET_FLAG(x)) that lets Owner hand it on. A faction created before
        //    this line existed has neither for coatofarms.
        //  - MfFaction.defaultPermissionsByName is the same kind of snapshot, persisted to the
        //    faction's default_permissions JSON column, so it has no SET_FLAG(coatofarms) key
        //    either.
        //
        // MfFactionRole.hasPermission resolves role grant, then faction default, then the
        // permission's own default. All three miss and SetFlag's default is false, so
        // /f role setpermission refuses the grant for want of
        // SET_ROLE_PERMISSION(SET_FLAG(coatofarms)), and that command has no operator bypass.
        //
        // The VALUE is reachable on such a faction regardless: MfFlagValues.get falls back to the
        // flag's default for a missing key, and an operator holding mf.force.flag can set another
        // faction's flags outright. It is the delegation inside the faction that is lost. This
        // landed while the Patriam database still held one faction and was about to be wiped, so
        // nothing was stranded; that window is closed now, so treat this line as load-bearing.
        MfFlag.string(
            "coatofarms",
            { plugin.config.getString("factions.defaults.flags.coatofarms") ?: "" },
            { value ->
                if (value.length > COAT_OF_ARMS_MAX_LENGTH) {
                    return@string MfFlagValidationFailure(
                        plugin.language["FactionFlagCoatOfArmsValidationFailure", COAT_OF_ARMS_MAX_LENGTH.toString()]
                    )
                }
                return@string MfFlagValidationSuccess
            }
        )
    )
) : MutableList<MfFlag<out Any>> by flags {

    operator fun <T : Any> get(name: String) = singleOrNull { it.name.equals(name, ignoreCase = true) } as? MfFlag<T>

    val alliesCanInteractWithLand = get<Boolean>("alliesCanInteractWithLand")!!
    val vassalageTreeCanInteractWithLand = get<Boolean>("vassalageTreeCanInteractWithLand")!!
    val isNeutral = get<Boolean>("neutral")!!
    val color = get<String>("color")!!
    val allowFriendlyFire = get<Boolean>("allowFriendlyFire")!!
    val acceptBonusPower = get<Boolean>("acceptBonusPower")!!
    val enableMobProtection = get<Boolean>("enableMobProtection")!!
    val liegeChainCanInteractWithLand = get<Boolean>("liegeChainCanInteractWithLand")!!
    val protectVillagerTrade = get<Boolean>("protectVillagerTrade")!!

    /** See the registration comment above: MF never reads this, the heraldry plugin does. */
    val coatOfArms = get<String>("coatofarms")!!

    fun defaults() = MfFlagValues(plugin, flags.associate { it.name to it.defaultValue })

    companion object {
        /**
         * The longest value the coatofarms flag will accept.
         *
         * A ceiling on MF's own storage and display rather than a statement about any arms code
         * format. The widest code PatriamHeraldry can currently issue is well under half of this, so
         * the limit is generous enough that a future codec change is unlikely to meet it, while still
         * stopping a pasted essay from going into a faction row and out through /f flag list.
         */
        const val COAT_OF_ARMS_MAX_LENGTH = 64
    }
}
