package dev.opencode.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.Profile
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.ApiException
import dev.opencode.mobile.net.OcEvent
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import dev.opencode.mobile.net.text
import dev.opencode.mobile.notify.Notifier
import dev.opencode.mobile.sync.WatchService
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call

// ------------------------------------------------------------------- state

data class ToolRow(
    val id: String,
    val name: String,
    val status: String, // running | ok | error
    val input: String = "",
    val output: String = "",
    val error: String = "",
)

data class LiveMessage(
    val id: String,
    val reasoning: String = "",
    val text: String = "",
    val tools: List<ToolRow> = emptyList(),
)

data class SessionsState(
    val loading: Boolean = false,
    val sessions: List<JsonObject> = emptyList(),
    val active: Set<String> = emptySet(),
    val error: String? = null,
)

data class SessionState(
    val loading: Boolean = false,
    val session: JsonObject? = null,
    val messages: List<JsonObject> = emptyList(),
    val live: LiveMessage? = null,
    val busy: Boolean = false,
    /** Oturumun kendisi değil, alt ajanı çalışıyor. */
    val childBusy: Boolean = false,
    val permissions: List<JsonObject> = emptyList(),
    val forms: List<JsonObject> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

/** Bilgisayardaki kapatma gözcüsünün canlılık raporu (shutdown-watch.ps1 -Status). */
data class WatchStatus(
    val live: Boolean,
    val pid: Int?,
    val uptimeMinutes: Int?,
    val lastSayim: String?,
    val lastSayimAt: String?,
    val lastLogLine: String?,
    val lastLogAt: String?,
    val shutdownSent: Boolean,
)

/** Çevrimdışıyken yapılan, bağlantı gelince sırayla gönderilecek eylem. */
@Serializable
data class QueuedAction(
    val id: String,
    val kind: String,
    val sessionID: String?,
    val label: String,
    val payload: String,
    val lastError: String? = null,
)

/** Bağlantı durumu yüzeyi — UI'a metin üretmek için. */
sealed interface ConnState {
    /** SSE akışı açık. */
    data object Connected : ConnState

    /** Kopukluk sonrası yeniden bağlanma bekleniyor. */
    data class Connecting(val attempt: Int, val nextRetrySeconds: Int) : ConnState

    /** Bağlantı yok / kurulmadı. */
    data class Offline(val sinceMillis: Long) : ConnState
}

sealed interface Screen {
    data object Connect : Screen
    data object Sessions : Screen
    data class Session(val id: String) : Screen
}

/** Uygulamanın ön planda olup olmadığı — bildirim buna göre gönderilir. */
object AppVisibility {
    var visible by mutableStateOf(true)
}

// ------------------------------------------------------------- view model

class AppViewModel(
    private val appContext: Context,
    private val settings: Settings,
) : ViewModel() {

    private var api: Api? = null
    private var sseCall: Call? = null

    /** api ile eşleşen bağlantı bilgileri — profil değişiminde yeniden kurulum tespiti için. */
    private var apiBaseUrl: String? = null
    private var apiToken: String? = null

    /** SSE yeniden bağlanma denemesi ve bekleyen yeniden-abone işi. */
    private var sseAttempt = 0
    private var reconnectJob: Job? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Sessions)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Kayıtlı profiller ve aktif profil (UI bağlaması için). */
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /** Bağlantı durumu (UI'da ince durum satırı). */
    private val _connState = MutableStateFlow<ConnState>(ConnState.Offline(0L))
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    /** Bağlantı yokken yapılan, bağlantı gelince gönderilecek eylemler. */
    private val _pendingActions = MutableStateFlow<List<QueuedAction>>(emptyList())
    val pendingActions: StateFlow<List<QueuedAction>> = _pendingActions.asStateFlow()

    private val queueJson = Json { ignoreUnknownKeys = true }

    /** Kendi yürütmeleri süren oturumlar (UI'da gösterilen set buna üst oturumlar eklenerek hesaplanır). */
    private var rawActive: Set<String> = emptySet()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _globalPermissions = MutableStateFlow<List<JsonObject>>(emptyList())
    val globalPermissions: StateFlow<List<JsonObject>> = _globalPermissions.asStateFlow()

    private val _agents = MutableStateFlow<List<JsonObject>>(emptyList())
    val agents: StateFlow<List<JsonObject>> = _agents.asStateFlow()

    private val _models = MutableStateFlow<List<JsonObject>>(emptyList())
    val models: StateFlow<List<JsonObject>> = _models.asStateFlow()

    private val _commands = MutableStateFlow<List<JsonObject>>(emptyList())
    val commands: StateFlow<List<JsonObject>> = _commands.asStateFlow()

    private val _darkTheme = MutableStateFlow(true)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    /**
     * Gönderilmiş kapatma görevi:
     * yeni biçim `"<force|soft>|<oturumID>|<HH:MM veya ->|<armedAtEpochMs>"`,
     * eski biçim `"force|<oturumID>"` / `"soft|<oturumID>"` (at=null, armedAt=0).
     */
    private val _shutdownPlan = MutableStateFlow<String?>(null)
    val shutdownPlan: StateFlow<String?> = _shutdownPlan.asStateFlow()

    /** Gözcünün en son okunan canlılık durumu (bilinmiyorsa null). */
    private val _watchStatus = MutableStateFlow<WatchStatus?>(null)
    val watchStatus: StateFlow<WatchStatus?> = _watchStatus.asStateFlow()

    init {
        viewModelScope.launch {
            settings.connection.collect { conn ->
                if (conn == null) {
                    _connected.value = false
                    api = null
                    apiBaseUrl = null
                    apiToken = null
                    sseAttempt = 0
                    _connState.value = ConnState.Offline(System.currentTimeMillis())
                    _screen.value = Screen.Connect
                } else {
                    attach(conn.baseUrl, conn.token)
                    if (_screen.value is Screen.Connect) _screen.value = Screen.Sessions
                }
            }
        }
        viewModelScope.launch {
            settings.profiles.collect { _profiles.value = it }
        }
        viewModelScope.launch {
            settings.activeProfileId.collect { _activeProfileId.value = it }
        }
        viewModelScope.launch {
            // Kuyruğu depodan bir kez oku (init).
            val raw = settings.queue.first()
            if (!raw.isNullOrBlank() && _pendingActions.value.isEmpty()) {
                runCatching {
                    queueJson.decodeFromString(ListSerializer(QueuedAction.serializer()), raw)
                }.onSuccess { if (it.isNotEmpty()) _pendingActions.value = it }
            }
        }
        viewModelScope.launch {
            settings.darkTheme.collect { _darkTheme.value = it }
        }
        viewModelScope.launch {
            settings.shutdownPlan.collect { plan ->
                _shutdownPlan.value = plan
                // Depodan gelen plan (uygulama açılışı) için gözcü canlılığını sorgula —
                // bayat banner temizliği. Bağlantı sonra kurulursa attach() tetikler.
                if (plan != null && api != null) refreshShutdownStatus()
            }
        }
    }

    // ------------------------------------------------------------ connection

    private fun attach(baseUrl: String, token: String) {
        val url = baseUrl.trimEnd('/')
        // Aynı bağlantı zaten kuruluysa dokunma; farklıysa (profil değişimi) yeniden kur.
        if (api != null && _connected.value && apiBaseUrl == url && apiToken == token) return
        sseCall?.cancel()
        sseCall = null
        reconnectJob?.cancel()
        reconnectJob = null
        sseAttempt = 0
        api = Api(url, token)
        apiBaseUrl = url
        apiToken = token
        _connected.value = true
        _connState.value = ConnState.Connecting(0, 0)
        subscribeEvents()
        refreshAgents()
        refreshModels()
        refreshCommands()
        refreshSessions()
        flushQueue()
        // Plan varsa gözcü canlılığını başlangıçta bir kez sorgula (bayat banner temizliği için).
        if (_shutdownPlan.value != null) refreshShutdownStatus()
        // Bildirimlerin tek kaynağı: SSE'yi arka planda açık tutan ön plan servisi.
        // Servis Settings'i kendisi okur; burada yalnız başlatırız.
        WatchService.start(appContext)
    }

    /** Pairing kodu / URL'i ile bağlanır ve profili kaydeder. */
    fun connectWithCode(baseUrl: String, codeOrUrl: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val token = Api.redeem(baseUrl, codeOrUrl)
                val url = baseUrl.trimEnd('/')
                settings.saveProfile(makeProfile(url, token))
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Bağlanılamadı")
            }
        }
    }

    /** Sunucu parolası ile bağlanır (Basic auth parolası token yerine geçer) ve profili kaydeder. */
    fun connectWithPassword(baseUrl: String, password: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val url = baseUrl.trimEnd('/')
                val probe = Api(url, password)
                probe.get("/api/info")
                settings.saveProfile(makeProfile(url, password))
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Parola reddedildi")
            }
        }
    }

    /** URL'den kısa bir profil adı üretir (host:port). */
    private fun makeProfile(baseUrl: String, token: String): Profile {
        val name = baseUrl.substringAfter("://").substringBefore("/").ifBlank { baseUrl }
        return Profile(
            id = System.currentTimeMillis().toString(),
            name = name,
            baseUrl = baseUrl,
            token = token,
        )
    }

    /** Profili seçer; değişim bağlantı akışını tetikleyerek yeniden bağlanır. */
    fun selectProfile(id: String) {
        viewModelScope.launch {
            val wasActive = settings.activeProfileId.first() == id
            settings.setActiveProfile(id)
            // Zaten aktifse akış değişmez; yine de yeniden bağlanmayı dene.
            if (wasActive) {
                settings.profiles.first().firstOrNull { it.id == id }?.let { attach(it.baseUrl, it.token) }
            }
        }
    }

    /** Profili siler (aktifse bağlantı akışı bağlantıyı sıfırlar). */
    fun deleteProfile(id: String) {
        viewModelScope.launch { settings.deleteProfile(id) }
    }

    /**
     * Bağlantıyı keser. Anlamı: aktif profili sil + SSE iptal + kapatma görevini iptal +
     * api sıfırla. Tema ve kapatma planı KORUNUR (settings.clear() çağrılmaz).
     */
    fun disconnect() {
        viewModelScope.launch {
            sseCall?.cancel()
            sseCall = null
            reconnectJob?.cancel()
            reconnectJob = null
            cancelShutdown()
            // Ön plan servisini durdur — bağlantı istemci tarafından kesiliyor.
            WatchService.stop(appContext)
            settings.clearConnection()
            api = null
            apiBaseUrl = null
            apiToken = null
            _connected.value = false
            sseAttempt = 0
            _connState.value = ConnState.Offline(System.currentTimeMillis())
        }
    }

    fun setDarkTheme(dark: Boolean) {
        _darkTheme.value = dark
        viewModelScope.launch { settings.setDarkTheme(dark) }
    }

    // ----------------------------------------------------------------- SSE

    /**
     * SSE akışına yeniden abone olur. Kopuklukta üstel backoff ile (en fazla 30 sn)
     * tekrar dener; `while(true)` sarmalı kurmaz, geri-çağrı + gecikmeli coroutine
     * deseni kullanır. Yeniden bağlanmada `sseAttempt > 0` ise kaçan güncellemeler
     * telafi edilir.
     */
    private fun subscribeEvents() {
        reconnectJob?.cancel()
        reconnectJob = null
        sseCall?.cancel()
        val current = api ?: return
        sseCall = current.events(
            onOpen = {
                val reconnected = sseAttempt > 0
                sseAttempt = 0
                _connState.value = ConnState.Connected
                if (reconnected) {
                    // Kopukluk sırasında kaçan güncellemeleri telafi et.
                    refreshSessions()
                    refreshMessages()
                    refreshPermissions()
                    flushQueue()
                }
            },
            onEvent = ::handleEvent,
            onError = {
                if (api !== current) return@events
                reconnectJob?.cancel()
                reconnectJob = viewModelScope.launch {
                    sseAttempt += 1
                    val wait = minOf(1 shl minOf(sseAttempt, 5), 30)   // 2^attempt sn, en fazla 30
                    _connState.value = ConnState.Connecting(sseAttempt, wait)
                    delay(wait * 1000L)
                    if (api === current) subscribeEvents()
                }
            },
        )
    }

    /** Durum satırına dokununca hemen yeniden abone olur. */
    fun reconnectNow() {
        sseAttempt = 1
        _connState.value = ConnState.Connecting(1, 0)
        subscribeEvents()
    }

    /** Bekleyen izinleri tazeler (kopukluk sonrası telafi). */
    private fun refreshPermissions() {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.pendingPermissions() }.onSuccess { list ->
                _globalPermissions.value = list
                val id = currentSessionId()
                if (id != null) {
                    _session.update { it.copy(permissions = list.filter { p -> p.str("sessionID") == id }) }
                }
            }
        }
    }

    private fun handleEvent(ev: OcEvent) {
        val d = ev.data
        val sid = d.str("sessionID")

        when (ev.type) {
            "session.execution.started" -> {
                if (sid != null) {
                    rawActive = rawActive + sid
                    ensureKnown(sid)   // yeni alt ajan oturumları listeye kat (üst bağ için)
                    recomputeActive()
                }
            }

            // 2.x'te bitiş olayları: idle / failed (execution.succeeded yok)
            "session.idle", "session.execution.failed" -> finishSession(sid)

            "session.text.started" -> ensureLive(d)

            "session.text.delta" -> {
                if (sid != currentSessionId()) return
                val mid = d.str("assistantMessageID") ?: return
                _session.update { st ->
                    val live = (st.live ?: LiveMessage(mid)).copy(text = st.live?.text.orEmpty() + d.str("delta").orEmpty())
                    st.copy(live = live)
                }
            }

            "session.reasoning.started" -> ensureLive(d)

            "session.reasoning.delta" -> {
                if (sid != currentSessionId()) return
                val mid = d.str("assistantMessageID") ?: return
                _session.update { st ->
                    val live = (st.live ?: LiveMessage(mid))
                        .copy(reasoning = st.live?.reasoning.orEmpty() + d.str("delta").orEmpty())
                    st.copy(live = live)
                }
            }

            "session.tool.input.started" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) { it.copy(name = d.str("name") ?: it.name, status = "running") }
            }

            "session.tool.input.delta" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) { it.copy(input = it.input + (d.str("delta") ?: d.str("text").orEmpty())) }
            }

            "session.tool.input.ended" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) { it.copy(input = d.str("text") ?: it.input) }
            }

            "session.tool.called" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) {
                    it.copy(
                        status = "running",
                        input = d.obj("input").toString().takeIf { s -> s != "{}" } ?: it.input,
                    )
                }
            }

            "session.tool.progress" -> Unit

            "session.tool.success" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) {
                    it.copy(status = "ok", output = toolContent(d) ?: it.output)
                }
            }

            "session.tool.failed" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) {
                    val msg = d.obj("error").str("message") ?: d.str("error") ?: "hata"
                    it.copy(status = "error", error = msg, output = toolContent(d) ?: it.output)
                }
            }

            "permission.asked" -> {
                val req = unwrap(d, "request")
                _globalPermissions.update { list -> list + req }
                if (sid == currentSessionId()) {
                    _session.update { it.copy(permissions = it.permissions + req) }
                }
                // Bildirim artık WatchService tarafından atılır (çift bildirim olmasın).
            }

            "permission.replied" -> {
                val rid = d.str("requestID") ?: d.str("id")
                _globalPermissions.update { list -> list.filterNot { it.str("id") == rid } }
                _session.update { it.copy(permissions = it.permissions.filterNot { p -> p.str("id") == rid }) }
            }

            "form.created" -> {
                val form = unwrap(d, "form")
                val fid = form.str("id") ?: return
                if (sid == currentSessionId()) {
                    _session.update { st ->
                        st.copy(forms = st.forms.filterNot { it.str("id") == fid } + form)
                    }
                }
                // Bildirim artık WatchService tarafından atılır (çift bildirim olmasın).
            }

            "form.replied", "form.cancelled" -> {
                val fid = d.str("id") ?: d.obj("form").str("id") ?: return
                _session.update { st -> st.copy(forms = st.forms.filterNot { it.str("id") == fid }) }
            }

            else -> Unit
        }
    }

    /** Bazı olaylar nesneyi doğrudan, bazıları sarmalı (`{form: …}` / `{request: …}`) taşır. */
    private fun unwrap(d: JsonObject, key: String): JsonObject =
        if (d.str("id") != null && (key != "form" || d.arr("fields").isNotEmpty())) d else d.obj(key)

    private fun toolContent(d: JsonObject): String? {
        val content = d.arr("content")
        if (content.isEmpty()) return null
        return content.joinToString("\n") { it.obj().str("text").orEmpty() }.trim().ifBlank { null }
    }

    private fun ensureLive(d: JsonObject) {
        if (d.str("sessionID") != currentSessionId()) return
        val mid = d.str("assistantMessageID") ?: return
        _session.update { st -> st.copy(live = st.live ?: LiveMessage(mid)) }
    }

    private fun toolId(d: JsonObject): String =
        d.str("id") ?: d.str("toolCallID") ?: d.str("toolID").orEmpty()

    private fun upsertTool(d: JsonObject, change: (ToolRow) -> ToolRow) {
        val toolID = toolId(d)
        if (toolID.isEmpty()) return
        _session.update { st ->
            val live = st.live ?: LiveMessage(id = d.str("assistantMessageID") ?: "live")
            val tools = live.tools.toMutableList()
            val idx = tools.indexOfFirst { it.id == toolID }
            if (idx >= 0) {
                tools[idx] = change(tools[idx])
            } else {
                tools.add(change(ToolRow(id = toolID, name = d.str("name") ?: "tool", status = "running")))
            }
            st.copy(live = live.copy(tools = tools))
        }
    }

    private fun finishSession(sid: String?) {
        val wasBusy = sid != null && rawActive.contains(sid)
        if (sid != null) rawActive = rawActive - sid
        recomputeActive()
        if (sid == currentSessionId()) {
            viewModelScope.launch { refreshMessages() }
        }
        if (wasBusy && !AppVisibility.visible) {
            val title = _sessions.value.sessions.firstOrNull { it.str("id") == sid }?.str("title")
                ?.ifBlank { null } ?: "oturum"
            notify(sid.hashCode(), "Görev tamamlandı", title)
        }
    }

    /**
     * Aktif oturumlara üst (ana) oturumlarını da ekler: bir alt ajan çalışırken
     * onu başlatan oturum da "çalışıyor" sayılır.
     */
    private fun recomputeActive() {
        val sessions = _sessions.value.sessions
        val parents = sessions.associate { it.str("id").orEmpty() to it.str("parentID") }
        val expanded = rawActive.toMutableSet()
        for (id in rawActive) {
            var p = parents[id]
            val seen = mutableSetOf<String>()
            while (p != null && p !in expanded && seen.add(p)) {
                expanded += p
                p = parents[p]
            }
        }
        _sessions.update { it.copy(active = expanded) }
        val cur = currentSessionId()
        if (cur != null) {
            _session.update {
                it.copy(busy = cur in expanded, childBusy = cur in expanded && cur !in rawActive)
            }
        }
    }

    /** Yeni açılan (ör. alt ajan) oturumları listeye ekler — üst oturum bağını kurmak için. */
    private fun ensureKnown(sid: String) {
        if (_sessions.value.sessions.any { it.str("id") == sid }) return
        val current = api ?: return
        viewModelScope.launch {
            val info = runCatching { current.session(sid) }.getOrNull() ?: return@launch
            _sessions.update { st ->
                if (st.sessions.any { it.str("id") == sid }) st
                else st.copy(sessions = listOf(info) + st.sessions)
            }
            recomputeActive()
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        runCatching { Notifier.notify(appContext, id, title, text) }
    }

    private fun currentSessionId(): String? = (_screen.value as? Screen.Session)?.id

    // ------------------------------------------------------------- sessions

    fun refreshSessions() {
        val current = api ?: return
        viewModelScope.launch {
            _sessions.update { it.copy(loading = true, error = null) }
            try {
                val list = current.sessions()
                val active = runCatching { current.active() }.getOrDefault(emptySet())
                rawActive = active
                _sessions.update { it.copy(loading = false, sessions = list) }
                recomputeActive()
            } catch (e: Exception) {
                _sessions.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun newSession(title: String, directory: String?, onDone: (String?) -> Unit) {
        val current = api ?: return
        viewModelScope.launch {
            try {
                val s = current.createSession(title.ifBlank { null }, directory?.ifBlank { null })
                refreshSessions()
                onDone(s.str("id"))
            } catch (e: Exception) {
                onDone(null)
            }
        }
    }

    fun openSession(id: String) {
        _screen.value = Screen.Session(id)
        val current = api ?: return
        viewModelScope.launch {
            _session.update { SessionState(loading = true) }
            try {
                val info = current.session(id)
                val msgs = current.messages(id)
                val perms = runCatching { current.pendingPermissions() }.getOrDefault(emptyList())
                    .filter { it.str("sessionID") == id }
                val forms = runCatching { current.forms(id) }.getOrDefault(emptyList())
                _session.update {
                    SessionState(
                        loading = false,
                        session = info,
                        messages = msgs,
                        busy = _sessions.value.active.contains(id),
                        childBusy = _sessions.value.active.contains(id) && !rawActive.contains(id),
                        permissions = perms,
                        forms = forms,
                    )
                }
            } catch (e: Exception) {
                _session.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun refreshMessages() {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            try {
                val msgs = current.messages(id)
                val info = current.session(id)
                val forms = runCatching { current.forms(id) }.getOrDefault(emptyList())
                _session.update { it.copy(messages = msgs, session = info, forms = forms, live = null) }
            } catch (_: Exception) {
            }
        }
    }

    fun sendPrompt(text: String) {
        val id = currentSessionId() ?: return
        _session.update { it.copy(sending = true, error = null) }
        runOrQueue(
            kind = "prompt",
            sessionID = id,
            label = "prompt: " + text.replace("\n", " ").take(40),
            payload = buildJsonObject { put("text", text) }.toString(),
            onError = { msg -> _session.update { it.copy(sending = false, error = msg) } },
            onQueued = { _session.update { it.copy(sending = false) } },
        ) { api ->
            api.prompt(id, text)
            _session.update { it.copy(sending = false) }
            refreshMessages()
        }
    }

    fun interrupt() {
        val id = currentSessionId() ?: return
        runOrQueue(
            kind = "interrupt",
            sessionID = id,
            label = "durdur: oturum ${id.take(8)}",
            payload = "{}",
        ) { api ->
            api.interrupt(id)
            refreshMessages()
        }
    }

    // ------------------------------------------------- session yönetimi (uzun bas)

    fun deleteSession(id: String) {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.deleteSession(id) }
                .onSuccess {
                    if (currentSessionId() == id) goBack()
                    refreshSessions()
                }
                .onFailure { e ->
                    _sessions.update { it.copy(error = "Silinemedi: ${e.message}") }
                }
        }
    }

    fun renameSession(id: String, title: String) {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.renameSession(id, title) }
                .onSuccess {
                    _sessions.update { st ->
                        st.copy(sessions = st.sessions.map { s ->
                            if (s.str("id") == id) JsonObject(s + ("title" to JsonPrimitive(title))) else s
                        })
                    }
                    _session.update { st ->
                        val cur = st.session
                        if (cur != null && cur.str("id") == id)
                            st.copy(session = JsonObject(cur + ("title" to JsonPrimitive(title))))
                        else st
                    }
                }
                .onFailure { e ->
                    _sessions.update { it.copy(error = "Yeniden adlandırılamadı: ${e.message}") }
                }
        }
    }

    /** Oturumu çoğaltır ve kopyasını açar. */
    fun forkSession(id: String) {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.forkSession(id) }
                .onSuccess { newId ->
                    refreshSessions()
                    openSession(newId)
                }
                .onFailure { e ->
                    _sessions.update { it.copy(error = "Çoğaltılamadı: ${e.message}") }
                }
        }
    }

    /** Mesajın bulunduğu noktaya kadar oturumu geri alır. */
    fun revertToMessage(messageID: String) {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            _session.update { it.copy(busy = true) }
            runCatching { current.revertTo(id, messageID) }
                .onSuccess {
                    _session.update { it.copy(busy = false, live = null) }
                    refreshMessages()
                    refreshSessions()
                }
                .onFailure { e ->
                    _session.update { it.copy(busy = false, error = "Geri alma başarısız: ${e.message}") }
                }
        }
    }

    /** Liste sırasına göre önceki (-1) / sonraki (+1) oturuma geçiş — kaydırma hareketi. */
    fun switchRelative(offset: Int) {
        val list = _sessions.value.sessions
        val cur = currentSessionId() ?: return
        val idx = list.indexOfFirst { it.str("id") == cur }
        if (idx < 0) return
        val target = list.getOrNull(idx + offset) ?: return
        target.str("id")?.let { openSession(it) }
    }

    // --------------------------------------- kapatma görevi (oturuma prompt)

    /**
     * "Tüm işler bitince bilgisayarı kapat" görevini hedef oturuma **prompt olarak**
     * gönderir. Görev bilgisayar tarafında (OpenCode oturumunda) yürütüldüğü için
     * telefon kapalı olsa da uygulama kapalı olsa da sürer.
     *
     * Hedef: [targetId] verilmişse o oturum; yoksa başlığında "opencode-mobile"
     * geçen oturum, o da yoksa en son güncellenen oturum.
     * [force] true ise kapatma komutu çalışan programları zorla kapatır (`shutdown /f`).
     * [at] "HH:MM" ise gözcü o saate kadar hiç tetiklenmez; null ise "işler bitince".
     *
     * Görev TEK SEFERLİKTİR: prompt bunu açıkça söyler — oturum kapatma komutunu en
     * fazla bir kez çalıştırır, döngüye girmez. (Geçmişte bir oturum bunu sürekli
     * tekrarlayan bir döngüye sokmuştu; prompt'taki kurallar bunu engeller.)
     */
    fun armShutdown(force: Boolean, targetId: String? = null, at: String? = null) {
        val current = api ?: return
        viewModelScope.launch {
            val id = if (!targetId.isNullOrBlank()) {
                targetId
            } else {
                val sessions = runCatching { current.sessions(50) }.getOrDefault(emptyList())
                val target =
                    sessions.firstOrNull { it.str("title")?.contains("opencode-mobile", true) == true }
                        ?: sessions.maxByOrNull { it.obj("time").num("updated") ?: 0.0 }
                target?.str("id")
            }
            if (id == null) {
                notify(41, "Kapatma görevi kurulamadı", "Hedef oturum bulunamadı.")
                return@launch
            }
            runCatching { current.prompt(id, shutdownTask(id, force, at)) }
                .onSuccess {
                    val plan = (if (force) "force" else "soft") + "|" + id + "|" +
                        (at ?: "-") + "|" + System.currentTimeMillis()
                    _shutdownPlan.value = plan
                    viewModelScope.launch { settings.setShutdownPlan(plan) }
                    notify(
                        41,
                        "Kapatma görevi gönderildi",
                        if (at != null) {
                            "En erken $at — oturumlar boşalınca kapatılacak (telefon kapalı olsa da sürer)."
                        } else {
                            "Tüm işler bitince bilgisayar kapatılacak (telefon kapalı olsa da sürer)."
                        },
                    )
                }
                .onFailure { e ->
                    notify(41, "Kapatma görevi gönderilemedi", e.message ?: "bilinmeyen hata")
                }
        }
    }

    /** Gönderilmiş kapatma görevini iptal eder (gözlemci oturumu durdurur). */
    fun cancelShutdown() {
        val plan = _shutdownPlan.value ?: return
        val current = api
        // Yeni biçimde id ikinci alan: "<force|soft>|<id>|<HH:MM|->|<armedAt>".
        val id = plan.split('|').getOrNull(1).orEmpty()
        _shutdownPlan.value = null
        viewModelScope.launch {
            settings.setShutdownPlan(null)
            if (current != null && id.isNotBlank()) {
                runCatching { current.interrupt(id) }
                runCatching {
                    current.prompt(
                        id,
                        "[İPTAL] DÜŞÜNME, aynen çalıştır:\n$cancelCmd\n" +
                            "Sonra \"kapatma görevi iptal edildi\" yaz ve bitir. " +
                            "shutdown komutunu ÇALIŞTIRMA.",
                    )
                }
            }
        }
    }

    // ------------------------------------------ gözcü canlılık / bayat plan

    /** Gözcü durumunu sorar: hedef oturumda `-Status` çalıştırır, mesaj akışından JSON raporu okur. */
    fun refreshShutdownStatus() {
        val current = api ?: return
        viewModelScope.launch {
            try {
                val id = shutdownTargetId(current)
                if (id == null) {
                    _watchStatus.value = null
                } else {
                    runCatching { current.shell(id, statusCmd) }
                    var found: WatchStatus? = null
                    var attempt = 0
                    while (found == null && attempt < 5) {
                        kotlinx.coroutines.delay(400)
                        // Yeni pencere kullan: yoğun oturumda eski 200 mesajlık
                        // pencereye kabuk çalışması hiç düşmeyebilir.
                        val msgs = runCatching { current.messagesRecent(id, 30) }.getOrDefault(emptyList())
                        found = parseStatusFromMessages(msgs)
                        attempt++
                    }
                    _watchStatus.value = found
                }
            } catch (_: Exception) {
                // Yut — durum bilinmiyor olarak kalır.
            } finally {
                maybeClearStalePlan()
            }
        }
    }

    private suspend fun shutdownTargetId(current: Api): String? {
        val fromPlan = _shutdownPlan.value?.split('|')?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (fromPlan != null) return fromPlan
        val inMemory = _sessions.value.sessions.firstOrNull()?.str("id")
        if (inMemory != null) return inMemory
        return runCatching { current.sessions(1) }.getOrDefault(emptyList()).firstOrNull()?.str("id")
    }

    private fun parseStatusFromMessages(msgs: List<JsonObject>): WatchStatus? {
        // Siradan bagimsiz: eslesen kabuk mesajlari arasindan EN YENISINI sec
        // (API sirasi degisken olabilir; lastOrNull eski okumaya dusebilirdi).
        val msg = msgs.filter { m ->
            m.str("type") == "shell" &&
                m.str("command")?.contains("-Status") == true &&
                m.str("status") == "exited"
        }.maxByOrNull { it.obj("time").num("created") ?: 0.0 } ?: return null
        val out = msg.obj("output").str("output") ?: return null
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: return null
        return runCatching {
            val j = kotlinx.serialization.json.Json.parseToJsonElement(line).obj()
            WatchStatus(
                live = j.str("live") == "true",
                pid = j.num("pid")?.toInt(),
                uptimeMinutes = j.num("uptimeMinutes")?.toInt(),
                lastSayim = j.str("lastSayim"),
                lastSayimAt = j.str("lastSayimAt"),
                lastLogLine = j.str("lastLogLine"),
                lastLogAt = j.str("lastLogAt"),
                shutdownSent = j.str("shutdownSent") == "true",
            )
        }.getOrNull()
    }

    /**
     * Bayat kapatma planını temizler. Yalnızca KESİN olumsuz okumada temizler:
     * sorgu başarısızsa (durum null) hiçbir şey yapmaz. Yeni biçimde iki koşuldan
     * biri gerekir: betik aramadan sonra çalışıp ölmüş (log aramadan yeni) ya da
     * 30 dk'dır hiç başlamamış. Böylece "ajan henüz başlatmadı" durumunda banner
     * yanlışlıkla temizlenmez. Eski biçimde (armedAt=0) 3 ardışık kesin okuma yeter.
     */
    fun maybeClearStalePlan() {
        val plan = _shutdownPlan.value ?: return
        val st = _watchStatus.value ?: return        // bilinmiyor -> kanıt yok, temizleme
        if (st.live) return
        if (st.shutdownSent) {
            // Mevcut koşu kapatma komutunu göndermiş: görev TAMAMLANDI (makine kapandı/kapanıyor).
            clearStalePlan(plan, "Kapatma görevi tamamlandı", "Kapatma komutu gönderildi — bilgisayar kapandı.")
            return
        }
        val armedAt = plan.split('|').getOrNull(3)?.toLongOrNull() ?: 0L
        if (armedAt == 0L) {
            // Eski biçim plan (önceki uygulamadan kalmış): tek kesin okuma yeter.
            clearStalePlan(plan, "Bayat kapatma görevi temizlendi", "Gözcü çalışmıyor — kapatma OLMAYACAK.")
            return
        }
        val elapsed = System.currentTimeMillis() - armedAt
        val ranSinceArm = parseLogTime(st.lastLogAt)?.let { it > armedAt - 60_000 } ?: false
        if (ranSinceArm || elapsed > 30 * 60_000) {
            clearStalePlan(plan, "Bayat kapatma görevi temizlendi", "Gözcü çalışmıyor — kapatma OLMAYACAK.")
        }
    }

    /** "yyyy-MM-dd HH:mm:ss" (PC saati) -> epoch ms; bilinmiyorsa null. */
    private fun parseLogTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(s)?.time
        }.getOrNull()
    }

    private fun clearStalePlan(expected: String, title: String, text: String) {
        if (_shutdownPlan.value != expected) return
        _shutdownPlan.value = null
        viewModelScope.launch { settings.setShutdownPlan(null) }
        notify(41, title, text)
    }

    /** İptal komutu — "kapatma" skill'indeki betik; gözcüyü durdurur, bekleyen kapatmayı iptal eder. */
    private val cancelCmd =
        "powershell -NoProfile -ExecutionPolicy Bypass -File " +
            "\"C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1\" -Cancel"

    /** Canlılık sorgusu — gözcüyü değiştirmez, tek satır JSON rapor döndürür. */
    private val statusCmd =
        "powershell -NoProfile -ExecutionPolicy Bypass -File " +
            "\"C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1\" -Status"

    /**
     * Hedef oturuma gönderilecek gözlemci görev metni. Düşünmeyi kaldıran yapıda:
     * ajana tek satır komut verir; bekleme/kontrol/katma "kapatma" skill'indeki
     * betikte ([shutdown-watch.ps1]) yapılır. Kurallar (tek seferlik, döngü yok)
     * hem skill'de hem burada yazılıdır.
     */
    private fun shutdownTask(sessionID: String, force: Boolean, at: String? = null): String {
        val script = "C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1"
        val forceArg = if (force) " -Force" else ""
        val atArg = if (at != null) " -At \"$at\"" else ""
        val startCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File \"$script\" -Sid \"$sessionID\"$forceArg$atArg"
        return """
[GÖREV: BİLGİSAYARI KAPAT — TEK SEFERLİK GÖZLEMCİ] (skill: kapatma)

DÜŞÜNME, TARTIŞMA, ARAŞTIRMA YAPMA — bu bir uygulama görevidir, aynen uygula.
Tüm kurallar "kapatma" skill'inde; özet: betik bekler, kontrol eder ve kapatma
komutunu BİR KEZ çalıştırır.

ŞİMDİ aynen bu satırı çalıştır:
$startCmd

Sonra tek satır yaz: "kapatma gözcüsü başlatıldı — tüm işler bitince bilgisayar kapanacak"
ve GÖREVİ BİTİR. Bekleme döngüsü kurma, kontrol sorgusu çalıştırma, başka işlem yapma.

Kurallar: shutdown.exe'yi kendin ASLA çalıştırma; kendini tekrarlama, döngüye girme.
(Geçmişte bir oturum bunu sürekli tekrarlayan bir döngüye sokmuştu ve bilgisayar
defalarca kendiliğinden kapandı — bu bir HATAdır, tekrarlanmayacak.)

"[İPTAL]" mesajı gelirse DÜŞÜNME, aynen çalıştır:
$cancelCmd
ve tek satır yaz: "kapatma görevi iptal edildi".
        """.trimIndent()
    }

    // ----------------------------------------------------- agent / model / form

    private fun refreshAgents() {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.agents() }.onSuccess { list -> _agents.value = list }
        }
    }

    private fun refreshModels() {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.models() }.onSuccess { list -> _models.value = list }
        }
    }

    private fun refreshCommands() {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.commands() }.onSuccess { list -> _commands.value = list }
        }
    }

    /** Komutu (ör. /init, /review) açık oturumda çalıştırır. */
    fun runCommand(name: String, text: String) {
        val id = currentSessionId() ?: return
        _session.update { it.copy(sending = true) }
        runOrQueue(
            kind = "command",
            sessionID = id,
            label = "komut: $name",
            payload = buildJsonObject { put("name", name); put("text", text) }.toString(),
            onError = { msg -> _session.update { it.copy(sending = false, error = msg) } },
            onQueued = { _session.update { it.copy(sending = false) } },
        ) { api ->
            api.runCommand(id, name, text)
            _session.update { it.copy(sending = false) }
            refreshMessages()
        }
    }

    /** Oturumun ajanını (build / plan / özel) değiştirir. */
    fun switchAgent(agent: String) {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            runCatching { current.switchAgent(id, agent) }
            _session.update { st ->
                st.copy(session = st.session?.let { s ->
                    kotlinx.serialization.json.buildJsonObject {
                        for ((k, v) in s) put(k, v)
                        put("agent", kotlinx.serialization.json.JsonPrimitive(agent))
                    }
                })
            }
        }
    }

    /** Oturumun modelini değiştirir. */
    fun switchModel(providerID: String, modelID: String, variant: String? = null) {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            runCatching { current.switchModel(id, providerID, modelID, variant) }
            _session.update { st ->
                st.copy(session = st.session?.let { s ->
                    kotlinx.serialization.json.buildJsonObject {
                        for ((k, v) in s) if (k != "model") put(k, v)
                        put(
                            "model",
                            kotlinx.serialization.json.buildJsonObject {
                                put("id", modelID)
                                put("providerID", providerID)
                                if (variant != null) put("variant", variant)
                            },
                        )
                    }
                })
            }
        }
    }

    /** Soru formunu yanıtla. answer: alan adı → değer. */
    fun replyForm(formID: String, answer: JsonObject) {
        val id = currentSessionId() ?: return
        runOrQueue(
            kind = "form",
            sessionID = id,
            label = "form yanıtı: ${formID.take(8)}",
            payload = buildJsonObject {
                put("formID", formID)
                put("answer", answer)
            }.toString(),
        ) { api ->
            api.replyForm(id, formID, answer)
            _session.update { st -> st.copy(forms = st.forms.filterNot { it.str("id") == formID }) }
        }
    }

    /** Soru formunu iptal et. */
    fun cancelForm(formID: String) {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            runCatching { current.cancelForm(id, formID) }
            _session.update { st -> st.copy(forms = st.forms.filterNot { it.str("id") == formID }) }
        }
    }

    // -------------------------------------------------------------- izinler

    fun replyPermission(sessionID: String, requestID: String, decision: String) {
        val onay = decision == "once" || decision == "always"
        runOrQueue(
            kind = "permission",
            sessionID = sessionID,
            label = "izin: ${if (onay) "onay" else "ret"} (oturum ${sessionID.take(8)})",
            payload = buildJsonObject {
                put("requestID", requestID)
                put("decision", decision)
            }.toString(),
        ) { api ->
            api.replyPermission(sessionID, requestID, decision)
        }
    }

    // ------------------------------------------------- çevrimdışı kuyruk

    /**
     * [block]'u dener; sunucuya ulaşılamazsa (IOException ya da api==null) eylemi
     * kuyruğa ekler. Sunucu reddederse (ApiException) kuyruğa EKLEMEZ, hatayı
     * [onError] ile mevcut hata akışına bırakır.
     */
    private fun runOrQueue(
        kind: String,
        sessionID: String?,
        label: String,
        payload: String,
        onError: (String) -> Unit = {},
        onQueued: () -> Unit = {},
        block: suspend (Api) -> Unit,
    ) {
        val current = api
        if (current == null) {
            enqueue(kind, sessionID, label, payload, null)
            onQueued()
            return
        }
        viewModelScope.launch {
            try {
                block(current)
            } catch (e: ApiException) {
                onError(e.message ?: "sunucu isteği reddetti")
            } catch (e: IOException) {
                enqueue(kind, sessionID, label, payload, e.message)
                onQueued()
            } catch (e: Exception) {
                // Beklenmeyen hata (serileştirme vb.) — kuyruğa ekleme, yüzeye bırak.
                onError(e.message ?: "beklenmeyen hata")
            }
        }
    }

    private fun enqueue(kind: String, sessionID: String?, label: String, payload: String, error: String?) {
        val action = QueuedAction(
            id = java.util.UUID.randomUUID().toString(),
            kind = kind,
            sessionID = sessionID,
            label = label,
            payload = payload,
            lastError = error,
        )
        updateQueue(_pendingActions.value + action)
    }

    /** Kuyruğu tek noktadan güncelleyip kalıcı hale getirir. */
    private fun updateQueue(list: List<QueuedAction>) {
        _pendingActions.value = list
        val raw = if (list.isEmpty()) {
            null
        } else {
            queueJson.encodeToString(ListSerializer(QueuedAction.serializer()), list)
        }
        viewModelScope.launch { settings.setQueue(raw) }
    }

    /** Tek eylemi kuyruktan çıkarır. */
    fun removeFromQueue(id: String) {
        updateQueue(_pendingActions.value.filterNot { it.id == id })
    }

    /**
     * Kuyruktaki eylemleri sırayla gönderir. Başarılı olanı listeden çıkarır,
     * başarısız olanı `lastError` ile tutar. api null ise dokunmaz.
     */
    fun flushQueue() {
        val current = api ?: return
        val snapshot = _pendingActions.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val sent = mutableSetOf<String>()
            val errors = mutableMapOf<String, String>()
            for (action in snapshot) {
                try {
                    replay(current, action)
                    sent.add(action.id)
                } catch (e: ApiException) {
                    errors[action.id] = e.message ?: "sunucu reddetti"
                } catch (e: IOException) {
                    errors[action.id] = e.message ?: "bağlantı yok"
                } catch (e: Exception) {
                    errors[action.id] = e.message ?: "beklenmeyen hata"
                }
            }
            // Bu sırada yeni eklenen eylemleri koru.
            val updated = _pendingActions.value.mapNotNull { a ->
                when {
                    a.id in sent -> null
                    errors.containsKey(a.id) -> a.copy(lastError = errors[a.id])
                    else -> a
                }
            }
            updateQueue(updated)
        }
    }

    private suspend fun replay(api: Api, a: QueuedAction) {
        val p = runCatching { queueJson.parseToJsonElement(a.payload).obj() }.getOrDefault(JsonObject(emptyMap()))
        val sid = a.sessionID
        when (a.kind) {
            "prompt" -> if (sid != null) api.prompt(sid, p.str("text").orEmpty())
            "interrupt" -> if (sid != null) api.interrupt(sid)
            "permission" -> if (sid != null) {
                api.replyPermission(sid, p.str("requestID").orEmpty(), p.str("decision") ?: "once")
            }
            "command" -> if (sid != null) {
                api.runCommand(sid, p.str("name").orEmpty(), p.str("text").orEmpty())
            }
            "form" -> if (sid != null) {
                api.replyForm(sid, p.str("formID").orEmpty(), p.obj("answer"))
            }
            else -> Unit
        }
    }

    fun goBack() {
        _screen.value = Screen.Sessions
        refreshSessions()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return AppViewModel(app, Settings(app)) as T
        }
    }
}

fun JsonObject.messageType(): String = str("type").orEmpty()
fun JsonObject.cost(): Double = num("cost") ?: 0.0