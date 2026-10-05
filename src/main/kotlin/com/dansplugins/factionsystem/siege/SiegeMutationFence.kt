package com.dansplugins.factionsystem.siege

import com.dansplugins.factionsystem.api.SiegeEffectLease
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import com.dansplugins.factionsystem.MedievalFactions
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Effects never wait. Native mutations announce intent before waiting for an admitted effect.
 * Native mutations share this boundary and retain their existing service locks and callback
 * concurrency. An effect owns it exclusively, excluding every native repository/cache mutation.
 * Using a shared native boundary avoids serialising independent precommit callbacks, which may
 * coordinate with each other. A damage callback holding an effect cannot mutate native state.
 */
class SiegeMutationFence {
    val epoch: UUID = UUID.randomUUID()
    private val lock = ReentrantReadWriteLock(true)
    private val writers = AtomicInteger()
    private val healthy = AtomicBoolean(true)

    fun isReadable(): Boolean = healthy.get() && writers.get() == 0 &&
        (!lock.isWriteLocked || lock.isWriteLockedByCurrentThread)

    /** An unacknowledged repository commit may differ from the cache until a fresh runtime loads it. */
    fun poison() { healthy.set(false) }

    fun requireMutationAllowed() {
        check(!lock.isWriteLockedByCurrentThread) { "A native mutation cannot run inside an effect lease" }
    }
    fun requireEffectLease() {
        check(lock.isWriteLockedByCurrentThread) { "Gate activation requires an admitted native effect lease" }
    }

    fun tryAcquire(): SiegeEffectLease? {
        if (!healthy.get() || writers.get() != 0 || lock.readHoldCount != 0 || !lock.writeLock().tryLock()) return null
        if (!healthy.get() || writers.get() != 0) { lock.writeLock().unlock(); return null }
        val owner = Thread.currentThread()
        return object : SiegeEffectLease {
            private var closed = false
            override fun close() {
                check(Thread.currentThread() === owner) { "Effect lease must close on its acquiring thread" }
                if (!closed) { closed = true; lock.writeLock().unlock() }
            }
        }
    }

    fun <T> mutate(nonblocking: Boolean = false, action: () -> T): T {
        requireMutationAllowed()
        writers.incrementAndGet()
        if (nonblocking && !lock.readLock().tryLock()) {
            writers.decrementAndGet()
            throw IllegalStateException("A native transition is in progress; mutation refused without blocking the server thread")
        }
        if (!nonblocking) lock.readLock().lock()
        try { return action() }
        finally { lock.readLock().unlock(); writers.decrementAndGet() }
    }
}

/** Kept outside the mocked plugin surface as well as outside its service construction order. */
internal object NativeFenceRegistry {
    private val fences = ConcurrentHashMap<MedievalFactions, SiegeMutationFence>()
    fun get(plugin: MedievalFactions): SiegeMutationFence = fences.computeIfAbsent(plugin) { SiegeMutationFence() }
}

internal fun <T> MedievalFactions.fenceSiegeMutation(action: () -> T): T =
    NativeFenceRegistry.get(this).mutate(server.isPrimaryThread, action)

/** Wrap only an actual repository mutation, after native validation and cancellable gates. */
internal fun <T> MedievalFactions.commitSiegeRepository(action: () -> T): T = try {
    action()
} catch (conflict: com.dansplugins.factionsystem.failure.OptimisticLockingFailureException) {
    throw conflict
} catch (unsupported: UnsupportedOperationException) {
    // Legacy repositories can explicitly decline a new durable operation before writing anything.
    throw unsupported
} catch (failure: Exception) {
    NativeFenceRegistry.get(this).poison()
    logger.severe("Native siege admission denied until restart: a repository mutation could not be acknowledged")
    throw failure
}
