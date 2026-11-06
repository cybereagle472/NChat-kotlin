package com.nchat.app.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.nchat.app.data.ServiceLocator

/**
 * "Reply" straight from the notification shade.
 *
 * The text never opens the app: it goes through the same `ChatRepository.sendMessage` the
 * composer uses, so it lands in the outbox, gets a tick, and survives being offline — exactly
 * like a message typed on screen. The notification is dismissed afterwards, and the chat itself
 * is *not* marked read, because replying from the shade is not the same as reading the thread.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(NcNotifications.EXTRA_CHAT_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NcNotifications.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()

        // The receiver can run in a cold process: the graph has to exist before the repository
        // is touched, and `init` is idempotent.
        ServiceLocator.init(context.applicationContext)

        if (text.isNotEmpty()) {
            ServiceLocator.chatRepository.sendMessage(
                chatId = chatId,
                text = text,
                replyAuthor = null,
                replyText = null,
                replyToId = null
            )
        }
        ServiceLocator.notifications.cancel(chatId)
    }
}
