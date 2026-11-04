package com.nchat.app.feature.chats

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme

/** Where the recorder currently is in the hold → lock → pause lifecycle. */
enum class VoicePhase { Holding, Locked, Paused }

/** Everything the recording strip needs to render one frame. */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Holding,
    val durationMs: Long = 0L,
    val waveform: List<Float> = emptyList(),
    /** 0..1 — how far the finger has slid left towards cancelling. */
    val cancelProgress: Float = 0f,
    /** 0..1 — how far the finger has slid up towards locking. */
    val lockProgress: Float = 0f
)

/** `00:04` style timer used by the strip and the sent voice bubble. */
fun formatVoiceDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * The recording bar from `ChatView.tsx`: gradient shell, live dot + timer + waveform on
 * top, then either the "slide to cancel / slide up to lock" guidance while the mic is
 * held, or the delete / pause / send controls once the recording is locked.
 */
@Composable
fun VoiceRecordingStrip(
    state: VoiceUiState,
    onDelete: () -> Unit,
    onTogglePause: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locked = state.phase != VoicePhase.Holding
    val extra = NcTheme.extra

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(extra.composerGradient))
            .padding(horizontal = NcSpace.lg, vertical = NcSpace.md)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
            ) {
                RecordingDot(paused = state.phase == VoicePhase.Paused)
                Text(
                    text = formatVoiceDuration(state.durationMs),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Waveform(
                    values = state.waveform,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NcSpace.sm)
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!locked) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { translationX = -state.cancelProgress * 90.dp.toPx() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (state.cancelProgress > 0.02f) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .alpha(state.cancelProgress)
                                    .padding(end = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Release to cancel",
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.ChevronLeft,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp)
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronLeft,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 0.dp)
                            )
                            Text(
                                text = "Slide to cancel",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        }
                    }

                    LockCue(progress = state.lockProgress)
                    MicBubble(cancelProgress = state.cancelProgress)
                } else {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                    ) {
                        CircleControl(
                            icon = Icons.Rounded.Delete,
                            contentDescription = "Delete recording",
                            onClick = onDelete
                        )
                        CircleControl(
                            icon = if (state.phase == VoicePhase.Paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (state.phase == VoicePhase.Paused) "Resume recording" else "Pause recording",
                            onClick = onTogglePause
                        )
                        Text(
                            text = if (state.phase == VoicePhase.Paused) "Paused" else "Recording…",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable(onClick = onSend),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Send,
                                contentDescription = "Send voice message",
                                tint = extra.typing,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingDot(paused: Boolean) {
    val transition = rememberInfiniteTransition(label = "recordingDot")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingDotPulse"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .alpha(if (paused) 0.4f else pulse)
            .clip(CircleShape)
            .background(Color(0xFFF87171))
    )
}

@Composable
private fun Waveform(values: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        values.takeLast(20).forEach { value ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4f + value.coerceIn(0f, 1f) * 22f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
private fun LockCue(progress: Float) {
    Row(
        modifier = Modifier
            .graphicsLayer {
                translationY = -progress * 14.dp.toPx()
                val scale = 1f + progress * 0.1f
                scaleX = scale
                scaleY = scale
            }
            .alpha(0.6f + progress * 0.4f)
            .padding(horizontal = NcSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.xs)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "Slide up to lock",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun MicBubble(cancelProgress: Float) {
    Box(
        modifier = Modifier
            .graphicsLayer { translationX = -cancelProgress * 90.dp.toPx() }
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Recording",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CircleControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
