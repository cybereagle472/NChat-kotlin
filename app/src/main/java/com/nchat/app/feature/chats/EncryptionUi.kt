package com.nchat.app.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.network.PeerSecurity
import com.nchat.app.network.SecurityCode

private val NoticeBackground = Color(0xFFFFFBEB)
private val NoticeText = Color(0xFF78350F)
private val NoticeAccent = Color(0xFFB45309)
private val VerifiedGreen = Color(0xFF059669)

/**
 * The line under a chat's header while a change of the other person's security code is
 * unacknowledged.
 *
 * It stays until the user answers it: a person's keys change when they sign in again or change
 * phone, and also when somebody has put themselves in between, and the app cannot tell which.
 * [acceptLabel] is null when there is nothing to accept yet.
 */
@Composable
fun SecurityChangeBanner(
    notice: String,
    acceptLabel: String?,
    onView: () -> Unit,
    onAccept: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NoticeBackground)
            .padding(start = NcSpace.md, end = NcSpace.sm, top = 8.dp, bottom = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = NoticeAccent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp)
            )
            Text(
                text = notice,
                color = NoticeText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetAction(label = "View code", color = NoticeAccent, onClick = onView)
            if (acceptLabel != null) {
                SheetAction(label = acceptLabel, color = NoticeAccent, onClick = onAccept)
            }
        }
    }
}

/**
 * A chat's encryption sheet: what is protected, the security code to compare with the other
 * person, and the user's own record of having compared it.
 *
 * [loadCode] works the code out for a key (ten thousand hashes, so off the main thread) and
 * answers null when that key is no longer the other person's. Everything the user says here
 * names the key the code on the screen was worked out for: a tick given while the keys change
 * underneath must not land on keys nobody looked at.
 */
@Composable
fun SecurityCodeDialog(
    name: String,
    security: PeerSecurity?,
    loadCode: suspend (key: String) -> String?,
    onSetVerified: (key: String, verified: Boolean) -> Unit,
    onAccept: (key: String) -> Unit,
    onDismiss: () -> Unit
) {
    val extra = NcTheme.extra
    val key = security?.key
    // The key travels with its code, so that a code is never shown under keys it does not belong to.
    val worked by produceState<Pair<String, String?>?>(initialValue = null, key) {
        value = null
        if (key != null) value = key to loadCode(key)
    }
    val code = worked?.takeIf { it.first == key }?.second

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(NcSpace.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Encryption",
                    color = extra.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = encryptionSummary(name, security),
                color = extra.textSecondary,
                fontSize = 13.sp
            )

            if (security != null && key != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Security code",
                    color = extra.textTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                when {
                    worked == null || worked?.first != key -> CodeNote("Working out the code…")
                    code == null -> CodeNote("The code can't be shown right now. Close this and open it again in a moment.")
                    else -> SecurityCodeGrid(code)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Compare this number with the one $name sees in this chat — side by side, or " +
                        "read aloud on a call. If the two are the same, nobody is in between.",
                    color = extra.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (security.verified) VerifiedGreen else extra.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (security.verified) "You marked this code as verified" else "Not verified",
                        color = if (security.verified) VerifiedGreen else extra.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val accent = MaterialTheme.colorScheme.primary
                if (security != null && key != null) {
                    if (security.verified) {
                        SheetAction(label = "Remove tick", color = extra.textTertiary) { onSetVerified(key, false) }
                    } else {
                        if (security.changed) {
                            SheetAction(label = securityAcceptLabel(security) ?: "OK", color = accent) { onAccept(key) }
                        }
                        // Only a code that is on the screen can have been compared.
                        if (code != null) {
                            SheetAction(label = "Mark as verified", color = accent) { onSetVerified(key, true) }
                        }
                    }
                }
                SheetAction(label = "Close", color = accent, onClick = onDismiss)
            }
        }
    }
}

/** Sixty digits as four rows of three groups: narrow enough for a small screen and a large font. */
@Composable
private fun SecurityCodeGrid(code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3F4F6))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SecurityCode.grouped(code).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { group ->
                    Text(
                        text = group,
                        color = NcTheme.extra.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeNote(text: String) {
    Text(
        text = text,
        color = NcTheme.extra.textTertiary,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3F4F6))
            .padding(horizontal = 12.dp, vertical = 14.dp)
    )
}

@Composable
private fun SheetAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
}
