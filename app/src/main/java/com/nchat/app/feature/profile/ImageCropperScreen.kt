package com.nchat.app.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.util.ImageCrop
import kotlin.math.roundToInt

private const val MinZoom = 1f
private const val MaxZoom = 4f

/**
 * `ImageCropper.tsx`: a black "Move and Scale" screen with a circular crop frame,
 * rule-of-thirds guides, drag / pinch / double-tap gestures and the zoom slider.
 */
@Composable
fun ImageCropperScreen(
    image: ImageBitmap,
    onCancel: () -> Unit,
    onCrop: (ImageBitmap) -> Unit
) {
    var zoom by remember { mutableFloatStateOf(MinZoom) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var framePx by remember { mutableFloatStateOf(900f) }

    fun clamp(current: Offset, zoomValue: Float, rotationValue: Float): Offset {
        val max = ImageCrop.maxPan(
            widthPx = image.width,
            heightPx = image.height,
            framePx = framePx,
            zoom = zoomValue,
            rotationDegrees = rotationValue
        )
        return Offset(
            current.x.coerceIn(-max.x, max.x),
            current.y.coerceIn(-max.y, max.y)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: cancel, title, confirm.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel",
                        tint = Color(0xE6FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Move and Scale",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
                        )
                        .clickable {
                            onCrop(
                                ImageCrop.cropToSquare(
                                    source = image,
                                    zoom = zoom,
                                    rotationDegrees = rotation,
                                    pan = pan,
                                    framePx = framePx
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Use photo",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val frameSide = minOf(maxWidth, maxHeight) * 0.92f
                val measuredPx = with(density) { frameSide.toPx() }
                SideEffect { framePx = measuredPx }

                Box(
                    modifier = Modifier
                        .size(frameSide)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A1A))
                        .pointerInput(image, measuredPx) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val nextZoom = (zoom * zoomChange).coerceIn(MinZoom, MaxZoom)
                                zoom = nextZoom
                                pan = clamp(pan + panChange, nextZoom, rotation)
                            }
                        }
                        .pointerInput(image, measuredPx) {
                            detectTapGestures(
                                onDoubleTap = {
                                    zoom = if (zoom > 1.1f) MinZoom else 2f
                                    pan = clamp(pan, zoom, rotation)
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = ImageCrop.coverScale(
                            image.width,
                            image.height,
                            size.minDimension
                        ) * zoom
                        val circle = Path().apply {
                            addOval(Rect(center = center, radius = size.minDimension / 2f))
                        }
                        clipPath(circle) {
                            withTransform({
                                translate(center.x + pan.x, center.y + pan.y)
                                rotate(rotation, pivot = Offset.Zero)
                                scale(scale, scale, pivot = Offset.Zero)
                            }) {
                                drawImage(
                                    image = image,
                                    topLeft = Offset(-image.width / 2f, -image.height / 2f),
                                    filterQuality = FilterQuality.High
                                )
                            }
                        }

                        // Rule-of-thirds guides over the photo.
                        val thirdW = size.width / 3f
                        val thirdH = size.height / 3f
                        val guide = Color(0x59FFFFFF)
                        val stroke = 1.dp.toPx()
                        drawLine(guide, Offset(thirdW, 0f), Offset(thirdW, size.height), stroke)
                        drawLine(guide, Offset(thirdW * 2, 0f), Offset(thirdW * 2, size.height), stroke)
                        drawLine(guide, Offset(0f, thirdH), Offset(size.width, thirdH), stroke)
                        drawLine(guide, Offset(0f, thirdH * 2), Offset(size.width, thirdH * 2), stroke)
                    }

                    // White ring around the crop circle.
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color(0xCCFFFFFF),
                            radius = size.minDimension / 2f - 1.dp.toPx(),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenWith,
                    contentDescription = null,
                    tint = Color(0x80FFFFFF),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Drag to reposition • Pinch to zoom • Double-tap to snap",
                    color = Color(0x80FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ZoomButton(Icons.Rounded.ZoomOut, "Zoom out") {
                        zoom = (zoom - 0.25f).coerceIn(MinZoom, MaxZoom)
                        pan = clamp(pan, zoom, rotation)
                    }
                    Slider(
                        value = zoom,
                        onValueChange = {
                            zoom = it.coerceIn(MinZoom, MaxZoom)
                            pan = clamp(pan, zoom, rotation)
                        },
                        valueRange = MinZoom..MaxZoom,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF8B5CF6),
                            inactiveTrackColor = Color(0x26FFFFFF)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    ZoomButton(Icons.Rounded.ZoomIn, "Zoom in") {
                        zoom = (zoom + 0.25f).coerceIn(MinZoom, MaxZoom)
                        pan = clamp(pan, zoom, rotation)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(zoom * 100).roundToInt()}%",
                        color = Color(0x80FFFFFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.size(width = 44.dp, height = 18.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x1AFFFFFF))
                            .clickable {
                                rotation = (rotation + 90f) % 360f
                                pan = clamp(pan, zoom, rotation)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RotateRight,
                            contentDescription = "Rotate",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Rotate",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(modifier = Modifier.size(44.dp))
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x1AFFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(17.dp)
        )
    }
}
