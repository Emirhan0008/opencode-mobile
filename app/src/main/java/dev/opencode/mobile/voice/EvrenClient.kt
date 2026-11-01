package dev.opencode.mobile.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * EVREN API istemcisi — **OpenCode'dan tamamen bağımsız**. Yalnızca üç iş yapar:
 *
 *  - [transcribe]  `/v1/audio/transcriptions` → `qwen3-asr-1.7b` (Türkçe STT)
 *  - [speak]       `/v1/audio/speech`         → `voxcpm2` (Türkçe TTS)
 *  - [summarize]   `/v1/chat/completions`     → `qwen3.8-flash-next` (sesli özet)
 *
 * Her iş için AYRI, tek modele kısıtlı anahtar kullanılır (en az yetki). Bir
 * anahtar bozuksa yalnızca o iş başarısız olur, diğerleri çalışmaya devam eder.
 */
class EvrenClient(
    baseUrl: String,
    private val asrKey: String,
    private val ttsKey: String,
    private val sumKey: String,
) {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Tüm anahtarlar dolu mu (servis başlamadan önce kontrol). */
    fun configured(): Boolean = asrKey.isNotBlank() && ttsKey.isNotBlank()

    fun summaryConfigured(): Boolean = sumKey.isNotBlank()

    // ---------------------------------------------------------------- STT

    /**
     * 16 kHz mono 16-bit WAV kaydını metne çevirir. Boş dönerse konuşma
     * anlaşılamamıştır (çağıran taraf tekrar dener).
     */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        require(asrKey.isNotBlank()) { "ASR anahtarı yok" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL_ASR)
            .addFormDataPart("language", "tr")
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                "audio.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$base/v1/audio/transcriptions")
            .header("Authorization", "Bearer $asrKey")
            .post(body)
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw VoiceException("STT ${res.code}: ${text.take(160)}")
            runCatching {
                json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrDefault("").trim()
        }
    }

    // ---------------------------------------------------------------- TTS

    /**
     * Metni MP3'e çevirir (48 kHz, doğal Türkçe). Uzun metin cümle cümle
     * bölünür; burada ilk 1.200 karakter gönderilir (sesli özet zaten kısa).
     */
    suspend fun speak(text: String, voice: String): ByteArray = withContext(Dispatchers.IO) {
        require(ttsKey.isNotBlank()) { "TTS anahtarı yok" }
        val clean = text.trim().take(1200)
        if (clean.isEmpty()) return@withContext ByteArray(0)
        val payload = buildJsonObject {
            put("model", MODEL_TTS)
            put("input", clean)
            put("voice", voice)
            put("response_format", "mp3")
        }
        val req = Request.Builder()
            .url("$base/v1/audio/speech")
            .header("Authorization", "Bearer $ttsKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { res ->
            val bytes = res.body?.bytes() ?: ByteArray(0)
            if (!res.isSuccessful) {
                throw VoiceException("TTS ${res.code}: ${String(bytes).take(160)}")
            }
            bytes
        }
    }

    // ---------------------------------------------------------------- özet

    /**
     * Tur çıktısını sesli okunacak kısa özete çevirir. `reasoning_effort: none`
     * kritik: düşünme kapatılmazsa yanıt 5 sn'yi aşıyor, sesli akışta bu çok
     * uzun. Bu ayarla ~2 sn.
     */
    suspend fun summarize(text: String, detail: Boolean = false): String = withContext(Dispatchers.IO) {
        require(sumKey.isNotBlank()) { "Özet anahtarı yok" }
        val instruction = if (detail) {
            "Sesli okunacak ayrintili ozet yaz. En fazla 8 cumle. Markdown, madde isareti, " +
                "baslik, kod blogu KULLANMA. Turkce."
        } else {
            "Sesli okunacak ozet yaz. En fazla 3 cumle. Markdown, madde isareti, baslik " +
                "KULLANMA. Turkce."
        }
        val payload = buildJsonObject {
            put("model", MODEL_SUMMARY)
            put("reasoning_effort", "none")
            put("temperature", 0.3)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", instruction)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", text.take(24_000))
                        },
                    )
                },
            )
        }
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $sumKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw VoiceException("Özet ${res.code}: ${raw.take(160)}")
            parseContent(raw)
        }
    }

    /**
     * Özetlenmiş bir yanıt hakkında **sonradan sorulan** soruyu, yanıtın
     * tamamına bakarak yanıtlar. Özet metni yerine ham yanıt verilir; böylece
     * özette yer almayan ayrıntılar da (ör. "kritik çelişkiler hangileriydi")
     * kaynaktan çıkarılabilir. Yanıt sesli okunacağı için kısa ve düz yazıdır.
     */
    suspend fun ask(question: String, context: String): String = withContext(Dispatchers.IO) {
        require(sumKey.isNotBlank()) { "Özet anahtarı yok" }
        val instruction =
            "Sana verilen METIN, bir yazilim asistaninin kullaniciya yazdigi yanitin tamamidir. " +
                "Kullanicinin SORU'suna YALNIZCA bu metne dayanarak yanit ver. " +
                "Metinde olmayan bir sey sorulursa 'Bu bilgi metinde yok' de, tahmin etme. " +
                "Yanit sesli okunacak: duz yaz, markdown/madde/baslik/kod KULLANMA, en fazla 6 cumle. " +
                "Turkce."
        val payload = buildJsonObject {
            put("model", MODEL_SUMMARY)
            put("reasoning_effort", "none")
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", instruction)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                "METIN:\n${context.take(24_000)}\n\nSORU: $question",
                            )
                        },
                    )
                },
            )
        }
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $sumKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw VoiceException("Soru ${res.code}: ${raw.take(160)}")
            parseContent(raw)
        }
    }

    private fun parseContent(raw: String): String {
        val obj: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw VoiceException("Özet yanıtı çözülemedi")
        val choices = obj["choices"] ?: throw VoiceException("Özet yanıtında choices yok")
        val first = choices.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject
        } ?: throw VoiceException("Özet yanıtı boş")
        val msg = first["message"]?.jsonObject
        return msg?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(Regex("[*#`>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Hızlı sağlık kontrolü (kota/anahtar geçerli mi). */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val key = asrKey.ifBlank { ttsKey }
            val req = Request.Builder()
                .url("$base/v1/models")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        const val MODEL_ASR = "qwen3-asr-1.7b"
        const val MODEL_TTS = "voxcpm2"
        const val MODEL_SUMMARY = "qwen3.8-flash-next"

        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** Sesli komut akışında kullanıcıya okunacak kısa hata. */
class VoiceException(message: String) : Exception(message)
