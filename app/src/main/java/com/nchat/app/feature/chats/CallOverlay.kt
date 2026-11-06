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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.data.model.ActiveCall
import com.nchat.app.data.model.CallState
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.TextureViewRenderer
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The call screen, driven entirely by [ActiveCall] **and the media engine**.
 *
 * Ported from `CallModal.tsx` (gradient backdrop, brand glow, pulsing rings, status line,
 * controls) with one rule: nothing here is decided locally. "Ringing…" means the other phone
 * is ringing, "Connecting…" means SDP is exchanged and ICE is still working, and the timer only
 * runs once ICE/DTLS actually connected.
 *
 * Video is real now: a video call renders the far end full-screen with our own camera in the
 * corner, both fed by `org.webrtc` surfaces. If the camera or microphone could not start, the
 * call continues and [ActiveCall.mediaError] says why instead of showing a black rectangle.
 */
@Composable
fun CallOverlay(
    call: ActiveCall,
    localTrack: VideoTrack?,
    remoteTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit = {},
    onToggleSpeaker: () -> Unit = {},
    onFlipCamera: () -> Unit = {}
) {
    /*
     * Keep the screen awake while this is on screen.
     *
     * Without it the display timed out a minute or two into a call, the app was throttled, the
     * peer connection went to DISCONNECTED — and the call was then killed for being "lost". A
     * call screen that lets the phone sleep is not a call screen. `keepScreenOn` on the view
     * covers the dialog this overlay lives in, which a window flag on the activity would not.
     */
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val previous = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = previous }
    }

    // Live timer, anchored to the moment media connected.
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.state, call.startedAt) {
        elapsedSeconds = 0L
        if (call.state != CallState.Live || call.startedAt <= 0L) return@LaunchedEffect
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - call.startedAt) / 1000L).coerceAtLeast(0L)
            delay(1_000)
        }
    }

    val live = call.state == CallState.Live
    val ringing = call.state == CallState.Ringing
    val connecting = call.state == CallState.Connecting
    val canAnswer = ringing && call.incoming
    val showRemoteVideo = call.isVideo && remoteTrack != null && eglContext != null &&
        (live || connecting)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(CallBackground))
    ) {
        if (showRemoteVideo) {
            // The far end, full bleed. `mirror = false`: their camera is not a mirror.
            VideoSurface(
                track = remoteTrack,
                egl = eglContext,
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
            // A scrim so the name, timer and buttons stay readable over any picture.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(CallScrim))
            )
        } else {
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
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- header
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(NcSpace.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (call.isVideo) "Video call" else "Voice call",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // ---------------------------------------------------------- centre
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!showRemoteVideo) {
                    Box(contentAlignment = Alignment.Center) {
                        if (ringing) {
                            PulsingRing(delayMillis = 0)
                            PulsingRing(delayMillis = 700)
                        } else if (live) {
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
                }

                Text(
                    text = call.peerName,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (showRemoteVideo) NcSpace.md else NcSpace.xxxl)
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
                        text = statusLine(call, live, ringing, connecting, canAnswer, elapsedSeconds),
                        color = when {
                            live -> Color(0xFF4ADE80)
                            call.state == CallState.Ended -> Color(0xFFFCA5A5)
                            else -> Color.White.copy(alpha = 0.8f)
                        },
                        fontSize = 17.sp,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }

                // The truth about a degraded call, rather than a silent line.
                call.mediaError?.let { reason ->
                    Text(
                        text = reason,
                        color = Color(0xFFFCA5A5).copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = NcSpace.sm, start = NcSpace.xl, end = NcSpace.xl)
                    )
                }
            }

            // ------------------------------------------------------- local preview
            if (call.isVideo && call.localVideo && eglContext != null && (live || connecting)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = NcSpace.lg, bottom = NcSpace.md),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Our own camera, mirrored like every camera app does.
                    LocalPreview(
                        track = localTrack,
                        egl = eglContext,
                        modifier = Modifier
                            .width(104.dp)
                            .height(148.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    )
                }
            }

            // ----------------------------------------------------------- controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = NcSpace.xxxl, top = NcSpace.sm, start = NcSpace.lg, end = NcSpace.lg),
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

                    call.state == CallState.Ended -> Unit

                    else -> {
                        CallControl(
                            icon = if (call.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (call.micMuted) "Unmute" else "Mute",
                            active = call.micMuted,
                            onClick = onToggleMute
                        )
                        CallControl(
                            icon = if (call.speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                            label = "Speaker",
                            active = call.speakerOn,
                            onClick = onToggleSpeaker
                        )
                        if (call.isVideo && call.localVideo) {
                            CallControl(
                                icon = Icons.Rounded.Cameraswitch,
                                label = "Flip",
                                onClick = onFlipCamera
                            )
                        }
                        CallControl(
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
}

private fun statusLine(
    call: ActiveCall,
    live: Boolean,
    ringing: Boolean,
    connecting: Boolean,
    canAnswer: Boolean,
    elapsedSeconds: Long
): String = when {
    live && call.reconnecting -> "Reconnecting…"
    live -> formatCallDuration(elapsedSeconds)
    call.state == CallState.Ended -> call.note ?: "Call ended"
    connecting -> "Connecting…"
    canAnswer -> if (call.isVideo) "Incoming video call…" else "Incoming call…"
    ringing -> if (call.isVideo) "Ringing… (video)" else "Ringing…"
    else -> "Call ended"
}

/**
 * One `org.webrtc` surface.
 *
 * The renderer is created in `factory` with the engine's EGL context, and the track is
 * attached once per track change (tracked on the view's tag so recomposition cannot double-add
 * the same sink, which shows up as a frozen or black picture).
 */
@Composable
private fun VideoSurface(
    track: VideoTrack?,
    egl: EglBase.Context?,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    if (track == null || egl == null) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                // No `setZOrderMediaOverlay` here: that put this surface on a separate compositor
                // layer, which is what turned the small self-view into a black hole. The
                // full-screen far-end picture is the only SurfaceView left in this window.
            }
        },
        update = { view ->
            val attached = view.tag as? VideoTrack
            if (attached !== track) {
                attached?.removeSink(view)
                track.addSink(view)
                view.tag = track
            }
        },
        onRelease = { view ->
            (view.tag as? VideoTrack)?.let { current ->
                runCatching { current.removeSink(view) }
            }
            view.tag = null
            runCatching { view.release() }
        }
    )
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

private val CallBackground = listOf(
    Color(0xFF0F172A),
    Color(0xFF2E1065),
    Color(0xFF0F172A)
)

/** Top/bottom shade so the timer and controls stay legible over a bright video frame. */
private val CallScrim = listOf(
    Color.Black.copy(alpha = 0.45f),
    Color.Transparent,
    Color.Black.copy(alpha = 0.55f)
)

private val NcPaletteViolet = Color(0xFF8B5CF6)

private fun formatCallDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * Our own camera, in the corner of a video call.
 *
 * A [TextureViewRenderer] rather than a [SurfaceViewRenderer], and that is the fix. A SurfaceView
 * is composited by the window manager on a layer of its own: a small tile inside a clipped,
 * rounded, bordered Compose row becomes a perfectly-sized black hole, while the full-screen
 * far-end picture — a plain, unclipped SurfaceView — is fine. That is exactly the reported
 * symptom, and it is also why the other person could see us perfectly: capture, encoding and the
 * peer connection were never involved in this bug, only the drawing of our own preview. A
 * TextureView is an ordinary view, so Compose's clip, corners and z-order all apply to it.
 *
 * The tile stays covered until the renderer reports a genuine first frame, so a camera that is
 * still warming up says "Starting camera…" instead of showing black — a black rectangle is
 * indistinguishable from a broken camera, which is what this looked like.
 */
@Composable
private fun LocalPreview(
    track: VideoTrack?,
    egl: EglBase.Context?,
    modifier: Modifier = Modifier
) {
    if (track == null || egl == null) return
    var playing by remember(track) { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureViewRenderer(ctx).apply {
                    init(egl, FirstFrameWatcher { main.post { playing = true } })
                    setMirror(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                }
            },
            update = { view ->
                val attached = view.tag as? VideoTrack
                if (attached !== track) {
                    attached?.let { previous -> runCatching { previous.removeSink(view) } }
                    track.addSink(view)
                    view.tag = track
                }
            },
            // `onReset` is what makes a recycled view safe: without it a renderer can be reused
            // by a later composition still bound to the previous track's frames.
            onReset = { view ->
                (view.tag as? VideoTrack)?.let { previous -> runCatching { previous.removeSink(view) } }
                view.tag = null
            },
            onRelease = { view ->
                (view.tag as? VideoTrack)?.let { previous -> runCatching { previous.removeSink(view) } }
                view.tag = null
                runCatching { view.release() }
            }
        )
        if (!playing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1F27)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Starting\ncamera…",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** The renderer's very first real frame, delivered on its own thread. */
private class FirstFrameWatcher(private val onFirstFrame: () -> Unit) : RendererCommon.RendererEvents {
    override fun onFirstFrameRendered() = onFirstFrame()
    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
}
