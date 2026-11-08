package com.nchat.app.data.repository

/** Skip the initial placeholder, but persist an empty list after content has been observed. */
internal class ConversationCacheWriteGate {
    private var observedContent = false

    fun shouldWrite(isEmpty: Boolean): Boolean {
        if (!isEmpty) observedContent = true
        return observedContent
    }
}
