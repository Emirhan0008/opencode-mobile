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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.LiveMessage
import dev.opencode.mobile.SessionState
import dev.opencode.mobile.ToolRow
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import kotlinx.serialization.json.JsonObject

@Composable
fun SessionScreen(
    state: SessionState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onPermission: (sessionID: String, requestID: String, decision: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.live?.text?.length) {
        val count = state.messages.size + (if (state.live != null) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(Oc.bg)) {

        // ------------------------------------------------------------- top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Oc.panel)
                .border(1.dp, Oc.border)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "geri", tint = Oc.text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.session?.str("title")?.ifBlank { null } ?: state.session?.str("id") ?: "…",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val model = state.session?.obj("model")
                Text(
                    buildString {
                        append(state.session?.obj("location")?.str("directory") ?: "?")
                        val prov = model?.str("providerID")
                        val mid = model?.str("id")
                        if (prov != null || mid != null) {
                            append("  ·  ").append(prov ?: "?").append('/').append(mid ?: "?")
                        }
                        append("  ·  $").append("%.3f".format(state.session?.num("cost") ?: 0.0))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.busy) {
                Text("⏳", modifier = Modifier.padding(end = 4.dp))
                IconButton(onClick = onInterrupt) {
                    Icon(Icons.Default.Stop, contentDescription = "durdur", tint = Oc.err)
                }
            }
        }

        // -------------------------------------------------------------- akış
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.permissions, key = { "perm-" + it.str("id") }) { req ->
                PermissionCard(
                    action = req.str("action") ?: "?",
                    resources = req.arr("resources").map { it.obj().str("path") ?: it.obj().str("uri") ?: it.toString() },
                    message = req.str("message"),
                    onDecision = { decision ->
                        onPermission(req.str("sessionID").orEmpty(), req.str("id").orEmpty(), decision)
                    },
                )
            }

            items(state.messages, key = { it.str("id").orEmpty() }) { msg ->
                MessageCard(msg, liveID = state.live?.id)
            }

            state.live?.let { live ->
                item(key = "live") { LiveCard(live) }
            }
        }

        state.error?.let {
            Text("✖ $it", color = Oc.err, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // ------------------------------------------------------------ giriş
        Row(
            Modifier
                .fillMaxWidth()
                .background(Oc.panel)
                .border(1.dp, Oc.border)
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
                Icon(Icons.Default.Send, contentDescription = "gönder", tint = Oc.accent)
            }
        }
    }
}

// ------------------------------------------------------------------ cards

@Composable
private fun MessageCard(msg: JsonObject, liveID: String?) {
    val id = msg.str("id")
    if (id != null && id == liveID) return // canlı kart bunun yerine gösteriliyor

    when (msg.str("type")) {
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
                        "text" -> Text(part.str("text").orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        "reasoning" -> ReasoningBlock(part.str("text").orEmpty())
                        "tool" -> ToolBlock(part)
                    }
                }
            }
        }

        "system", "compaction", "synthetic", "skill" -> Unit

        else -> Unit
    }
}

@Composable
private fun LiveCard(live: LiveMessage) {
    Panel(border = Oc.accent, header = "▸ akıyor…") {
        if (live.reasoning.isNotBlank()) ReasoningBlock(live.reasoning)
        if (live.text.isNotBlank()) Text(live.text, style = MaterialTheme.typography.bodyLarge)
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
            Text(
                text,
                color = Oc.purple,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolBlock(part: JsonObject) {
    val name = part.str("name") ?: "tool"
    val state = part.obj("state")
    val status = state.str("status") ?: "?"
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
            Text(statusIcon(status), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Text("tool · $name", color = Oc.blue, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Oc.dim,
            )
        }
        if (open) {
            val input = state.obj("input")
            Text(
                input.toString().take(1500),
                color = Oc.dim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            val content = state.arr("content")
            if (content.isNotEmpty()) {
                val out = content.joinToString("\n") { it.obj().str("text").orEmpty() }.trim()
                if (out.isNotBlank()) {
                    Text(
                        out.take(2500),
                        color = Oc.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .background(Oc.codeBg)
                            .padding(6.dp),
                    )
                }
            }
            state.obj("error").str("message")?.let {
                Text("✖ $it", color = Oc.err, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LiveToolBlock(tool: ToolRow) {
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
        Text("tool · ${tool.name}", color = Oc.blue, style = MaterialTheme.typography.labelLarge)
    }
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
    "running" -> "⏳"
    else -> "•"
}