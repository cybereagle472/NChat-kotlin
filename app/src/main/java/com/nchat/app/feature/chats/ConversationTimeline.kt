package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.network.E2eeText

/**
 * A sealed message this phone has no key for — the kind that only a backup
 * brings back. It was sent before this phone had the keys it has now: the
 * phone is new to the account, the account was erased from it, or it was in
 * use on another phone in between.
 *
 * Not: content of a kind this build does not know, a file that is no longer
 * there, a deleted message. Those say what is the matter with them, message
 * by message, and a backup changes nothing about them.
 */
internal val Message.lockedHere: Boolean
    get() = unreadable && !deleted && text == E2eeText.CANNOT_SHOW

/** One row of a conversation as the screen lists it. */
internal sealed interface TimelineItem {
    val key: String

    data class Separator(val label: String) : TimelineItem {
        override val key: String = "sep-$label"
    }

    data class Bubble(val message: Message, val showSender: Boolean) : TimelineItem {
        override val key: String = message.id
    }

    /** "Resume here" marker inserted before the first message that was unread on open. */
    data object UnreadDivider : TimelineItem {
        override val key: String = "unread-divider"
    }

    /**
     * A stretch of messages this phone cannot show (see [lockedHere]), told
     * once. [messages] is never empty and is oldest first.
     *
     * The row is known by its newest message: older pages that arrive add to
     * the stretch from above, and the list holds its place by a key that
     * does not change when they do.
     */
    data class Locked(val messages: List<Message>) : TimelineItem {
        override val key: String = "locked-${messages.last().id}"
    }
}

/**
 * [messages], oldest first, as the rows of the screen: a line where a day
 * begins ([dayLabel] names it), the marker before [firstUnreadId], and a
 * bubble for each message — except that messages this phone cannot show
 * which follow one another become one row, however many they are and
 * whoever wrote them. A phone that is new to an account would otherwise
 * show a wall of the same sentence where the history was.
 *
 * Such a row stands under the day its first message was sent. What follows
 * it says its own day if that is another one, and names its sender again.
 */
internal fun buildTimeline(
    messages: List<Message>,
    firstUnreadId: String? = null,
    dayLabel: (Long) -> String,
): List<TimelineItem> {
    val timeline = mutableListOf<TimelineItem>()
    var lastLabel: String? = null
    var previousAuthor: String? = null
    var dividerPlaced = false
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        var end = index + 1
        if (message.lockedHere) {
            while (end < messages.size && messages[end].lockedHere) end++
        }
        val label = dayLabel(message.timestamp)
        val newDay = label != lastLabel
        if (newDay) {
            timeline += TimelineItem.Separator(label)
            lastLabel = label
        }
        if (!dividerPlaced && firstUnreadId != null && (index until end).any { messages[it].id == firstUnreadId }) {
            timeline += TimelineItem.UnreadDivider
            dividerPlaced = true
        }
        if (message.lockedHere) {
            timeline += TimelineItem.Locked(messages.subList(index, end).toList())
            previousAuthor = null
        } else {
            // Avatar only on the first bubble of a sender run (or after a day separator).
            timeline += TimelineItem.Bubble(message, showSender = newDay || previousAuthor != message.authorId)
            previousAuthor = message.authorId
        }
        index = end
    }
    return timeline
}

/**
 * Where the message [messageId] is listed: its bubble, or the row that
 * stands for it together with others. -1 when it is not among the rows.
 */
internal fun List<TimelineItem>.indexOfMessage(messageId: String): Int = indexOfFirst { item ->
    when (item) {
        is TimelineItem.Bubble -> item.message.id == messageId
        is TimelineItem.Locked -> item.messages.any { it.id == messageId }
        else -> false
    }
}
