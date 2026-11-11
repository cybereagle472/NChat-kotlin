package com.nchat.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun theFirstLookIsAlwaysDue() {
        assertTrue(updateCheckDue(0L, null, lastCheckSucceeded = false))
        assertTrue(updateCheckDue(5 * hour, null, lastCheckSucceeded = true))
    }

    /** The app's process can live for days; a release must not wait for the next cold start. */
    @Test
    fun anAnswerIsTrustedForSixHoursNotForever() {
        val checkedAt = 10 * hour
        assertFalse(updateCheckDue(checkedAt + 1, checkedAt, lastCheckSucceeded = true))
        assertFalse(updateCheckDue(checkedAt + 6 * hour - 1, checkedAt, lastCheckSucceeded = true))
        assertTrue(updateCheckDue(checkedAt + 6 * hour, checkedAt, lastCheckSucceeded = true))
        assertTrue(updateCheckDue(checkedAt + 72 * hour, checkedAt, lastCheckSucceeded = true))
    }

    /** No network at start-up used to mean no update offer until the app was restarted. */
    @Test
    fun aFailedLookIsRepeatedAfterAMinuteNotOnEveryReturn() {
        val failedAt = 10 * hour
        assertFalse(updateCheckDue(failedAt + 59_999, failedAt, lastCheckSucceeded = false))
        assertTrue(updateCheckDue(failedAt + 60_000, failedAt, lastCheckSucceeded = false))
    }

    @Test
    fun everyReturnToTheForegroundIsADifferentValue() {
        val before = UpdateForeground.resumes.value
        UpdateForeground.onResume()
        UpdateForeground.onResume()
        assertEquals(before + 2, UpdateForeground.resumes.value)
    }
}
