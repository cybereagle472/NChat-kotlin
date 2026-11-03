@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.data.Chat
import com.nchat.app.data.MockData
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import com.nchat.app.data.utils.AutoUpdater
import com.nchat.app.data.utils.UpdateInfo
import kotlinx.coroutines.launch

private val Green = Color(0xFF087E68)
private val BrightGreen = Color(0xFF20C997)
private val Muted = Color(0xFF718096)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NChatApp() }
    }
}

@Composable
private fun NChatApp() {
    var dark by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedChat by remember { mutableStateOf<Chat?>(null) }
    var showLab by remember { mutableStateOf(false) }
    var showDeveloper by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var dataSize by remember { mutableIntStateOf(500) }
    var compact by remember { mutableStateOf(false) }
    var showAvatars by remember { mutableStateOf(true) }
    var showDividers by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            updateInfo = AutoUpdater.checkForUpdate()
            if (updateInfo != null) showUpdateDialog = true
        } catch (_: Exception) {
            // Update checks are optional; the app remains usable offline.
        }
    }

    val chats = remember(dataSize) { MockData.generate(dataSize) }
    val filtered = remember(chats, query, filter) {
        chats.filter { chat ->
            (query.isBlank() || chat.name.contains(query, true) || chat.message.contains(query, true)) &&
            when (filter) { 1 -> chat.unread > 0; 2 -> chat.favorite; else -> true }
        }
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = BrightGreen, secondary = Green, background = Color(0xFF101B20),
        surface = Color(0xFF17252B), onSurface = Color(0xFFEAF2F4)
    ) else lightColorScheme(
        primary = Green, secondary = BrightGreen, background = Color(0xFFF6F8F7),
        surface = Color.White, onSurface = Color(0xFF182522)
    )) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (selectedChat == null && !showLab && !showDeveloper) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("Chats", "Updates", "Communities", "Calls").forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = selectedTab == i,
                                onClick = { selectedTab = i },
                                icon = { Text(listOf("▤", "◉", "◎", "☎")[i], fontSize = 20.sp) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (selectedChat == null && !showLab && !showDeveloper && selectedTab == 0) {
                    FloatingActionButton(onClick = { notice = "New chat action is responsive." }, containerColor = BrightGreen) {
                        Text("+", color = Color.White, fontSize = 28.sp)
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize()
                .background(Brush.verticalGradient(
                    if (dark) listOf(Color(0xFF101B20), Color(0xFF172A30), Color(0xFF10231F))
                    else listOf(Color(0xFFF0F8F5), Color(0xFFE5F2EE), Color(0xFFF7FAF9))
                ))
                .padding(padding)) {
                when {
                    selectedChat != null -> ChatDetailScreen(selectedChat!!, { selectedChat = null }, { notice = it })
                    showDeveloper -> DeveloperProfileScreen { showDeveloper = false }
                    showLab -> PerformanceLabScreen(
                        dataSize, { dataSize = it }, compact, { compact = it },
                        showAvatars, { showAvatars = it }, showDividers, { showDividers = it },
                        dark, { dark = it }, { showLab = false }
                    )
                    selectedTab == 0 -> {
                        HomeHeader(
                            { showLab = true },
                            { showDeveloper = true },
                            { dark = !dark },
                            {
                                scope.launch {
                                    checkingUpdate = true
                                    try {
                                        updateInfo = AutoUpdater.checkForUpdate()
                                        if (updateInfo != null) showUpdateDialog = true
                                        else notice = "You are already using the latest version."
                                    } catch (e: Exception) {
                                        notice = "Could not check for updates. Check your internet connection."
                                    } finally {
                                        checkingUpdate = false
                                    }
                                }
                            },
                            checkingUpdate
                        )
                        SearchField(query, { query = it })
                        FilterChips(filter, { filter = it })
                        ChatList(filtered, compact, showAvatars, showDividers) { selectedChat = it }
                        Text("Showing ${filtered.size} of ${chats.size} chats",
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            color = Muted, fontSize = 11.sp)
                    }
                    else -> PlaceholderTab(listOf("", "Updates", "Communities", "Calls")[selectedTab]) { showLab = true }
                }
            }
        }
        if (showUpdateDialog && updateInfo != null) {
            val available = updateInfo!!
            AlertDialog(
                onDismissRequest = { if (!updateBusy) showUpdateDialog = false },
                title = { Text("NChat update ${available.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("A new version is ready.")
                        Text(available.notes, color = Muted, fontSize = 13.sp)
                        if (updateBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Downloading update…", fontSize = 12.sp, color = Muted)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !updateBusy, onClick = {
                        scope.launch {
                            updateBusy = true
                            try {
                                val apk = AutoUpdater.downloadUpdate(context, available)
                                val started = AutoUpdater.installUpdate(context, apk)
                                if (started) showUpdateDialog = false
                                else notice = "Allow NChat UI Lab to install unknown apps in Android settings, then return and tap Update again."
                            } catch (e: Exception) {
                                notice = e.message ?: "Update download failed. Please try again."
                            } finally {
                                updateBusy = false
                            }
                        }
                    }) { Text(if (updateBusy) "Please wait" else "Download & install") }
                },
                dismissButton = {
                    TextButton(enabled = !updateBusy, onClick = { showUpdateDialog = false }) { Text("Later") }
                }
            )
        }
        if (notice.isNotBlank()) {
            AlertDialog(onDismissRequest = { notice = "" },
                confirmButton = { TextButton(onClick = { notice = "" }) { Text("OK") } },
                title = { Text("NChat") }, text = { Text(notice) })
        }
    }
}


