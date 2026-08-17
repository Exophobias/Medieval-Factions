package com.dansplugins.factionsystem.locks

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.locks.MfUnlockResult.FAILURE
import com.dansplugins.factionsystem.locks.MfUnlockResult.NOT_LOCKED
import com.dansplugins.factionsystem.locks.MfUnlockResult.SUCCESS
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import org.bukkit.block.Block
import org.bukkit.block.BlockFace.DOWN
import org.bukkit.block.BlockFace.UP
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Bisected.Half.BOTTOM
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level.SEVERE
import kotlin.concurrent.withLock

class MfLockService(private val plugin: MedievalFactions, private val repository: MfLockRepository) {

    private val lockedBlocks: MutableMap<MfBlockPosition, MfLockedBlock> = ConcurrentHashMap()

    /** Serialises repository writes with cache publication and claim-cascade eviction. */
    private val mutationLock = ReentrantLock(true)

    /** Coordinate a claim-owner commit with durable/cache lock eviction. */
    internal fun <T> withMutationLock(action: () -> T): T = mutationLock.withLock(action)

    init {
        plugin.logger.info("Loading locked blocks...")
        val startTime = System.currentTimeMillis()
        lockedBlocks.putAll(repository.getLockedBlocks().map { it.block to it })
        plugin.logger.info("${lockedBlocks.size} locked blocks loaded (${System.currentTimeMillis() - startTime}ms)")
    }

    fun lock(block: MfBlockPosition, claim: MfClaimedChunk, player: MfPlayer): Result4k<MfLockedBlock, ServiceFailure> =
        mutationLock.withLock {
            resultFrom {
                val liveClaim = plugin.services.claimService.getClaim(claim.worldId, claim.x, claim.z)
                require(liveClaim?.factionId == claim.factionId) {
                    "Claim ownership changed before the block could be locked"
                }
                val liveFaction = plugin.services.factionService.getFaction(player.id)
                require(liveFaction?.id == claim.factionId) {
                    "Player ${player.id.value} is no longer a member of the claiming faction"
                }
                val lockedBlock = repository.upsert(
                    MfLockedBlock(
                        block = MfBlockPosition(
                            worldId = block.worldId,
                            x = block.x,
                            y = block.y,
                            z = block.z
                        ),
                        chunkX = claim.x,
                        chunkZ = claim.z,
                        playerId = player.id,
                        accessors = emptyList()
                    )
                )
                lockedBlocks[block] = lockedBlock
                return@resultFrom lockedBlock
            }.mapFailure { exception ->
                ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
            }
        }

    fun unlock(block: Block, callback: (result: MfUnlockResult) -> Unit) {
        val blockData = block.blockData
        val holder = (block.state as? Chest)?.inventory?.holder
        val blocks = if (blockData is Bisected) {
            if (blockData.half == BOTTOM) {
                listOf(block, block.getRelative(UP))
            } else {
                listOf(block, block.getRelative(DOWN))
            }
        } else if (holder is DoubleChest) {
            val left = holder.leftSide as? Chest
            val right = holder.rightSide as? Chest
            listOfNotNull(left?.block, right?.block)
        } else {
            listOf(block)
        }
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val lockedBlocks = blocks.mapNotNull { getLockedBlock(MfBlockPosition.fromBukkitBlock(it)) }
                val lockedBlock = lockedBlocks.firstOrNull()
                if (lockedBlock == null) {
                    callback(NOT_LOCKED)
                    return@Runnable
                }
                delete(lockedBlock).onFailure {
                    plugin.logger.log(SEVERE, "Failed to delete block: ${it.reason.message}", it.reason.cause)
                    callback(FAILURE)
                    return@Runnable
                }
                callback(SUCCESS)
            }
        )
    }

    fun save(lockedBlock: MfLockedBlock): Result4k<MfLockedBlock, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val current = lockedBlocks[lockedBlock.block]
            if (lockedBlock.version == 0) {
                require(current == null) { "Block is already locked" }
            } else {
                require(current?.id == lockedBlock.id && current.version == lockedBlock.version) {
                    "Locked block ${lockedBlock.id.value} is stale or was removed"
                }
            }
            requireStillAuthorised(lockedBlock)
            val upsertedLockedBlock = repository.upsert(lockedBlock)
            current?.let { lockedBlocks.remove(it.block, it) }
            lockedBlocks[upsertedLockedBlock.block] = upsertedLockedBlock
            return@resultFrom upsertedLockedBlock
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    fun delete(lockedBlock: MfLockedBlock): Result4k<Unit, ServiceFailure> = mutationLock.withLock {
        resultFrom {
            val current = lockedBlocks[lockedBlock.block]
            require(current?.id == lockedBlock.id && current.version == lockedBlock.version) {
                "Locked block ${lockedBlock.id.value} is stale or was replaced"
            }
            repository.delete(lockedBlock)
            check(lockedBlocks.remove(lockedBlock.block, current)) {
                "Locked block changed while it was being deleted"
            }
            return@resultFrom
        }.mapFailure { exception ->
            ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
        }
    }

    fun getLockedBlock(block: MfBlockPosition): MfLockedBlock? = mutationLock.withLock {
        lockedBlocks[block]
    }

    @JvmName("getLockedBlockByLockedBlockId")
    fun getLockedBlock(id: MfLockedBlockId): MfLockedBlock? = mutationLock.withLock {
        lockedBlocks.values.singleOrNull { it.id == id }
    }

    @JvmName("getLockedBlocksByPlayerId")
    fun getLockedBlocks(playerId: MfPlayerId): List<MfLockedBlock> = mutationLock.withLock {
        lockedBlocks.values.filter { it.playerId == playerId }
    }

    @JvmName("getLockedBlocksByClaim")
    fun getLockedBlocks(claim: MfClaimedChunk): List<MfLockedBlock> = mutationLock.withLock {
        lockedBlocks.values.filter {
            it.block.worldId == claim.worldId &&
                it.chunkX == claim.x &&
                it.chunkZ == claim.z
        }
    }

    internal fun unloadLockedBlocks(claim: MfClaimedChunk) = mutationLock.withLock {
        getLockedBlocks(claim).forEach { lockedBlocks.remove(it.block) }
    }

    /** Remove cached locks for many cascaded claims with one pass over the lock cache. */
    internal fun unloadLockedBlocks(claims: Collection<MfClaimedChunk>) = mutationLock.withLock {
        if (claims.isEmpty()) return@withLock
        val chunks = claims.mapTo(HashSet()) { Triple(it.worldId, it.x, it.z) }
        lockedBlocks.entries.removeIf { (_, lock) ->
            Triple(lock.block.worldId, lock.chunkX, lock.chunkZ) in chunks
        }
        Unit
    }

    /** Mirror the lock rows removed atomically with a committed faction-member departure. */
    internal fun unloadLockedBlocks(playerIds: Set<MfPlayerId>) = mutationLock.withLock {
        if (playerIds.isEmpty()) return@withLock
        lockedBlocks.entries.removeIf { (_, lock) -> lock.playerId in playerIds }
        Unit
    }

    private fun requireStillAuthorised(lockedBlock: MfLockedBlock) {
        val claim = plugin.services.claimService.getClaim(
            lockedBlock.block.worldId,
            lockedBlock.chunkX,
            lockedBlock.chunkZ
        )
        requireNotNull(claim) { "The locked block's claim no longer exists" }
        val ownerFaction = plugin.services.factionService.getFaction(lockedBlock.playerId)
        require(ownerFaction?.id == claim.factionId) {
            "The locked block owner is no longer a member of the claiming faction"
        }
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }
}
