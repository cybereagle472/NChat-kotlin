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
}
