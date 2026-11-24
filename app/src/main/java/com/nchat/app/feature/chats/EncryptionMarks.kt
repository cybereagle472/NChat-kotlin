package com.nchat.app.feature.chats

import com.nchat.app.data.model.DeliveryState
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.network.PeerSecurity

/**
 * The messages of a one-to-one conversation that reached the server readable although the
 * conversation is end-to-end encrypted — the ones the screen marks "Not encrypted".
 *
 * A sealed message carries a lock. That alone would leave the absence of a lock as the only
 * sign of a message somebody slipped into the conversation, and nobody notices an absence. So
 * the exception is what gets marked: text or a position that came through unsealed after
 * encryption with this person began. It is true of a forged message, and equally of an honest
 * one written on a client without encryption (the website); either way the reader should know.
 *
 * [encryptedSince] is when this phone first had a session with the other person, by its own
 * clock — null when it never had one, and then nothing is marked. Two rules, either is enough:
 *
 *  - the message is dated at or after [encryptedSince];
 *  - a sealed message stands before it in [messages] (oldest first). This one does not depend
 *    on the phone's clock having been right, and the first one does not depend on a sealed
 *    message being among the rows that happen to be loaded.
 *
 * Not marked: attachments, which are not sealed yet (marking every one of them would say
 * nothing); deleted messages and system lines; and this phone's own messages that are still on
 * their way or failed, which the server has not seen in any form.
 */
fun unsealedMessageIds(messages: List<Message>, encryptedSince: Long?): Set<String> {
    if (encryptedSince == null) return emptySet()
    val marked = LinkedHashSet<String>()
    var sealedSeen = false
    for (message in messages) {
        if (message.encrypted || message.unreadable) {
            sealedSeen = true
            continue
        }
        if (message.deleted || message.systemEvent) continue
        if (message.kind != MessageKind.Text && message.kind != MessageKind.Location) continue
        if (message.isMine && (message.failed || message.state == DeliveryState.Sending)) continue
        if (sealedSeen || message.timestamp >= encryptedSince) marked += message.id
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
    // Attachments are named for as long as they are not sealed; this sentence goes when they are.
    else ->
        "Texts and locations in this chat are end-to-end encrypted: only your phone and $name's can read " +
            "them. Photos, videos, voice messages and files are not encrypted yet."
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
