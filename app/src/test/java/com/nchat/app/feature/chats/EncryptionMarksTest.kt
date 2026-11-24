package com.nchat.app.feature.chats

import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.network.PeerSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which messages of an encrypted conversation the server could read. Without
 * a mark on those, a message somebody slipped into the conversation would
 * look like any other.
 */
class EncryptionMarksTest {
    private fun text(id: String, at: Long, mine: Boolean = false, sealed: Boolean = false) = Message(
        id = id,
        chatId = "peer",
        authorId = if (mine) "me" else "peer",
        authorName = if (mine) "Me" else "Peer",
        text = "words",
        timestamp = at,
        isMine = mine,
        state = if (mine) DeliveryState.Sent else DeliveryState.Read,
        encrypted = sealed,
    )

    @Test
    fun aChatThatWasNeverEncryptedMarksNothing() {
        val messages = listOf(text("1", 10), text("2", 20, mine = true))
        assertEquals(emptySet<String>(), unsealedMessageIds(messages, encryptedSince = null))
    }

    @Test
    fun whatWasWrittenBeforeEncryptionBeganIsLeftAlone() {
        val messages = listOf(text("old-1", 100), text("old-2", 200, mine = true), text("sealed", 400, sealed = true))
        assertEquals(emptySet<String>(), unsealedMessageIds(messages, encryptedSince = 300))
    }

    @Test
    fun readableWordsAfterEncryptionBeganStandOut() {
        val messages = listOf(
            text("old", 100),
            text("sealed", 400, sealed = true),
            text("slipped in", 500),
            text("sent from the website", 600, mine = true),
            text("sealed again", 700, mine = true, sealed = true),
        )
        assertEquals(setOf("slipped in", "sent from the website"), unsealedMessageIds(messages, encryptedSince = 300))
        assertEquals("the very moment encryption began counts", setOf("at"), unsealedMessageIds(listOf(text("at", 300)), 300))
    }

    @Test
    fun aClockThatWasWrongDoesNotHideWhatFollowsASealedMessage() {
        // This phone's clock was a day ahead when encryption began.
        val tomorrow = 86_400_000L
        assertEquals(
            setOf("slipped in"),
            unsealedMessageIds(listOf(text("sealed", 400, sealed = true), text("slipped in", 500)), encryptedSince = tomorrow),
        )
        // A page that holds no sealed message at all can only be judged by the date.
        assertEquals(setOf("late"), unsealedMessageIds(listOf(text("early", 100), text("late", 500)), encryptedSince = 300))
        assertEquals(emptySet<String>(), unsealedMessageIds(listOf(text("early", 100), text("late", 500)), encryptedSince = tomorrow))
    }

    @Test
    fun aSealedMessageThisPhoneCannotOpenIsStillASealedOne() {
        val unreadable = text("for another phone", 400).copy(unreadable = true)
        assertEquals(
            setOf("after it"),
            unsealedMessageIds(listOf(text("before it", 350), unreadable, text("after it", 450)), encryptedSince = 1_000),
        )
    }

    @Test
    fun sealedHistoryThisPhoneHoldsNoKeysForStillSetsTheMeasure() {
        // After a reinstall: the sealed messages of before are on the server, and nothing has
        // been agreed with the other person from this phone yet.
        val unreadable = text("for the phone before", 400).copy(unreadable = true)
        assertEquals(
            setOf("after it"),
            unsealedMessageIds(listOf(text("before it", 350), unreadable, text("after it", 450)), encryptedSince = null),
        )
    }

    @Test
    fun everyKindIsMarkedSoThatACaptionIsNoWayAroundIt() {
        val messages = listOf(
            text("photo with a caption", 500).copy(kind = MessageKind.Photo),
            text("video", 500).copy(kind = MessageKind.Video),
            text("voice", 500).copy(kind = MessageKind.Voice),
            text("document", 500).copy(kind = MessageKind.Document),
            text("position", 500).copy(kind = MessageKind.Location),
            text("my photo", 500, mine = true).copy(kind = MessageKind.Photo),
        )
        assertEquals(messages.map { it.id }.toSet(), unsealedMessageIds(messages, encryptedSince = 300))
    }

    @Test
    fun whatCarriesNobodysWordsOrNeverReachedTheServerIsNotMarked() {
        val messages = listOf(
            text("deleted", 500).copy(deleted = true),
            text("system line", 500).copy(systemEvent = true),
            // On their way, or never sent: the server has not seen them.
            text("sending", 500, mine = true).copy(state = DeliveryState.Sending),
            text("failed", 500, mine = true).copy(failed = true),
            text("photo still uploading", 500, mine = true).copy(kind = MessageKind.Photo, state = DeliveryState.Sending),
            text("sent", 500, mine = true),
            text("read", 500, mine = true).copy(state = DeliveryState.Read),
            // Somebody else's message has no "sending": whatever state it carries, it arrived.
            text("theirs", 500).copy(state = DeliveryState.Sending),
        )
        assertEquals(setOf("sent", "read", "theirs"), unsealedMessageIds(messages, encryptedSince = 300))
    }

    // ------------------------------------------------------------- wording

    private val settled = PeerSecurity(key = "their-key", verified = false, changed = false, held = false, since = 0L)

    @Test
    fun theNoticeSaysWhatChangedAndWhatWaits() {
        val changed = settled.copy(changed = true)
        val held = changed.copy(held = true)
        val lost = changed.copy(key = null)

        assertTrue(securityChangeNotice("Sara", changed).startsWith("Sara's security code changed."))
        assertFalse("nothing waits for a contact nobody had verified", securityChangeNotice("Sara", changed).contains("wait"))
        assertTrue(securityChangeNotice("Sara", held).contains("messages to Sara wait"))
        assertTrue(securityChangeNotice("Sara", lost).contains("could not be read"))

        assertEquals("OK", securityAcceptLabel(changed))
        assertEquals("Accept", securityAcceptLabel(held))
        assertNull("no key yet: nothing to accept", securityAcceptLabel(lost))
        assertNull(securityAcceptLabel(held.copy(key = null)))
    }

    @Test
    fun theSheetNeverCallsAChatEncryptedThatIsNot() {
        val never = encryptionSummary("Sara", null)
        assertTrue(never.contains("not end-to-end encrypted yet"))

        val encrypted = encryptionSummary("Sara", settled)
        assertTrue(encrypted.contains("are end-to-end encrypted"))
        assertTrue("and says what is not covered", encrypted.contains("voice messages and files are not encrypted yet"))
        assertEquals("a verified chat is described the same way", encrypted, encryptionSummary("Sara", settled.copy(verified = true)))

        val changed = encryptionSummary("Sara", settled.copy(changed = true))
        val held = encryptionSummary("Sara", settled.copy(changed = true, held = true))
        val lost = encryptionSummary("Sara", settled.copy(changed = true, key = null))
        assertTrue(changed.contains("security code changed"))
        assertTrue(held.contains("Nothing is sent to Sara"))
        assertTrue(lost.contains("could not be read"))
        assertEquals("four states, four texts", 4, setOf(encrypted, changed, held, lost).size)
        assertNotEquals(never, encrypted)
    }
}
