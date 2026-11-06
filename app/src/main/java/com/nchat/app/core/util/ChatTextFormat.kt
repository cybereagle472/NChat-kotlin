package com.nchat.app.core.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * WhatsApp's inline formatting: `*bold*`, `_italic_`, `~strikethrough~`, `` `monospace` ``.
 *
 * The rules are the ones WhatsApp actually enforces, because the naive "wrap anything between two
 * markers" gets them wrong:
 *
 *  * the marker must be followed by a non-space (so `2 * 3 * 4` stays arithmetic);
 *  * the closing marker must be preceded by a non-space, and must exist on the same line;
 *  * a doubled marker (`**`) is literal text, not emphasis;
 *  * styles nest — `*bold _and italic_*` works.
 *
 * Parsing happens once per bubble (the result is remembered by the bubble against its text), so
 * this never runs during scrolling.
 */
fun chatFormatted(
    raw: String,
    /** Display names that should light up when they appear as `@Name` — the group's roster. */
    mentions: List<String> = emptyList(),
    /** How a mention is drawn. Null leaves `@Name` as ordinary text. */
    mentionStyle: SpanStyle? = null
): AnnotatedString = buildAnnotatedString {
    appendFormatted(raw, mentions, mentionStyle)
}

/**
 * Names are matched longest-first, so `@Nasir Khan` wins over `@Nasir` when both exist, and only
 * *known* names light up — a stray `@` followed by random words stays plain text, which is what
 * keeps `@` usable in ordinary sentences.
 */
private fun mentionAt(text: String, index: Int, mentions: List<String>): String? {
    if (text[index] != '@') return null
    return mentions
        .asSequence()
        .filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .firstOrNull { name ->
            text.regionMatches(index + 1, name, 0, name.length, ignoreCase = true)
        }
}

private fun AnnotatedString.Builder.appendFormatted(
    text: String,
    mentions: List<String> = emptyList(),
    mentionStyle: SpanStyle? = null
) {
    var index = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }

    while (index < text.length) {
        // A mention is checked before the formatting markers: `@` is never a marker, but the name
        // after it must be consumed whole.
        val mention = if (mentionStyle != null) mentionAt(text, index, mentions) else null
        if (mention != null) {
            flush()
            withStyle(mentionStyle) { append(text.substring(index, index + 1 + mention.length)) }
            index += 1 + mention.length
            continue
        }
        val marker = text[index]
        val style = styleFor(marker)
        if (style == null) {
            plain.append(marker)
            index++
            continue
        }
        // Needs real text right after the opener, and it must not be the marker itself.
        val afterOpen = text.getOrNull(index + 1)
        if (afterOpen == null || afterOpen.isWhitespace() || afterOpen == marker) {
            plain.append(marker)
            index++
            continue
        }
        val close = findClose(text, index + 1, marker)
        if (close < 0) {
            plain.append(marker)
            index++
            continue
        }
        flush()
        withStyle(style) { appendFormatted(text.substring(index + 1, close), mentions, mentionStyle) }
        index = close + 1
    }
    flush()
}

/** The marker's style, or null when the character is ordinary text. */
private fun styleFor(marker: Char): SpanStyle? = when (marker) {
    '*' -> SpanStyle(fontWeight = FontWeight.Bold)
    '_' -> SpanStyle(fontStyle = FontStyle.Italic)
    '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    '`' -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14000000))
    else -> null
}

/**
 * The index of the marker that closes a span opened at [from].
 *
 * A closer has to be preceded by a non-space, has to be a single marker (a `**` pair means the
 * user typed asterisks, not emphasis) and has to sit on the same line — `*bold` across a newline
 * is not bold in WhatsApp either.
 */
private fun findClose(text: String, from: Int, marker: Char): Int {
    var cursor = from
    while (cursor < text.length) {
        val current = text[cursor]
        if (current == '\n') return -1
        if (current == marker && cursor > from && !text[cursor - 1].isWhitespace()) {
            val next = text.getOrNull(cursor + 1)
            if (next != marker) return cursor
        }
        cursor++
    }
    return -1
}
