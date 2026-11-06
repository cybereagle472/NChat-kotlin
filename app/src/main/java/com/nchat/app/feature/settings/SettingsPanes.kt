package com.nchat.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.core.designsystem.NcAvatar
import com.nchat.app.core.designsystem.NcBackdrop
import com.nchat.app.core.designsystem.NcSpace
import com.nchat.app.core.designsystem.NcTheme
import com.nchat.app.core.designsystem.NcToaster
import com.nchat.app.core.designsystem.NcToggle
import com.nchat.app.core.designsystem.pressFeedback
import com.nchat.app.core.designsystem.rememberPressSource
import com.nchat.app.core.diagnostics.CheckRow
import com.nchat.app.core.diagnostics.SyncCheck
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.local.StorageEntry
import com.nchat.app.data.model.BlockedContact
import androidx.compose.foundation.lazy.items
import com.nchat.app.network.ApiLog
import kotlinx.coroutines.launch
import com.nchat.app.data.repository.NotificationSettings
import com.nchat.app.data.repository.PrivacySettings

private val PaneTitle = Color(0xFF1E293B)
private val PaneBody = Color(0xFF64748B)
private val PaneValue = Color(0xFF7C3AED)

/** Shared chrome for the settings sub-pages: gradient header with a back pill + list body. */
@Composable
private fun SettingsPaneScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    NcBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(NcTheme.extra.brandGradient))
                    .statusBarsPadding()
                    .padding(horizontal = NcSpace.md, vertical = NcSpace.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val interaction = rememberPressSource()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .pressFeedback(interaction)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = onBack
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = NcSpace.md)
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            color = Color(0xCCFFFFFF),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NcSpace.md),
                verticalArrangement = Arrangement.spacedBy(NcSpace.sm),
                content = content
            )
        }
    }
}

/** White glass card that groups related rows. */
@Composable
private fun PaneSection(title: String? = null, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                color = PaneBody,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = NcSpace.xs, top = NcSpace.xs, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCCFFFFFF))
                .border(1.dp, Color(0xCCFFFFFF), RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    last: Boolean = false
) {
    val interaction = rememberPressSource()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier
                            .pressFeedback(interaction, pressedScale = 0.99f)
                            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PaneTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = PaneBody,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    color = PaneValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFFCBD5E1),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp)
                )
            }
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 14.dp),
                thickness = 1.dp,
                color = Color(0xFFF1F5F9)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    last: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PaneTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = PaneBody,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.size(NcSpace.sm))
            NcToggle(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 14.dp),
                thickness = 1.dp,
                color = Color(0xFFF1F5F9)
            )
        }
    }
}

/** Settings ▸ Privacy: who can see what, plus the two behaviour switches. */
@Composable
fun PrivacyPane(
    settings: PrivacySettings,
    onBack: () -> Unit,
    onLastSeenChange: () -> Unit,
    onProfilePhotoChange: () -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onOnlineStatusChange: (Boolean) -> Unit
) {
    SettingsPaneScaffold(
        title = "Privacy",
        subtitle = "Who can see my info",
        onBack = onBack
    ) {
        item(key = "visibility") {
            PaneSection(title = "Who can see my info") {
                SettingRow(
                    title = "Last seen",
                    subtitle = "When you were last online",
                    value = settings.lastSeen.label,
                    onClick = onLastSeenChange
                )
                SettingRow(
                    title = "Profile photo",
                    subtitle = "Your avatar in search results",
                    value = settings.profilePhoto.label,
                    onClick = onProfilePhotoChange,
                    last = true
                )
            }
        }

        item(key = "other") {
            PaneSection(title = "Other") {
                SwitchRow(
                    title = "Read receipts",
                    subtitle = "Show blue ticks when you read a message",
                    checked = settings.readReceipts,
                    onCheckedChange = onReadReceiptsChange
                )
                SwitchRow(
                    title = "Online status",
                    subtitle = "Let friends see when you are active",
                    checked = settings.onlineStatus,
                    onCheckedChange = onOnlineStatusChange,
                    last = true
                )
            }
        }

        item(key = "privacy-footer") {
            Text(
                text = "Changing these settings affects what other people see about you. " +
                    "Existing chats keep their own history.",
                color = PaneBody,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = NcSpace.xs, vertical = NcSpace.xs)
            )
        }
    }
}

