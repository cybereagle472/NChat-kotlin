package com.nchat.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCacheWriteGateTest {
    @Test
    fun skipsInitialPlaceholderButPersistsLaterEmptyState() {
        val gate = ConversationCacheWriteGate()
        assertFalse(gate.shouldWrite(isEmpty = true))
        assertFalse(gate.shouldWrite(isEmpty = true))
        assertTrue(gate.shouldWrite(isEmpty = false))
        assertTrue(gate.shouldWrite(isEmpty = true))
        assertTrue(gate.shouldWrite(isEmpty = false))
    }
}
