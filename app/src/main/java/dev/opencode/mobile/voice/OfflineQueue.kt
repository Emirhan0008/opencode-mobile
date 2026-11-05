package dev.opencode.mobile.voice

import dev.opencode.mobile.QueuedAction
import dev.opencode.mobile.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Sesli akışın çevrimdışı kuyruğu.
 *
 * Uygulama açıkken `AppViewModel` kuyruğu bellekte tutar; servis tek başına
 * yaşıyorsa (uygulama kapatılmış, yalnızca ön plan servisi çalışıyor) ViewModel
 * yoktur. O durumda istek kaybolmasın diye doğrudan DataStore'daki kuyruğa
 * yazılır — uygulama bir sonraki açılışta kuyruğu okuyup gönderir.
 */
object OfflineQueue {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(QueuedAction.serializer())

    suspend fun appendPrompt(
        settings: Settings,
        sessionID: String,
        title: String,
        text: String,
    ): Boolean = runCatching {
        val raw = settings.queue.first()
        val existing = if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, raw)
        val action = QueuedAction(
            id = UUID.randomUUID().toString(),
            kind = "prompt",
            sessionID = sessionID,
            label = "sesli istek → $title",
            payload = buildJsonObject { put("text", text) }.toString(),
            lastError = "çevrimdışı",
        )
        settings.setQueue(json.encodeToString(serializer, existing + action))
        true
    }.getOrDefault(false)
}
