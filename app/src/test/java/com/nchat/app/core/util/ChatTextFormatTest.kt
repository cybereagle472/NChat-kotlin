package com.nchat.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextFormatTest {
    @Test
    fun pairedDelimitersStayLiteral() {
        val result = chatFormatted("**bold** and __italic__")
        assertEquals("**bold** and __italic__", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun singleDelimitersStillFormatText() {
        val result = chatFormatted("Hello *bold* and _italic_")
        assertEquals("Hello bold and italic", result.text)
        assertEquals(2, result.spanStyles.size)
    }

    @Test
    fun mentionsDoNotMatchTheStartOfAnotherName() {
        val result = chatFormatted("@Anna and @Ann!", listOf("Ann"), SpanStyle(color = Color.Red))
        assertEquals("@Anna and @Ann!", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(10, result.spanStyles[0].start)
        assertEquals(14, result.spanStyles[0].end)
    }
}
