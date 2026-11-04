@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.nchat.app.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSize
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcTypingDots
import com.nchat.app.core.util.TimeFormat
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Message

@Composable
fun ConversationScreen(
    chatId: String,
    chatTitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = viewModel(
        key = chatId,
        factory = ConversationViewModel.factory(
            chatId = chatId,
            chatTitle = chatTitle,
            repository = ServiceLocator.chatRepository
        )
    )
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val activeMessageId by viewModel.activeMessageId.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val timeline = remember(messages) { buildTimeline(messages) }

    val typingRowCount = if (chat?.isTyping == true) 1 else 0
    LaunchedEffect(timeline.size, typingRowCount) {
        val lastIndex = timeline.lastIndex + typingRowCount
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NcTheme.extra.wallpaperTop, NcTheme.extra.wallpaperBottom)
                )
            )
    ) {
        ConversationHeader(
            chatTitle = chatTitle,
            chat = chat,
            onBack = onBack
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = NcSpace.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items = timeline, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.Separator -> DaySeparator(label = item.label)
                    is TimelineItem.Bubble -> MessageBubble(
                        message = item.message,
                        showAuthor = chat?.isGroup == true,
                        onLongPress = { viewModel.onSelectMessage(item.message.id) }
                    )
                }
            }

            if (chat?.isTyping == true) {
                item(key = "typing-indicator") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = NcTheme.extra.incomingBubble,
                            tonalElevation = 1.dp
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                                NcTypingDots()
                            }
                        }
                    }
                }
            }
        }

        MessageComposer(
            text = composerText,
            onTextChange = viewModel::onComposerChange,
            onSend = {
                viewModel.onSend()
                keyboard?.hide()
            }
        )
    }

    if (activeMessageId != null) {
        ReactionPicker(
            onDismiss = { viewModel.onSelectMessage(null) },
            onPick = viewModel::onReaction
        )
    }
}

@Composable
private fun ConversationHeader(
    chatTitle: String,
    chat: Chat?,
    onBack: () -> Unit
) {
    val status = when {
        chat?.isTyping == true -> "typing…"
        chat?.isOnline == true -> "online"
        chat?.isGroup == true -> "${chat.memberCount ?: 0} members"
        else -> "last seen recently"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.headerGradient))
            .statusBarsPadding()
            .padding(horizontal = NcSpace.xs, vertical = NcSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
        NcAvatar(
            name = chatTitle,
            emoji = chat?.avatarEmoji,
            size = 40.dp,
            showPresence = chat?.isGroup == false,
            isOnline = chat?.isOnline == true
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = NcSpace.sm)
        ) {
            Text(
                text = chatTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status,
                color = Color.White.copy(alpha = if (chat?.isTyping == true) 0.95f else 0.74f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        IconButton(onClick = { }) {
            Icon(Icons.Rounded.Videocam, contentDescription = "Video call", tint = Color.White)
        }
        IconButton(onClick = { }) {
            Icon(Icons.Rounded.Call, contentDescription = "Voice call", tint = Color.White)
        }
        IconButton(onClick = { }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = Color.White)
        }
    }
}

@Composable
private fun MessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    val canSend = text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(26.dp)
                )
                .padding(horizontal = NcSpace.xs, vertical = 2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = { }, modifier = Modifier.size(NcSize.touchTarget)) {
                Icon(
                    imageVector = Icons.Rounded.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = NcTheme.extra.mutedText
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NcTheme.extra.mutedText
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = NcSize.composerMaxHeight),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    maxLines = 5
                )
            }
            IconButton(onClick = { }, modifier = Modifier.size(NcSize.touchTarget)) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = "Attach",
                    tint = NcTheme.extra.mutedText
                )
            }
            IconButton(onClick = { }, modifier = Modifier.size(NcSize.touchTarget)) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = "Camera",
                    tint = NcTheme.extra.mutedText
                )
            }
        }

        Spacer(modifier = Modifier.width(NcSpace.sm))

        AnimatedContent(
            targetState = canSend,
            transitionSpec = {
                (scaleIn(tween(160), initialScale = 0.7f) + fadeIn(tween(160))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.7f) + fadeOut(tween(120)))
            },
            label = "sendOrMic"
        ) { showSend ->
            if (showSend) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = "Send message",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Record voice message",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionPicker(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emojis = listOf("❤️", "😂", "😮", "😢", "🙏", "👍", "🔥", "🎉")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = NcSpace.xl, vertical = NcSpace.sm)) {
            Text(
                text = "React to message",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NcSpace.lg),
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                emojis.forEach { emoji ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onPick(emoji) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 19.sp)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface TimelineItem {
    val key: String

    data class Separator(val label: String) : TimelineItem {
        override val key: String = "sep-$label"
    }

    data class Bubble(val message: Message) : TimelineItem {
        override val key: String = message.id
    }
}

private fun buildTimeline(messages: List<Message>): List<TimelineItem> {
    val timeline = mutableListOf<TimelineItem>()
    var lastLabel: String? = null
    messages.forEach { message ->
        val label = TimeFormat.daySeparator(message.timestamp)
        if (label != lastLabel) {
            timeline += TimelineItem.Separator(label)
            lastLabel = label
        }
        timeline += TimelineItem.Bubble(message)
    }
    return timeline
}
