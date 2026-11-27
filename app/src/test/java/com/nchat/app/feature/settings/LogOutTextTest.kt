package com.nchat.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the two ways of leaving say before they happen. One keeps the
 * messages on the phone and one erases them for good: a sentence that makes
 * the second sound like the first costs somebody their history, and one that
 * makes the first sound like the second leaves it on a phone given away.
 */
class LogOutTextTest {

    @Test
    fun loggingOutSaysThatTheMessagesStayAndWhatWouldStillEraseThem() {
        assertEquals("Log Out", LogOutText.KEEP_ROW)
        assertEquals("Log out?", LogOutText.KEEP_TITLE)
        val body = LogOutText.KEEP_BODY
        assertTrue(body, body.contains("stay on this phone"))
        assertTrue("how they come back", body.contains("Sign in to this account again"))
        assertTrue("and that nothing has to be saved first", body.contains("no backup"))
        assertTrue("another account on the phone still removes them", body.contains("different account") && body.contains("erased"))
        assertFalse("it is not the button for a phone that is given away", body.contains("give"))
        assertEquals("Log out", LogOutText.KEEP_CONFIRM)
    }

    @Test
    fun erasingSaysWhatGoesThatItIsForGoodAndWhatItIsFor() {
        assertEquals("Log out and erase", LogOutText.ERASE_ROW)
        assertEquals("Erase NChat from this phone?", LogOutText.ERASE_TITLE)
        val body = LogOutText.ERASE_BODY
        assertTrue(body, body.contains("erased"))
        assertTrue("the keys go, which is what makes it final", body.contains("keys"))
        assertTrue("without a backup nothing brings the encrypted messages back", body.contains("Without a backup") && body.contains("not be able to read"))
        assertTrue("on no phone, not only on this one", body.contains("any other"))
        assertTrue("what it is for", body.contains("give this phone"))
        assertTrue("what it does not reach", body.contains("saved outside NChat"))
        assertFalse("it never says that anything stays readable in NChat", body.contains("stay on this phone"))
        assertEquals("Erase and log out", LogOutText.ERASE_CONFIRM)
        assertEquals("Save a backup first", LogOutText.BACK_UP_FIRST)
    }

    @Test
    fun theTwoAreToldApartBeforeAnyDialogOpens() {
        val note = LogOutText.ROWS_NOTE
        assertTrue(note, note.contains("Log Out keeps your messages on this phone"))
        assertTrue(note, note.contains("erase") && note.contains("give the phone away"))
        assertNotEquals(LogOutText.KEEP_ROW, LogOutText.ERASE_ROW)
        assertNotEquals(LogOutText.KEEP_CONFIRM, LogOutText.ERASE_CONFIRM)
        assertEquals("Cancel", LogOutText.CANCEL)
    }
}
