package com.nchat.app.feature.chats

import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.network.PeerSecurity

/**
 * The messages of a one-to-one conversation that reached the server readable although the
 * conversation is end-to-end encrypted — the ones the screen marks "Not encrypted".
 *
 * A sealed message carries a lock. That alone would leave the absence of a lock as the only
 * sign of a message somebody slipped into the conversation, and nobody notices an absence. So
 * the exception is what gets marked: whatever came through unsealed after encryption with this
 * person began. That is true of a forged message, and equally of an honest one — written on a
 * client without encryption (the website), or of a kind that is not sealed yet. Every kind is
 * marked for that reason: leaving attachments out because none of them is sealed today would
 * leave a photo with a caption as the way to slip words in unmarked.
 *
 * Two rules, either is enough:
 *
 *  - a sealed message stands before it in [messages] (oldest first) — one this phone opened,
 *    or one it holds no keys for. This rule needs nothing but the rows: not a clock that was
 *    right, and not a session, which a phone does not have after a reinstall until the next
 *    message is exchanged;
 *  - the message is dated at or after [encryptedSince]: when this phone first had a session
 *    with the other person, by its own clock, or null when it never had one. This rule does
 *    not need a sealed message to be among the rows that happen to be loaded.
 *
 * Not marked: deleted messages and system lines, which carry nobody's words; and this phone's
 * own messages that are still on their way or failed, which the server has not seen at all.
 *
 * What the rules leave out is the history from before the first sealed message. It never was
 * protected: the server holds it readable and can change it, or add to it under an old date.
 */
fun unsealedMessageIds(messages: List<Message>, encryptedSince: Long?): Set<String> {
    val marked = LinkedHashSet<String>()
    var sealedSeen = false
    for (message in messages) {
        if (message.encrypted || message.unreadable) {
            sealedSeen = true
            continue
        }
        if (!message.reachedServerReadable) continue
        if (sealedSeen || (encryptedSince != null && message.timestamp >= encryptedSince)) marked += message.id
    }
    return marked
}

// ------------------------------------------------------------ outside the conversation

/** What a row of the chat list says about the encryption of its conversation. */
enum class ChatLock {
    /** Nothing was ever encrypted in it on this phone. */
    None,

    /** End-to-end encrypted. */
    Encrypted,

    /** Encrypted, and something about the other person's keys changed that the user has not looked at. */
    Changed,

    /** As [Changed], and the old security code had been verified: nothing is sent until the user has looked. */
    Held,
}

/**
 * Everything a row of the chat list shows about encryption.
 *
 * [notice] takes the place of the preview for as long as a change is not looked at — inside
 * the chat it is a banner, and somebody who never opens the chat would not learn of it.
 *
 * [unsealedPreview]: the newest message, which the row previews, reached the server readable
 * although the chat is encrypted. The lock on a row must not vouch for words that never were
 * sealed, so the preview says "Not encrypted". A row has one message to go by and not the rows
 * around it, so only the second rule of [unsealedMessageIds] applies: the message is dated at
 * or after the moment encryption began on this phone.
 */
data class ChatListMark(val lock: ChatLock, val notice: String?, val unsealedPreview: Boolean)

/**
 * [security]: what this phone knows about the other person of a one-to-one chat, null when
 * nothing was ever encrypted with them. [groupEncryptedSince]: when a group was first encrypted
 * on this phone, null when it never was. Each is looked at only for its own kind of chat.
 */
fun chatListMark(chat: Chat, security: PeerSecurity?, groupEncryptedSince: Long?): ChatListMark {
    val known = security.takeIf { !chat.isGroup }
    val since = if (chat.isGroup) groupEncryptedSince else known?.since
    val lock = when {
        chat.isGroup -> if (groupEncryptedSince != null) ChatLock.Encrypted else ChatLock.None
        known == null -> ChatLock.None
        known.held -> ChatLock.Held
        known.changed -> ChatLock.Changed
        else -> ChatLock.Encrypted
    }
    val notice = when {
        known == null || lock == ChatLock.None || lock == ChatLock.Encrypted -> null
        known.key == null -> "Encryption keys could not be read"
        known.held -> "Security code changed — messages are waiting"
        else -> "Security code changed"
    }
    return ChatListMark(
        lock = lock,
        notice = notice,
        unsealedPreview = chat.lastMessageUnsealed && since != null && chat.lastMessageAt >= since,
    )
}

