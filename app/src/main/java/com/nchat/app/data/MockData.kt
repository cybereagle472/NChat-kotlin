package com.nchat.app.data
data class Chat(val name: String, val msg: String, val time: String, val unread: Int)
object MockData {
    val chats = List(50) {
        Chat("User $it", "Ye mock message hai speed check ke liye $it", "${it%12+1}:${it%60} PM", it%5)
    }
}
