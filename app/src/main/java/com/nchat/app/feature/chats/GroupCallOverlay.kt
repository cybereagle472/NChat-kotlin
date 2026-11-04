package com.nchat.app.feature.chats

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcSpace
import kotlinx.coroutines.delay

private val GroupCallBackground = listOf(
    Color(0xFF0F172A),
    Color(0xFF2E1065),
    Color(0xFF0F172A)
)

private enum class GroupCallPhase { Calling, InCall, Ended }

/**
 * `GroupCallModal.tsx`: the "Calling everyone in …" screen with its violet ping ring, then
 * the two-column participant grid with per-tile connecting badges and the mute / camera /
 * speaker / hang-up controls.
 */
@Composable
fun GroupCallOverlay(
    title: String,
    emoji: String?,
    isVideo: Boolean,
    memberCount: Int,
    participants: List<String>,
    onClose: () -> Unit
) {
    var phase by remember { mutableStateOf(GroupCallPhase.Calling) }
    var joined by remember { mutableStateOf(0) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(true) }
    var videoOff by remember { mutableStateOf(false) }

    // Only show a handful of tiles, like the reference grid.
    val roster = remember(participants) { participants.distinct().take(5) }

    LaunchedEffect(Unit) {
        delay(2_400)
        phase = GroupCallPhase.InCall
        val joiners = minOf(roster.size, 3)
        repeat(joiners) {
            delay(900)
            joined += 1
        }
    }

    LaunchedEffect(phase) {
        if (phase != GroupCallPhase.InCall) return@LaunchedEffect
        while (true) {
            delay(1_000)
            elapsedSeconds += 1
        }
    }

    fun leave() {
        phase = GroupCallPhase.Ended
        onClose()
    }

    val subtitle = when (phase) {
        GroupCallPhase.Calling -> "Calling ${memberCount.coerceAtLeast(2)} people…"
        GroupCallPhase.InCall -> {
            val count = joined + 1
            val clock = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
            "$count in call · $clock"
        }
        GroupCallPhase.Ended -> "Call ended"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GroupCallBackground))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: close on the left, group name and status centred.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.md, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = ::leave),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = subtitle, color = Color(0xB3FFFFFF), fontSize = 12.sp)
                }
                Box(modifier = Modifier.size(40.dp))
            }

            when (phase) {
                GroupCallPhase.Calling -> CallingBody(
                    title = title,
                    emoji = emoji,
                    memberCount = memberCount,
                    onCancel = ::leave,
                    modifier = Modifier.weight(1f)
                )

                GroupCallPhase.InCall -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = NcSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CallTile(
                            name = "You",
                            emoji = null,
                            isSelf = true,
                            muted = muted,
                            status = "connected",
                            showVideo = isVideo && !videoOff
                        )
                        val tiles = roster.mapIndexed { index, name ->
                            name to (index < joined)
                        }
                        tiles.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { (name, connected) ->
                                    CallTile(
                                        name = name,
                                        emoji = null,
                                        isSelf = false,
                                        muted = !connected,
                                        status = if (connected) "connected" else "connecting",
                                        showVideo = isVideo,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isVideo) {
                            CircleToggle(
                                icon = if (videoOff) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                                active = !videoOff,
                                contentDescription = if (videoOff) "Turn camera on" else "Turn camera off",
                                onClick = { videoOff = !videoOff }
                            )
                        }
                        CircleToggle(
                            icon = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            active = !muted,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            onClick = { muted = !muted }
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = NcSpace.sm)
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                                .clickable(onClick = ::leave),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "Leave call",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        CircleToggle(
                            icon = if (speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                            active = speakerOn,
                            contentDescription = if (speakerOn) "Speaker off" else "Speaker on",
                            onClick = { speakerOn = !speakerOn }
                        )
                    }
                }

                GroupCallPhase.Ended -> Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CallingBody(
    title: String,
    emoji: String?,
    memberCount: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "groupCallPing")
    val ping by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart),
        label = "groupCallPingProgress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // animate-ping: a violet halo expanding and fading behind the avatar.
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer {
                        val scale = 1f + ping * 0.6f
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - ping) * 0.6f
                    }
                    .clip(CircleShape)
                    .background(Color(0x4D8B5CF6))
            )
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color(0x4D8B5CF6)),
                contentAlignment = Alignment.Center
            ) {
                if (emoji != null) {
                    Text(text = emoji, fontSize = 42.sp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }

        Text(
            text = "Calling everyone in $title…",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp)
        )
        Text(
            text = "$memberCount people",
            color = Color(0x80FFFFFF),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Box(
            modifier = Modifier
                .padding(top = 32.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFFEF4444))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CallEnd,
                contentDescription = "Cancel call",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = "Cancel",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun CallTile(
    name: String,
    emoji: String?,
    isSelf: Boolean,
    muted: Boolean,
    status: String,
    showVideo: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 140.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x991E293B)),
        contentAlignment = Alignment.Center
    ) {
        // Without a live stream the reference falls back to the gradient avatar.
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (showVideo) {
                        Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF1E1B4B)))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF7C3AED)))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (emoji != null) {
                Text(text = emoji, fontSize = 26.sp)
            } else {
                Text(
                    text = name.trim().take(1).uppercase().ifBlank { "?" },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isSelf) "$name (You)" else name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (muted) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MicOff,
                        contentDescription = "Muted",
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
            if (status != "connected") {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = status, color = Color(0xCCFFFFFF), fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun CircleToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) Color(0x26FFFFFF) else Color.White)
            .then(
                if (active) Modifier.border(1.dp, Color(0x33FFFFFF), CircleShape) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.White else Color(0xFF0F172A),
            modifier = Modifier.size(20.dp)
        )
    }
}
