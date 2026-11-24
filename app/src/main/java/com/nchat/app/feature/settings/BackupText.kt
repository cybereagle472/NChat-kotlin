package com.nchat.app.feature.settings

import com.nchat.app.network.E2eeBackup

/**
 * What the backup screen tells the user. Apart from the screen, so that every
 * sentence can be checked without a phone: "saved" for a file that is empty,
 * or "restored" for a file that was not, would cost somebody their history.
 */
internal object BackupText {

    const val ABOUT =
        "Encrypted messages can be read only on the phone that received them. Signing out, or signing in on " +
            "another phone, leaves them unreadable for good — unless you have a backup.\n\n" +
            "A backup is one file, locked with a passphrase that only you know. Keep it wherever you like. " +
            "NChat never sees the passphrase and cannot recover it."

    const val PASSPHRASE_ADVICE =
        "Use several words. Anyone who gets hold of the file can try passphrases for as long as they like."

    const val RESTORE_ADVICE =
        "Restore only a file you saved yourself: what a backup says is believed like this phone's own memory. " +
            "Photos and files come back as long as the server still has them."

    /** Null when the passphrase will do and was typed the same twice; otherwise what to tell the user. */
    fun passphraseProblem(passphrase: String, repeated: String): String? =
        E2eeBackup.passphraseProblem(passphrase.toCharArray())
            ?: "The two passphrases are not the same.".takeUnless { E2eeBackup.samePassphrase(passphrase.toCharArray(), repeated.toCharArray()) }

    /** After a backup was written; [result] is null when there was nothing to write it from. */
    fun written(result: E2eeBackup.Written?): String {
        if (result == null) return UNAVAILABLE + " Nothing was saved."
        val missing = if (result.unreadableChats == 0) {
            ""
        } else {
            " ${counted(result.unreadableChats, "chat")} could not be read on this phone and ${if (result.unreadableChats == 1) "is" else "are"} not in the file."
        }
        if (result.messages == 0) return "There are no encrypted messages on this phone yet, so the file holds nothing.$missing"
        return "Saved ${counted(result.messages, "message")} from ${counted(result.chats, "chat")}. " +
            "Keep the file and remember the passphrase: one is worth nothing without the other.$missing"
    }

    /** After a file was restored to its end; [count] is null when there was nothing to restore into. */
    fun restored(count: Int?): String = when (count) {
        null -> UNAVAILABLE + " Nothing was restored."
        0 -> "Everything in this backup is already on this phone."
        else -> "Restored ${counted(count, "message")}. Open a chat to see them."
    }

    /** When a file was turned down, or could not be read to its end. */
    fun failed(failure: E2eeBackup.Failure): String = when (failure.problem) {
        E2eeBackup.Problem.NotABackup -> "That file is not an NChat backup."
        E2eeBackup.Problem.NewerVersion -> "This backup was made by a newer version of NChat. Update the app, then try again."
        E2eeBackup.Problem.OtherAccount -> "This backup belongs to another account. Sign in to that account to restore it."
        E2eeBackup.Problem.WrongPassphrase -> "That passphrase does not open this backup."
        E2eeBackup.Problem.Damaged ->
            if (failure.restored == 0) {
                "This backup file is damaged, and nothing could be restored from it."
            } else {
                "This backup file is damaged. ${counted(failure.restored, "message")} came back; the rest of the file cannot be read."
            }
    }

    const val TYPE_AGAIN = "Type the passphrase again, then save."

    const val NO_PASSPHRASE = "Type the passphrase of the backup first, then choose the file."

    const val NOT_SAVED = "The backup could not be saved, and nothing was left in the file. Try again, or choose another place for it."

    const val NOT_READ = "The file could not be read. Try again, or copy it onto this phone first."

    fun fileName(year: Int, month: Int, day: Int): String = "nchat-backup-$year-${twoDigits(month)}-${twoDigits(day)}.nchatbackup"

    private const val UNAVAILABLE = "Encrypted chats can't be opened on this phone right now. Restart NChat and try again."

    private fun counted(count: Int, what: String): String = "$count $what" + if (count == 1) "" else "s"

    private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
}
