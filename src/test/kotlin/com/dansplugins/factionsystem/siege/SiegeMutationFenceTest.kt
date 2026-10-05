package com.dansplugins.factionsystem.siege

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SiegeMutationFenceTest {
    @Test fun independentNativeCallbacksRemainConcurrentWhileEffectsAreExcluded() {
        val fence = SiegeMutationFence()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val mutations = (1..2).map {
                workers.submit { fence.mutate {
                    entered.countDown()
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    assertNull(fence.tryAcquire())
                    assertTrue(release.await(1, TimeUnit.SECONDS))
                } }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(fence.tryAcquire())
            release.countDown()
            mutations.forEach { it.get(1, TimeUnit.SECONDS) }
            requireNotNull(fence.tryAcquire()).close()
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun pendingTransitionRefusesNewEffectsAndWaitsForAdmittedBatch() {
        val fence = SiegeMutationFence()
        val effect = requireNotNull(fence.tryAcquire())
        val started = CountDownLatch(1); val committed = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit { started.countDown(); fence.mutate { committed.countDown() } }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // Waiting for the writer-intent observation is bounded; it is not a timing sleep.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (true) {
                val probe = fence.tryAcquire()
                if (probe == null) break
                probe.close()
                assertTrue(System.nanoTime() < deadline)
                Thread.yield()
            }
            assertEquals(1L, committed.count)
            effect.close(); effect.close()
            future.get(1, TimeUnit.SECONDS)
            assertEquals(0L, committed.count)
            requireNotNull(fence.tryAcquire()).close()
        } finally {effect.close();worker.shutdownNow()}
    }
    @Test fun reentrantNativeMutationCannotCommitDuringDamageEvents() {
        val fence = SiegeMutationFence(); var wrote = false
        requireNotNull(fence.tryAcquire()).use {
            assertThrows(IllegalStateException::class.java) {fence.mutate {wrote=true}}
        }
        assertFalse(wrote)
        fence.mutate {wrote=true};assertTrue(wrote)
    }
    @Test fun failedWriteReleasesPendingIntentAndEpochIsPerRuntime() {
        val fence = SiegeMutationFence()
        assertThrows(IllegalArgumentException::class.java) {fence.mutate {throw IllegalArgumentException("write failed")}}
        requireNotNull(fence.tryAcquire()).close()
        assertNotEquals(fence.epoch, SiegeMutationFence().epoch)
    }
}
