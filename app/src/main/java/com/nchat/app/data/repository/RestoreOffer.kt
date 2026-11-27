package com.nchat.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether to ask "Restore your messages?".
 *
 * It is asked once, and only of somebody it concerns: an account that signed
 * in on a phone which held nothing of it — so its earlier encrypted messages
 * can come back from a backup and from nowhere else — and which has then met
 * such a message. Somebody new, and somebody whose messages never were
 * encrypted, meet none and are not asked. Somebody who signs in on the phone
 * that kept everything is not asked either: a message that phone cannot show
 * is the exception there, and is told where it stands in the chat.
 *
 * Nothing here is written down. A question that was not asked before the app
 * was closed is not asked later; the notice in the chat says the same, with
 * the same way to a backup.
 */
class RestoreOffer {
    private val lock = Any()

    /** Guarded by [lock]: a phone that started anew signed in, and the question has not been raised yet. */
    private var waiting = false

    private val _asking = MutableStateFlow(false)

    /** True while the question should be on the screen. */
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    /** Somebody signed in. [startedAnew]: the phone held nothing of the account (see `EndToEnd.signedIn`). */
    fun signedIn(startedAnew: Boolean) {
        synchronized(lock) {
            waiting = startedAnew
            _asking.value = false
        }
    }

    /** A sealed message this phone has no key for is being shown as a notice. Called for every such message. */
    fun cannotShow() {
        synchronized(lock) {
            if (!waiting) return
            waiting = false
            _asking.value = true
        }
    }

    /** The user answered, either way — or went and restored a backup without being asked. */
    fun answered() {
        synchronized(lock) {
            waiting = false
            _asking.value = false
        }
    }

    /** The account left this phone: there is nobody to ask. */
    fun signedOut() = answered()
}
