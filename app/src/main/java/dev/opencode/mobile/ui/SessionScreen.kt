package dev.opencode.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.LiveMessage
import dev.opencode.mobile.R
import dev.opencode.mobile.SessionState
import dev.opencode.mobile.ToolRow
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import dev.opencode.mobile.net.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SessionScreen(
    state: SessionState,
    agents: List<JsonObject>,
    models: List<JsonObject>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onPermission: (sessionID: String, requestID: String, decision: String) -> Unit,
    onSwitchAgent: (String) -> Unit,
    onSwitchModel: (providerID: String, modelID: String, variant: String?) -> Unit,
    onReplyForm: (formID: String, answer: JsonObject) -> Unit,
    onCancelForm: (formID: String) -> Unit,
    onRevert: (messageID: String) -> Unit,
    onSwipe: (offset: Int) -> Unit,
    commands: List<JsonObject>,
    onRunCommand: (name: String, text: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showCommands by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // sağa/sola kaydırma ile oturumlar arası geçiş
    var dragX by remember { mutableStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 120.dp.toPx() }

    LaunchedEffect(state.messages.size, state.live?.text?.length, state.forms.size) {
        val count = state.forms.size + state.messages.size + (if (state.live != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(Oc.bg)) {

        // ------------------------------------------------------------- top bar
        Column(
            Modifier
                .fillMaxWidth()
                .background(Oc.panel)
                .statusBarsPadding()
                .border(1.dp, Oc.border),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "geri", tint = Oc.text)
                }
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = "OpenCode Mobile",
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                // başlık satırı: tam genişlik, uzun isimler 2 satıra kadar sığar
                Column(Modifier.weight(1f)) {
                    Text(
                        state.session?.str("title")?.ifBlank { null } ?: state.session?.str("id") ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(state.session?.obj("location")?.str("directory") ?: "?")
                            append("  ·  $").append("%.3f".format(state.session?.num("cost") ?: 0.0))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // ikinci ince satır: model · ajan · oturum tokenları · sonuç rozeti
                    SessionContextLine(state.session)
                }
                if (state.busy) {
                    Text(
                        if (state.childBusy) "⏳ alt ajan" else "⏳",
                        color = if (state.childBusy) Oc.purple else Oc.text,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = onInterrupt) {
                        Icon(Icons.Default.Stop, contentDescription = "durdur", tint = Oc.err)
                    }
                }
            }

            // ajan / model seçicileri — kendi satırlarında, ismi sıkıştırmazlar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentPicker(
                    current = state.session?.str("agent"),
                    agents = agents,
                    onPick = onSwitchAgent,
                )
                Spacer(Modifier.width(6.dp))
                ModelPicker(
                    current = state.session?.obj("model"),
                    models = models,
                    onPick = onSwitchModel,
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { showCommands = true }) {
                    Text("⌨ komutlar", color = Oc.accent, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
            }
        }

        // -------------------------------------------------------------- akış
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragCancel = { dragX = 0f },
                        onDragEnd = {
                            when {
                                dragX < -swipeThreshold -> onSwipe(1)  // sola: sonraki
                                dragX > swipeThreshold -> onSwipe(-1)  // sağa: önceki
                            }
                            dragX = 0f
                        },
                        onHorizontalDrag = { _, amount -> dragX += amount },
                    )
                },
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.forms, key = { "form-" + it.str("id") }) { form ->
                FormCard(form, onReply = onReplyForm, onCancel = onCancelForm)
            }

            items(state.permissions, key = { "perm-" + it.str("id") }) { req ->
                PermissionCard(
                    action = req.str("action") ?: "?",
                    resources = req.arr("resources").mapNotNull { it.text() },
                    message = req.str("message"),
                    onDecision = { decision ->
                        onPermission(req.str("sessionID").orEmpty(), req.str("id").orEmpty(), decision)
                    },
                )
            }

            items(state.messages, key = { it.str("id").orEmpty() }) { msg ->
                MessageCard(
                    msg = msg,
                    liveID = state.live?.id,
                    busy = state.busy,
                    onRevert = onRevert,
                    onQuote = { quote -> input = (input.trimEnd() + "\n\n" + quote).trimStart('\n') },
                )
            }

            state.live?.let { live ->
                item(key = "live") { LiveCard(live) }
            }
        }

        state.error?.let {
            Text("✖ $it", color = Oc.err, modifier = Modifier.padding(horizontal = 12.dp))
        }

        if (showCommands) {
            CommandsDialog(
                commands = commands,
                onRun = { name, text ->
                    showCommands = false
                    onRunCommand(name, text)
                },
                onDismiss = { showCommands = false },
            )
        }

        // ------------------------------------------------------------ giriş
        Row(
            Modifier
                .fillMaxWidth()
                .background(Oc.panel)
                .border(1.dp, Oc.border)
                .navigationBarsPadding()
                .imePadding()
                .padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("mesaj yaz…", color = Oc.dim) },
                colors = ocFieldColors(),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = !state.sending && input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "gönder", tint = Oc.accent)
            }
        }
    }
}

