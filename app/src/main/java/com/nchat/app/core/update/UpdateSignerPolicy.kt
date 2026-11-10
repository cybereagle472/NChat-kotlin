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

/** What the signer check concluded about a downloaded APK. Only [TRUSTED] lets it through. */
internal enum class SignerVerdict {
    /** Signed with the pinned release key, and Android can install it over the copy on this phone. */
    TRUSTED,

    /** Android returned no certificate for the file, so there was nothing to compare. */
    UNREADABLE,

    /** Signed, but not with the pinned release key. */
    NOT_RELEASE_KEY,

    /** The file is the official release; the copy on this phone was signed with another key. */
    INSTALLED_KEY_DIFFERS
}

/**
 * The checks, in the order their answers are useful to the person reading the message.
 *
 * "No certificate could be read" is kept apart from "wrong certificate": the first is this phone
 * failing to inspect a file, the second is a file that must not be installed. Both stop the
 * update; only the wording differs, because calling a file wrongly signed when its signer was
 * never read sends people looking for a forgery that may not exist.
 */
internal fun judgeUpdateSigners(
    installed: Set<String>,
    incoming: Set<String>,
    history: Set<String>,
    pinned: String
): SignerVerdict = when {
    incoming.isEmpty() -> SignerVerdict.UNREADABLE
    incoming != setOf(pinned) -> SignerVerdict.NOT_RELEASE_KEY
    !compatibleUpdateSigners(installed, incoming, history) -> SignerVerdict.INSTALLED_KEY_DIFFERS
    else -> SignerVerdict.TRUSTED
}
