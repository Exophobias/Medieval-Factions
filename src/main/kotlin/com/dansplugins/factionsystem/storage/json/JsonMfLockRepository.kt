package com.dansplugins.factionsystem.storage.json

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.locks.MfLockedBlock
import com.dansplugins.factionsystem.locks.MfLockedBlockId
import com.google.gson.Gson
import java.util.*

class JsonMfLockRepository(
    private val plugin: MedievalFactions,
    private val storageManager: JsonStorageManager
) : MfLockRepository {

    private val fileName = "locks.json"
    private val gson: Gson = Gson()

    data class LockData(
        val locks: MutableList<MfLockedBlock> = mutableListOf()
    )

    private fun loadData(): LockData =
        storageManager.loadJsonData(fileName, "locks", gson, LockData::class.java) { LockData() }

    private fun saveData(data: LockData) {
        storageManager.writeJsonFile(fileName, data, null)
    }

    override fun getLockedBlock(id: MfLockedBlockId): MfLockedBlock? {
        val data = loadData()
        return data.locks.find { it.id == id }
    }

    override fun getLockedBlock(worldId: UUID, x: Int, y: Int, z: Int): MfLockedBlock? {
        val data = loadData()
        return data.locks.find { it.block.worldId == worldId && it.block.x == x && it.block.y == y && it.block.z == z }
    }

    override fun getLockedBlocks(): List<MfLockedBlock> {
        val data = loadData()
        return data.locks.toList()
    }

    override fun upsert(lockedBlock: MfLockedBlock): MfLockedBlock = storageManager.withFileLock(fileName) {
        val data = loadData()
        val existingIndex = data.locks.indexOfFirst { it.id == lockedBlock.id }

        val updated = if (existingIndex >= 0) {
            val existing = data.locks[existingIndex]
            if (existing.version != lockedBlock.version) {
                throw OptimisticLockingFailureException("Invalid version: ${lockedBlock.version}")
            }
            lockedBlock.copy(version = lockedBlock.version + 1).also { data.locks[existingIndex] = it }
        } else {
            lockedBlock.copy(version = 1).also { data.locks.add(it) }
        }
        saveData(data)
        updated
    }

    override fun delete(lockedBlock: MfLockedBlock) = storageManager.withFileLock(fileName) {
        val data = loadData()
        val removed = data.locks.removeIf { it.id == lockedBlock.id && it.version == lockedBlock.version }
        if (!removed) {
            throw OptimisticLockingFailureException(
                "Locked block ${lockedBlock.id.value} is no longer at version ${lockedBlock.version}"
            )
        }
        saveData(data)
    }
}
