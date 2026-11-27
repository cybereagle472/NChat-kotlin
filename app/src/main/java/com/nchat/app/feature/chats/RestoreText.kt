package com.nchat.app.feature.chats

/**
 * What the app says about messages this phone has no key for: the notice
 * that stands in a chat in their place, and the question asked once after
 * signing in. Apart from the screens, so that every sentence can be checked
 * without a phone.
 *
 * "Before you signed in on this phone" is true of every way such a message
 * comes about: the phone is new to the account, the account was erased from
 * it, or it was in use on another phone in between. No sentence promises
 * that a backup holds them — only the user knows what they saved, and when.
 */
internal object RestoreText {

    /** The first line of the notice in a chat, for [count] messages in a row. */
    fun lockedTitle(count: Int): String =
        if (count == 1) "1 message can't be shown on this phone" else "$count messages can't be shown on this phone"

    fun lockedBody(count: Int): String =
        if (count == 1) {
            "It was sent before you signed in on this phone. If you saved a backup, restore it to read it."
        } else {
            "They were sent before you signed in on this phone. If you saved a backup, restore it to read them."
        }

    /** The button of the notice and of the question: it opens Settings ▸ Chat backup. */
    const val RESTORE = "Restore backup"

    const val ASK_TITLE = "Restore your messages?"

    const val ASK_BODY =
        "Some of your encrypted messages were sent before you signed in on this phone, so it cannot show them yet. " +
            "If you saved a backup, restore it now to read them here.\n\n" +
            "You can also do this later: Settings, then Chat backup."

    const val NOT_NOW = "Not now"
}
