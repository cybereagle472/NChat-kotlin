package com.nchat.app.data.repository

import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Friend graph contract for the Discover tab: accept / decline incoming requests, send
 * and cancel outgoing ones, and unfriend. The Rust service will implement this later.
 */
interface FriendRepository {
    val friends: StateFlow<List<Friend>>
    fun accept(id: String)
    fun decline(id: String)
    fun sendRequest(id: String)
    fun cancelRequest(id: String)
    fun unfriend(id: String)
}

class InMemoryFriendRepository(
    seed: List<Friend> = MockDataProvider.friends()
) : FriendRepository {

    private val _friends = MutableStateFlow(seed)
    override val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    override fun accept(id: String) = move(id, FriendRelation.Friend)

    override fun decline(id: String) = move(id, FriendRelation.None)

    override fun unfriend(id: String) = move(id, FriendRelation.None)

    override fun cancelRequest(id: String) = move(id, FriendRelation.None)

    override fun sendRequest(id: String) = move(id, FriendRelation.Outgoing, stamp = true)

    private fun move(id: String, relation: FriendRelation, stamp: Boolean = false) {
        _friends.update { list ->
            list.map { friend ->
                if (friend.id != id) {
                    friend
                } else {
                    friend.copy(
                        relation = relation,
                        requestedAt = when {
                            stamp -> System.currentTimeMillis()
                            relation == FriendRelation.None -> null
                            else -> friend.requestedAt
                        }
                    )
                }
            }
        }
    }
}
