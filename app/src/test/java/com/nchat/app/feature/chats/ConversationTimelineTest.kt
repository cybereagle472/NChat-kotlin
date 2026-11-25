package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.network.E2eeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a conversation is listed on the screen: a line for each day, a bubble
 * for each message — and one notice for a stretch of messages this phone has
 * no key for, however many there are. A phone that is new to an account used
 * to show a wall of "can't be shown" in their place, one for every message.
 */
class ConversationTimelineTest {
    private val day = 24L * 60 * 60 * 1000

    private fun label(at: Long): String = "day ${at / day}"

    private fun text(id: String, at: Long, author: String = "peer") = Message(
        id = id,
        chatId = "peer",
        authorId = author,
        authorName = author,
        text = "words",
        timestamp = at,
        isMine = author == "me",
        encrypted = true,
    )

    /** A sealed message this phone has no key for, as the repository hands it over. */
    private fun locked(id: String, at: Long, author: String = "peer") =
        text(id, at, author).copy(text = E2eeText.CANNOT_SHOW, encrypted = false, unreadable = true)

    private fun timeline(messages: List<Message>, firstUnreadId: String? = null) = buildTimeline(messages, firstUnreadId, ::label)

    @Test
    fun aConversationWithNothingLockedIsListedMessageByMessage() {
        val a = text("a", 10)
        val b = text("b", 20)
        val c = text("c", 30, author = "me")
        val d = text("d", day + 10)
        assertEquals(
            listOf(
                TimelineItem.Separator("day 0"),
                TimelineItem.Bubble(a, showSender = true),
                TimelineItem.Bubble(b, showSender = false),
                TimelineItem.Bubble(c, showSender = true),
                TimelineItem.Separator("day 1"),
                TimelineItem.Bubble(d, showSender = true),
            ),
            timeline(listOf(a, b, c, d)),
        )
        assertEquals(emptyList<TimelineItem>(), timeline(emptyList()))
    }

    @Test
    fun aStretchThisPhoneCannotShowIsToldOnce() {
        val first = locked("l1", 10)
        val second = locked("l2", 20, author = "me")
        val third = locked("l3", day + 10)
        val readable = text("r1", day + 20)
        val alone = locked("l4", day + 30)
        val last = text("r2", 2 * day + 10)
        assertEquals(
            listOf(
                TimelineItem.Separator("day 0"),
                // Three messages, from both sides and over two days: one notice, where they begin.
                TimelineItem.Locked(listOf(first, second, third)),
                // What follows says its own day, since the notice stood under another.
                TimelineItem.Separator("day 1"),
                TimelineItem.Bubble(readable, showSender = true),
                TimelineItem.Locked(listOf(alone)),
                TimelineItem.Separator("day 2"),
                TimelineItem.Bubble(last, showSender = true),
            ),
            timeline(listOf(first, second, third, readable, alone, last)),
        )
    }

    @Test
    fun aWholeHistoryThisPhoneCannotShowIsOneNoticeAndNotAWall() {
        val history = (0 until 300).map { locked("m$it", it * (day / 10)) }
        val items = timeline(history)
        assertEquals(listOf(TimelineItem.Separator("day 0"), TimelineItem.Locked(history)), items)
        assertEquals(300, (items[1] as TimelineItem.Locked).messages.size)
    }

    @Test
    fun whatABackupCannotBringBackIsStillToldMessageByMessage() {
        // Content of a kind this build does not know, a file that is gone, a deleted message:
        // each says what is the matter with it, and none of them is what "Restore backup" is for.
        val newer = locked("newer", 20).copy(text = E2eeText.NEEDS_UPDATE)
        val gone = locked("gone", 30).copy(text = E2eeText.FILE_UNAVAILABLE)
        val deleted = locked("deleted", 40).copy(deleted = true)
        val before = locked("before", 10)
        val after = locked("after", 50)
        assertEquals(
            listOf(
                TimelineItem.Separator("day 0"),
                TimelineItem.Locked(listOf(before)),
                TimelineItem.Bubble(newer, showSender = true),
                TimelineItem.Bubble(gone, showSender = false),
                TimelineItem.Bubble(deleted, showSender = false),
                TimelineItem.Locked(listOf(after)),
            ),
            timeline(listOf(before, newer, gone, deleted, after)),
        )
        assertTrue(before.lockedHere)
        assertFalse(newer.lockedHere)
        assertFalse(gone.lockedHere)
        assertFalse(deleted.lockedHere)
        assertFalse("a message that opened is not locked, whatever it says", text("t", 1).copy(text = E2eeText.CANNOT_SHOW).lockedHere)
    }

