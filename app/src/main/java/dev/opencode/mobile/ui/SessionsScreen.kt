package dev.opencode.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ConnState
import dev.opencode.mobile.QueuedAction
import dev.opencode.mobile.R
import dev.opencode.mobile.SessionsState
import dev.opencode.mobile.WatchStatus
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Liste filtresi: üst düzey (varsayılan) / tümü / yalnızca çalışanlar / son 24 saat. */
enum class SessionFilter(val label: String) {
    TOP("üst düzey"),
    ALL("tümü"),
    ACTIVE("aktif"),
    RECENT("son 24s"),
}

/** Oturum listesi satırı: gerçek oturum ya da iç içe grubu açan başlık. */
private sealed interface SessionListRow {
    data class Item(val obj: JsonObject, val depth: Int) : SessionListRow
    data class Expander(val parentID: String, val count: Int, val expanded: Boolean, val depth: Int) : SessionListRow
}

@Composable
fun SessionsScreen(
    state: SessionsState,
    permissionCount: Int,
    /** Bekleyen soru (form) sayısı — başlıkta göstergesi. */
    questionCount: Int = 0,
    /** Bekleyen sorusu olan oturum kimlikleri — satırda işaret. */
    questionSessions: Set<String> = emptySet(),
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    /** Evren kota yanıtı (yoksa şerit gösterilmez). */
    quota: JsonObject? = null,
    quotaError: Boolean = false,
    quotaLoading: Boolean = false,
    /** Kota şeridi açık mı (ayarlardan kapatılabilir). */
    quotaBadge: Boolean = true,
    onRefreshQuota: () -> Unit = {},
    /** Üst bardaki ⚙ — ayarlar ekranını açar. */
    onOpenSettings: () -> Unit = {},
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (title: String, directory: String) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onFork: (String) -> Unit,
    shutdownArmed: Boolean,
    onArmShutdown: (force: Boolean, targetId: String?, at: String?) -> Unit,
    onCancelShutdown: () -> Unit,
    /** Plan varsa "HH:MM" (yoksa/şimdiyse null) — banner'da "en erken" göstergesi için. */
    shutdownAt: String? = null,
    /** Gözcünün son canlılık durumu (bilinmiyorsa null). */
    watchStatus: WatchStatus? = null,
    onRefreshWatch: () -> Unit = {},
    /** Bağlantı durumu — Connected ise durum satırı gösterilmez. */
    connState: ConnState = ConnState.Connected,
    onReconnect: () -> Unit = {},
    /** Çevrimdışı kuyruk (boşsa kart gösterilmez). */
    pendingActions: List<QueuedAction> = emptyList(),
    onRemoveQueued: (String) -> Unit = {},
    onFlushQueue: () -> Unit = {},
) {
    var showNew by remember { mutableStateOf(false) }
    var showShutdown by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Alt ajan (subagent) oturumları varsayılan olarak gizli — "tümü" ile açılır.
    var filter by remember { mutableStateOf(SessionFilter.TOP) }
    // TOP görünümünde çocuk oturum grubu açık mı (parentID kümesi).
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    // Çevrimdışıyken geçen süreyi canlı göstermek için saniyelik tik.
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    if (connState is ConnState.Offline) {
        LaunchedEffect(connState) {
            while (true) {
                delay(1000)
                nowTick = System.currentTimeMillis()
            }
        }
    }

    Scaffold(
        containerColor = Oc.bg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Oc.panel)
                    .statusBarsPadding()
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
                        Spacer(Modifier.width(6.dp))
                    }
                    if (questionCount > 0) {
                        Text("❓ $questionCount", color = Oc.purple, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                    }
                    // Kompakt başlık eylemleri (IconButton 48dp yerine ~34dp) — "çıkış" tek satıra sığsın.
                    BarAction(onClick = onOpenSettings) {
                        Text("⚙", style = MaterialTheme.typography.labelLarge)
                    }
                    BarAction(onClick = onToggleTheme) {
                        Text(if (darkTheme) "☀" else "☾", style = MaterialTheme.typography.labelLarge)
                    }
                    BarAction(onClick = { showShutdown = true }) {
                        Icon(
                            painterResource(R.drawable.ic_power),
                            contentDescription = "kapatma planı",
                            tint = if (shutdownArmed) Oc.warn else Oc.dim,
                        )
                    }
                    BarAction(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "yenile", tint = Oc.accent)
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Oc.border, RoundedCornerShape(4.dp))
                            .clickable(onClick = onDisconnect)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "çıkış",
                            color = Oc.dim,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // ------------------------------------------------ bağlantı durumu
                // Connected ise hiçbir şey gösterilmez; satıra dokununca yeniden bağlanılır.
                when (connState) {
                    is ConnState.Connected -> Unit
                    is ConnState.Connecting -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Oc.panelAlt)
                            .clickable(onClick = onReconnect)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "↺ Bağlanıyor… (${connState.attempt}. deneme" +
                                if (connState.nextRetrySeconds > 0) " · ${connState.nextRetrySeconds} sn sonra)" else ")",
                            color = Oc.warn,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is ConnState.Offline -> {
                        val elapsed = if (connState.sinceMillis > 0) {
                            formatElapsed((nowTick - connState.sinceMillis) / 1000)
                        } else {
                            null
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Oc.err.copy(alpha = 0.12f))
                                .clickable(onClick = onReconnect)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "✕ Çevrimdışı (bağlantı yok)" + if (elapsed != null) " · $elapsed" else "",
                                color = Oc.err,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // ---------------------------------------------------- kota şeridi
                // Yalnızca rozet açıkken ve gösterilecek bir şey varsa çizilir.
                if (quotaBadge && (quota != null || quotaError || quotaLoading)) {
                    val (label, color) = when {
                        quota != null -> {
                            val remaining = quota.num("daily_remaining_tokens")?.toLong() ?: 0L
                            val limit = quota.num("daily_limit_tokens")?.toLong() ?: 0L
                            val pct = if (limit > 0) remaining.toDouble() / limit * 100.0 else 0.0
                            val credit = formatCredit(quota.str("remaining_cr"))
                            val reset = formatReset(quota.str("daily_reset_at"))
                            val c = when (quota.str("level")) {
                                "ok" -> Oc.accent
                                "warn" -> Oc.warn
                                else -> Oc.err
                            }
                            val text = "⚡ kota ${formatTokens(remaining)}/${formatTokens(limit)} krk " +
                                "(%${"%.0f".format(pct)}) · $credit kr · ⟳ $reset"
                            text to c
                        }
                        quotaLoading -> "⚡ kota yükleniyor…" to Oc.dim
                        else -> "⚡ kota alınamadı · dokun ve yeniden dene" to Oc.dim
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Oc.panelAlt)
                            .clickable(onClick = onRefreshQuota)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color = color,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // kapatma görevi bandı
                if (shutdownArmed) {
                    val atLabel = shutdownAt?.takeIf { it.isNotBlank() && it != "-" }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Oc.warn.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (atLabel != null) {
                                "⏱ kapatma görevi aktif — oturumlar boşalınca kapanacak (en erken $atLabel)"
                            } else {
                                "⏱ kapatma görevi gönderildi — tüm işler bitince bilgisayar kapatılacak (telefon kapalı olsa da sürer)"
                            },
                            color = Oc.warn,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelShutdown) { Text("iptal", color = Oc.err) }
                    }
                }

                // ------------------------------------------- çevrimdışı kuyruk
                if (pendingActions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Oc.panelAlt)
                            .border(1.dp, Oc.border)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "KUYRUKTA ${pendingActions.size} EYLEM BEKLİYOR",
                                color = Oc.warn,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onFlushQueue) { Text("Şimdi gönder", color = Oc.accent) }
                        }
                        pendingActions.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    a.lastError?.let { err ->
                                        Text(
                                            "✖ $err",
                                            color = Oc.err,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { onRemoveQueued(a.id) }) {
                                    Text("✖", color = Oc.err)
                                }
                            }
                        }
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

            fun matchesQuery(s: JsonObject): Boolean {
                val title = s.str("title").orEmpty()
                val dir = s.obj("location").str("directory").orEmpty()
                val id = s.str("id").orEmpty()
                return query.isBlank() || title.contains(query, true) || dir.contains(query, true) || id.contains(query, true)
            }
            fun matchesFilter(s: JsonObject): Boolean {
                val id = s.str("id").orEmpty()
                return when (filter) {
                    SessionFilter.TOP -> true
                    SessionFilter.ALL -> true
                    SessionFilter.ACTIVE -> state.active.contains(id)
                    SessionFilter.RECENT -> {
                        val updated = s.obj("time").num("updated") ?: s.obj("time").num("created") ?: 0.0
                        now - updated.toLong() < 24L * 60 * 60 * 1000
                    }
                }
            }
            // Alt ajanlar (child session) yalnızca ÇALIŞIRKEN gösterilir; bitmiş olanlar
            // hiçbir filtrede listelenmez (gürültüyü önler).
            val matching = state.sessions
                .filter { matchesQuery(it) }
                .filter { it.str("parentID") == null || state.active.contains(it.str("id")) }

            val rows: List<SessionListRow> = if (filter == SessionFilter.TOP) {
                // "Üst düzey": kök oturumlar + altındaki alt ajanlar iç içe (özyineli).
                val matchingIds = matching.mapNotNull { it.str("id") }.toSet()
                val byUpdated: (JsonObject) -> Double = {
                    it.obj("time").num("updated") ?: it.obj("time").num("created") ?: 0.0
                }
                val childrenByParent = matching
                    .filter { it.str("parentID") != null }
                    .groupBy { it.str("parentID").orEmpty() }
                val roots = matching
                    .filter { it.str("parentID") == null || it.str("parentID").orEmpty() !in matchingIds }
                    .sortedByDescending(byUpdated)
                buildList {
                    val emitted = HashSet<String>()
                    fun emit(s: JsonObject, depth: Int) {
                        val sid = s.str("id").orEmpty()
                        if (sid.isNotEmpty() && !emitted.add(sid)) return // döngü koruması
                        add(SessionListRow.Item(s, depth))
                        val kids = childrenByParent[sid].orEmpty().sortedByDescending(byUpdated)
                        if (kids.isNotEmpty()) {
                            add(SessionListRow.Expander(sid, kids.size, sid in expanded, depth))
                            if (sid in expanded) kids.forEach { emit(it, depth + 1) }
                        }
                    }
                    roots.forEach { emit(it, 0) }
                }
            } else {
                // "tümü/aktif/son 24s": düz liste; çocuklar bayrakla işaretlenir.
                matching.filter { matchesFilter(it) }
                    .map { SessionListRow.Item(it, if (it.str("parentID") != null) 1 else 0) }
            }

            when {
                state.loading && state.sessions.isEmpty() ->
                    CircularProgressIndicator(color = Oc.accent, modifier = Modifier.align(Alignment.Center))

                rows.isEmpty() ->
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
                    items(rows, key = { row -> when (row) {
                        is SessionListRow.Item -> row.obj.str("id").orEmpty()
                        is SessionListRow.Expander -> "exp:" + row.parentID
                    } }) { row ->
                        when (row) {
                            is SessionListRow.Item -> {
                                val s = row.obj
                                SessionRow(
                                    id = s.str("id").orEmpty(),
                                    title = s.str("title")?.ifBlank { null } ?: "(başlıksız)",
                                    directory = s.obj("location").str("directory") ?: "?",
                                    updatedAt = s.obj("time").num("updated") ?: s.obj("time").num("created") ?: 0.0,
                                    cost = s.num("cost") ?: 0.0,
                                    busy = state.active.contains(s.str("id")),
                                    failed = s.str("outcome") == "failed",
                                    child = row.depth > 0,
                                    hasQuestion = s.str("id")?.let { questionSessions.contains(it) } ?: false,
                                    indent = row.depth,
                                    onClick = { s.str("id")?.let(onOpen) },
                                    onDelete = onDelete,
                                    onRename = onRename,
                                    onFork = onFork,
                                )
                            }
                            is SessionListRow.Expander -> ExpanderRow(
                                count = row.count,
                                expanded = row.expanded,
                                depth = row.depth,
                                onToggle = {
                                    expanded = if (row.parentID in expanded) expanded - row.parentID else expanded + row.parentID
                                },
                            )
                        }
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
                    sessions = state.sessions,
                    watchStatus = watchStatus,
                    onRefreshWatch = onRefreshWatch,
                    onArm = { force, targetId, at ->
                        showShutdown = false
                        onArmShutdown(force, targetId, at)
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

/** Üst bar için kompakt eylem düğmesi (IconButton 48dp yerine ~34dp). */
@Composable
private fun BarAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ExpanderRow(count: Int, expanded: Boolean, depth: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (24 + 16 * depth).dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panelAlt)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (expanded) "▾ " else "▸ ") + "$count alt ajan",
            color = Oc.purple,
            style = MaterialTheme.typography.labelSmall,
        )
    }
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
    hasQuestion: Boolean = false,
    indent: Int = 0,
    onClick: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onFork: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxWidth().padding(start = (16 * indent).dp)) {
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
            if (hasQuestion) {
                Text("❓", color = Oc.purple, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
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

/** Kapatma görevinin gönderileceği oturumu seçer (otomatik = uygulama seçer). */
@Composable
private fun TargetPicker(
    sessions: List<JsonObject>,
    target: JsonObject?,
    onPick: (JsonObject?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val candidates = sessions.filter { it.str("parentID") == null }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                target?.str("title") ?: "otomatik (“opencode-mobile” ya da en son güncellenen)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("otomatik") },
                onClick = {
                    onPick(null)
                    expanded = false
                },
            )
            candidates.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            s.str("title") ?: s.str("id") ?: "?",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onPick(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Kapatma görevi diyaloğu: kur (hedef oturum + zaman seçilebilir) / iptal et. */
@Composable
private fun ShutdownDialog(
    armed: Boolean,
    sessions: List<JsonObject>,
    watchStatus: WatchStatus?,
    onRefreshWatch: () -> Unit,
    onArm: (force: Boolean, targetId: String?, at: String?) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Diyalog açıkken canlılığı ilk açılışta ve 30 sn'de bir otomatik yenile.
    LaunchedEffect(Unit) {
        onRefreshWatch()
        while (true) {
            delay(30_000)
            onRefreshWatch()
        }
    }

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
        var force by rememberSaveable { mutableStateOf(false) }
        var target by remember { mutableStateOf<JsonObject?>(null) }
        var timeSel by rememberSaveable { mutableStateOf("now") }
        var customHour by rememberSaveable { mutableStateOf(3) }
        var customMinute by rememberSaveable { mutableStateOf(0) }
        val at = when (timeSel) {
            "now" -> null
            "custom" -> "%02d:%02d".format(customHour, customMinute)
            else -> timeSel
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Bilgisayarı kapat") },
            text = {
                Column {
                    Text(
                        "Tüm işler bittiğinde bilgisayar kapatılsın. Görev, seçilen oturuma " +
                            "gözlemci olarak gönderilir ve orada bekler; telefon kapalı olsa da " +
                            "sürer. Kapatma komutu (shutdown.exe /s /t 120) yalnızca bir kez " +
                            "çalıştırılır.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Kapatma zamanı",
                        style = MaterialTheme.typography.labelMedium,
                        color = Oc.dim,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TimeChip("Şimdi (işler bitince)", timeSel == "now") { timeSel = "now" }
                        listOf("00:00", "01:00", "02:00", "03:00").forEach { t ->
                            TimeChip(t, timeSel == t) { timeSel = t }
                        }
                        TimeChip("Özel…", timeSel == "custom") { timeSel = "custom" }
                    }
                    if (timeSel == "custom") {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            NumberPicker(customHour, (0..23).toList()) { customHour = it }
                            Text(":", color = Oc.dim)
                            NumberPicker(customMinute, (0..55 step 5).toList()) { customMinute = it }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Hedef oturum",
                        style = MaterialTheme.typography.labelMedium,
                        color = Oc.dim,
                    )
                    Spacer(Modifier.height(4.dp))
                    TargetPicker(
                        sessions = sessions,
                        target = target,
                        onPick = { target = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    WatchStatusCard(status = watchStatus, onRefresh = onRefreshWatch)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = force, onCheckedChange = { force = it })
                        Text("Açık programları zorla kapat (/f)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onArm(force, target?.str("id"), at) }) {
                    Text("Kapatmayı kur", color = Oc.warn)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Vazgeç", color = Oc.dim) }
            },
        )
    }
}

/** Kapatma zamanı çipi — liste filtresi çipleriyle aynı görünüm. */
@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Oc.bg else Oc.dim,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Oc.accent else Oc.panelAlt)
            .border(1.dp, if (selected) Oc.accent else Oc.border, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** İki haneli sayı seçici (saat / dakika). */
@Composable
private fun NumberPicker(value: Int, options: List<Int>, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("%02d".format(value)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text("%02d".format(o)) },
                    onClick = {
                        onPick(o)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Gözcünün canlılık durumu + "Yenile" tuşu. */
@Composable
private fun WatchStatusCard(status: WatchStatus?, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Gözcü", style = MaterialTheme.typography.labelMedium, color = Oc.dim)
            val text = when {
                status == null -> "Gözcü durumu bilinmiyor"
                !status.live -> "Gözcü: çalışmıyor — kapatma OLMAZ"
                else -> buildString {
                    append("Gözcü: çalışıyor")
                    val parts = mutableListOf<String>()
                    status.pid?.let { parts += "PID $it" }
                    status.uptimeMinutes?.let { parts += "$it dk" }
                    if (parts.isNotEmpty()) append(" (${parts.joinToString(" · ")})")
                    status.lastSayimAt?.let { append(" · son tur $it") }
                }
            }
            Text(
                text,
                color = when {
                    status == null -> Oc.dim
                    status.live -> Oc.accent
                    else -> Oc.err
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRefresh) { Text("Yenile", color = Oc.dim) }
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

/** Geçen süreyi kısa Türkçe metne çevirir (ör. "12 sn", "3 dk", "2 sa"). */
fun formatElapsed(seconds: Long): String {
    if (seconds < 60) return "$seconds sn"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes dk"
    val hours = minutes / 60
    if (hours < 24) return "$hours sa"
    return "${hours / 24} gün"
}

/** Token sayısını kısa biçime çevirir: 148000 -> "148k", 14800000 -> "14,8M". */
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> trimDecimal(n / 1_000_000.0) + "M"
    n >= 1_000 -> trimDecimal(n / 1_000.0) + "k"
    else -> n.toString()
}

/** Ondalığı virgülle yazar, gereksiz ",0" kuyruğunu atar. */
private fun trimDecimal(v: Double): String =
    "%.1f".format(v).replace('.', ',').removeSuffix(",0")

/** Ham kredi metnini ("1010.0000") iki ondalıklı Türkçe biçime çevirir ("1010,00"). */
private fun formatCredit(raw: String?): String {
    val v = raw?.toDoubleOrNull() ?: return "?"
    return "%.2f".format(v).replace('.', ',')
}

/** ISO 8601 zamanı yerel saatle "dd.MM HH:mm" biçimine çevirir (bilinmiyorsa "?"). */
private fun formatReset(iso: String?): String {
    if (iso.isNullOrBlank()) return "?"
    return runCatching {
        java.time.Instant.parse(iso)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
    }.getOrDefault("?")
}
