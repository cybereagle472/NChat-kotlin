package com.nchat.app.feature.chats

import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind

/** Only confirmed direct text messages have an editing endpoint on the current backend. */
internal fun canEditMessage(message: Message, isGroup: Boolean, now: Long = System.currentTimeMillis()): Boolean =
    !isGroup &&
        message.isMine &&
        !message.deleted &&
        message.kind == MessageKind.Text &&
        message.clientMessageId != message.id &&
        now - message.timestamp < 15 * 60 * 1000L
