package dev.opencode.mobile.voice

import android.content.Context
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Sesli komut akışının **durum makinesi**. Servisten bağımsız tutuldu ki
 * test edilebilsin ve servis yalnızca yaşam döngüsü/kablo işini yapsın.
 *
 * Akış (ekrana bakmadan, kilitli ekranda) — **eller serbest**: tuş yalnızca
 * başlatmak için gerekir, gerisi konuşarak yürür:
 *
 *   [DINLE] → STT → [OKU: "şunu mu söyledin …"] → otomatik dinle → "evet"
 *        → [OTURUM SOR] → otomatik dinle → "iki" → prompt gönder
 *        → tur bitene kadar bekle → modelin YANITINI özetle → [OKU: özet]
 *
 * Sözler: evet/onayla/tamam · hayır/tekrar · iptal · bir…beş.
 */
class VoiceFlow(
    private val context: Context,
    private val settings: Settings,
    private val audio: AudioEngine,
    private val onState: (String) -> Unit,
) {

    enum class Stage { IDLE, LISTENING, CONFIRMING, PICKING, SENDING, WAITING, SPEAKING }

    @Volatile var stage: Stage = Stage.IDLE
        private set

    private var pendingText: String? = null
    private var targetSessionId: String? = null

    /** TTS bittikten sonra mikrofonu açmadan önce beklenen süre (yankı önleme). */
    private suspend fun afterSpeechPause() = delay(500)

    // ------------------------------------------------------------- yardımcı

    private suspend fun api(): Api? {
        val conn = settings.connection.first() ?: return null
        return Api(conn.baseUrl, conn.token)
    }

    private suspend fun evren(): EvrenClient? {
        val base = settings.evrenBaseUrl.first()
        val asr = settings.evrenAsrKey.first()
        val tts = settings.evrenTtsKey.first()
        val sum = settings.evrenSumKey.first()
        if (asr.isBlank() || tts.isBlank()) return null
        return EvrenClient(base, asr, tts, sum)
    }

    private suspend fun voice(): String = settings.voiceName.first()

    /** Metni kulaklığa okur. Hata olursa sessizce geçer (akış ölmesin). */
    private suspend fun say(text: String, ev: EvrenClient?) {
        if (text.isBlank()) return
        stage = Stage.SPEAKING
        onState(text)
        val client = ev ?: return
        runCatching {
            val bytes = client.speak(text, voice())
            audio.play(bytes)
        }.onFailure { if (it !is CancellationException) onState("okuma hatası: ${it.message}") }
    }

    /** Tuşla kesilebilir okuma: oynatma sırasında [stopSpeaking] çağrılır. */
    fun stopSpeaking() {
        audio.stop()
    }

    // --------------------------------------------------------------- akış

    /** Kulaklık tuşu. Aşamaya göre davranır. */
    suspend fun onButton() {
        when (stage) {
            Stage.SPEAKING -> {
                // Konuşmayı kes ve bekleme moduna dön.
                stopSpeaking()
                stage = Stage.IDLE
                onState("sessiz")
            }
            Stage.CONFIRMING -> confirmAndPick()
            Stage.PICKING -> {
                onState("numara bekleniyor…")
                listenForNumber()
            }
            Stage.IDLE, Stage.WAITING, Stage.SENDING -> listenAndConfirm()
            Stage.LISTENING -> Unit // kayıt kendi kendine biter
        }
    }

    /** 1) Dinle → 2) metni oku → 3) sesli onay al. Tuş gerekmez. */
    private suspend fun listenAndConfirm(attempt: Int = 1) {
        val ev = evren()
        if (ev == null) {
            onState("EVREN anahtarları eksik — Ayarlar → Sesli Komut")
            return
        }
        val api = api()
        if (api == null) {
            onState("OpenCode bağlantısı yok")
            return
        }

        stage = Stage.LISTENING
        onState(if (attempt == 1) "dinliyorum…" else "tekrar dinliyorum…")
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record()
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        if (wav == null) {
            stage = Stage.IDLE
            if (attempt < 2) {
                say("Ses duyamadım. Bir kez daha dinliyorum.", ev)
                listenAndConfirm(attempt + 1)
            } else {
                say("Ses duyamadım. Hazır olduğunda dinle düğmesine bas.", ev)
            }
            return
        }

        onState("yazıya çeviriyorum…")
        val text = runCatching { ev.transcribe(wav) }.getOrElse { e ->
            stage = Stage.IDLE
            onState("STT hatası: ${e.message}")
            say("Yazıya çeviremedim. İnterneti kontrol et.", ev)
            return
        }

        if (text.isBlank()) {
            stage = Stage.IDLE
            if (attempt < 2) {
                say("Anlayamadım. Bir kez daha dinliyorum.", ev)
                listenAndConfirm(attempt + 1)
            } else {
                say("Anlayamadım. Hazır olduğunda dinle düğmesine bas.", ev)
            }
            return
        }

        pendingText = text
        onState("duyduğum: $text")

        if (settings.voiceConfirm.first()) {
            stage = Stage.CONFIRMING
            say("Şunu mu söyledin: $text", ev)
            awaitYesNo(ev, attempt = 1)
        } else {
            confirmAndPick()
        }
    }

    /**
     * "Evet/hayır" dinler — eller serbest modun kalbi. Tuşa basmak gerekmez:
     * soru okunur okunmaz kayıt başlar.
     *
     *  - evet / onayla / tamam / doğru → [confirmAndPick]
     *  - hayır / yanlış / tekrar       → [restartListening]
     *  - iptal / vazgeç                → akış biter
     *  - anlaşılmaz                    → bir kez daha sorar, sonra iptal eder
     */
    private suspend fun awaitYesNo(ev: EvrenClient, attempt: Int) {
        if (attempt > 1) say("Evet ya da hayır de.", ev)
        afterSpeechPause()
        stage = Stage.LISTENING
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 7_000, silenceMs = 900, noSpeechMs = 4_500)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        onState("cevap: ${answer.ifBlank { "—" }}")

        when (parseYesNo(answer)) {
            true -> confirmAndPick()
            false -> {
                say("Peki, yeniden söyle.", ev)
                restartListening()
            }
            null -> {
                if (parseCancel(answer)) {
                    stage = Stage.IDLE
                    say("Vazgeçtim.", ev)
                    return
                }
                if (attempt < 2) {
                    awaitYesNo(ev, attempt + 1)
                } else {
                    stage = Stage.IDLE
                    onState("onay alınamadı")
                    say("Onay alamadım, iptal ettim.", ev)
                }
            }
        }
    }

    /** "evet/hayır" sınıflandırması. Belirsizse null. */
    private fun parseYesNo(text: String): Boolean? {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null
        // Olumsuzlar önce: "hayır" içinde "evet" yok ama "yok" gibi kısa sözler
        // başka kelimelerin içinde geçebilir, bu yüzden tam kelime aranır.
        val no = listOf("hayır", "hayir", "yok", "olmaz", "yanlış", "yanlis", "tekrar", "değil", "degil", "iptal", "vazgeç", "vazgec")
        val yes = listOf("evet", "onayla", "onay", "tamam", "doğru", "dogru", "olur", "gönder", "gonder", "kabul", "aynen", "peki")
        fun has(words: List<String>) = words.any { w -> Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(t) || t == w }
        if (has(no)) return false
        if (has(yes)) return true
        return null
    }

    private fun parseCancel(text: String): Boolean {
        val t = text.lowercase()
        return listOf("iptal", "vazgeç", "vazgec", "boşver", "bosver").any { t.contains(it) }
    }

    /** Onaylandı → oturum sor. */
    private suspend fun confirmAndPick() {
        val text = pendingText ?: return
        val api = api() ?: return
        stage = Stage.PICKING
        val ev = evren()
        val sessions = runCatching { api.sessions(6) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
            .take(5)
        if (sessions.isEmpty()) {
            onState("oturum yok")
            stage = Stage.IDLE
            say("Hiç oturum bulamadım.", ev)
            return
        }
        val list = sessions.mapIndexed { i, s ->
            "${i + 1}. ${s.str("title")?.take(48) ?: "oturum"}"
        }
        onState("hangi oturum? ${list.joinToString(" , ")}")
        say("Hangi oturuma göndereyim? ${list.joinToString(". ")}", ev)
        say("Numarayı söyle.", ev)
        stage = Stage.PICKING
        // Eller serbest: numara sorusu okunur okunmaz dinlemeye geç (tuş yok).
        afterSpeechPause()
        listenForNumber(sessions, attempt = 1)
    }

    /** Numarayı dinle ve eşleştir; anlaşılmazsa bir kez daha sorar. */
    private suspend fun listenForNumber(
        sessions: List<JsonObject>? = null,
        attempt: Int = 1,
    ) {
        val ev = evren() ?: return
        val api = api() ?: return
        val list = sessions ?: runCatching { api.sessions(6) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
            .take(5)

        if (attempt > 1) say("Bir ile beş arasında bir numara söyle.", ev)
        stage = Stage.LISTENING
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 8_000, silenceMs = 900, noSpeechMs = 4_000)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        onState("seçim: ${answer.ifBlank { "—" }}")

        if (parseCancel(answer)) {
            pendingText = null
            stage = Stage.IDLE
            say("Vazgeçtim.", ev)
            return
        }

        val idx = parseOrdinal(answer)
        if (idx == null) {
            // Sessiz/anlaşılmaz: bir kez daha dinle, sonra en yeni oturuma düş.
            if (attempt < 2) {
                listenForNumber(list, attempt + 1)
                return
            }
        }
        val chosen = list.getOrNull((idx ?: 1) - 1) ?: list.firstOrNull()
        if (chosen == null) {
            stage = Stage.IDLE
            return
        }
        targetSessionId = chosen.str("id")
        val title = chosen.str("title") ?: "oturum"
        onState("hedef: $title")
        say("Tamam, $title oturumuna gönderiyorum.", ev)
        afterSpeechPause()
        send(api, ev, chosen.str("id")!!, title)
    }

    /** Türkçe sayı adlarını/rakamları 1..5'e çevirir. */
    private fun parseOrdinal(text: String): Int? {
        val t = text.lowercase()
        val words = listOf(
            1 to listOf("bir", "1", "birinci", "ilk"),
            2 to listOf("iki", "2", "ikinci"),
            3 to listOf("üç", "uc", "3", "üçüncü", "ucuncu"),
            4 to listOf("dört", "dort", "4", "dördüncü", "dorduncu"),
            5 to listOf("beş", "bes", "5", "beşinci", "besinci"),
        )
        for ((n, keys) in words) if (keys.any { t.contains(it) }) return n
        return null
    }

    /** Promptu gönder, tur bitince modelin yanıtını özetleyip oku. */
    private suspend fun send(api: Api, ev: EvrenClient, sessionId: String, title: String) {
        val text = pendingText ?: return
        stage = Stage.SENDING
        val before = runCatching { api.assistantText(sessionId) }.getOrDefault("")
        val sent = runCatching { api.prompt(sessionId, text) }.isSuccess
        pendingText = null
        if (!sent) {
            stage = Stage.IDLE
            onState("gönderilemedi")
            say("Gönderemedim. Bağlantıyı kontrol et.", ev)
            return
        }
        onState("gönderildi → $title")
        say("Gönderdim. Bitince özetini okuyacağım.", ev)

        stage = Stage.WAITING
        val done = waitForTurn(api, sessionId, before, timeoutMs = 10 * 60_000)
        if (!done) {
            stage = Stage.IDLE
            onState("tur bitmedi (zaman aşımı)")
            return
        }
        if (!settings.voiceAutoSummary.first()) {
            stage = Stage.IDLE
            onState("tur bitti")
            return
        }
        speakSummary(api, ev, sessionId)
    }

    /**
     * Turun bitmesini bekler: oturum aktiflikten çıkana VE asistanın yeni bir
     * metin yanıtı görünene kadar. (Sadece aktifliğe bakmak yetmez; model
     * araç çağırırken de pasif görünebilir.)
     */
    private suspend fun waitForTurn(
        api: Api,
        sessionId: String,
        before: String,
        timeoutMs: Int,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var sawRunning = false
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(4_000)
            val running = runCatching { sessionId in api.active() }.getOrDefault(false)
            if (running) {
                sawRunning = true
                continue
            }
            val now = runCatching { api.assistantText(sessionId) }.getOrDefault("")
            if (now.isNotEmpty() && now != before) return true
            if (sawRunning && now.isNotEmpty()) return true
        }
        return false
    }

    /** Modelin kullanıcıya yazdığı yanıtı özetler ve kulaklığa okur. */
    suspend fun speakSummary(api: Api, ev: EvrenClient?, sessionId: String, detail: Boolean = false) {
        val client = ev ?: evren() ?: return
        val answer = runCatching { api.assistantText(sessionId) }.getOrDefault("")
        if (answer.isBlank()) {
            stage = Stage.IDLE
            onState("yanıt yok")
            say("Henüz bir yanıt yok.", client)
            return
        }
        if (!client.summaryConfigured()) {
            stage = Stage.IDLE
            // Özet anahtarı yoksa yanıtın kendisini kısaca oku.
            say(answer.take(400), client)
            return
        }
        onState("özetliyorum…")
        val summary = runCatching { client.summarize(answer, detail) }.getOrElse { e ->
            stage = Stage.IDLE
            onState("özet hatası: ${e.message}")
            say("Özet çıkaramadım. Ham yanıtı okuyorum. ${answer.take(400)}", client)
            return
        }
        stage = Stage.IDLE
        onState("özet: $summary")
        say(summary, client)
        stage = Stage.IDLE
    }

    /** Son oturumun yanıtını özetleyip okur (tuşla "özet" istendiğinde). */
    suspend fun summarizeLast() {
        val api = api() ?: return
        val ev = evren() ?: return
        val sessions = runCatching { api.sessions(3) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
        val sid = targetSessionId ?: sessions.firstOrNull()?.str("id") ?: return
        speakSummary(api, ev, sid)
    }

    /** Onay aşamasında "yanlış duydun" → temizle ve yeniden dinle. */
    suspend fun restartListening() {
        pendingText = null
        targetSessionId = null
        stage = Stage.IDLE
        listenAndConfirm()
    }

    fun reset() {
        stage = Stage.IDLE
        pendingText = null
    }
}
