package dev.opencode.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.SessionsState
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liste filtresi: üst düzey (varsayılan) / tümü / yalnızca çalışanlar / son 24 saat. */
enum class SessionFilter(val label: String) {
    TOP("üst düzey"),
    ALL("tümü"),
    ACTIVE("aktif"),
    RECENT("son 24s"),
}

@Composable
fun SessionsScreen(
    state: SessionsState,
    permissionCount: Int,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (title: String, directory: String) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onFork: (String) -> Unit,
    shutdownArmed: Boolean,
    onArmShutdown: (force: Boolean) -> Unit,
    onCancelShutdown: () -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var showShutdown by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Alt ajan (subagent) oturumları varsayılan olarak gizli — "tümü" ile açılır.
    var filter by remember { mutableStateOf(SessionFilter.TOP) }

    Scaffold(
        containerColor = Oc.bg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Oc.panel)
                    .border(1.dp, Oc.border),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = "OpenCode Mobile",
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("┌─ SESSIONS", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (permissionCount > 0) {
                        Text("🔐 $permissionCount", color = Oc.warn, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onToggleTheme) {
                        Text(if (darkTheme) "☀" else "☾", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { showShutdown = true }) {
                        Icon(
                            painterResource(R.drawable.ic_power),
                            contentDescription = "kapatma planı",
                            tint = if (shutdownArmed) Oc.warn else Oc.dim,
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "yenile", tint = Oc.accent)
                    }
                    TextButton(onClick = onDisconnect) { Text("çıkış", color = Oc.dim) }
                }

                // kapatma görevi bandı
                if (shutdownArmed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Oc.warn.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⏱ kapatma görevi gönderildi — tüm işler bitince bilgisayar kapatılacak (telefon kapalı olsa da sürer)",
                            color = Oc.warn,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelShutdown) { Text("iptal", color = Oc.err) }
                    }
                }

                // ------------------------------------------------------- arama
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Oc.dim)
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("başlık / dizin ara…", color = Oc.dim) },
                        colors = ocFieldColors(),
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "temizle", tint = Oc.dim)
                        }
                    }
                }

                // ----------------------------------------------------- filtre
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SessionFilter.entries.forEach { f ->
                        FilterChipRow(f, selected = filter == f) { filter = f }
                    }
                }
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
            val now = System.currentTimeMillis()
            val visible = state.sessions.filter { s ->
                val id = s.str("id").orEmpty()
                val title = s.str("title").orEmpty()
                val dir = s.obj("location").str("directory").orEmpty()
                val matchesQuery = query.isBlank() ||
                    title.contains(query, ignoreCase = true) ||
                    dir.contains(query, ignoreCase = true) ||
                    id.contains(query, ignoreCase = true)
                val matchesFilter = when (filter) {
                    SessionFilter.TOP -> s.str("parentID") == null
                    SessionFilter.ALL -> true
                    SessionFilter.ACTIVE -> state.active.contains(id)
                    SessionFilter.RECENT -> {
                        val updated = s.obj("time").num("updated") ?: s.obj("time").num("created") ?: 0.0
                        now - updated.toLong() < 24L * 60 * 60 * 1000
                    }
                }
                matchesQuery && matchesFilter
            }

            when {
                state.loading && state.sessions.isEmpty() ->
                    CircularProgressIndicator(color = Oc.accent, modifier = Modifier.align(Alignment.Center))

                visible.isEmpty() ->
                    Text(
                        if (state.sessions.isEmpty()) {
                            "Session yok. Sağ alttaki + ile yeni bir tane açın."
                        } else {
                            "Eşleşen session yok."
                        },
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
                    items(visible, key = { it.str("id").orEmpty() }) { s ->
                        SessionRow(
                            id = s.str("id").orEmpty(),
                            title = s.str("title")?.ifBlank { null } ?: "(başlıksız)",
                            directory = s.obj("location").str("directory") ?: "?",
                            updatedAt = s.obj("time").num("updated") ?: s.obj("time").num("created") ?: 0.0,
                            cost = s.num("cost") ?: 0.0,
                            busy = state.active.contains(s.str("id")),
                            failed = s.str("outcome") == "failed",
                            child = s.str("parentID") != null,
                            onClick = { s.str("id")?.let(onOpen) },
                            onDelete = onDelete,
                            onRename = onRename,
                            onFork = onFork,
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

            if (showShutdown) {
                ShutdownDialog(
                    armed = shutdownArmed,
                    onArm = { force ->
                        showShutdown = false
                        onArmShutdown(force)
                    },
                    onCancel = {
                        showShutdown = false
                        onCancelShutdown()
                    },
                    onDismiss = { showShutdown = false },
                )
            }

            state.error?.let {
                Text("✖ $it", color = Oc.err, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun FilterChipRow(filter: SessionFilter, selected: Boolean, onClick: () -> Unit) {
    Text(
        filter.label,
        color = if (selected) Oc.bg else Oc.dim,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Oc.accent else Oc.panelAlt)
            .border(1.dp, if (selected) Oc.accent else Oc.border, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    id: String,
    title: String,
    directory: String,
    updatedAt: Double,
    cost: Double,
    busy: Boolean,
    failed: Boolean,
    child: Boolean,
    onClick: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onFork: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Oc.panel)
                .border(1.dp, when {
                    busy -> Oc.accent
                    failed -> Oc.err
                    else -> Oc.border
                }, RoundedCornerShape(4.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(when {
                        busy -> Oc.accent
                        failed -> Oc.err
                        else -> Oc.dim
                    }),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (child) "↳ " else "") + title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "$directory  ·  ${formatTime(updatedAt)}  ·  $${"%.3f".format(cost)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                when {
                    busy -> "çalışıyor"
                    failed -> "başarısız"
                    child -> "alt ajan"
                    else -> ""
                },
                color = when {
                    busy -> Oc.accent
                    failed -> Oc.err
                    else -> Oc.purple
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // uzun basma menüsü
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Başlığı kopyala") },
                onClick = {
                    clipboard.setText(AnnotatedString(title))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("ID'yi kopyala") },
                onClick = {
                    clipboard.setText(AnnotatedString(id))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Yeniden adlandır") },
                onClick = {
                    menu = false
                    showRename = true
                },
            )
            DropdownMenuItem(
                text = { Text("Çoğalt (fork)") },
                onClick = {
                    menu = false
                    onFork(id)
                },
            )
            DropdownMenuItem(
                text = { Text("Sil", color = Oc.err) },
                onClick = {
                    menu = false
                    showDelete = true
                },
            )
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Yeniden adlandır") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    if (name.isNotBlank() && name != title) onRename(id, name.trim())
                }) { Text("Kaydet", color = Oc.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Vazgeç", color = Oc.dim) }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Oturumu sil?") },
            text = { Text("“$title” kalıcı olarak silinecek. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete(id)
                }) { Text("Sil", color = Oc.err) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Vazgeç", color = Oc.dim) }
            },
        )
    }
}

/** Kapatma planı diyaloğu: kur / iptal et. */
@Composable
private fun ShutdownDialog(
    armed: Boolean,
    onArm: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (armed) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Kapatma görevi gönderildi") },
            text = {
                Text(
                    "Tüm işler bittiğinde bilgisayar kapatılacak. Görev bilgisayar tarafındaki " +
                        "OpenCode oturumunda sürer — telefon kapalı olsa da uygulama kapalı olsa da " +
                        "işlenir. Kapatma komutu yalnızca BİR KEZ çalıştırılır.",
                )
            },
            confirmButton = {
                TextButton(onClick = onCancel) { Text("İptal et", color = Oc.err) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Kapat", color = Oc.dim) }
            },
        )
    } else {
        var force by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Bilgisayarı kapat") },
            text = {
                Column {
                    Text(
                        "Tüm oturumlar (alt ajanlar dahil) bittiğinde bilgisayar kapatılsın. " +
                            "Görev, “opencode-mobile” oturumu varsa ona, yoksa en son güncellenen " +
                            "oturuma gözlemci olarak gönderilir; telefon kapalı olsa da sürer. " +
                            "Kapatma komutu (shutdown.exe /s /t 120) yalnızca bir kez çalıştırılır.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = force, onCheckedChange = { force = it })
                        Text("Açık programları zorla kapat (/f)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onArm(force) }) { Text("Kapatmayı kur", color = Oc.warn) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Vazgeç", color = Oc.dim) }
            },
        )
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