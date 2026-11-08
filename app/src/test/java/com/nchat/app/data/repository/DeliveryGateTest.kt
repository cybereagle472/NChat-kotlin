package com.nchat.app.data.repository

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryGateTest {
    @Test
    fun oneAttemptPerMessageButDifferentMessagesCanProceed() {
        val gate = DeliveryGate()
        assertTrue(gate.tryAcquire("a"))
        assertFalse(gate.tryAcquire("a"))
        assertTrue(gate.tryAcquire("b"))
        gate.release("a")
        assertTrue(gate.tryAcquire("a"))
        gate.release("a")
        gate.release("b")
    }

    @Test
    fun concurrentAttemptCannotEnterWhileAnotherHoldsTheId() {
        val gate = DeliveryGate()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit<Boolean> {
                if (!gate.tryAcquire("message")) return@submit false
                try {
                    ready.countDown()
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    gate.release("message")
                }
                true
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertFalse(gate.tryAcquire("message"))
            release.countDown()
            assertTrue(running.get(5, TimeUnit.SECONDS))
            assertTrue(gate.tryAcquire("message"))
            gate.release("message")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