/** Settings ▸ Notifications: the five switches from the reference notification pane. */
@Composable
fun NotificationsPane(
    settings: NotificationSettings,
    onBack: () -> Unit,
    onMessagesChange: (Boolean) -> Unit,
    onCallsChange: (Boolean) -> Unit,
    onPreviewChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit
) {
    SettingsPaneScaffold(
        title = "Notifications",
        subtitle = "Messages, calls, sounds",
        onBack = onBack
    ) {
        item(key = "messages") {
            PaneSection(title = "Message notifications") {
                SwitchRow(
                    title = "Messages",
                    subtitle = "Alert me for new messages",
                    checked = settings.messages,
                    onCheckedChange = onMessagesChange
                )
                SwitchRow(
                    title = "Show preview",
                    subtitle = "Include the message text in the alert",
                    checked = settings.preview,
                    onCheckedChange = onPreviewChange,
                    last = true
                )
            }
        }

        item(key = "calls") {
            PaneSection(title = "Call notifications") {
                SwitchRow(
                    title = "Calls",
                    subtitle = "Ring for incoming voice and video calls",
                    checked = settings.calls,
                    onCheckedChange = onCallsChange,
                    last = true
                )
            }
        }

        item(key = "sound") {
            PaneSection(title = "Sound") {
                SwitchRow(
                    title = "Sound",
                    subtitle = "Play a tone for new messages",
                    checked = settings.sound,
                    onCheckedChange = onSoundChange
                )
                SwitchRow(
                    title = "Vibrate",
                    subtitle = "Buzz when a message arrives",
                    checked = settings.vibrate,
                    onCheckedChange = onVibrateChange,
                    last = true
                )
            }
        }
    }
}

/**
 * Settings ▸ Storage and data.
 *
 * Every row is measured from a directory this app owns (`StorageReport`), so the numbers are real
 * instead of a guess, and clearing one only ever touches that directory. Rows whose loss is
 * *invisible* — the message cache, the drafts-and-lists file — are marked and confirmed, because
 * "free up 40 MB" should not quietly cost somebody their unsent paragraph.
 */
@Composable
fun StoragePane(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<StorageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingClear by remember { mutableStateOf<StorageEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        entries = com.nchat.app.data.local.StorageReport.scanAsync(context)
        loading = false
    }

    val total = entries.sumOf { it.bytes }

    SettingsPaneScaffold(
        title = "Storage and data",
        subtitle = if (loading) "Measuring…" else "${com.nchat.app.data.local.StorageReport.format(total)} on this device",
        onBack = onBack
    ) {
        item(key = "summary") {
            PaneSection(title = "Used by NChat") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = com.nchat.app.data.local.StorageReport.format(total),
                            color = PaneTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Media, message history and updates",
                            color = PaneBody,
                            fontSize = 11.sp
                        )
                    }
                    if (total > 0L) {
                        Text(
                            text = "Clear all",
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        busy = "all"
                                        entries.forEach { entry ->
                                            com.nchat.app.data.local.StorageReport.clear(context, entry.key)
                                        }
                                        entries = com.nchat.app.data.local.StorageReport.scanAsync(context)
                                        busy = null
                                        NcToaster.show("Storage cleared")
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        items(items = entries, key = { it.key }) { entry ->
            PaneSection {
                StorageRow(
                    entry = entry,
                    busy = busy == entry.key,
                    onClear = {
                        if (entry.destructive) pendingClear = entry else {
                            scope.launch {
                                busy = entry.key
                                com.nchat.app.data.local.StorageReport.clear(context, entry.key)
                                entries = com.nchat.app.data.local.StorageReport.scanAsync(context)
                                busy = null
                                NcToaster.show("Cleared ${entry.label.lowercase()}")
                            }
                        }
                    }
                )
            }
        }
    }

    pendingClear?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("Clear ${entry.label.lowercase()}?", fontWeight = FontWeight.SemiBold) },
            text = { Text(entry.detail, fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClear = null
                        scope.launch {
                            busy = entry.key
                            com.nchat.app.data.local.StorageReport.clear(context, entry.key)
                            entries = com.nchat.app.data.local.StorageReport.scanAsync(context)
                            busy = null
                            NcToaster.show("Cleared ${entry.label.lowercase()}")
                        }
                    }
                ) {
                    Text("Clear", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text("Cancel", color = PaneBody)
                }
            }
        )
    }
}

