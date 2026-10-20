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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.data.Profile

/**
 * Bağlantı ekranı. En kolay yol: PC'de `opencode pair` (veya `opencode pair --remote`)
 * ile çıkan bağlantıyı doğrudan yapıştırmak. Sunucu adresi ve kod otomatik ayrıştırılır.
 * Kayıtlı profiller en üstte seçilebilir/silinebilir.
 */
@Composable
fun ConnectScreen(
    connecting: Boolean,
    error: String?,
    profiles: List<Profile> = emptyList(),
    activeProfileId: String? = null,
    onSelectProfile: (String) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onConnectCode: (baseUrl: String, codeOrUrl: String) -> Unit,
    onConnectPassword: (baseUrl: String, password: String) -> Unit,
    /** Bağlantı yokken de Telegram yedek erişimine ulaşmak için. */
    onOpenTelegram: () -> Unit = {},
) {
    var pairLink by remember { mutableStateOf("") }
    var manualUrl by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------ kayıtlı profiller
        if (profiles.isNotEmpty()) {
            Text(
                "KAYITLI PROFİLLER",
                style = MaterialTheme.typography.labelMedium,
                color = Oc.dim,
            )
            profiles.forEach { p ->
                val active = p.id == activeProfileId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Oc.panelAlt else Oc.panel)
                        .border(1.dp, if (active) Oc.accent else Oc.border, RoundedCornerShape(4.dp))
                        .clickable { onSelectProfile(p.id) }
                        .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (active) "✓" else "·", color = if (active) Oc.accent else Oc.dim)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            p.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = Oc.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onDeleteProfile(p.id) }) {
                        Text("✖", color = Oc.err)
                    }
                }
            }
            HorizontalDivider(color = Oc.border)
        }

        // -------------------------------------------------------- yeni bağlantı
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = "OpenCode Mobile",
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("┌─ OpenCode Mobile ─────────────", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "YENİ BAĞLANTI",
            style = MaterialTheme.typography.labelMedium,
            color = Oc.dim,
        )
        Text(
            "PC'nizde `opencode pair --remote` çalıştırın, çıkan bağlantıyı buraya yapıştırın.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = pairLink,
            onValueChange = { pairLink = it },
            label = { Text("Pairing bağlantısı") },
            placeholder = { Text("http://.../auth/connect/....") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = ocFieldColors(),
        )

        Button(
            onClick = {
                val link = pairLink.trim()
                val base = link.substringBefore("/auth/connect/").trimEnd('/')
                onConnectCode(base, link)
            },
            enabled = !connecting && pairLink.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Oc.accent, contentColor = Oc.bg),
        ) {
            Text(if (connecting) "… bağlanılıyor" else "▸ BAĞLAN")
        }

        OutlinedButton(
            onClick = { showManual = !showManual },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showManual) "▾ elle giriş" else "▸ elle giriş (adres + kod)") }

        if (showManual) {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("Sunucu adresi") },
                placeholder = { Text("http://192.168.1.5:49374") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = ocFieldColors(),
            )
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text("Pairing kodu") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = ocFieldColors(),
            )
            OutlinedButton(
                onClick = { onConnectCode(manualUrl.trim(), manualCode.trim()) },
                enabled = !connecting && manualUrl.isNotBlank() && manualCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("▸ bağlan") }
        }

        HorizontalDivider(color = Oc.border)

        OutlinedButton(
            onClick = { showPassword = !showPassword },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showPassword) "▾ parola ile giriş" else "▸ parola ile giriş") }

        if (showPassword) {
            Text(
                "Sunucu parolasını bilgisayarda `opencode service set password \"...\"` ile ayarlayabilirsiniz.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Sunucu parolası") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = ocFieldColors(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    val base = manualUrl.trim().ifBlank { pairLink.trim().substringBefore("/auth/connect/").trimEnd('/') }
                    onConnectPassword(base, password)
                },
                enabled = !connecting && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("▸ parola ile bağlan") }
        }

        if (error != null) {
            Text("✖ $error", color = Oc.err, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Uzaktan erişim için: `opencode pair --remote` → OpenTunnel adresi + kod. " +
                "Aynı Wi-Fi şart değil; bağlantı uçtan uca şifrelidir.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(color = Oc.border)
        TextButton(onClick = onOpenTelegram, modifier = Modifier.fillMaxWidth()) {
            Text("✈ Telegram ile eriş (yedek)", color = Oc.accent)
        }
    }
}

@Composable
fun ocFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Oc.accent,
    unfocusedBorderColor = Oc.border,
    focusedTextColor = Oc.text,
    unfocusedTextColor = Oc.text,
    focusedLabelColor = Oc.accent,
    unfocusedLabelColor = Oc.dim,
    cursorColor = Oc.accent,
    focusedContainerColor = Oc.panel,
    unfocusedContainerColor = Oc.panel,
)