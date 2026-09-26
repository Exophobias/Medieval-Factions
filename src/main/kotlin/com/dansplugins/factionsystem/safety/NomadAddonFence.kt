package com.dansplugins.factionsystem.safety

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * MF-owned latch for a faction subtype whose authoritative records live in another plugin.
 * It deliberately remains after the last Nomad disbands. Removing it needs an audited procedure
 * that can prove every former Nomad identity and camp record was retired; an absent directory
 * alone is never proof of that.
 */
class NomadAddonFence(mfDataFolder: Path) {
    private val mfFolder = mfDataFolder.toAbsolutePath()
    private val pluginFolder = mfFolder.parent ?: throw IllegalArgumentException("MF data folder has no parent")
    private val marker = mfFolder.resolve("nomad-addon-required.marker")
    private val campFolder = pluginFolder.resolve("PatriamNomads").resolve("camps")
    private val identities = campFolder.resolve("identities.manifest")

    /** Any marker entry, including a corrupt file or link, means MF must fail closed. */
    fun markerPresent(): Boolean = try {
        Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    } catch (_: IOException) {
        true // An unreadable marker is never proof that the guard was unarmed.
    }

    @Throws(IOException::class)
    fun armed(): Boolean {
        if (!markerPresent()) return false
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)
            || Files.size(marker) != MARKER_BYTES.size.toLong()
            || !Files.readAllBytes(marker).contentEquals(MARKER_BYTES)) {
            throw IOException("Nomad addon marker is invalid")
        }
        return true
    }

    fun campDirectoryPresent(): Boolean = Files.isDirectory(campFolder, NOFOLLOW_LINKS)

    fun identitiesPresent(): Boolean = Files.isRegularFile(identities, NOFOLLOW_LINKS)

    /** Null means no startup objection. Enabled state is checked after all plugins enable. */
    @Throws(IOException::class)
    fun startupIssue(pluginRegistered: Boolean, pluginEnabled: Boolean? = null): String? {
        if (!armed()) return null
        return when {
            !pluginRegistered -> "PatriamNomads is missing"
            pluginEnabled == false -> "PatriamNomads did not enable"
            !campDirectoryPresent() -> "PatriamNomads camp data directory is missing"
            !identitiesPresent() -> "PatriamNomads camp identity manifest is missing"
            else -> null
        }
    }

    /** Marker commit must precede Nomads' first camp record write. */
    @Synchronized
    @Throws(IOException::class)
    fun arm() {
        if (!campDirectoryPresent()) throw IOException("Nomad camp directory is unavailable")
        if (!identitiesPresent()) throw IOException("Nomad camp identity manifest is unavailable")
        if (armed()) return
        Files.createDirectories(mfFolder)
        val candidate = Files.createTempFile(mfFolder, ".nomad-addon-required-", ".new")
        try {
            FileChannel.open(candidate, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(MARKER_BYTES)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(candidate, marker, ATOMIC_MOVE)
            FileChannel.open(mfFolder, READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(candidate)
        }
    }

    companion object {
        private val MARKER_BYTES = "PATRIAM-NOMADS-REQUIRED-1\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
