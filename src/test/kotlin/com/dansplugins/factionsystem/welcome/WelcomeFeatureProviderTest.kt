package com.dansplugins.factionsystem.welcome

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class WelcomeFeatureProviderTest {
    @Test fun selfRealmProjectionRechecksBothNativeAndRolePermissionWithoutForeignRoster() {
        val owner = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        val factions = mock(MfFactionService::class.java)
        val claims = mock(MfClaimService::class.java)
        val permissions = mock(MfFactionPermissions::class.java)
        val info = MfFactionPermission("VIEW_INFO", "View info", true)
        val player = mock(Player::class.java)
        val id = UUID.randomUUID()
        val grants = mutableSetOf("mf.info", "mf.help", "mf.power")
        `when`(player.uniqueId).thenReturn(id)
        `when`(player.hasPermission(anyString())).thenAnswer { it.arguments[0] in grants }
        `when`(owner.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.playerService).thenReturn(mock(MfPlayerService::class.java))
        `when`(owner.factionPermissions).thenReturn(permissions)
        `when`(permissions.viewInfo).thenReturn(info)
        `when`(owner.config).thenReturn(mock(FileConfiguration::class.java))
        val language = mock(Language::class.java) { invocation ->
            if (invocation.method.returnType == String::class.java) invocation.arguments.firstOrNull()?.toString().orEmpty() else null
        }
        `when`(owner.language).thenReturn(language)
        val faction = mock(MfFaction::class.java)
        val role = mock(MfFactionRole::class.java)
        val factionId = MfFactionId("realm-1")
        `when`(factions.getFaction(MfPlayerId(id.toString()))).thenReturn(faction)
        `when`(faction.id).thenReturn(factionId)
        `when`(faction.getRole(MfPlayerId(id.toString()))).thenReturn(role)
        `when`(role.name).thenReturn("Member")
        `when`(role.hasPermission(faction, info)).thenReturn(true)
        `when`(faction.name).thenReturn("Realm")
        `when`(faction.displayName).thenReturn("Realm")
        `when`(faction.description).thenReturn("Your realm description")
        `when`(faction.members).thenReturn(emptyList())
        `when`(claims.getClaimCount(factionId)).thenReturn(7)
        val provider = WelcomeFeatureProvider(owner)
        val current = provider.project(player)
        assertTrue(current.rows().any { it.label() == "Claimed chunks" && it.value() == "7" })
        assertTrue(current.help().none { it.staff() })
        grants -= "mf.power"
        assertTrue(provider.project(player).rows().none { it.label().contains("power", ignoreCase = true) || it.label() == "Claimed chunks" })
        grants += "mf.power"
        `when`(role.hasPermission(faction, info)).thenReturn(false)
        val revoked = provider.project(player)
        assertFalse(revoked.rows().any { it.value() == "Realm" })
        assertTrue(revoked.sections().isEmpty())
        assertTrue(revoked.actions().none { it.command() == "f info" })
        grants += "mf.info.other"
        assertTrue(provider.project(player).rows().any { it.value() == "Realm" })
        grants -= "mf.info"
        assertTrue(provider.project(player).sections().isEmpty())
    }
}
