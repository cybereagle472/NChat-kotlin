package com.nchat.app.data.repository

import com.nchat.app.network.ApiException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRetryPolicyTest {
    @Test
    fun retriesOnlyTransientFailures() {
        assertTrue(isTransientSendFailure(null))
        assertTrue(isTransientSendFailure(IOException("offline")))
        assertTrue(isTransientSendFailure(ApiException(408, "timeout")))
        assertTrue(isTransientSendFailure(ApiException(429, "rate limited")))
        assertTrue(isTransientSendFailure(ApiException(503, "unavailable")))
        assertFalse(isTransientSendFailure(ApiException(400, "bad request")))
        assertFalse(isTransientSendFailure(ApiException(403, "forbidden")))
        assertFalse(isTransientSendFailure(IllegalArgumentException("bad input")))
    }
}
