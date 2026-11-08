package com.nchat.app.network

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiLogTest {
    @Test
    fun formattingIsSafeAcrossThreads() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map {
                Callable {
                    repeat(1000) {
                        val line = ApiLog.format(ApiLog.Entry(0L, "GET", "/health", 200, 1L, null))
                        check(Regex("^\\d{2}:\\d{2}:\\d{2}  GET\\s+/health  200  1 ms$").matches(line))
                    }
                    true
                }
            }
            assertTrue(pool.invokeAll(tasks).all { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }
}
