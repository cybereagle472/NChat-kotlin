package com.nchat.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun onlyValidatedInternetIsOnline() {
        assertFalse(validatedInternet(null, null))
        assertFalse(validatedInternet(true, null))
        assertFalse(validatedInternet(null, true))
        assertFalse(validatedInternet(true, false))
        assertFalse(validatedInternet(false, true))
        assertFalse(validatedInternet(false, false))
        assertTrue(validatedInternet(true, true))
    }
}
