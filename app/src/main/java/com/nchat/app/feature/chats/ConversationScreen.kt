@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.nchat.app.feature.chats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nchat.app.core.designsystem.NcAvatar
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
        factory = ConversationViewModel.factory(chatId, chatTitle, ServiceLocator.chatRepository)
    )
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val activeMessageId by viewModel.activeMessageId.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val timeline = remember(messages) { buildTimeline(messages) }
    val typingRow = if (chat?.isTyping == true) 1 else 0

    LaunchedEffect(timeline.size, typingRow) {
        val last = timeline.lastIndex + typingRow
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(NcTheme.extra.bodyGradient))
    ) {
        ConversationHeader(chatTitle = chatTitle, chat = chat, onBack = onBack)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NcSpace.md, vertical = NcSpace.md),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items = timeline, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.Separator -> DateSeparatorPill(label = item.label)
                    is TimelineItem.Bubble -> MessageBubble(
                        message = item.message,
                        onLongPress = { viewModel.onSelectMessage(item.message.id) }
                    )
                }
            }
            if (chat?.isTyping == true) {
                item(key = "typing") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .shadow(1.dp, RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                                .background(Color.White)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            NcTypingDots()
                        }
                    }
                }
            }
        }

        ComposerBar(
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
private fun ConversationHeader(chatTitle: String, chat: Chat?, onBack: () -> Unit) {
    val status = when {
        chat?.isTyping == true -> "typing…"
        chat?.isOnline == true -> "online"
        chat?.isGroup == true -> "group chat"
        else -> "last seen recently"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
            .shadow(8.dp)
            .statusBarsPadding()
            .padding(horizontal = NcSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        NcAvatar(
            name = chatTitle,
            emoji = chat?.avatarEmoji,
            size = 40.dp,
            onGradient = true
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chatTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status,
                color = Color(0xCCFFFFFF),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }

        HeaderAction(Icons.Rounded.Videocam, "Video call")
        HeaderAction(Icons.Rounded.Call, "Voice call")
        HeaderAction(Icons.Rounded.MoreVert, "More options")
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    var mediaOpen by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (mediaOpen) 45f else 0f,
        animationSpec = tween(220),
        label = "plusRotation"
    )
    val hasText = text.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = NcSpace.md, vertical = NcSpace.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(NcTheme.extra.composerGradient))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x26FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .rotate(rotation)
                        .clickable { mediaOpen = !mediaOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (mediaOpen) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = "Attach",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Type a message...",
                            color = Color(0x99FFFFFF),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        maxLines = 4
                    )
                }

                AnimatedContent(
                    targetState = hasText,
                    transitionSpec = {
                        (scaleIn(tween(150), initialScale = 0.7f) + fadeIn(tween(150))) togetherWith
                            (scaleOut(tween(120), targetScale = 0.7f) + fadeOut(tween(120)))
                    },
                    label = "sendButton"
                ) { showSend ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showSend) Color.White else Color(0x33FFFFFF))
                            .clickable(enabled = showSend, onClick = onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = if (showSend) "Send message" else "Record voice message",
                            tint = if (showSend) NcTheme.extra.typing else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val emojis = listOf("❤️", "😂", "😮", "😢", "🙏", "👍", "🔥", "🎉")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = NcSpace.xl, vertical = NcSpace.sm)) {
            Text(
                text = "React to message",
                style = MaterialTheme.typography.titleSmall,
                color = NcTheme.extra.textSecondary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NcSpace.lg),
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                emojis.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 19.sp)
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
