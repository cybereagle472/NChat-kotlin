package com.nchat.app.core.update

/** Match all current signers, or a single installed signer in the new APK's rotation history. */
internal fun compatibleUpdateSigners(installed: Set<String>, incoming: Set<String>, history: Set<String>): Boolean {
    if (installed.isEmpty() || incoming.isEmpty()) return false
    if (installed == incoming) return true
    return installed.size == 1 && incoming.size == 1 && installed.first() in history
}
