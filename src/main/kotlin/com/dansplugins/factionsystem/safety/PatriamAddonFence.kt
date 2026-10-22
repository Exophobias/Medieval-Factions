package com.dansplugins.factionsystem.safety

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE

/** Durable latch: an absent or damaged authority must never reopen Patriam gameplay gates. */
class PatriamAddonFence(private val folder: Path) {
    private val marker = folder.resolve("patriam-governance-required.marker")

    fun required(): Boolean = Files.exists(marker, NOFOLLOW_LINKS) || !Files.notExists(marker, NOFOLLOW_LINKS)

    @Synchronized
    @Throws(IOException::class)
    fun arm() {
        if (required()) {
            if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
                !Files.readAllBytes(marker).contentEquals(CONTENT)
            ) {
                throw IOException("Patriam governance marker is invalid")
            }
            return
        }
        Files.createDirectories(folder)
        val candidate = Files.createTempFile(folder, ".patriam-governance-", ".new")
        try {
            FileChannel.open(candidate, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(CONTENT)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(candidate, marker, ATOMIC_MOVE)
            // Directory forcing persists the rename on filesystems that expose directory channels.
            // Windows denies opening directories; the forced candidate plus atomic move remains its
            // supported protocol, matching the native configuration writers.
            try {
                FileChannel.open(folder, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            } catch (failure: java.nio.file.AccessDeniedException) {
                if (!System.getProperty("os.name").lowercase().contains("windows")) throw failure
            } catch (failure: UnsupportedOperationException) {
                if (!System.getProperty("os.name").lowercase().contains("windows")) throw failure
            }
            check(Files.readAllBytes(marker).contentEquals(CONTENT))
        } finally {
            Files.deleteIfExists(candidate)
        }
    }

    companion object {
        private val CONTENT = "PATRIAM-GOVERNANCE-REQUIRED-1\n".toByteArray(Charsets.US_ASCII)
    }
}
