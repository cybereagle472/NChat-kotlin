package com.nchat.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.designsystem.pressFeedback

/**
 * Port of `ProfileView.tsx`: gradient title bar, the 96dp avatar inside a violet ring,
 * name / @handle / presence line, the slate-50 "About" card and the three round
 * Message / Call / Video actions.
 */
@Composable
fun ProfileViewScreen(
    name: String,
    handle: String,
    about: String,
    isOnline: Boolean,
    emoji: String?,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onCall: (video: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val extra = NcTheme.extra
    var menuOpen by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp)
                .background(Brush.linearGradient(extra.brandGradient))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NcSpace.sm, vertical = NcSpace.sm),
                verticalAlignment = Alignment.CenterVertically
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
                Text(
                    text = "Profile",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (blocked) "Unblock user" else "Block user",
                                fontSize = 14.sp,
                                color = if (blocked) Color(0xFF16A34A) else Color(0xFF334155)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = null,
                                tint = if (blocked) Color(0xFF16A34A) else extra.textTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            blocked = !blocked
                            menuOpen = false
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(top = NcSpace.xxl)
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEDE9FE))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                NcAvatar(name = name, emoji = emoji, size = 96.dp)
            }

            Text(
                text = name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NcSpace.md)
            )
            Text(
                text = "@$handle",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF7C3AED),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isOnline) "online" else "last seen recently",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(top = 2.dp)
            )

            if (blocked) {
                Text(
                    text = "You blocked $name",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFDC2626),
                    modifier = Modifier
                        .padding(horizontal = NcSpace.xl, vertical = NcSpace.lg)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFEF2F2))
                        .border(1.dp, Color(0xFFFEE2E2), RoundedCornerShape(16.dp))
                        .padding(NcSpace.md),
                    textAlign = TextAlign.Center
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = NcSpace.xl, vertical = NcSpace.lg)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF8FAFC))
                    .padding(NcSpace.lg)
            ) {
                Text(
                    text = "ABOUT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = about,
                    fontSize = 14.sp,
                    color = Color(0xFF334155),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NcSpace.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAction(Icons.Rounded.ChatBubbleOutline, "Message", onClick = onMessage)
                ProfileAction(Icons.Rounded.Call, "Call", onClick = { onCall(false) })
                ProfileAction(Icons.Rounded.Videocam, "Video", onClick = { onCall(true) })
            }
        }
    }
}

@Composable
private fun ProfileAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = rememberPressSource()
    Column(
        modifier = Modifier.padding(horizontal = NcSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .pressFeedback(interaction)
                .clip(CircleShape)
                .background(Color(0xFFEDE9FE))
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
                tint = Color(0xFF7C3AED),
                modifier = Modifier.size(20.dp)
            )
        }
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF7C3AED))
    }
}
