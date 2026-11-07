package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme

/**
 * The emoji panel behind the smiley in the composer.
 *
 * A messenger without an emoji button makes people hunt for their keyboard's emoji drawer, and on
 * a lot of Android keyboards that is three taps deep. The set is deliberately small and curated —
 * the emoji a conversation actually uses — because a full Unicode picker is a search problem, and
 * this is a quick insert. Picked emoji are appended to the composer text.
 */
private data class EmojiGroup(val symbol: String, val label: String, val emoji: List<String>)

private val EMOJI_GROUPS = listOf(
    EmojiGroup(
        "😀", "Smileys",
        listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍",
            "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳",
            "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤",
            "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
            "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢",
            "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👻", "💀", "👽", "🤖", "💩"
        )
    ),
    EmojiGroup(
        "🙌", "Gestures",
        listOf(
            "👍", "👎", "👌", "🤌", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️",
            "👋", "🤚", "🖐️", "✋", "🖖", "👏", "🙌", "🫶", "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾",
            "🫡", "🫠", "🤷", "🤦", "🙆", "🙅", "💁", "🙋", "🧏", "🙇", "🤦‍♂️", "🤦‍♀️", "🙆‍♂️", "🙆‍♀️"
        )
    ),
    EmojiGroup(
        "❤️", "Hearts",
        listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗",
            "💖", "💘", "💝", "💟", "♥️", "💋", "💌", "🌹", "🌷", "🌺", "🌸", "💐", "🎁", "✨", "⭐",
            "🌟", "💫", "🔥", "💥", "❄️", "🌈", "☀️", "🌙", "☁️", "🌊"
        )
    ),
    EmojiGroup(
        "🐶", "Animals",
        listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵",
            "🐔", "🐧", "🐦", "🦆", "🦉", "🦄", "🐝", "🦋", "🐌", "🐢", "🐍", "🐙", "🦀", "🐬", "🐳",
            "🦈", "🐟", "🐠", "🐎", "🦌", "🐘", "🦒", "🐑", "🐐", "🌵", "🌴", "🌱", "🌻", "🍀"
        )
    ),
    EmojiGroup(
        "🍔", "Food",
        listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑", "🥭", "🍍", "🥥",
            "🥝", "🍅", "🥑", "🥦", "🥕", "🌽", "🌶️", "🥒", "🍞", "🥐", "🥖", "🧈", "🧀", "🍳", "🥞",
            "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🍜", "🍝", "🍣", "🍤", "🍚", "🍰", "🎂", "🍩",
            "🍪", "🍫", "🍬", "🍦", "☕", "🍵", "🧃", "🥤", "🍺", "🍷", "🥂", "🎉", "🎊"
        )
    ),
    EmojiGroup(
        "⚽", "Things",
        listOf(
            "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🎱", "🏓", "🏸", "🥊", "🏆", "🥇", "🎯", "🎮", "🎲",
            "🎸", "🎹", "🎤", "🎧", "🎬", "📷", "📱", "💻", "⌚", "💡", "🔑", "🔒", "💰", "💳", "📦",
            "✉️", "📝", "📌", "📎", "📍", "🗓️", "⏰", "⏳", "🚗", "🚕", "🚌", "🚲", "✈️", "🚀", "🏠",
            "🏥", "🏫", "🌍", "✅", "❌", "❓", "❗", "💯", "🔔", "🎵"
        )
    )
)

/** The panel itself: category strip on top, eight emoji per row underneath. */
@Composable
fun EmojiPickerPanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var group by remember { mutableStateOf(EMOJI_GROUPS.first()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF2FFFFFF))
            .padding(NcSpace.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EMOJI_GROUPS.forEach { entry ->
                val selected = entry == group
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) Color(0x1A8B5CF6) else Color.Transparent
                        )
                        .clickable { group = entry },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = entry.symbol, fontSize = 16.sp)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .padding(top = NcSpace.xs)
        ) {
            items(group.emoji) { emoji ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 21.sp)
                }
            }
        }

        Text(
            text = group.label,
            style = MaterialTheme.typography.labelSmall,
            color = NcTheme.extra.textMuted,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}