/**
 * The same for a row of the Groups tab. A group has no security code of its own, so there is
 * never a notice: it is encrypted on this phone since [encryptedSince], or it is not.
 */
fun groupListMark(group: Group, encryptedSince: Long?): ChatListMark = ChatListMark(
    lock = if (encryptedSince != null) ChatLock.Encrypted else ChatLock.None,
    notice = null,
    unsealedPreview = group.lastActivityUnsealed && encryptedSince != null && group.lastActivityAt >= encryptedSince,
)

/** In front of a preview or a notification of words the server could read in an encrypted chat. */
const val NOT_ENCRYPTED_PREFIX = "Not encrypted · "

/**
 * What a notification says for [message]: [body], and in front of it that it was not encrypted
 * when it reached the server readable although its chat is encrypted on this phone since
 * [encryptedSince] (null: never). A notification is where a message slipped into an encrypted
 * chat would be read first, and there it would look like any other.
 */
fun notificationBody(body: String, message: Message, encryptedSince: Long?): String =
    if (encryptedSince != null && message.reachedServerReadable && message.timestamp >= encryptedSince) {
        NOT_ENCRYPTED_PREFIX + body
    } else {
        body
    }

/**
 * What the notification about a changed security code says; null when there is nothing to
 * announce. Keys this phone could not read are not news about the other person: that is said
 * in the chat and on its row, not announced.
 */
fun securityNotification(name: String, security: PeerSecurity?): String? = when {
    security == null || security.key == null -> null
    security.changed || security.held -> securityChangeNotice(name, security)
    else -> null
}

/**
 * True when what this phone knows about somebody went from [before] to [now] in a way the user
 * has not been told of: their code changed, it changed again, or messages now wait for it.
 * Not for a change that was there already, and not when it was looked at.
 */
fun announcesSecurityChange(before: PeerSecurity?, now: PeerSecurity?): Boolean {
    if (now == null || !(now.changed || now.held)) return false
    if (before == null || !(before.changed || before.held)) return true
    return before.key != now.key || (now.held && !before.held)
}

/**
 * What the encryption sheet of a chat says first. [security] is null for a conversation
 * nothing was ever encrypted in.
 */
fun encryptionSummary(name: String, security: PeerSecurity?): String = when {
    security == null ->
        "Messages in this chat are not end-to-end encrypted yet. That starts by itself, with the first " +
            "message after both of you use a version of NChat that supports it."
    security.key == null ->
        "This chat is end-to-end encrypted, but its keys could not be read on this phone. New ones are " +
            "agreed with the next message, and the security code will be here after that."
    security.held ->
        "$name's security code changed, and you had verified the old one. Nothing is sent to $name until " +
            "you accept the new code or verify it."
    security.changed ->
        "$name's security code changed. That happens when somebody changes phone or sets NChat up again. " +
            "If it surprises you, compare the code with $name."
    else ->
        "Messages in this chat are end-to-end encrypted: only your phone and $name's can read them. That " +
            "covers texts, locations, photos, videos, voice messages and files."
}

/**
 * What the encryption sheet of a group says. [encrypted]: a sealed message was written or read
 * in the group on this phone.
 */
fun groupEncryptionSummary(encrypted: Boolean): String = if (encrypted) {
    "Messages in this group are end-to-end encrypted: only the phones of its members can read them. That " +
        "covers texts, locations, photos, videos, voice messages and files. Somebody who joins reads from " +
        "then on; somebody who leaves reads nothing that follows. To compare a member's security code, " +
        "open your chat with them."
} else {
    "Messages in this group are not end-to-end encrypted yet. That starts by itself, with the first " +
        "message after every member uses a version of NChat that supports it."
}

/** The line under the chat's header while a change of the other person's security code is unacknowledged. */
fun securityChangeNotice(name: String, security: PeerSecurity): String = when {
    security.key == null ->
        "This chat's encryption keys could not be read on this phone. New ones are agreed with the next message."
    security.held ->
        "$name's security code changed. You had verified the old one, so messages to $name wait until you have looked."
    else ->
        "$name's security code changed. This usually means $name changed phone or set NChat up again."
}

/** What the button that settles the notice is called; null when there is nothing to accept yet. */
fun securityAcceptLabel(security: PeerSecurity): String? = when {
    security.key == null -> null
    security.held -> "Accept"
    else -> "OK"
}
