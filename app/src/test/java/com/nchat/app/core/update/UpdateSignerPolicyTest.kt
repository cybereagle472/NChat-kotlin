package com.nchat.app.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSignerPolicyTest {
    @Test
    fun checksMatchingSignersAndVerifiedRotationHistory() {
        assertTrue(compatibleUpdateSigners(setOf("old"), setOf("old"), emptySet()))
        assertTrue(compatibleUpdateSigners(setOf("old"), setOf("new"), setOf("old", "new")))
        assertFalse(compatibleUpdateSigners(setOf("old"), setOf("new"), setOf("new")))
        assertFalse(compatibleUpdateSigners(emptySet(), setOf("new"), setOf("new")))
        assertFalse(compatibleUpdateSigners(setOf("a", "b"), setOf("a", "c"), setOf("a", "b", "c")))
        assertTrue(compatibleUpdateSigners(setOf("a", "b"), setOf("b", "a"), emptySet()))
    }

    @Test
    fun retriesOnlyTransientHttpFailures() {
        assertTrue(isTransientUpdateFailure(UpdateHttpException(504)))
        assertTrue(isTransientUpdateFailure(UpdateHttpException(429)))
        assertTrue(isTransientUpdateFailure(java.net.SocketTimeoutException()))
        assertFalse(isTransientUpdateFailure(UpdateHttpException(404)))
        assertFalse(isTransientUpdateFailure(InvalidUpdateDownload("bad file")))
    }
}
