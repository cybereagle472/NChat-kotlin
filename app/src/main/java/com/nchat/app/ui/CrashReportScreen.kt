package com.nchat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the launch *after* a crash.
 *
 * The text is selectable and copyable on purpose: the whole point is that a user can paste
 * the reason into a message instead of describing "it keeps stopping".
 */
@Composable
fun CrashReportScreen(
    report: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "NChat stopped last time",
            color = Color(0xFFF8FAFC),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "The app saved the reason below. Copy it and send it back so it can be fixed.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp
        )
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF111827), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = report,
                color = Color(0xFFE2E8F0),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { clipboard.setText(AnnotatedString(report)) }
            ) {
                Text("Copy report")
            }
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = onDismiss
            ) {
                Text("Delete and start", color = Color(0xFF94A3B8))
            }
        }
    }
}
