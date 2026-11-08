package com.nchat.app.feature.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileSetupCompletionTest {
    @Test fun completesOnlyAfterSaveSucceeds() = runBlocking {
        val steps = mutableListOf<String>()
        completeProfileSetup(save = { steps += "saved" }, markComplete = { steps += "completed" })
        assertEquals(listOf("saved", "completed"), steps)
    }

    @Test fun failureKeepsSetupIncomplete() = runBlocking {
        var completed = false
        try {
            completeProfileSetup(save = { throw IOException("offline") }, markComplete = { completed = true })
        } catch (_: IOException) {
            // Expected; the caller can show the error and let the user retry.
        }
        assertFalse(completed)
    }

    @Test fun cancellationDoesNotMarkSetupComplete() = runBlocking {
        var completed = false
        try {
            completeProfileSetup(save = { throw CancellationException() }, markComplete = { completed = true })
        } catch (_: CancellationException) {
            // Cancellation is propagated rather than treated as success.
        }
        assertFalse(completed)
    }
}
