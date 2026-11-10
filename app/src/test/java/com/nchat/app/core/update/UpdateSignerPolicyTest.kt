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

    /**
     * `PackageManager.getPackageArchiveInfo` loads the certificates of an APK *file* only when it
     * is asked with a flag that release checks for. In AOSP, Android 9 (`android-9.0.0_r1`),
     * Android 10 (`android-10.0.0_r1`) and the first Android 13 builds (`android-13.0.0_r1` to
     * `_r3`) check `GET_SIGNATURES` alone: asked with only `GET_SIGNING_CERTIFICATES` they return
     * no signer, and the updater then refuses every download as "not signed by NChat's verified
     * release key ... Received: unavailable". Android 11, 12, later 13 builds and 14+ accept
     * either flag, so asking with both is right everywhere from Android 9 up.
     */
    @Test
    fun asksForAnApkFilesCertificatesTheWayEveryAndroidReleaseLoadsThem() {
        val legacy = 0x00000040 // PackageManager.GET_SIGNATURES
        val modern = 0x08000000 // PackageManager.GET_SIGNING_CERTIFICATES, new in Android 9 (API 28)
        for (sdk in 24..27) {
            assertEquals("API $sdk has only the legacy flag", legacy, signerLookupFlags(sdk))
        }
        for (sdk in 28..36) {
            val flags = signerLookupFlags(sdk)
            assertTrue("API $sdk must ask with GET_SIGNATURES", (flags and legacy) != 0)
            assertTrue("API $sdk must ask with GET_SIGNING_CERTIFICATES", (flags and modern) != 0)
        }
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
