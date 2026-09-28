package com.dansplugins.factionsystem.api

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MfApiServerTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var uut: MfApiServer

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        uut = MfApiServer(plugin)
    }

    @AfterEach
    fun tearDown() {
        uut.stop()
    }

    @Test
    fun enabledApiServesHealthAndClaimsAndReleasesItsPort() {
        val port = ServerSocket(0).use { it.localPort }
        enableApi(port)
        uut.start()

        val health = request(port, "/api/health")
        assertEquals(200, health.statusCode())
        assertTrue(health.body().contains("healthy"))
        assertTrue(health.body().contains("5.9.0-test"))
        val claims = request(port, "/api/claims")
        assertEquals(200, claims.statusCode())
        assertEquals("[]", claims.body())

        uut.stop()
        uut.stop()
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress("127.0.0.1", port))
        }
    }

    @Test
    fun failedBindIsCleanedUpAndTheApiCanStartAfterThePortIsReleased() {
        val port = ServerSocket(0).use { occupied ->
            enableApi(occupied.localPort)
            uut.start()
            occupied.localPort
        }
        uut.start()
        assertEquals(200, request(port, "/api/health").statusCode())
    }

    private fun enableApi(port: Int) {
        val services = mock(Services::class.java)
        val claims = mock(MfClaimService::class.java)
        `when`(services.claimService).thenReturn(claims)
        `when`(claims.claims).thenReturn(emptyList())
        `when`(services.factionService).thenReturn(mock(MfFactionService::class.java))
        `when`(services.playerService).thenReturn(mock(MfPlayerService::class.java))
        `when`(services.factionRelationshipService).thenReturn(mock(MfFactionRelationshipService::class.java))
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.description).thenReturn(PluginDescriptionFile("MedievalFactions", "5.9.0-test", "example.Plugin"))
        `when`(config.getBoolean("api.enabled", false)).thenReturn(true)
        `when`(config.getInt("api.port", 8080)).thenReturn(port)
        `when`(config.getString("api.host", "127.0.0.1")).thenReturn("127.0.0.1")
    }

    private fun request(port: Int, path: String): HttpResponse<String> =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString()
        )

    @Test
    fun start_ShouldTreatApiAsOptInWhenEnabledKeyIsAbsent() {
        // A mocked FileConfiguration returns false for every getBoolean call, which is exactly
        // what a real one returns for a key the server's config.yml does not contain yet.
        uut.start()

        // The fallback passed to getBoolean must be false, matching the shipped config.yml.
        verify(config).getBoolean("api.enabled", false)
        // A disabled API never reads its bind settings, so no socket can have been opened.
        verify(config, never()).getInt(anyString(), anyInt())
        verify(config, never()).getString(anyString(), anyString())
    }
}
