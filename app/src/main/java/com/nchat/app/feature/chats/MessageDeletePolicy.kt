package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.data.model.hasUnconfirmedDelivery

/** Only a confirmed server message can be recalled; the group backend applies a one-hour limit. */
internal fun canDeleteForEveryone(message: Message, isGroup: Boolean, now: Long = System.currentTimeMillis()): Boolean =
    message.isMine &&
        !message.deleted &&
        !message.hasUnconfirmedDelivery() &&
        (!isGroup || now - message.timestamp < 60 * 60 * 1000L)

/** Don't offer a batch recall if any selected message is ineligible or disappeared. */
internal fun canDeleteAllForEveryone(
    selected: List<Message>, selectedCount: Int, isGroup: Boolean, now: Long = System.currentTimeMillis()
): Boolean = selected.isNotEmpty() && selected.size == selectedCount &&
    selected.all { canDeleteForEveryone(it, isGroup, now) }
