package com.nchat.app.feature.settings

import com.nchat.app.network.E2eeBackup
import com.nchat.app.network.E2eeBackup.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the backup screen says. A backup is the one place where a wrong word
 * costs somebody their history — "saved" for a file that is empty, or
 * "restored" for a file that was not — so every sentence is pinned here.
 */
class BackupTextTest {

    @Test
    fun thePageSaysWhenABackupIsNeededAndWhenItIsNot() {
        // Logging out keeps the messages on the phone; what a backup is for is a new phone, and
        // this one after it was erased. Telling people otherwise would have them fear a button.
        val about = BackupText.ABOUT
        assertFalse(about, about.contains("Signing out") || about.contains("signing out"))
        assertTrue(about, about.contains("new phone"))
        assertTrue("the button that erases is called what the screen calls it", about.contains("Log out and erase"))
        assertTrue("and what does not need one is said too", about.contains("Logging out") && about.contains("keeps"))
        assertTrue(about.contains("passphrase"))
        assertTrue("nobody can recover it", about.contains("cannot recover"))
        for (subtitle in listOf(BackupText.SUBTITLE)) {
            assertFalse(subtitle, subtitle.contains("sign in again"))
            assertTrue(subtitle, subtitle.contains("new phone"))
        }
    }

    @Test
    fun aPassphraseIsAskedForTwiceAndHasToBeOne() {
        assertNull(BackupText.passphraseProblem("correct horse battery", "correct horse battery"))
        // The space a keyboard adds after the last word is not a difference.
        assertNull(BackupText.passphraseProblem("correct horse battery ", " correct horse battery"))
        assertTrue(BackupText.passphraseProblem("correct horse battery", "correct horse batterx")!!.contains("not the same"))
        assertTrue(BackupText.passphraseProblem("correct horse battery", "")!!.contains("not the same"))
        // What is wrong with the passphrase itself is said first: repeating it would not help.
        assertTrue(BackupText.passphraseProblem("short", "short")!!.contains("10 characters"))
        assertTrue(BackupText.passphraseProblem("short", "something else entirely")!!.contains("10 characters"))
        assertTrue(BackupText.passphraseProblem("", "")!!.contains("10 characters"))
        assertTrue(BackupText.passphraseProblem("aaaaaaaaaaaa", "aaaaaaaaaaaa")!!.contains("easy to guess"))
    }

    @Test
    fun aSavedBackupSaysWhatIsInItAndWhatIsNot() {
        val usual = BackupText.written(E2eeBackup.Written(messages = 1234, chats = 12, unreadableChats = 0))
        assertTrue(usual, usual.startsWith("Saved 1234 messages from 12 chats."))
        assertTrue("it says what the file is worth without the passphrase", usual.contains("passphrase"))
        assertFalse(usual.contains("could not"))

        val one = BackupText.written(E2eeBackup.Written(messages = 1, chats = 1, unreadableChats = 0))
        assertTrue(one, one.startsWith("Saved 1 message from 1 chat."))

        // With nothing to put in it no file is left behind, and nothing is called saved.
        val nothing = BackupText.written(E2eeBackup.Written(messages = 0, chats = 0, unreadableChats = 0))
        assertFalse(nothing, nothing.startsWith("Saved"))
        assertTrue(nothing, nothing.contains("no encrypted messages") && nothing.contains("No file was saved"))

        // Chats that could not be read are named as missing, with a number.
        val partly = BackupText.written(E2eeBackup.Written(messages = 40, chats = 3, unreadableChats = 2))
        assertTrue(partly, partly.contains("2 chats could not be read") && partly.contains("are not in the file"))
        val oneMissing = BackupText.written(E2eeBackup.Written(messages = 40, chats = 3, unreadableChats = 1))
        assertTrue(oneMissing, oneMissing.contains("1 chat could not be read") && oneMissing.contains("is not in the file"))
        val onlyMissing = BackupText.written(E2eeBackup.Written(messages = 0, chats = 0, unreadableChats = 1))
        assertTrue(onlyMissing, onlyMissing.contains("1 chat could not be read") && !onlyMissing.startsWith("Saved"))
        assertFalse("there is no file for it to be missing from", onlyMissing.contains("in the file"))

        // Nobody signed in, or storage that cannot be opened: nothing was written.
        val unavailable = BackupText.written(null)
        assertTrue(unavailable, unavailable.contains("can't be opened") && !unavailable.startsWith("Saved"))
    }

    @Test
    fun aRestoreSaysHowMuchCameBack() {
        assertTrue(BackupText.restored(57).startsWith("Restored 57 messages."))
        assertTrue(BackupText.restored(1).startsWith("Restored 1 message."))
        val nothing = BackupText.restored(0)
        assertFalse(nothing, nothing.startsWith("Restored"))
        assertTrue(nothing, nothing.contains("already on this phone"))
        assertTrue(BackupText.restored(null).contains("can't be opened"))
    }

    @Test
    fun everyReasonAFileIsTurnedDownHasItsOwnSentence() {
        val sentences = Problem.values().associateWith { BackupText.failed(E2eeBackup.Failure(it)) }
        assertEquals("no two reasons read the same", Problem.values().size, sentences.values.toSet().size)
        assertTrue(sentences.getValue(Problem.NotABackup).contains("not an NChat backup"))
        assertTrue(sentences.getValue(Problem.NewerVersion).contains("Update"))
        assertTrue(sentences.getValue(Problem.OtherAccount).contains("another account"))
        assertTrue(sentences.getValue(Problem.WrongPassphrase).contains("passphrase"))
        assertTrue(sentences.getValue(Problem.Damaged).contains("damaged"))
        assertTrue("nothing came back, and it says so", sentences.getValue(Problem.Damaged).contains("nothing"))

        // A damaged file that gave something back says how much — and that the rest is gone.
        val partly = BackupText.failed(E2eeBackup.Failure(Problem.Damaged, restored = 31))
        assertTrue(partly, partly.contains("damaged") && partly.contains("31 messages") && partly.contains("rest"))
        assertFalse(partly.contains("nothing"))
        assertTrue(BackupText.failed(E2eeBackup.Failure(Problem.Damaged, restored = 1)).contains("1 message "))
        // The technical name of the reason is never what the user reads.
        for (sentence in sentences.values) for (problem in Problem.values()) assertFalse(sentence, sentence.contains(problem.name))
    }

    @Test
    fun whileItWorksItSaysThatItTakesAWhile() {
        // Making the key from the passphrase is slow on purpose: several seconds on a phone.
        // A screen that only says "Saving…" for that long looks stuck.
        for (line in listOf(BackupText.SAVING, BackupText.RESTORING)) {
            assertTrue(line, line.contains("a minute"))
        }
        assertTrue(BackupText.SAVING.startsWith("Saving"))
        assertTrue(BackupText.RESTORING.startsWith("Restoring"))
    }

    @Test
    fun aFileIsNamedByTheDayItWasMade() {
        assertEquals("nchat-backup-2026-11-23.nchatbackup", BackupText.fileName(2026, 11, 23))
        assertEquals("nchat-backup-2027-01-05.nchatbackup", BackupText.fileName(2027, 1, 5))
    }
}