    @Test
    fun afterANoticeTheNextBubbleSaysWhoWroteIt() {
        val before = text("a", 10)
        val hidden = locked("l", 20)
        val after = text("b", 30)
        val items = timeline(listOf(before, hidden, after))
        assertEquals(TimelineItem.Bubble(after, showSender = true), items.last())
    }

    @Test
    fun theUnreadMarkerStandsBeforeTheNoticeWhenTheFirstUnreadMessageIsInIt() {
        val read = text("a", 10)
        val l1 = locked("l1", 20)
        val l2 = locked("l2", 30)
        val l3 = locked("l3", 40)
        val new = text("b", 50)
        assertEquals(
            listOf(
                TimelineItem.Separator("day 0"),
                TimelineItem.Bubble(read, showSender = true),
                TimelineItem.UnreadDivider,
                TimelineItem.Locked(listOf(l1, l2, l3)),
                TimelineItem.Bubble(new, showSender = true),
            ),
            timeline(listOf(read, l1, l2, l3, new), firstUnreadId = "l2"),
        )
        // …and where it always stood when the first unread message is one that can be shown.
        assertEquals(
            listOf(
                TimelineItem.Separator("day 0"),
                TimelineItem.Bubble(read, showSender = true),
                TimelineItem.Locked(listOf(l1, l2, l3)),
                TimelineItem.UnreadDivider,
                TimelineItem.Bubble(new, showSender = true),
            ),
            timeline(listOf(read, l1, l2, l3, new), firstUnreadId = "b"),
        )
        assertEquals("once", 1, timeline(listOf(l1, l2, l3), firstUnreadId = "l1").count { it is TimelineItem.UnreadDivider })
    }

    @Test
    fun aMessageIsFoundWhereItIsListed() {
        val a = text("a", 10)
        val l1 = locked("l1", 20)
        val l2 = locked("l2", 30)
        val b = text("b", 40)
        val items = timeline(listOf(a, l1, l2, b))
        // separator, a, notice, b
        assertEquals(1, items.indexOfMessage("a"))
        assertEquals("a message inside the notice is where the notice is", 2, items.indexOfMessage("l1"))
        assertEquals(2, items.indexOfMessage("l2"))
        assertEquals(3, items.indexOfMessage("b"))
        assertEquals(-1, items.indexOfMessage("not loaded"))
    }

    @Test
    fun everyRowHasAKeyOfItsOwnAndANoticeKeepsItsWhenOlderMessagesJoinIt() {
        val l2 = locked("l2", day + 20)
        val l3 = locked("l3", day + 30)
        val readable = text("r", 2 * day + 10)
        val later = locked("l4", 2 * day + 20)
        val page = timeline(listOf(l2, l3, readable, later))
        assertEquals("no two rows share a key", page.size, page.map { it.key }.toSet().size)

        // An older page arrives and its messages cannot be shown either: the same notice, grown.
        val l0 = locked("l0", 10)
        val l1 = locked("l1", day + 10)
        val more = timeline(listOf(l0, l1, l2, l3, readable, later))
        assertEquals(page.size, more.size)
        val before = page.first { it is TimelineItem.Locked }
        val grown = more.first { it is TimelineItem.Locked } as TimelineItem.Locked
        assertEquals(4, grown.messages.size)
        assertEquals("the list holds its place by this", before.key, grown.key)
        assertEquals(more.size, more.map { it.key }.toSet().size)
    }

    @Test
    fun theNoticeSaysHowManyAndWhatCanBeDone() {
        assertEquals("1 message can't be shown on this phone", RestoreText.lockedTitle(1))
        assertEquals("12 messages can't be shown on this phone", RestoreText.lockedTitle(12))
        // Why, in words that are true of a new phone and of one the account came back to.
        for (count in listOf(1, 12)) {
            val body = RestoreText.lockedBody(count)
            assertTrue(body, body.contains("before you signed in on this phone"))
            assertTrue("and what brings them back", body.contains("backup"))
            assertFalse("no promise that a backup holds them", body.contains("will be back"))
        }
        assertTrue(RestoreText.lockedBody(1).startsWith("It was sent"))
        assertTrue(RestoreText.lockedBody(12).startsWith("They were sent"))
        assertEquals("Restore backup", RestoreText.RESTORE)
    }

    @Test
    fun theQuestionAfterSigningInSaysWhatItIsAboutAndThatItCanWait() {
        assertEquals("Restore your messages?", RestoreText.ASK_TITLE)
        val body = RestoreText.ASK_BODY
        assertTrue(body, body.contains("backup"))
        assertTrue("it is about messages from before, not the ones to come", body.contains("before you signed in on this phone"))
        assertTrue("where to find it later", body.contains("Settings") && body.contains("Chat backup"))
        assertEquals("Restore backup", RestoreText.RESTORE)
        assertEquals("Not now", RestoreText.NOT_NOW)
    }
}