@Composable
private fun StorageRow(entry: StorageEntry, busy: Boolean, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = PaneTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${com.nchat.app.data.local.StorageReport.format(entry.bytes)} · ${entry.detail}",
                color = PaneBody,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.size(NcSpace.sm))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = PaneTitle
            )
        } else if (entry.bytes > 0L) {
            Text(
                text = "Clear",
                color = if (entry.destructive) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** Settings ▸ Blocked users: everyone the user has blocked, with a one-tap unblock. */
@Composable
fun BlockedUsersPane(
    blockedPeople: List<BlockedContact>,
    onBack: () -> Unit,
    onUnblock: (BlockedContact) -> Unit
) {
    SettingsPaneScaffold(
        title = "Blocked Users",
        subtitle = "Manage blocked contacts",
        onBack = onBack
    ) {
        if (blockedPeople.isEmpty()) {
            item(key = "blocked-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99FFFFFF))
                        .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(16.dp))
                        .padding(vertical = 40.dp, horizontal = NcSpace.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3E8FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = null,
                            tint = Color(0xFF9333EA),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "No blocked users",
                        color = PaneTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = NcSpace.md)
                    )
                    Text(
                        text = "Blocking pauses the conversation — nothing is deleted. " +
                            "Unblock and you both continue exactly where you left off.",
                        color = PaneBody,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            item(key = "blocked-list") {
                PaneSection {
                    blockedPeople.forEachIndexed { index, person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NcAvatar(name = person.name, imageUrl = person.avatarUrl, size = 40.dp)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = NcSpace.md)
                            ) {
                                Text(
                                    text = person.name,
                                    color = PaneTitle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Blocked — chat and history kept",
                                    color = PaneBody,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "Unblock",
                                color = PaneColorAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onUnblock(person) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        if (index != blockedPeople.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                thickness = 1.dp,
                                color = Color(0xFFF1F5F9)
                            )
                        }
                    }
                }
            }

            item(key = "blocked-footer") {
                Spacer(modifier = Modifier.height(NcSpace.xs))
                Text(
                    text = "Blocked contacts cannot call you or see your status updates.",
                    color = PaneBody,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = NcSpace.xs)
                )
            }
        }
    }
}

private val PaneColorAccent = Color(0xFF7C3AED)

/**
 * Settings ▸ Sync check.
 *
 * Answers "is my data missing, or is the request failing?" without a cable: every endpoint
 * the screens depend on is called once and shown with the server's own answer, plus the live
 * request log and the identity the app is signed in as. One tap copies the lot.
 */
@Composable
fun SyncCheckPane(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CheckRow>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    fun runNow() {
        if (running) return
        running = true
        scope.launch {
            rows = runCatching { SyncCheck.run(ServiceLocator.api) }
                .getOrElse {
                    listOf(
                        CheckRow(
                            name = "Check crashed",
                            path = "-",
                            ok = false,
                            detail = "${it.javaClass.simpleName}: ${it.message}",
                            millis = 0
                        )
                    )
                }
            running = false
        }
    }

    LaunchedEffect(Unit) { runNow() }

    val tokens = ServiceLocator.tokens
    val identity = buildString {
        appendLine("NChat sync report")
        appendLine("server:  ${ServiceLocator.serverUrl}")
        appendLine("account: ${tokens.email ?: "not signed in"}")
        appendLine("user id: ${tokens.userId ?: "-"}")
        appendLine("name:    ${tokens.fullName ?: "-"}   @${tokens.username ?: "-"}")
        appendLine()
        rows.forEach {
            appendLine("${if (it.ok) "OK  " else "FAIL"} ${it.name} (${it.path}) ${it.millis} ms")
            appendLine("      ${it.detail}")
        }
        appendLine()
        appendLine("recent requests")
        appendLine(ApiLog.dump().ifBlank { "(none)" })
    }

    SettingsPaneScaffold(
        title = "Sync check",
        subtitle = "What the server answers",
        onBack = onBack
    ) {
        item(key = "identity") {
            PaneSection {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = tokens.email ?: "not signed in",
                        color = PaneTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "id ${tokens.userId?.take(8) ?: "-"} · " +
                            ServiceLocator.serverUrl.removePrefix("https://").removePrefix("http://"),
                        color = PaneBody,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NcSpace.md, bottom = NcSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(NcSpace.sm)
            ) {
                PaneButton(
                    label = if (running) "Checking…" else "Run check",
                    modifier = Modifier.weight(1f),
                    onClick = { runNow() }
                )
                PaneButton(
                    label = "Copy report",
                    modifier = Modifier.weight(1f),
                    onClick = { clipboard.setText(AnnotatedString(identity)) }
                )
            }
        }

        item(key = "rows") {
            PaneSection {
                rows.forEachIndexed { index, row ->
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (row.ok) "OK" else "FAIL",
                                color = if (row.ok) Color(0xFF16A34A) else Color(0xFFDC2626),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = row.name,
                                color = PaneTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(text = "${row.millis} ms", color = PaneBody, fontSize = 10.sp)
                        }
                        Text(
                            text = "${row.path} — ${row.detail}",
                            color = PaneBody,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (index != rows.lastIndex) {
                        HorizontalDivider(color = Color(0x22000000))
                    }
                }
                if (rows.isEmpty()) {
                    Text(
                        text = "Checking…",
                        color = PaneBody,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        item(key = "log") {
            Column(modifier = Modifier.padding(top = NcSpace.md)) {
                Text(
                    text = "Recent requests",
                    color = PaneTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = ApiLog.dump().ifBlank { "(none yet)" },
                    color = PaneBody,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x99FFFFFF))
                        .padding(10.dp)
                )
            }
        }
    }
}

/** A small filled pill used for the two actions above. */
@Composable
private fun PaneButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = rememberPressSource()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF7C3AED))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressFeedback(interaction)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
