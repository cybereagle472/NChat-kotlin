package com.nchat.app.feature.chats

import com.nchat.app.data.model.DeliveryState
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
        if (message.deleted || message.systemEvent) continue
        if (message.isMine && (message.failed || message.state == DeliveryState.Sending)) continue
        if (sealedSeen || (encryptedSince != null && message.timestamp >= encryptedSince)) marked += message.id
    }
    return marked
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
        "$name's security code changed. That happens when somebody signs in again or changes phone. " +
            "If it surprises you, compare the code with $name."
    else ->
        "Messages in this chat are end-to-end encrypted: only your phone and $name's can read them. That " +
            "covers texts, locations, photos, videos, voice messages and files."
}

/** The line under the chat's header while a change of the other person's security code is unacknowledged. */
fun securityChangeNotice(name: String, security: PeerSecurity): String = when {
    security.key == null ->
        "This chat's encryption keys could not be read on this phone. New ones are agreed with the next message."
    security.held ->
        "$name's security code changed. You had verified the old one, so messages to $name wait until you have looked."
    else ->
        "$name's security code changed. This usually means $name signed in again or changed phone."
}

/** What the button that settles the notice is called; null when there is nothing to accept yet. */
fun securityAcceptLabel(security: PeerSecurity): String? = when {
    security.key == null -> null
    security.held -> "Accept"
    else -> "OK"
}
