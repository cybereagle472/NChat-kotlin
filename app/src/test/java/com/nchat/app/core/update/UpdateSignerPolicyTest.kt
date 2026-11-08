package com.nchat.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSignerPolicyTest {
    @Test
    fun certificateFingerprintIsStableSha256Hex() {
        assertEquals(
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            certificateSha256(byteArrayOf(1, 2, 3))
        )
    }

    @Test
    fun fallsBackToLegacySignerDataOnlyWhenModernDataIsEmpty() {
        assertEquals(setOf("modern"), preferredSignerSet(setOf("modern"), setOf("legacy")))
        assertEquals(setOf("legacy"), preferredSignerSet(emptySet(), setOf("legacy")))
    }

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