// ------------------------------------------------------------- agent / model

@Composable
private fun AgentPicker(current: String?, agents: List<JsonObject>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val primary = agents.filter { it.str("mode") == "primary" }

    Box {
        Text(
            current ?: "agent",
            color = Oc.purple,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 130.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, Oc.border, RoundedCornerShape(3.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            primary.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(agent.str("name") ?: agent.str("id").orEmpty())
                            agent.str("description")?.let {
                                Text(it.take(60), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        open = false
                        agent.str("id")?.let(onPick)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    current: JsonObject?,
    models: List<JsonObject>,
    onPick: (providerID: String, modelID: String, variant: String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = "${current?.str("providerID") ?: "?"}/${current?.str("id") ?: "model"}"

    Box {
        Text(
            label,
            color = Oc.blue,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(140.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, Oc.border, RoundedCornerShape(3.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                val providerID = m.str("providerID") ?: return@forEach
                val modelID = m.str("modelID") ?: m.str("id") ?: return@forEach
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.str("name") ?: modelID)
                            Text("$providerID / $modelID", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(providerID, modelID, null)
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------ komutlar

/** OpenCode komutlarını (/init, /review, uzantı komutları…) listeler ve çalıştırır. */
@Composable
private fun CommandsDialog(
    commands: List<JsonObject>,
    onRun: (name: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<JsonObject?>(null) }
    var args by remember { mutableStateOf("") }
    val visible = commands.filter {
        query.isBlank() ||
            it.str("name").orEmpty().contains(query, ignoreCase = true) ||
            it.str("description").orEmpty().contains(query, ignoreCase = true)
    }

    // Tek diyalog, iki aşamalı: liste → argüman (pencere değişimi bazı cihazlarda sorun çıkarıyor)
    AlertDialog(
        onDismissRequest = {
            if (picked == null) onDismiss() else picked = null
        },
        title = {
            Text(if (picked == null) "Komutlar" else "/" + (picked?.str("name") ?: "?"))
        },
        text = {
            if (picked == null) {
                Column(Modifier.heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("ara…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(visible) { c ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = c
                                        args = ""
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    "/" + (c.str("name") ?: "?"),
                                    color = Oc.accent,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                c.str("description")?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Oc.dim,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column {
                    picked?.str("description")?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Oc.dim)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        placeholder = { Text("bağımsız değişkenler (isteğe bağlı)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (picked == null) {
                TextButton(onClick = onDismiss) { Text("Kapat", color = Oc.dim) }
            } else {
                TextButton(onClick = {
                    val name = picked?.str("name").orEmpty()
                    val text = args.trim()
                    picked = null
                    onRun(name, text)
                }) { Text("Çalıştır", color = Oc.accent) }
            }
        },
        dismissButton = {
            if (picked != null) {
                TextButton(onClick = { picked = null }) { Text("Vazgeç", color = Oc.dim) }
            }
        },
    )
}

// ------------------------------------------------------------------ cards

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    msg: JsonObject,
    liveID: String?,
    busy: Boolean,
    onQuote: (String) -> Unit,
    onRevert: (String) -> Unit,
) {
    val id = msg.str("id")
    if (id != null && id == liveID) return // canlı kart bunun yerine gösteriliyor
    val type = msg.str("type")
    if (type != "user" && type != "assistant" && type != "compaction" && type != "synthetic") return

    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val plain = messageText(msg)

    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { menu = true }),
    ) {
        when (type) {
            "user" -> Panel(border = Oc.blue, header = "» sen") {
                Text(msg.str("text").orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }

            "assistant" -> {
                val model = msg.obj("model")
                Panel(
                    border = Oc.border,
                    header = "▸ ${msg.str("agent") ?: "agent"}  ·  ${model.str("providerID")}/${model.str("id")}",
                ) {
                    for (raw in msg.arr("content")) {
                        val part = raw.obj()
                        when (part.str("type")) {
                            "text" -> RichText(part.str("text").orEmpty())
                            "reasoning" -> ReasoningBlock(part.str("text").orEmpty())
                            "tool" -> ToolBlock(part)
                        }
                    }
                    // model/sağlayıcı hatası: içerik boş kalmışsa da hata görünür olsun
                    val err = msg.obj("error").str("message")
                    if (err != null || msg.str("finish") == "error") {
                        Text(
                            "✖ " + (err ?: "yanıt üretilemedi"),
                            color = Oc.err,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // tur özeti: süre · token · maliye · bitiş rozeti (ince ayraçla ayrılır)
                    TurnMeta(msg)
                }
            }

            "compaction" -> CollapsibleNotice(
                glyph = "⇲ bağlam sıkıştırıldı",
                color = Oc.purple,
                body = plain,
            )

            "synthetic" -> CollapsibleNotice(
                glyph = "◇ sentetik",
                color = Oc.dim,
                body = plain,
            )
        }

        // uzun basma menüsü: kopyala / alıntıla / geri al
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Metni kopyala") },
                enabled = plain.isNotBlank(),
                onClick = {
                    clipboard.setText(AnnotatedString(plain))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Yanıta alıntıla") },
                enabled = plain.isNotBlank(),
                onClick = {
                    onQuote(plain.lineSequence().joinToString("\n") { "> $it" })
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Bu noktaya geri al (revert)") },
                enabled = id != null && !busy,
                onClick = {
                    menu = false
                    if (id != null) onRevert(id)
                },
            )
        }
    }
}

/** Kopyalama/alıntılama için mesajın düz metni. */
private fun messageText(msg: JsonObject): String = when (msg.str("type")) {
    "user" -> msg.str("text").orEmpty().trim()
    "assistant" -> msg.arr("content").mapNotNull { raw ->
        val part = raw.obj()
        when (part.str("type")) {
            "text" -> part.str("text")?.trim()
            "reasoning" -> part.str("text")?.trim()?.let { "[düşünme]\n$it" }
            else -> null
        }
    }.filter { it.isNotBlank() }.joinToString("\n\n")
    // bağlam sıkıştırma / sentetik mesajlar: düz `text` ya da `content[]` içinde özet
    "compaction", "synthetic" -> msg.str("text")?.trim()?.takeIf { it.isNotBlank() }
        ?: msg.arr("content").mapNotNull { it.obj().str("text")?.trim() }
            .filter { it.isNotBlank() }.joinToString("\n\n")
    else -> ""
}

/** Tur özeti: ince ayraç + süre · token · maliye · bitiş rozeti + değişen dosyalar. */
@Composable
private fun TurnMeta(msg: JsonObject) {
    val time = msg.obj("time")
    val created = time.num("created")?.toLong()
    val completed = time.num("completed")?.toLong()
    val dur = if (created != null && completed != null && completed >= created) {
        fmtDuration(completed - created)
    } else {
        "akıyor…"
    }

    HorizontalDivider(
        color = Oc.border,
        thickness = 1.dp,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )

    val tokens = tokenLine(msg.obj("tokens"))
    val cost = msg.num("cost") ?: 0.0
    val summary = buildString {
        append(dur)
        if (tokens.isNotBlank()) {
            append(" · ")
            append(tokens)
        }
        if (cost > 0) {
            append(" · ")
            append(fmtCost(cost))
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            summary,
            color = Oc.dim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        finishBadge(msg.str("finish"))?.let { (label, color) ->
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
        }
    }

    ChangedFiles(msg.obj("snapshot").arr("files").mapNotNull { it.text() })
}

/** Bitmiş turun `finish` değerini rozet metni + rengine çevirir (bilinmeyen: olduğu gibi). */
private fun finishBadge(finish: String?): Pair<String, Color>? = when (finish) {
    "stop" -> "✓ bitti" to Oc.accent
    "tool-calls" -> "↳ araç bekliyor" to Oc.blue
    "length" -> "✂ uzunluk doldu" to Oc.warn
    "error" -> "✖ hata" to Oc.err
    null, "" -> null
    else -> finish to Oc.dim
}

/** `snapshot.files` boş değilse dokununca açılan değişen dosya listesi. */
@Composable
private fun ChangedFiles(files: List<String>) {
    if (files.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + "▤ ${files.size} dosya değişti",
            color = Oc.dim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .clickable { open = !open }
                .padding(vertical = 2.dp),
        )
        if (open) {
            for (f in files) {
                Text(
                    if (f.length > 70) "…" + f.takeLast(70) else f,
                    color = Oc.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Oc.codeBg)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** compaction / synthetic mesajları için tam genişlikte, katlanır ince şerit. */
@Composable
private fun CollapsibleNotice(glyph: String, color: Color, body: String) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(Oc.panelAlt)
            .border(1.dp, Oc.border, RoundedCornerShape(3.dp)),
    ) {
        Text(
            (if (open) "▾ " else "▸ ") + glyph,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (open && body.isNotBlank()) {
            RichText(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = Oc.dim,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/** Oturum başlığının altındaki ikinci ince satır: model · ajan · toplam token · sonuç. */
@Composable
private fun SessionContextLine(session: JsonObject?) {
    if (session == null) return
    val model = session.obj("model")
    val modelText = buildString {
        append(model.str("providerID") ?: "?")
        append('/')
        append(model.str("id") ?: "?")
        model.str("variant")?.let { append('#').append(it) }
    }
    val agent = session.str("agent") ?: "?"
    val tokens = tokenLine(session.obj("tokens"))
    val line = buildAnnotatedString {
        withStyle(SpanStyle(color = Oc.blue)) { append(modelText) }
        append(" · ")
        append(agent)
        if (tokens.isNotBlank()) {
            append(" · ")
            append(tokens)
        }
        when (session.str("outcome")) {
            "succeeded" -> {
                append(" · ")
                withStyle(SpanStyle(color = Oc.accent)) { append("✓ tamamlandı") }
            }

            "failed" -> {
                append(" · ")
                withStyle(SpanStyle(color = Oc.err)) { append("✖ başarısız") }
            }
        }
    }
    Text(
        line,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `tokens` nesnesini "40,4M girdi · 1,1M çıktı · 1,2M düşünme · 1,13G önbellek" yapar. */
private fun tokenLine(tokens: JsonObject): String {
    val cache = tokens.obj("cache")
    val input = tokens.num("input") ?: 0.0
    val output = tokens.num("output") ?: 0.0
    val reasoning = tokens.num("reasoning") ?: 0.0
    val cacheRead = cache.num("read") ?: 0.0
    val parts = mutableListOf<String>()
    if (input > 0) parts += fmtCount(input.toLong()) + " girdi"
    if (output > 0) parts += fmtCount(output.toLong()) + " çıktı"
    if (reasoning > 0) parts += fmtCount(reasoning.toLong()) + " düşünme"
    if (cacheRead > 0) parts += fmtCount(cacheRead.toLong()) + " önbellek"
    return parts.joinToString(" · ")
}

@Composable
private fun LiveCard(live: LiveMessage) {
    Panel(border = Oc.accent, header = "▸ akıyor…") {
        if (live.reasoning.isNotBlank()) ReasoningBlock(live.reasoning)
        if (live.text.isNotBlank()) RichText(live.text)
        for (tool in live.tools) {
            LiveToolBlock(tool)
        }
        if (live.text.isBlank() && live.reasoning.isBlank() && live.tools.isEmpty()) {
            Text("…", color = Oc.dim)
        }
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    // boş düşünme parçaları için "0 krk" gösterme
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(Oc.panelAlt)
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾ düşünme" else "▸ düşünme", color = Oc.purple, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text("${text.length} krk", color = Oc.dim, style = MaterialTheme.typography.labelSmall)
        }
        if (open) {
            RichText(
                text,
                color = Oc.purple,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Tool girdi/çıktısını katlanabilir gösterir — hem tamamlanmış hem canlı tool'lar için. */
@Composable
private fun ToolDetails(input: String, output: String, error: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(Oc.panelAlt)
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾ ayrıntı" else "▸ ayrıntı", color = Oc.dim, style = MaterialTheme.typography.labelSmall)
        }
        if (open) {
            if (input.isNotBlank()) {
                Text(
                    "girdi:",
                    color = Oc.dim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    truncate(input, 8000),
                    color = Oc.dim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(Oc.codeBg).padding(6.dp),
                )
            }
            if (output.isNotBlank()) {
                Text(
                    "çıktı:",
                    color = Oc.dim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    truncate(output, 20000),
                    color = Oc.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(Oc.codeBg).padding(6.dp),
                )
            }
            if (error.isNotBlank()) {
                Text("✖ $error", color = Oc.err, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Uzun çıktıyı sınırlar; kesilirse sonuna kaç karakter kesildiğini ekler. */
private fun truncate(s: String, limit: Int): String =
    if (s.length <= limit) s else s.take(limit) + "\n… (kesildi, ${s.length - limit} krk)"

private val prettyJson = Json { prettyPrint = true }

private fun toolInputText(state: JsonObject): String {
    val el = state["input"] ?: return ""
    return when (el) {
        is JsonPrimitive -> el.content
        else -> prettyJson.encodeToString(JsonElement.serializer(), el)
    }
}

@Composable
private fun ToolBlock(part: JsonObject) {
    val name = part.str("name") ?: "tool"
    val state = part.obj("state")
    val status = state.str("status") ?: "?"
    val meta = state.obj("metadata")
    val exit = meta.num("exit")?.toInt()
    val truncated = meta.str("truncated") == "true"
    val dur = durationOf(part.obj("time"))

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(Oc.panelAlt)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusIcon(status), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Text("tool · $name", color = Oc.blue, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(status, color = Oc.dim, style = MaterialTheme.typography.labelSmall)
            dur?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, color = Oc.dim, style = MaterialTheme.typography.labelSmall)
            }
            if (exit != null && exit != 0) {
                Spacer(Modifier.width(6.dp))
                Text("exit $exit", color = Oc.err, style = MaterialTheme.typography.labelSmall)
            }
            if (truncated) {
                Spacer(Modifier.width(6.dp))
                Text("kesildi", color = Oc.warn, style = MaterialTheme.typography.labelSmall)
            }
        }
        ToolDetails(
            input = toolInputText(state),
            output = state.arr("content").joinToString("\n") { it.obj().str("text").orEmpty() },
            error = state.obj("error").str("message").orEmpty(),
        )
    }
}

/** `time.created` → `time.completed` farkı; ikisi de varsa "3,2 sn" döner. */
private fun durationOf(time: JsonObject): String? {
    val created = time.num("created")?.toLong() ?: return null
    val completed = time.num("completed")?.toLong() ?: return null
    if (completed < created) return null
    return fmtDuration(completed - created)
}

@Composable
private fun LiveToolBlock(tool: ToolRow) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(Oc.panelAlt)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusIcon(tool.status), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Text(
                if (tool.name.isBlank() || tool.name == "tool") "tool" else "tool · ${tool.name}",
                color = Oc.blue,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        ToolDetails(input = tool.input, output = tool.output, error = tool.error)
    }
}

// ------------------------------------------------------------------- form

/**
 * `question` aracıdan gelen etkileşimli form (soru kartı).
 * Alan türleri: string (seçenekli/seçeneksiz), number, integer, boolean, multiselect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormCard(
    form: JsonObject,
    onReply: (String, JsonObject) -> Unit,
    onCancel: (String) -> Unit,
) {
    val formID = form.str("id").orEmpty()
    val fields = form.arr("fields").map { it.obj() }.filter { it.str("hidden") != "true" }
    val answers = remember(formID) { mutableStateMapOf<String, JsonElement>() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panel)
            .border(1.dp, Oc.purple, RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("❓ SORU", color = Oc.purple, style = MaterialTheme.typography.labelLarge)
        Text(form.str("title") ?: "Seçim bekleniyor", style = MaterialTheme.typography.bodyLarge)

        for (field in fields) {
            val key = field.str("key") ?: continue
            val title = field.str("title") ?: key
            val type = field.str("type") ?: "string"
            val options = field.arr("options").map { it.obj() }
            val required = field.str("required") == "true"
            val default = field["default"]?.text()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title + if (required) " *" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                field.str("description")?.let {
                    Text(it, color = Oc.dim, style = MaterialTheme.typography.labelSmall)
                }

                when {
                    type == "boolean" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selected = answers[key]?.text()
                        listOf("evet" to "true", "hayır" to "false").forEach { (label, value) ->
                            ChoiceChip(
                                label = label,
                                selected = selected == value,
                            ) { answers[key] = JsonPrimitive(value == "true") }
                        }
                    }

                    options.isNotEmpty() && type != "multiselect" -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val selected = answers[key]?.text()
                            options.forEach { opt ->
                                val value = opt.str("value") ?: return@forEach
                                ChoiceChip(
                                    label = opt.str("label") ?: value,
                                    selected = selected == value,
                                ) { answers[key] = JsonPrimitive(value) }
                            }
                        }
                        // serbest yanıt her zaman mümkün
                        var custom by remember(formID, key) { mutableStateOf(default ?: "") }
                        OutlinedTextField(
                            value = custom,
                            onValueChange = {
                                custom = it
                                answers[key] = JsonPrimitive(it)
                            },
                            singleLine = true,
                            placeholder = { Text("serbest yanıt…", color = Oc.dim) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ocFieldColors(),
                        )
                    }

                    type == "multiselect" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selected = answers[key] as? JsonArray
                        val values = selected?.mapNotNull { it.text() } ?: emptyList()
                        options.forEach { opt ->
                            val value = opt.str("value") ?: return@forEach
                            ChoiceChip(
                                label = opt.str("label") ?: value,
                                selected = value in values,
                            ) {
                                val next = if (value in values) values - value else values + value
                                answers[key] = buildJsonArray { next.forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }

                    type == "external" -> {
                        Text(field.str("url") ?: "", color = Oc.blue, style = MaterialTheme.typography.bodySmall)
                    }

                    else -> {
                        var text by remember(formID, key) { mutableStateOf(default ?: "") }
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                answers[key] = JsonPrimitive(it)
                            },
                            singleLine = type != "string" || field.str("format") == null,
                            keyboardOptions = if (type == "number" || type == "integer") {
                                androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
                            } else {
                                androidx.compose.foundation.text.KeyboardOptions.Default
                            },
                            placeholder = { Text(field.str("placeholder") ?: "", color = Oc.dim) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ocFieldColors(),
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onCancel(formID) }) { Text("✗ iptal", color = Oc.err) }
            TextButton(onClick = {
                val answer = buildJsonObject {
                    for (f in fields) {
                        val key = f.str("key") ?: continue
                        val value = answers[key] ?: continue
                        put(key, value)
                    }
                }
                onReply(formID, answer)
            }) { Text("▸ gönder", color = Oc.accent) }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Oc.bg else Oc.text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Oc.accent else Oc.panelAlt)
            .border(1.dp, if (selected) Oc.accent else Oc.border, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PermissionCard(
    action: String,
    resources: List<String>,
    message: String?,
    onDecision: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panel)
            .border(1.dp, Oc.warn, RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔐 İZİN İSTEĞİ", color = Oc.warn, style = MaterialTheme.typography.labelLarge)
        Text(action, style = MaterialTheme.typography.bodyLarge)
        message?.let { Text(it, color = Oc.dim, style = MaterialTheme.typography.bodySmall) }
        if (resources.isNotEmpty()) {
            Text(
                resources.take(6).joinToString("\n") { "  • $it" },
                color = Oc.dim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onDecision("once") }) { Text("✓ bir kez", color = Oc.accent) }
            TextButton(onClick = { onDecision("always") }) { Text("✓ her zaman", color = Oc.blue) }
            TextButton(onClick = { onDecision("reject") }) { Text("✗ reddet", color = Oc.err) }
        }
    }
}

@Composable
private fun Panel(
    border: androidx.compose.ui.graphics.Color,
    header: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panel)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        Text(header, color = Oc.dim, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        content()
    }
}

private fun statusIcon(status: String): String = when (status) {
    "completed", "ok" -> "✅"
    "error", "failed" -> "❌"
    "running", "streaming" -> "⏳"
    else -> "•"
}