@Composable
private fun DeveloperProfileScreen(onBack: () -> Unit) {
    val ink = Color(0xFF17332D)
    val mint = Color(0xFFB9F2D9)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Color(0xFF063D35), Color(0xFF087E68), Color(0xFF20C997))))
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 26.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", Modifier.clickable { onBack() }.padding(end = 14.dp), color = Color.White, fontSize = 30.sp)
                        Text("DEVELOPER PROFILE", color = Color.White.copy(alpha = .82f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color.White, mint))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("MN", color = Color(0xFF075E54), fontSize = 25.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Muhammad Nasir", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("Aspiring Developer • AI Explorer", color = Color.White.copy(alpha = .86f), fontSize = 12.sp)
                            Text("Mansehra, Pakistan", color = Color.White.copy(alpha = .72f), fontSize = 11.sp)
                        }
                    }
                    Text(
                        "Curious by nature. Learning by building. Interested in turning practical ideas into useful digital products.",
                        color = Color.White, fontSize = 14.sp, lineHeight = 21.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileTag("CREATIVE", true)
                        ProfileTag("TECH-FOCUSED", true)
                        ProfileTag("ALWAYS LEARNING", true)
                    }
                }
            }
        }
        item {
            ProfileSectionCard("ABOUT", "A practical, self-driven learner with a background in computer studies and IT. Interested in technology that solves real problems, especially mobile apps, AI tools, and web experiences.")
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("SKILLS & TOOLKIT", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Areas I have studied, used, or am actively improving.", color = Muted, fontSize = 12.sp)
                val groups = listOf(
                    "Office & productivity" to listOf("MS Office", "Data entry", "English & Urdu typing"),
                    "Web & digital" to listOf("HTML", "CSS", "Web development", "SEO basics", "WordPress"),
                    "Programming & AI" to listOf("Python fundamentals", "AI tools", "AI-assisted coding", "Prompting"),
                    "Creative tools" to listOf("Graphic design basics", "Video editing basics")
                )
                groups.forEach { (heading, tags) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(heading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                tags.take(2).forEach { ProfileTag(it) }
                            }
                            if (tags.size > 2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    tags.drop(2).forEach { ProfileTag(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("PROJECTS & EXPERIMENTS", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Ideas and prototypes explored through hands-on learning.", color = Muted, fontSize = 12.sp)
                ProfileProject("NChat", "A messaging-app concept and UI prototype. Exploring Kotlin, Jetpack Compose, real-time experiences, and app update flows.", "ANDROID • KOTLIN")
                ProfileProject("Jarvis Assistant", "A personal assistant experiment combining Python, voice output, web information, and AI APIs.", "PYTHON • AI")
                ProfileProject("EagleX AI", "A lightweight AI chat-app concept focused on a simple interface and text-based model interaction.", "ANDROID • AI")
                ProfileProject("Zyrafic", "A planned productivity and tools website with an interest in useful content, SEO, and accessible web tools.", "WEB • SEO")
            }
        }
        item {
            ProfileSectionCard("CURRENT INTERESTS", "AI assistants and agents • Ethical hacking and cybersecurity learning • Android and web app development • APIs and backend systems • UI performance • Automation and productivity tools")
        }
        item {
            ProfileSectionCard("EDUCATION", "FSc Computer Science • 1-year Diploma in Information Technology (DIT). Studied fundamentals including computer applications, web development, databases, Python, and creative software.")
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MY APPROACH", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                ProfileSectionCard("LEARN → BUILD → IMPROVE", "I prefer practical experiments, clear step-by-step learning, and testing ideas on real devices. My focus is on steady progress and building a stronger technical foundation.")
                Text("GitHub: github.com/cybereagle472", color = Muted, fontSize = 12.sp)
                Text("Profile information is a short public-facing introduction. Skills marked as fundamentals or learning areas are not presented as professional certifications.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ProfileTag(label: String, inverted: Boolean = false) {
    Surface(
        color = if (inverted) Color.White.copy(alpha = .16f) else BrightGreen.copy(alpha = .12f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (inverted) Color.White.copy(alpha = .22f) else BrightGreen.copy(alpha = .25f))
    ) {
        Text(
            label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (inverted) Color.White else Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ProfileSectionCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(body, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ProfileProject(title: String, description: String, category: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(category, color = BrightGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun HomeHeader(onLab: () -> Unit, onDeveloper: () -> Unit, onTheme: () -> Unit, onUpdate: () -> Unit, checkingUpdate: Boolean) {
    Row(Modifier.fillMaxWidth()
        .background(Brush.linearGradient(listOf(Color(0xFF075E54), Color(0xFF0E9F79), Color(0xFF20C997))))
        .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("nChat", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Fast. Simple. Connected.", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
        }
        TextButton(onClick = onUpdate, enabled = !checkingUpdate) {
            Text(if (checkingUpdate) "CHECK…" else "↻ UPDATE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onTheme) { Text("◐", color = Color.White, fontSize = 22.sp) }
        TextButton(onClick = onDeveloper) { Text("ME", color = Color.White, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onLab) { Text("⚡ LAB", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = .88f),
            Color(0xFFB9E8D8).copy(alpha = .35f)
        )))
        .border(1.dp, Color.White.copy(alpha = .72f), RoundedCornerShape(16.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕", fontSize = 22.sp, color = Muted)
        Spacer(Modifier.width(9.dp))
        BasicTextField(value = query, onValueChange = onQuery, modifier = Modifier.weight(1f),
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner -> if (query.isEmpty()) Text("Search name or message", color = Muted, fontSize = 14.sp); inner() })
        if (query.isNotEmpty()) Text("×", Modifier.clickable { onQuery(""); keyboard?.hide() }, fontSize = 20.sp, color = Muted)
    }
}

@Composable
private fun FilterChips(filter: Int, onFilter: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "Unread", "Favorites").forEachIndexed { i, label ->
            FilterChip(selected = filter == i, onClick = { onFilter(i) }, label = { Text(label) }, shape = RoundedCornerShape(50))
        }
    }
}

@Composable
private fun ColumnScope.ChatList(chats: List<Chat>, compact: Boolean, showAvatars: Boolean, showDividers: Boolean, onChat: (Chat) -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
        items(items = chats, key = { it.id }, contentType = { "chat-row" }) { chat ->
            ChatRow(chat, compact, showAvatars, onChat)
            if (showDividers) Divider(
                Modifier.padding(start = if (showAvatars) 78.dp else 14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), thickness = .6.dp)
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, compact: Boolean, showAvatars: Boolean, onChat: (Chat) -> Unit) {
    val rowPadding by animateDpAsState(if (compact) 6.dp else 10.dp, label = "chatRowPadding")
    Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp)
        .clip(RoundedCornerShape(17.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .76f))
        .border(1.dp, Color.White.copy(alpha = .52f), RoundedCornerShape(17.dp))
        .clickable { onChat(chat) }
        .animateContentSize()
        .padding(horizontal = 14.dp, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically) {
        if (showAvatars) {
            Box(Modifier.size(if (compact) 40.dp else 51.dp).clip(CircleShape).background(chat.color),
                contentAlignment = Alignment.Center) {
                Text(chat.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(chat.time, color = if (chat.unread > 0) BrightGreen else Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.message, color = Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (chat.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(CircleShape).background(BrightGreen).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(chat.unread.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (chat.favorite) Text("★", color = Color(0xFFE3A62F), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(chat: Chat, onBack: () -> Unit, onAction: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    val messages = remember(chat.id) { mutableStateListOf("Hey! How are you?", chat.message, "This is a local UI performance demo.") }
    val reactions = remember(chat.id) { mutableStateMapOf<Int, String>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Green).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable { onBack() }.padding(horizontal = 10.dp), color = Color.White, fontSize = 30.sp)
            Column(Modifier.weight(1f)) {
                Text(chat.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("demo conversation", color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
            }
            TextButton(onClick = { onAction("Voice call button works in demo mode.") }) { Text("☎", color = Color.White, fontSize = 19.sp) }
            TextButton(onClick = { onAction("Video call button works in demo mode.") }) { Text("▣", color = Color.White, fontSize = 19.sp) }
        }
        Text("Tap any message to add 👍. Tap the reaction to remove it.",
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            color = Muted, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            itemsIndexed(messages) { index, item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            modifier = Modifier.clickable {
                                if (reactions[index] == "👍") reactions.remove(index)
                                else reactions[index] = "👍"
                            },
                            color = BrightGreen.copy(alpha = .16f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(item, Modifier.widthIn(max = 290.dp).padding(12.dp), fontSize = 14.sp)
                        }
                        reactions[index]?.let { reaction ->
                            Surface(
                                modifier = Modifier.padding(top = 3.dp).clickable { reactions.remove(index) },
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BrightGreen.copy(alpha = .5f))
                            ) {
                                Text("$reaction  Tap to remove", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f),
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner -> if (message.isEmpty()) Text("Message", color = Muted); inner() })
            TextButton(onClick = { if (message.isNotBlank()) { messages.add(message.trim()); message = "" } }) {
                Text("SEND", color = Green, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PerformanceLabScreen(
    dataSize: Int, onDataSize: (Int) -> Unit, compact: Boolean, onCompact: (Boolean) -> Unit,
    showAvatars: Boolean, onShowAvatars: (Boolean) -> Unit, showDividers: Boolean, onShowDividers: (Boolean) -> Unit,
    dark: Boolean, onDark: (Boolean) -> Unit, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Green).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable { onBack() }, color = Color.White, fontSize = 28.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Performance Lab", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Stress controls and UI experiments", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LabSection("DATASET STRESS TEST", "Generate larger local datasets and compare scrolling.")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(50, 500, 1000, 5000, 10000).forEach { size ->
                        FilterChip(selected = dataSize == size, onClick = { onDataSize(size) },
                            label = { Text(if (size >= 1000) "${size / 1000}k" else "$size") })
                    }
                }
                Text("Active dataset: $dataSize records", color = Muted, fontSize = 12.sp)
            }
            item {
                LabSection("RENDERING CONTROLS", "Toggle UI features to compare rendering cost.")
                SettingToggle("Compact rows", "Increase visible rows per screen", compact, onCompact)
                SettingToggle("Avatar placeholders", "Toggle circular avatar composables", showAvatars, onShowAvatars)
                SettingToggle("Row dividers", "Toggle separators between items", showDividers, onShowDividers)
                SettingToggle("Dark theme", "Switch the interface color scheme", dark, onDark)
            }
            item {
                LabSection("INTERACTION TESTS", "Manually test these flows on your device.")
                listOf("Scroll rapidly from top to bottom", "Search with short and long queries",
                    "Switch All, Unread and Favorites filters", "Open chats and send 20+ messages",
                    "Switch tabs repeatedly", "Change dataset while list is visible",
                    "Toggle compact rows and avatars", "Rotate device if supported",
                    "Background and restore the app", "Compare light and dark themes").forEach { TestRow(it) }
            }
            item {
                LabSection("PERFORMANCE OBSERVATIONS", "Record results on the same device for fair comparisons.")
                listOf("Scroll smoothness and dropped frames", "Time to first content render",
                    "Search input latency", "Memory use with 5k and 10k rows",
                    "Animation jank during fast scrolling", "Battery and device temperature").forEach { TestRow(it) }
                Text("This lab stresses the UI but does not report calibrated FPS or native memory metrics.",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun LabSection(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 7.dp)) {
        Text(title, color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun TestRow(label: String) {
    var checked by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { checked = !checked }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Text(label, fontSize = 13.sp, color = if (checked) Muted else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlaceholderTab(title: String, onLab: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("This section is a local UI prototype for interaction testing.", color = Muted)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onLab) { Text("Open Performance Lab") }
    }
}
