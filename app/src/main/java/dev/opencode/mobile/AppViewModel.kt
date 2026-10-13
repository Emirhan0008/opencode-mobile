package dev.opencode.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.OcEvent
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import dev.opencode.mobile.net.text
import dev.opencode.mobile.notify.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val permissions: List<JsonObject> = emptyList(),
    val forms: List<JsonObject> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

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

    private val _screen = MutableStateFlow<Screen>(Screen.Sessions)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

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

    private val _darkTheme = MutableStateFlow(true)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    init {
        viewModelScope.launch {
            settings.connection.collect { conn ->
                if (conn == null) {
                    _connected.value = false
                    api = null
                    _screen.value = Screen.Connect
                } else {
                    attach(conn.baseUrl, conn.token)
                    if (_screen.value is Screen.Connect) _screen.value = Screen.Sessions
                }
            }
        }
        viewModelScope.launch {
            settings.darkTheme.collect { _darkTheme.value = it }
        }
    }

    // ------------------------------------------------------------ connection

    private fun attach(baseUrl: String, token: String) {
        if (api != null && _connected.value) return
        api = Api(baseUrl, token)
        _connected.value = true
        subscribeEvents()
        refreshAgents()
        refreshModels()
        refreshSessions()
    }

    /** Pairing kodu / URL'i ile bağlanır. */
    fun connectWithCode(baseUrl: String, codeOrUrl: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val token = Api.redeem(baseUrl, codeOrUrl)
                settings.save(baseUrl, token)
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Bağlanılamadı")
            }
        }
    }

    /** Sunucu parolası ile bağlanır (Basic auth parolası token yerine geçer). */
    fun connectWithPassword(baseUrl: String, password: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val probe = Api(baseUrl, password)
                probe.get("/api/info")
                settings.save(baseUrl, password)
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "Parola reddedildi")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sseCall?.cancel()
            sseCall = null
            api = null
            _connected.value = false
            settings.clear()
        }
    }

    fun setDarkTheme(dark: Boolean) {
        _darkTheme.value = dark
        viewModelScope.launch { settings.setDarkTheme(dark) }
    }

    // ----------------------------------------------------------------- SSE

    private fun subscribeEvents() {
        sseCall?.cancel()
        val current = api ?: return
        sseCall = current.events(
            onEvent = ::handleEvent,
            onError = {
                // Akış koptuysa yeniden bağlan
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (api === current) subscribeEvents()
                }
            },
        )
    }

    private fun handleEvent(ev: OcEvent) {
        val d = ev.data
        val sid = d.str("sessionID")

        when (ev.type) {
            "session.execution.started" -> {
                if (sid != null) _sessions.update { it.copy(active = it.active + sid) }
                if (sid == currentSessionId()) _session.update { it.copy(busy = true) }
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
                if (!AppVisibility.visible) {
                    val action = req.str("action") ?: "izin"
                    val res = req.arr("resources").mapNotNull { it.text() }.take(3)
                    notify(req.str("id").hashCode(), "İzin isteği", (listOf(action) + res).joinToString("\n"))
                }
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
                if (!AppVisibility.visible) {
                    notify(fid.hashCode(), "Soru bekliyor", form.str("title") ?: "Bir soru yanıtı bekliyor")
                }
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
        val wasBusy = sid != null && _sessions.value.active.contains(sid)
        if (sid != null) _sessions.update { it.copy(active = it.active - sid) }
        if (sid == currentSessionId()) {
            _session.update { it.copy(busy = false) }
            viewModelScope.launch { refreshMessages() }
        }
        if (wasBusy && !AppVisibility.visible) {
            val title = _sessions.value.sessions.firstOrNull { it.str("id") == sid }?.str("title")
                ?.ifBlank { null } ?: "oturum"
            notify(sid.hashCode(), "Görev tamamlandı", title)
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
                _sessions.update { it.copy(loading = false, sessions = list, active = active) }
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
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            _session.update { it.copy(sending = true, error = null) }
            try {
                current.prompt(id, text)
                _session.update { it.copy(sending = false) }
                refreshMessages()
            } catch (e: Exception) {
                _session.update { it.copy(sending = false, error = e.message) }
            }
        }
    }

    fun interrupt() {
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            runCatching { current.interrupt(id) }
            refreshMessages()
        }
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
        val current = api ?: return
        val id = currentSessionId() ?: return
        viewModelScope.launch {
            runCatching { current.replyForm(id, formID, answer) }
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
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.replyPermission(sessionID, requestID, decision) }
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