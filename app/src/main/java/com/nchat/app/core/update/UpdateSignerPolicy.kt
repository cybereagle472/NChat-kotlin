package com.nchat.app.core.update

import java.security.MessageDigest

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
