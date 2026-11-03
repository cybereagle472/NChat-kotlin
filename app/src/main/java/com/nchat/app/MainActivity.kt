package com.nchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nchat.app.data.MockData
import com.nchat.app.utils.AutoUpdater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selectedTab by remember { mutableIntStateOf(0) }
            val tabs = listOf("Chats", "Status", "Communities", "Calls")
            var showUpdate by remember { mutableStateOf(false) }

            MaterialTheme(colorScheme = lightColorScheme()) {
                Scaffold(
                    topBar = {
                        Column {
                            Box(Modifier.fillMaxWidth().background(Color(0xFF075E54)).padding(16.dp)) {
                                Text("nChat", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                            }
                            TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF075E54)) {
                                tabs.forEachIndexed { i, t ->
                                    Tab(selected = selectedTab==i, onClick = { selectedTab=i },
                                        text = { Text(t, color = Color.White) })
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        val infinite = rememberInfiniteTransition()
                        val scale by infinite.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(800, easing=FastOutSlowInEasing), RepeatMode.Reverse))
                        FloatingActionButton(onClick = {}, containerColor = Color(0xFF25D366), modifier = Modifier.scale(scale)) {
                            Text("+", color = Color.White)
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        // WhatsApp like list with advance animation
                        LazyColumn {
                            items(MockData.chats) { chat ->
                                var visible by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) { visible = true }
                                AnimatedVisibility(visible, enter = slideInHorizontally { it/2 } + fadeIn(tween(400))) {
                                    Row(Modifier.fillMaxWidth().clickable{}.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFDFE5E7)), contentAlignment = Alignment.Center) {
                                            Text(chat.name.first().toString())
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(chat.name, style = MaterialTheme.typography.titleMedium)
                                            Text(chat.msg, maxLines = 1, color = Color.Gray)
                                        }
                                        if(chat.unread>0) {
                                            Box(Modifier.clip(CircleShape).background(Color(0xFF25D366)).padding(6.dp)) {
                                                Text(chat.unread.toString(), color = Color.White)
                                            }
                                        }
                                    }
                                }
                                Divider()
                            }
                        }
                        if(showUpdate) {
                            Card(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Naya update available hai", Modifier.weight(1f))
                                    Button(onClick = { AutoUpdater.downloadAndInstall(this@MainActivity) }) { Text("Update") }
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { AutoUpdater.checkAndDownload(this@MainActivity) { showUpdate = true } }
            }
        }
    }
}
