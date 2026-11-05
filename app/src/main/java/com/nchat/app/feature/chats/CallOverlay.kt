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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.data.model.ActiveCall
import com.nchat.app.data.model.CallState
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.designsystem.pressFeedback
import kotlinx.coroutines.delay

private val CallBackground = listOf(
    Color(0xFF0F172A),
    Color(0xFF2E1065),
    Color(0xFF0F172A)
)

/**
 * The call screen, driven entirely by [ActiveCall].
 *
 * Ported from `CallModal.tsx` (gradient backdrop, brand glow, pulsing avatar rings, status
 * line, controls) with one deliberate difference: **nothing here is decided locally**. The
 * old version counted 1.6 seconds and then declared itself connected, on a call the server
 * had never heard of and the other phone never rang for. Every state below comes from the
 * worker (`POST /calls`, `call:incoming`, `call:answered`, `call:ended`), so "Ringing…" means
 * the other phone is actually ringing.
 *
 * Audio/video media itself needs WebRTC, which this build does not carry yet; while connected
 * the screen says so plainly rather than pretending to be a live line.
 */
@Composable
fun CallOverlay(
    call: ActiveCall,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit
) {
    // Live timer, anchored to the moment the server reported the answer.
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.state, call.startedAt) {
        elapsedSeconds = 0L
        if (call.state != CallState.Connected || call.startedAt <= 0L) return@LaunchedEffect
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - call.startedAt) / 1000L).coerceAtLeast(0L)
            delay(1_000)
        }
    }

    val connected = call.state == CallState.Connected
    val ringing = call.state == CallState.Ringing
    // "Incoming" is the only case that offers Answer; everything else can only be ended.
    val canAnswer = ringing && call.incoming

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(CallBackground))
    ) {
        // Blurred brand glow behind the content.
        Box(
            modifier = Modifier
                .size(420.dp)
                .align(Alignment.TopCenter)
                .graphicsLayer { translationY = -140.dp.toPx() }
                .background(
                    Brush.radialGradient(
                        listOf(NcPaletteViolet.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(NcSpace.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        call.isVideo -> "Video call"
                        else -> "Voice call"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (ringing) {
                        PulsingRing(delayMillis = 0)
                        PulsingRing(delayMillis = 700)
                    } else if (connected) {
                        ConnectedRing()
                    }
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(NcTheme.extra.brandGradient))
                            .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        NcAvatar(name = call.peerName, emoji = null, size = 144.dp)
                    }
                }

                Text(
                    text = call.peerName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = NcSpace.xxxl)
                )

                Row(
                    modifier = Modifier.padding(top = NcSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
                ) {
                    if (ringing) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.7f))
                        )
                    }
                    Text(
                        text = when {
                            connected -> formatCallDuration(elapsedSeconds)
                            call.state == CallState.Ended -> call.note ?: "Call ended"
                            canAnswer -> if (call.isVideo) "Incoming video call…" else "Incoming call…"
                            else -> if (call.isVideo) "Ringing… (video)" else "Ringing…"
                        },
                        color = when {
                            connected -> Color(0xFF4ADE80)
                            call.state == CallState.Ended -> Color(0xFFFCA5A5)
                            else -> Color.White.copy(alpha = 0.75f)
                        },
                        fontSize = 17.sp,
                        fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }

                if (connected) {
                    // The truth beats a silent line the user would read as "the app is broken".
                    Text(
                        text = "Audio isn't connected in this build — calling media is next",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = NcSpace.md, start = NcSpace.xl, end = NcSpace.xl)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = NcSpace.xxxl, top = NcSpace.xl),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    canAnswer -> {
                        CallControl(
                            icon = Icons.Rounded.CallEnd,
                            label = "Decline",
                            destructive = true,
                            onClick = onDecline
                        )
                        CallControl(
                            icon = Icons.Rounded.Call,
                            label = "Answer",
                            active = true,
                            onClick = onAnswer
                        )
                    }

                    call.state != CallState.Ended -> CallControl(
                        icon = Icons.Rounded.CallEnd,
                        label = if (ringing) "Cancel" else "End",
                        destructive = true,
                        onClick = onHangUp
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = rememberPressSource()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NcSpace.sm)
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .pressFeedback(interaction)
                .clip(CircleShape)
                .background(
                    when {
                        destructive -> Color(0xFFEF4444)
                        active -> Color.White
                        else -> Color.White.copy(alpha = 0.14f)
                    }
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active && !destructive) Color(0xFF111827) else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PulsingRing(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "callPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, delayMillis = delayMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "callPulseProgress"
    )
    Box(
        modifier = Modifier
            .size(144.dp)
            .graphicsLayer {
                val scale = 1f + progress * 0.55f
                scaleX = scale
                scaleY = scale
                alpha = (1f - progress) * 0.5f
            }
            .clip(CircleShape)
            .background(NcPaletteViolet.copy(alpha = 0.35f))
    )
}

@Composable
private fun ConnectedRing() {
    val transition = rememberInfiniteTransition(label = "connectedRing")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connectedRingAlpha"
    )
    Box(
        modifier = Modifier
            .size(160.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Color(0xFF4ADE80).copy(alpha = 0.18f))
    )
}

private val NcPaletteViolet = Color(0xFF8B5CF6)

private fun formatCallDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
