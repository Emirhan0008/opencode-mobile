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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
