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
 *
 * Özet okunduktan sonra da dinlemeye devam edilir: "tekrar oku", "daha detaylı",
 * "yeni komut", "tamam/iptal". Böylece telefon cebite girerken tek tetikle
 * birden çok iş yapılabilir.
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

    /** Okunan son özet — "tekrar oku" için yeniden özetlemeden kullanılır. */
    private var lastSummary: String? = null

    /** Aynı yanıt için kaç kez takip komutu dinlendiği (sonsuz döngü koruması). */
    private var followUps = 0

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

    /**
     * "Evet/hayır" sınıflandırması. Türkçe konuşmada sık geçen kısa onay ve
     * ret sözlerinin tamamı tanınır. Belirsizse null.
     */
    private fun parseYesNo(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        // Olumsuzlar önce: "hayır" içinde "evet" yok ama "yok" gibi kısa sözler
        // başka kelimelerin içinde geçebilir, bu yüzden tam kelime aranır.
        if (hasAny(t, NO_WORDS)) return false
        if (hasAny(t, YES_WORDS)) return true
        return null
    }

    private fun parseCancel(text: String): Boolean = hasAny(normalize(text), CANCEL_WORDS)

    /** "tekrar oku / bir daha / yeniden" — son özeti tekrar ister. */
    private fun parseRepeat(text: String): Boolean =
        hasAny(normalize(text), REPEAT_WORDS) && !hasAny(normalize(text), CANCEL_WORDS)

    /** "daha detaylı / uzun anlat / ayrıntılı" — ayrıntılı özet ister. */
    private fun parseDetail(text: String): Boolean = hasAny(normalize(text), DETAIL_WORDS)

    /** "yeni komut / yeni istek / başka bir şey" — yeni sesli komut başlatır. */
    private fun parseNewCommand(text: String): Boolean = hasAny(normalize(text), NEW_WORDS)

    /** "tamam / teşekkürler / yeter / dur" — iş bitti, akışı kapat. */
    private fun parseDone(text: String): Boolean =
        hasAny(normalize(text), DONE_WORDS) && !hasAny(normalize(text), DETAIL_WORDS)

    /** Türkçe küçük harf + noktalama temizliği (STT virgül/nokta ekler). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace('İ', 'i').replace('I', 'ı')
            .replace(Regex("[.,!?;:\"'()\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Tam kelime eşleşmesi: "yok" → "yoktur" içinde sayılmaz. */
    private fun hasAny(t: String, words: List<String>): Boolean =
        words.any { w -> t == w || Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(t) }

    private companion object {
        const val MAX_FOLLOW_UPS = 2

        val YES_WORDS = listOf(
            "evet", "evet efendim", "onayla", "onay", "onaylıyorum", "tamam", "tamamdır",
            "doğru", "dogru", "doğrudur", "olur", "olsun", "gönder", "gonder", "gönderelim",
            "gonderelim", "yolla", "yollayalım", "kabul", "kabul ediyorum", "aynen", "peki",
            "tabii", "tabi", "elbette", "kesinlikle", "he", "hee", "hı hı", "ok", "okey",
            "okay", "yes", "süper", "harika", "devam", "başla", "basla",
        )
        val NO_WORDS = listOf(
            "hayır", "hayir", "yok", "yoktur", "olmaz", "olmasın", "yanlış", "yanlis",
            "değil", "degil", "tekrar", "yeniden", "bir daha", "düzelt", "duzelt",
            "sil", "temizle", "beğenmedim", "begenmedim", "no", "olmuyor",
        )
        val CANCEL_WORDS = listOf(
            "iptal", "iptal et", "vazgeç", "vazgec", "boşver", "bosver", "dur", "dursun",
            "sus", "kes", "çık", "cik", "bırak", "birak", "kapat", "kapatma", "hayır vazgeç",
        )
        val REPEAT_WORDS = listOf(
            "tekrar", "tekrar oku", "bir daha", "yeniden", "yeniden oku", "bir daha oku",
            "baştan", "bastan", "baştan oku", "anlamadım", "anlamadim", "duyamadım",
            "duyamadim", "ne dedin", "tekrarla", "yinele",
        )
        val DETAIL_WORDS = listOf(
            "detay", "detaylı", "detayli", "ayrıntı", "ayrinti", "ayrıntılı", "ayrintili",
            "uzun", "uzunca", "açıkla", "acikla", "açıklar mısın", "geniş", "genis",
            "daha fazla", "hepsini", "tamamını", "tamamen",
        )
        val NEW_WORDS = listOf(
            "yeni komut", "yeni istek", "yeni görev", "yeni gorev", "başka bir şey",
            "baska bir sey", "başka komut", "baska komut", "yeni mesaj", "yeniden dinle",
            "dinle", "yeni soru",
        )
        val DONE_WORDS = listOf(
            "tamam", "tamamdır", "tamamdir", "teşekkürler", "tesekkurler", "teşekkür",
            "tesekkur", "yeter", "bitti", "bu kadar", "güle güle", "gorusuruz",
            "görüşürüz", "kapat", "kapatabilirsin", "sus", "dur", "sağ ol", "sag ol",
        )
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

        // "tekrar oku" → listeyi yeniden oku ve numarayı yine sor.
        if (parseRepeat(answer) && attempt < 3) {
            val titles = list.mapIndexed { i, s -> "${i + 1}. ${s.str("title")?.take(48) ?: "oturum"}" }
            say("Oturumlar: ${titles.joinToString(". ")}. Hangisi?", ev)
            afterSpeechPause()
            listenForNumber(list, attempt + 1)
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

    /** Türkçe sayı adlarını/rakamları 1..5'e çevirir ("en yeni"/"son" = 1). */
    private fun parseOrdinal(text: String): Int? {
        val t = normalize(text)
        val words = listOf(
            1 to listOf("bir", "1", "birinci", "ilk", "ilkini", "ilki", "en yeni", "son", "sonuncu", "en son"),
            2 to listOf("iki", "2", "ikinci", "ikincisi", "ikisini"),
            3 to listOf("üç", "uc", "3", "üçüncü", "ucuncu", "üçüncüsü"),
            4 to listOf("dört", "dort", "4", "dördüncü", "dorduncu"),
            5 to listOf("beş", "bes", "5", "beşinci", "besinci"),
        )
        for ((n, keys) in words) if (hasAny(t, keys)) return n
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
        followUps = 0
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
        lastSummary = summary
        stage = Stage.IDLE
        onState("özet: $summary")
        say(summary, client)
        stage = Stage.IDLE

        // Okuma bitti: mikrofonu yeniden aç ve takip komutu bekle. Kullanıcı
        // "tekrar oku", "daha detaylı", "yeni komut" ya da "tamam" diyebilir.
        followUp(api, client, sessionId)
    }

    /**
     * Özet sonrası eller serbest takip komutları. Kullanıcı ekrana bakmadan
     * zinciri sürdürebilsin diye en fazla [MAX_FOLLOW_UPS] kez dinlenir.
     */
    private suspend fun followUp(api: Api, ev: EvrenClient, sessionId: String) {
        if (followUps >= MAX_FOLLOW_UPS) {
            followUps = 0
            return
        }
        followUps++
        afterSpeechPause()
        stage = Stage.LISTENING
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 7_000, silenceMs = 900, noSpeechMs = 5_000)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        if (answer.isBlank()) {
            // Sessizlik: akışı sessizce kapat (kullanıcı artık iş yapmıyor).
            followUps = 0
            stage = Stage.IDLE
            onState("hazır")
            return
        }
        onState("takip: $answer")

        when {
            parseDetail(answer) -> {
                say("Daha ayrıntılı okuyorum.", ev)
                speakSummary(api, ev, sessionId, detail = true)
            }
            parseRepeat(answer) -> {
                val again = lastSummary
                if (again.isNullOrBlank()) speakSummary(api, ev, sessionId)
                else {
                    say(again, ev)
                    followUp(api, ev, sessionId)
                }
            }
            parseNewCommand(answer) -> {
                followUps = 0
                restartListening()
            }
            parseCancel(answer) || parseDone(answer) -> {
                followUps = 0
                stage = Stage.IDLE
                say("Tamam, kapatıyorum.", ev)
            }
            parseYesNo(answer) == true -> {
                // "evet" = devam et / yeni komut dinle.
                followUps = 0
                restartListening()
            }
            else -> {
                say("Anlamadım. Tekrar oku, daha detaylı ya da yeni komut diyebilirsin.", ev)
                followUp(api, ev, sessionId)
            }
        }
    }

    /** Son oturumun yanıtını özetleyip okur (tuş/bildirim "özet" istendiğinde). */
    suspend fun summarizeLast(sessionId: String? = null) {
        val api = api() ?: return
        val ev = evren() ?: return
        val sid = sessionId ?: targetSessionId ?: run {
            val sessions = runCatching { api.sessions(3) }.getOrDefault(emptyList())
                .filter { it.str("parentID").isNullOrBlank() }
            sessions.firstOrNull()?.str("id")
        } ?: return
        targetSessionId = sid
        followUps = 0
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
        followUps = 0
    }
}
