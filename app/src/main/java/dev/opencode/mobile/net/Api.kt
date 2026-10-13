package dev.opencode.mobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

/** Bir SSE olayı: `type` + içindeki `data` nesnesi. */
data class OcEvent(val type: String, val data: JsonObject)

/** OpenCode HTTP API istemcisi. Kimlik: Basic auth, kullanıcı adı `opencode`, parola sunucu parolası veya pairing token'ı. */
class Api(baseUrl: String, private val token: String) {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE akışı sonsuz
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // ----------------------------------------------------------------- generic

    private fun builder(path: String): Request.Builder =
        Request.Builder()
            .url(base + path)
            .header("Authorization", Credentials.basic("opencode", token, Charsets.UTF_8))
            .header("Accept", "application/json")

    suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        execute(builder(path).get().build())
    }

    suspend fun post(path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        val payload = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        execute(builder(path).post(payload).build())
    }

    suspend fun delete(path: String): JsonElement = withContext(Dispatchers.IO) {
        execute(builder(path).delete().build())
    }

    suspend fun patch(path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        val payload = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        execute(builder(path).patch(payload).build())
    }

    private fun execute(request: Request): JsonElement {
        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, text.take(300))
            return if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    // ------------------------------------------------------------------ routes

    suspend fun sessions(limit: Int = 30): List<JsonObject> =
        get("/api/session?limit=$limit&order=desc").obj().arr("data").map { it.obj() }

    suspend fun session(id: String): JsonObject = get("/api/session/$id").obj().obj("data")

    suspend fun createSession(title: String? = null, directory: String? = null): JsonObject {
        val body = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
            if (!directory.isNullOrBlank()) put("location", buildJsonObject { put("directory", directory) })
        }
        return post("/api/session", body).obj().obj("data")
    }

    suspend fun messages(id: String, limit: Int = 200): List<JsonObject> =
        get("/api/session/$id/message?limit=$limit&order=asc").obj().arr("data").map { it.obj() }

    suspend fun prompt(id: String, text: String): JsonElement =
        post("/api/session/$id/prompt", buildJsonObject { put("text", text) })

    suspend fun interrupt(id: String): JsonElement = post("/api/session/$id/interrupt")

    suspend fun deleteSession(id: String): JsonElement = delete("/api/session/$id")

    suspend fun renameSession(id: String, title: String): JsonElement =
        patch("/api/session/$id", buildJsonObject { put("title", title) })

    /** Oturumu çoğaltır; yeni oturumun kimliğini döndürür. */
    suspend fun forkSession(id: String): String {
        val r = post("/api/session/$id/fork").obj()
        val data = if (r.containsKey("data")) r.obj("data") else r
        return data.str("id") ?: throw ApiException(500, "fork yanıtında oturum kimliği yok")
    }

    /** Belirtilen mesaja kadar tüm değişiklikleri geri alır (stage + commit). */
    suspend fun revertTo(id: String, messageID: String) {
        post("/api/session/$id/revert/stage", buildJsonObject { put("messageID", messageID) })
        post("/api/session/$id/revert/commit")
    }

    /** Oturumun kabuğunda komut çalıştırır (OpenCode sunucusunun çalıştığı makinede). */
    suspend fun shell(id: String, command: String): JsonElement =
        post("/api/session/$id/shell", buildJsonObject { put("command", command) })

    suspend fun active(): Set<String> =
        get("/api/session/active").obj().obj("data").keys

    suspend fun pendingPermissions(): List<JsonObject> =
        get("/api/permission/request").obj().arr("data").map { it.obj() }

    suspend fun replyPermission(sessionID: String, requestID: String, decision: String) {
        post(
            "/api/session/$sessionID/permission/$requestID/reply",
            buildJsonObject { put("decision", decision) },
        )
    }

    // ------------------------------------------------------------- agent / model

    suspend fun agents(): List<JsonObject> =
        get("/api/agent").obj().arr("data").map { it.obj() }

    suspend fun models(): List<JsonObject> =
        get("/api/model").obj().arr("data").map { it.obj() }

    /** Oturumun ajanını (build / plan / özel) değiştirir. */
    suspend fun switchAgent(id: String, agent: String): JsonElement =
        post("/api/session/$id/agent", buildJsonObject { put("agent", agent) })

    /** Oturumun modelini değiştirir. */
    suspend fun switchModel(id: String, providerID: String, modelID: String, variant: String? = null): JsonElement =
        post(
            "/api/session/$id/model",
            buildJsonObject {
                put(
                    "model",
                    buildJsonObject {
                        put("id", modelID)
                        put("providerID", providerID)
                        if (!variant.isNullOrBlank()) put("variant", variant)
                    },
                )
            },
        )

    // -------------------------------------------------------------------- forms

    /** Bekleyen formlar (question aracı soruları). */
    suspend fun forms(id: String): List<JsonObject> =
        get("/api/session/$id/form").obj().arr("data").map { it.obj() }

    /** Formu (soruyu) yanıtla; answer: alan adı → değer. */
    suspend fun replyForm(sessionID: String, formID: String, answer: JsonObject): JsonElement =
        post("/api/session/$sessionID/form/$formID/reply", buildJsonObject { put("answer", answer) })

    /** Formu (soruyu) iptal et — aracı da iptal eder. */
    suspend fun cancelForm(sessionID: String, formID: String): JsonElement =
        delete("/api/session/$sessionID/form/$formID")

    // --------------------------------------------------------------------- SSE

    /**
     * Olay akışına abone olur. Dönen [Call] iptal edilerek bağlantı kapatılır.
     * Hatada [onError] çağrılır; akış kapanınca yeniden bağlanmak çağıranın sorumluluğundadır.
     */
    fun events(onEvent: (OcEvent) -> Unit, onError: (Throwable) -> Unit = {}): Call {
        val call = client.newCall(builder("/api/event").header("Accept", "text/event-stream").get().build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        onError(ApiException(res.code, "SSE açılamadı: HTTP ${res.code}"))
                        return
                    }
                    val source = res.body?.source()
                    if (source == null) {
                        onError(IOException("SSE gövdesi boş"))
                        return
                    }
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            val obj = try {
                                json.parseToJsonElement(payload).obj()
                            } catch (_: Exception) {
                                continue
                            }
                            val type = obj.str("type") ?: continue
                            onEvent(OcEvent(type, obj.obj("data")))
                        }
                        if (!call.isCanceled()) onError(IOException("Olay akışı kapandı"))
                    } catch (e: Exception) {
                        if (!call.isCanceled()) onError(e)
                    }
                }
            }
        })
        return call
    }

    companion object {
        /** Pairing kodunu (veya tam pairing URL'ini) token'a çevirir; auth gerektirmez. */
        suspend fun redeem(baseUrl: String, codeOrUrl: String): String = withContext(Dispatchers.IO) {
            val code = codeOrUrl.trim().substringAfterLast("/auth/connect/").substringAfterLast('/')
            val target = baseUrl.trimEnd('/') + "/auth/connect/" + code
            val call = OkHttpClient().newCall(Request.Builder().url(target).get().build())
            call.execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw ApiException(res.code, text.take(300))
                val obj = Json.parseToJsonElement(text).obj()
                obj.str("token") ?: throw ApiException(res.code, "Yanıtta token yok")
            }
        }
    }
}

// ---------------------------------------------------------------- JSON helpers

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: JsonObject(emptyMap())
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull