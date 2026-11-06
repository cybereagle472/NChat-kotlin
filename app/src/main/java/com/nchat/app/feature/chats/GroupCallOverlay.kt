package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.data.repository.GroupCallPeer
import com.nchat.app.data.repository.GroupCallState
import com.nchat.app.data.repository.GroupCallUi
import kotlinx.coroutines.delay

/**
 * A group **voice** call in progress.
 *
 * Deliberately not a grid of video tiles: group video needs one camera feeding several encoders,
 * which is a different problem from the mesh itself, so this screen is honest about what it is —
 * a call with people, their names, who is connected and who is muted, plus the controls.
 *
 * The state lives in `CallRepository` (the overlay is drawn once by `HomeRoot`), so the call
 * survives navigating away from the chat, exactly like a 1:1 call does.
 */
@Composable
fun GroupCallOverlay(
    call: GroupCallUi,
    onHangUp: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var elapsed by remember(call.startedAt) { mutableStateOf(0L) }
    LaunchedEffect(call.startedAt, call.state) {
        if (call.state != GroupCallState.Live) return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - call.startedAt) / 1000L
            delay(1_000)
        }
    }

    Dialog(
        onDismissRequest = { /* a call is only left by hanging up */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0F172A))))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NcSpace.lg, vertical = NcSpace.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Text(
                        text = call.groupName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        text = when {
                            call.note != null -> call.note
                            call.state == GroupCallState.Ended -> "Call ended"
                            call.state == GroupCallState.Connecting -> "Connecting…"
                            else -> "${call.peers.size} in call · ${clock(elapsed)}"
                        },
                        color = Color(0xB3FFFFFF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (call.peers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "You're the first one here.\nWhoever joins will be listed below.",
                            color = Color(0x99FFFFFF),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = NcSpace.lg,
                            vertical = NcSpace.sm
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = call.peers, key = { it.id }) { peer ->
                            GroupPeerRow(peer = peer)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NcSpace.lg, vertical = NcSpace.lg),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupControl(
                        icon = if (call.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        label = if (call.micMuted) "Unmute" else "Mute",
                        background = if (call.micMuted) Color(0xFFFFFFFF) else Color(0x33FFFFFF),
                        tint = if (call.micMuted) Color(0xFF0F172A) else Color.White,
                        onClick = onToggleMic
                    )
                    GroupControl(
                        icon = if (call.speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        label = if (call.speakerOn) "Speaker" else "Earpiece",
                        background = Color(0x33FFFFFF),
                        tint = Color.White,
                        onClick = onToggleSpeaker
                    )
                    GroupControl(
                        icon = Icons.Rounded.CallEnd,
                        label = "Leave",
                        background = Color(0xFFDC2626),
                        tint = Color.White,
                        onClick = onHangUp
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPeerRow(peer: GroupCallPeer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1FFFFFFF))
            .padding(horizontal = NcSpace.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NcSpace.md)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF8B5CF6)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peer.name.take(1).uppercase(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (peer.connected) "Connected" else "Connecting…",
                color = if (peer.connected) Color(0xFF86EFAC) else Color(0x99FFFFFF),
                fontSize = 11.sp
            )
        }
        Icon(
            imageVector = if (peer.connected) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            contentDescription = null,
            tint = Color(0x99FFFFFF),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun GroupControl(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = label, color = Color(0xCCFFFFFF), fontSize = 11.sp)
    }
}

private fun clock(seconds: Long): String =
    String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
