package com.nchat.app.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One transient confirmation pill. [id] keeps repeated identical texts distinguishable. */
data class NcToastMessage(val text: String, val id: Long)

/**
 * Process-wide, state-backed toaster. The reference shows these through `sonner`; on Android
 * a single host at the root of the composition is enough, so callers never need a Context.
 */
object NcToaster {
    private val _message = MutableStateFlow<NcToastMessage?>(null)
    val message: StateFlow<NcToastMessage?> = _message.asStateFlow()

    private var sequence = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        sequence += 1
        _message.value = NcToastMessage(text = text, id = sequence)
    }

    fun dismiss() {
        _message.value = null
    }
}

/**
 * Host for [NcToaster]: a dark glass pill pinned near the bottom of the screen. It fades and
 * lifts in over 180 ms and auto-dismisses after 1.9 s. Place one per composition root — extra
 * hosts would show the same message twice.
 */
@Composable
fun NcToastHost(modifier: Modifier = Modifier) {
    val toast by NcToaster.message.collectAsStateWithLifecycle()
    var visibleText by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(toast?.id) {
        val current = toast
        if (current == null) {
            // Let the fade-out finish before dropping the text so it never flashes empty.
            delay(200)
            visibleText = null
        } else {
            visibleText = current.text
            delay(1900)
            NcToaster.dismiss()
        }
    }

    val visible = toast != null && visibleText != null
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "toastProgress"
    )
    val lift = with(density) { (12.dp * (1f - progress)).toPx() }

    Box(
        modifier = modifier
            .alpha(progress)
            .graphicsLayer { translationY = lift },
        contentAlignment = Alignment.Center
    ) {
        val text = visibleText
        if (text != null) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF21E293B))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
