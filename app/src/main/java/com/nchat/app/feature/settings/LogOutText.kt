package com.nchat.app.feature.settings

/**
 * What the two ways of leaving say before they happen. Apart from the
 * screen, so that every sentence can be checked without a phone: one of the
 * two keeps the messages and one erases them for good, and neither may sound
 * like the other.
 */
internal object LogOutText {

    // ------------------------------------------------ keeps what is on the phone

    const val KEEP_ROW = "Log Out"

    const val KEEP_TITLE = "Log out?"

    const val KEEP_BODY =
        "Your messages stay on this phone, locked. Sign in to this account again and they are all here — no backup needed.\n\n" +
            "If a different account signs in to NChat on this phone, they are erased."

    const val KEEP_CONFIRM = "Log out"

    // ------------------------------------------------------------ erases it

    const val ERASE_ROW = "Log out and erase"

    const val ERASE_TITLE = "Erase NChat from this phone?"

    const val ERASE_BODY =
        "Your messages and the files NChat keeps on this phone are erased, together with its encryption keys. " +
            "Without a backup, you will not be able to read your encrypted messages again — on this phone or on any other.\n\n" +
            "Do this before you give this phone to somebody else. What you saved outside NChat — photos in the " +
            "gallery, a backup file — is not touched."

    const val ERASE_CONFIRM = "Erase and log out"

    /** In the erasing dialog: opens Settings ▸ Chat backup instead of erasing. */
    const val BACK_UP_FIRST = "Save a backup first"

    // ----------------------------------------------------------------- both

    /** Under the two rows, before any dialog opens. */
    const val ROWS_NOTE = "Log Out keeps your messages on this phone. Log out and erase is for when you give the phone away."

    const val CANCEL = "Cancel"
}
