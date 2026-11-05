package com.nchat.app.data.repository

import com.nchat.app.data.model.BlockedContact
import com.nchat.app.data.model.Friend
import kotlinx.coroutines.flow.StateFlow

/**
 * Friend graph contract for the Discover tab: accept / decline incoming requests, send
 * and cancel outgoing ones, and unfriend. Implemented by [RemoteFriendRepository].
 */
interface FriendRepository {
    val friends: StateFlow<List<Friend>>

    /** Everyone this account has blocked, with a name and photo — Settings ▸ Blocked Users. */
    val blockedPeople: StateFlow<List<BlockedContact>>

    /** Just the ids, for every screen that has to hide or gate somebody. */
    val blockedIds: StateFlow<Set<String>>

    /** Re-read the graph from the server (also how the empty-home Retry refreshes it). */
    fun refresh()
    fun accept(id: String)
    fun decline(id: String)
    fun sendRequest(id: String)
    fun cancelRequest(id: String)
    fun unfriend(id: String)
    /**
     * Block somebody **without destroying anything**.
     *
     * Id-based, and local: the friendship row and the whole conversation stay on the server
     * exactly as they were, so unblocking restores the chat from the exact message you left
     * off at. Only the id ever identifies the person here — the old name-based lookup could
     * hit the wrong contact when two people shared a display name.
     */
    fun block(id: String, name: String, avatarUrl: String? = null)

    /** Undo a block. Local list only; any legacy server-side block is cleared too. */
    fun unblock(id: String)

    /**
     * Server-side search for the Find tab (`GET /users/search`).
     *
     * [friends] only ever carries the people the client already knows about — the suggestion
     * page plus the friend graph — which is exactly the limitation the reference worker calls
     * out: a search bar must ask the server as the user types. This is that call, debounced in
     * the implementation. An empty query clears the results.
     */
    val searchResults: StateFlow<List<Friend>>
    fun search(query: String)

    /**
     * Friend requests this device sent that the server has not confirmed yet.
     *
     * The reference flips the button to "requested" the instant it is tapped and only rolls
     * back if the call fails (`localSentRequestIds` in `Discover.tsx`); without that, a tap
     * that is silently rejected (409 already friends / already pending) looks like a dead
     * button.
     */
    val pendingSent: StateFlow<Set<String>>

    /** Last action failure, for a one-shot toast. Cleared by [clearActionError]. */
    val actionError: StateFlow<String?>
    fun clearActionError()

    /**
     * Why the friend graph could not be read, or null when the last load worked.
     *
     * The Friends tab used to answer every failure with "No friends yet" — an empty array and a
     * broken request looked identical, and the only way to tell them apart was Settings ▸ Sync
     * check. This carries the reason to the screen that shows the empty state.
     */
    val graphError: StateFlow<String?>
}
