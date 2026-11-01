package dev.opencode.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R

/**
 * Ayarlar ekranı: bildirim tercihleri ve görünüm (tema).
 *
 * Tema burada da değiştirilebilir; üst bardaki ☀/☾ düğmesi KALIR ve ikisi de
 * aynı VM durumuna (`setDarkTheme`) bağlanır — tek gerçek kaynak ViewModel'dir.
 */
@Composable
fun SettingsScreen(
    backgroundService: Boolean,
    notifyPermissions: Boolean,
    notifyForms: Boolean,
    notifyWork: Boolean,
    quotaBadge: Boolean,
    darkTheme: Boolean,
    onToggleBackground: (Boolean) -> Unit,
    onTogglePermissions: (Boolean) -> Unit,
    onToggleForms: (Boolean) -> Unit,
    onToggleWork: (Boolean) -> Unit,
    onToggleQuotaBadge: (Boolean) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onOpenTelegram: () -> Unit = {},
    // ---- sesli komut ----
    voiceEnabled: Boolean = false,
    voiceConfirm: Boolean = true,
    voiceAutoSummary: Boolean = true,
    voiceName: String = "deniz",
    /** En son sesli okunan metin — özet mi doğrudan yanıt mı olduğunu gösterir. */
    voiceLastSpoken: String = "",
    evrenBaseUrl: String = "",
    evrenAsrSet: Boolean = false,
    evrenTtsSet: Boolean = false,
    evrenSumSet: Boolean = false,
    voiceTestResult: String? = null,
    voiceTesting: Boolean = false,
    onToggleVoice: (Boolean) -> Unit = {},
    onToggleVoiceConfirm: (Boolean) -> Unit = {},
    onToggleVoiceAutoSummary: (Boolean) -> Unit = {},
    onChangeVoiceName: (String) -> Unit = {},
    onChangeEvrenBase: (String) -> Unit = {},
    onChangeAsrKey: (String) -> Unit = {},
    onChangeTtsKey: (String) -> Unit = {},
    onChangeSumKey: (String) -> Unit = {},
    onTestVoice: () -> Unit = {},
    onBack: () -> Unit,
) {
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
                Text("┌─ AYARLAR", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("← oturumlar", color = Oc.dim) }
            }
        }

        // ------------------------------------------------------------- içerik
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSection("BİLDİRİMLER") {
                // Ana anahtar: kapalıyken arka plan servisi hiç çalışmaz.
                SettingsSwitchRow(
                    title = "Arka plan bağlantı servisi",
                    subtitle = "Uygulama kapalıyken bağlantıda kalır ve bildirim gelir",
                    checked = backgroundService,
                    enabled = true,
                    onCheckedChange = onToggleBackground,
                )
                // Alt anahtarlar yalnızca ana anahtar açıkken etkin olur.
                SettingsSwitchRow(
                    title = "İzin istekleri",
                    checked = notifyPermissions,
                    enabled = backgroundService,
                    onCheckedChange = onTogglePermissions,
                )
                SettingsSwitchRow(
                    title = "Sorular",
                    checked = notifyForms,
                    enabled = backgroundService,
                    onCheckedChange = onToggleForms,
                )
                SettingsSwitchRow(
                    title = "Görev bitişi",
                    checked = notifyWork,
                    enabled = backgroundService,
                    onCheckedChange = onToggleWork,
                )
            }

            SettingsSection("SESLİ KOMUT") {
                SettingsSwitchRow(
                    title = "Sesli komut",
                    subtitle = "Bildirimdeki düğmelerle başlat; onay, oturum seçimi ve yanıt dinleme tamamen sesli. Durdur süren işi keser, Kapat özelliği kapatır",
                    checked = voiceEnabled,
                    enabled = true,
                    onCheckedChange = onToggleVoice,
                )
                SettingsSwitchRow(
                    title = "Duyduğunu sesli teyit et",
                    subtitle = "Prompt gönderilmeden önce ne anladığını okur; okuma biter bitmez mikrofon açılır (ekstra onay turu yok)",
                    checked = voiceConfirm,
                    enabled = voiceEnabled,
                    onCheckedChange = onToggleVoiceConfirm,
                )
                SettingsSwitchRow(
                    title = "Yanıtı sesli özetle",
                    subtitle = "Tur bitince modelin sana yazdığı metni özetleyip okur",
                    checked = voiceAutoSummary,
                    enabled = voiceEnabled,
                    onCheckedChange = onToggleVoiceAutoSummary,
                )
                if (voiceLastSpoken.isNotBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Oc.panel)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Son okunan",
                            color = Oc.dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (voiceLastSpoken.length > 400) voiceLastSpoken.take(400) + "…"
                            else voiceLastSpoken,
                            color = Oc.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Ses seçimi: dokununca sıradaki sese geçer (deniz/ece/emre/kaan).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = voiceEnabled) {
                            val voices = listOf("deniz", "ece", "emre", "kaan")
                            val i = voices.indexOf(voiceName).coerceAtLeast(0)
                            onChangeVoiceName(voices[(i + 1) % voices.size])
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ses", color = if (voiceEnabled) Oc.text else Oc.dim, style = MaterialTheme.typography.bodyLarge)
                        Text("VoxCPM2 · dokununca değişir", color = Oc.dim, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(voiceName, color = Oc.accent, style = MaterialTheme.typography.bodyLarge)
                }

                VoiceKeyField(
                    label = "EVREN adresi",
                    value = evrenBaseUrl,
                    placeholder = "https://evren-llmapi.ssyz.org.tr",
                    onCommit = onChangeEvrenBase,
                )
                VoiceKeyField(
                    label = "Konuşma→metin anahtarı",
                    saved = evrenAsrSet,
                    placeholder = "qwen3-asr-1.7b anahtarı",
                    onCommit = onChangeAsrKey,
                )
                VoiceKeyField(
                    label = "Metin→ses anahtarı",
                    saved = evrenTtsSet,
                    placeholder = "voxcpm2 anahtarı",
                    onCommit = onChangeTtsKey,
                )
                VoiceKeyField(
                    label = "Özet anahtarı",
                    saved = evrenSumSet,
                    placeholder = "qwen3.8-flash-next anahtarı",
                    onCommit = onChangeSumKey,
                )

                OutlinedButton(
                    onClick = onTestVoice,
                    enabled = !voiceTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (voiceTesting) "… deneniyor" else "▸ Bağlantıyı test et")
                }
                if (voiceTestResult != null) {
                    Text(
                        if (voiceTestResult.isEmpty()) "Bağlantı tamam ✓" else voiceTestResult,
                        color = if (voiceTestResult.isEmpty()) Oc.accent else Oc.err,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SettingsSection("GÖRÜNÜM") {
                SettingsSwitchRow(
                    title = "Koyu tema",
                    checked = darkTheme,
                    enabled = true,
                    onCheckedChange = onToggleTheme,
                )
                SettingsSwitchRow(
                    title = "Kota rozeti",
                    checked = quotaBadge,
                    enabled = true,
                    onCheckedChange = onToggleQuotaBadge,
                )
            }

            SettingsSection("YEDEK ERİŞİM") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenTelegram)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Telegram botu",
                            color = Oc.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Uygulama veya tünel kapalıyken Telegram'dan devam et",
                            color = Oc.dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("→", color = Oc.accent, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * Sesli komut için tek satırlık alan. Anahtarlar HİÇBİR ZAMAN geri okunmaz:
 * [saved] true ise kutu boş görünür ve "kayıtlı ✓" ipucu verir — yeni değer
 * yazılıp odak dışına çıkılınca kaydedilir. Boş gönderim kaydı SİLMEZ; silmek
 * için "temizle" düğmesi kullanılır.
 */
@Composable
private fun VoiceKeyField(
    label: String,
    value: String = "",
    saved: Boolean = false,
    placeholder: String = "",
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            // Adres alanı her tuşta kaydedilir (boş bırakılırsa varsayılana döner).
            if (label == "EVREN adresi") onCommit(it)
        },
        label = { Text(label) },
        placeholder = { Text(if (saved) "kayıtlı ✓ — değiştirmek için yaz" else placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = ocFieldColors(),
        trailingIcon = if (saved && label != "EVREN adresi") {
            {
                TextButton(onClick = { text = ""; onCommit("") }) { Text("temizle", color = Oc.err) }
            }
        } else {
            null
        },
    )
    // Anahtar alanları: odak dışına çıkınca (veya yazma bitince) kaydet.
    if (label != "EVREN adresi") {
        androidx.compose.runtime.LaunchedEffect(text) {
            if (text.isNotBlank()) {
                kotlinx.coroutines.delay(700)
                onCommit(text)
            }
        }
    }
}

/** Kenarlıklı ayar bölümü: başlık + alt satırlar. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
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

/**
 * Tek ayar satırı: solda başlık/açıklama, sağda anahtar. [enabled] false ise
 * soluk (basamaklı) gösterilir — ör. ana anahtar kapalıyken alt anahtarlar.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Oc.text else Oc.dim,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(subtitle, color = Oc.dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Oc.bg,
                checkedTrackColor = Oc.accent,
                uncheckedThumbColor = Oc.dim,
                uncheckedTrackColor = Oc.panelAlt,
                uncheckedBorderColor = Oc.border,
            ),
        )
    }
}
