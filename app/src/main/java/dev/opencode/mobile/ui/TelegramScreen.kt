package dev.opencode.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.TelegramStatus

/**
 * Telegram yedek erişim ekranı.
 *
 * Köprü bilgisayarda çalışır; uygulama yalnızca bir giriş noktası ve isteğe
 * bağlı durum kontrolü sunar. Mobil uygulama/tünel çalışmasa bile Telegram
 * üzerinden OpenCode oturumları sürülebilir — bu yüzden "yedek erişim".
 */
@Composable
fun TelegramScreen(
    botUsername: String,
    statusUrl: String,
    status: TelegramStatus?,
    error: String?,
    checking: Boolean,
    onChangeBot: (String) -> Unit,
    onChangeStatusUrl: (String) -> Unit,
    onCheck: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val username = botUsername.trim().removePrefix("@")
    val openable = username.isNotEmpty()

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
                Text("✈ TELEGRAM", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("← geri", color = Oc.dim) }
            }
        }

        // ------------------------------------------------------------- içerik
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("YEDEK ERİŞİM") {
                Text(
                    "Telegram botu bilgisayarında çalışır. Mobil uygulama veya tünel " +
                        "kapalıyken bile Telegram üzerinden OpenCode oturumlarını sürebilirsin.",
                    color = Oc.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = botUsername,
                    onValueChange = onChangeBot,
                    label = { Text("Bot kullanıcı adı") },
                    placeholder = { Text("ornek_bot") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username"))
                        runCatching { ctx.startActivity(intent) }
                    },
                    enabled = openable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (openable) "Telegram'da @$username aç" else "Önce bot kullanıcı adını gir")
                }
            }

            Section("DURUM (isteğe bağlı)") {
                Text(
                    "Köprü aynı ağdaysa /health ucundan canlı durum alınabilir. Uzaktan " +
                        "(mobil veri) erişilemez; bu normaldir ve yedek erişimi etkilemez.",
                    color = Oc.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = statusUrl,
                    onValueChange = onChangeStatusUrl,
                    label = { Text("Durum adresi") },
                    placeholder = { Text("http://192.168.1.93:49375/health") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCheck, enabled = !checking) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Durumu kontrol et")
                        }
                    }
                    if (statusUrl.isNotBlank()) {
                        TextButton(onClick = { onChangeStatusUrl("") }) { Text("temizle", color = Oc.dim) }
                    }
                }
                when {
                    checking -> Text(
                        "sorgulanıyor…",
                        color = Oc.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    error != null -> Text(
                        "⚠ $error",
                        color = Oc.warn,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    status != null -> {
                        val st = status
                        Text(
                            "✅ Köprü çalışıyor",
                            color = Oc.accent,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            buildString {
                                if (st.username != null) append("@${st.username}") else append("kullanıcı adı yok")
                                append(" · yetkili: ${st.allowed}")
                                append(" · çalışma: ${st.uptimeSeconds} sn")
                                st.opencode?.let { append("\nOpenCode: $it") }
                            },
                            color = Oc.dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!st.username.isNullOrBlank() && botUsername.isBlank()) {
                            TextButton(onClick = { onChangeBot(st.username ?: "") }) {
                                Text("Kullanıcı adını doldur: @${st.username}", color = Oc.accent)
                            }
                        }
                    }

                    else -> Text(
                        "Henüz kontrol edilmedi.",
                        color = Oc.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("KURULUM") {
                Step(
                    no = 1,
                    title = "Köprüyü başlat",
                    body = "cd W:\\opencode-mobile\\telegram-bridge\nnpm start",
                    clipboard = clipboard,
                )
                Step(
                    no = 2,
                    title = "Telegram'da bota /start yaz",
                    body = "Bot sana Telegram kullanıcı ID'ni söyler.",
                    clipboard = clipboard,
                )
                Step(
                    no = 3,
                    title = "ID'yi yetkilendir",
                    body = "telegram-bridge/.env → TELEGRAM_ALLOWED_IDS=<id>\nsonra botu yeniden başlat.",
                    clipboard = clipboard,
                )
            }

            Section("KOMUTLAR") {
                Text(
                    "/sessions · /use <no> · /new [dizin]\n" +
                        "/current · /last · /model · /perm · /stop · /help",
                    color = Oc.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Düz mesaj yazınca seçili oturuma prompt gider; yanıt canlı akar.",
                    color = Oc.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Kenarlıklı bölüm — ayarlar ekranındaki görünümle tutarlı. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Oc.panel)
            .border(1.dp, Oc.border, RoundedCornerShape(4.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Oc.dim, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** Numaralı kurulum adımı + panoya kopyala. */
@Composable
private fun Step(no: Int, title: String, body: String, clipboard: ClipboardManager) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$no. $title", color = Oc.text, style = MaterialTheme.typography.bodyLarge)
        Text(body, color = Oc.dim, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { clipboard.setText(AnnotatedString(body)) }) {
            Text("kopyala", color = Oc.accent)
        }
    }
}
