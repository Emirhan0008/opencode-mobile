package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.SessionsState
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(
    state: SessionsState,
    permissionCount: Int,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (title: String, directory: String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Oc.bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Oc.panel)
                    .border(1.dp, Oc.border)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("┌─ SESSIONS", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (permissionCount > 0) {
                    Text("🔐 $permissionCount", color = Oc.warn, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "yenile", tint = Oc.accent)
                }
                TextButton(onClick = onDisconnect) { Text("çıkış", color = Oc.dim) }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNew = true },
                containerColor = Oc.accent,
                contentColor = Oc.bg,
            ) { Icon(Icons.Default.Add, contentDescription = "yeni session") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.sessions.isEmpty() ->
                    CircularProgressIndicator(color = Oc.accent, modifier = Modifier.align(Alignment.Center))

                state.sessions.isEmpty() ->
                    Text(
                        "Session yok. Sağ alttaki + ile yeni bir tane açın.",
                        color = Oc.dim,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp, end = 8.dp, top = 8.dp, bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.sessions, key = { it.str("id").orEmpty() }) { s ->
                        SessionRow(
                            title = s.str("title")?.ifBlank { null } ?: "(başlıksız)",
                            directory = s.obj("location").str("directory") ?: "?",
                            updatedAt = s.obj("time").num("updated") ?: s.obj("time").num("created") ?: 0.0,
                            cost = s.num("cost") ?: 0.0,
                            busy = state.active.contains(s.str("id")),
                            onClick = { s.str("id")?.let(onOpen) },
                        )
                    }
                }
            }

            if (showNew) {
                NewSessionDialog(
                    onConfirm = { title, dir ->
                        showNew = false
                        onNew(title, dir)
                    },
                    onDismiss = { showNew = false },
                )
            }

            state.error?.let {
                Text("✖ $it", color = Oc.err, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun SessionRow(
    title: String,
    directory: String,
    updatedAt: Double,
    cost: Double,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panel)
            .border(1.dp, if (busy) Oc.accent else Oc.border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (busy) Oc.accent else Oc.dim),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$directory  ·  ${formatTime(updatedAt)}  ·  $${"%.3f".format(cost)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (busy) "çalışıyor" else "", color = Oc.accent, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NewSessionDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Oc.bg.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Oc.panel)
                .border(1.dp, Oc.border, RoundedCornerShape(6.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("┌─ YENİ SESSION", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Başlık") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = ocFieldColors(),
            )
            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text("Dizin (boş: varsayılan)") },
                placeholder = { Text("C:\\projeler\\api") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = ocFieldColors(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("vazgeç", color = Oc.dim) }
                TextButton(onClick = { onConfirm(title, dir) }) { Text("▸ aç", color = Oc.accent) }
            }
        }
    }
}

fun formatTime(epochMs: Double): String {
    if (epochMs <= 0) return "-"
    val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs.toLong()))
}