package com.nchat.app.core.update

import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * PackageManager flags used to read who signed an APK: the downloaded file, and the installed
 * app it is compared with.
 *
 * `getPackageArchiveInfo` loads the certificates of an APK *file* only when it is asked with a
 * flag that Android release checks for. Android 9, Android 10 and the first Android 13 builds
 * check `GET_SIGNATURES` alone (AOSP `PackageManager.getPackageArchiveInfo` at
 * `android-9.0.0_r1`, `android-10.0.0_r1`, `android-13.0.0_r1`..`_r3`): asked with only
 * `GET_SIGNING_CERTIFICATES` they return no signer at all, and the updater then turned every
 * download away as "not signed by NChat's verified release key ... Received: unavailable".
 * Android 11, 12, later 13 builds and 14+ accept either flag. Asking with both is therefore
 * right on every release from Android 9 (API 28) up; before that only the older flag exists.
 */
@Suppress("DEPRECATION")
internal fun signerLookupFlags(sdkInt: Int): Int =
    if (sdkInt >= 28) {
        PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

/** Lowercase SHA-256 certificate fingerprint, with no separators. */
internal fun certificateSha256(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificate)
        .joinToString("") { byte -> "%02x".format(byte) }

/** Prefer modern PackageManager signer data, falling back when an OEM returns it empty. */
internal fun preferredSignerSet(modern: Set<String>, legacy: Set<String>): Set<String> =
    modern.takeIf { it.isNotEmpty() } ?: legacy

/** Match all current signers, or a single installed signer in the new APK's rotation history. */
internal fun compatibleUpdateSigners(installed: Set<String>, incoming: Set<String>, history: Set<String>): Boolean {
    if (installed.isEmpty() || incoming.isEmpty()) return false
    if (installed == incoming) return true
    return installed.size == 1 && incoming.size == 1 && installed.first() in history
}
