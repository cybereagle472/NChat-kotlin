package com.nchat.app.data.repository

import com.nchat.app.data.model.Friend
import kotlinx.coroutines.flow.StateFlow

/**
 * Friend graph contract for the Discover tab: accept / decline incoming requests, send
 * and cancel outgoing ones, and unfriend. Implemented by [RemoteFriendRepository].
 */
interface FriendRepository {
    val friends: StateFlow<List<Friend>>

    /** Display names the user has blocked; drives the Settings ▸ Blocked Users pane. */
    val blockedNames: StateFlow<Set<String>>
    fun accept(id: String)
    fun decline(id: String)
    fun sendRequest(id: String)
    fun cancelRequest(id: String)
    fun unfriend(id: String)
    fun block(name: String)
    fun unblock(name: String)

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
}
