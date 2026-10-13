package dev.opencode.mobile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.ApiException
import dev.opencode.mobile.net.OcEvent
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call

// ------------------------------------------------------------------- state

data class ToolRow(
    val id: String,
    val name: String,
    val status: String, // running | ok | error
    val input: String = "",
    val output: String = "",
)

data class LiveMessage(
    val id: String,
    var reasoning: String = "",
    var text: String = "",
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
    val sending: Boolean = false,
    val error: String? = null,
)

sealed interface Screen {
    data object Connect : Screen
    data object Sessions : Screen
    data class Session(val id: String) : Screen
}

// ------------------------------------------------------------- view model

class AppViewModel(private val settings: Settings) : ViewModel() {

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
    }

    // ------------------------------------------------------------ connection

    private fun attach(baseUrl: String, token: String) {
        if (api != null && _connected.value) return
        api = Api(baseUrl, token)
        _connected.value = true
        subscribeEvents()
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
                if (sid == currentSessionId()) {
                    _session.update { it.copy(busy = true) }
                }
            }

            "session.execution.succeeded", "session.idle" -> finishSession(sid)
            "session.execution.failed", "session.execution.interrupted" -> finishSession(sid)

            "session.text.delta" -> {
                if (sid != currentSessionId()) return
                val mid = d.str("assistantMessageID") ?: return
                _session.update { st ->
                    val live = (st.live ?: LiveMessage(mid)).apply { text += d.str("delta").orEmpty() }
                    st.copy(live = live)
                }
            }

            "session.reasoning.delta" -> {
                if (sid != currentSessionId()) return
                val mid = d.str("assistantMessageID") ?: return
                _session.update { st ->
                    val live = (st.live ?: LiveMessage(mid)).apply { reasoning += d.str("delta").orEmpty() }
                    st.copy(live = live)
                }
            }

            "session.tool.input.started" -> {
                if (sid != currentSessionId()) return
                upsertTool(d.str("id").orEmpty(), d.str("name") ?: "tool", "running")
            }

            "session.tool.called" -> {
                if (sid != currentSessionId()) return
                upsertTool(d.str("id").orEmpty(), null, "running")
            }

            "session.tool.success" -> {
                if (sid != currentSessionId()) return
                upsertTool(d.str("id").orEmpty(), null, "ok")
            }

            "session.tool.failed" -> {
                if (sid != currentSessionId()) return
                upsertTool(d.str("id").orEmpty(), null, "error")
            }

            "permission.asked" -> {
                val req = d
                _globalPermissions.update { list -> list + req }
                if (sid == currentSessionId()) {
                    _session.update { it.copy(permissions = it.permissions + req) }
                }
            }

            "permission.replied" -> {
                val rid = d.str("requestID")
                _globalPermissions.update { list -> list.filterNot { it.str("id") == rid } }
                _session.update { it.copy(permissions = it.permissions.filterNot { p -> p.str("id") == rid }) }
            }

            else -> Unit
        }
    }

    private fun upsertTool(toolID: String, name: String?, status: String) {
        if (toolID.isEmpty()) return
        _session.update { st ->
            val live = st.live ?: LiveMessage(id = "live")
            val tools = live.tools.toMutableList()
            val idx = tools.indexOfFirst { it.id == toolID }
            if (idx >= 0) {
                val old = tools[idx]
                tools[idx] = old.copy(name = name ?: old.name, status = status)
            } else {
                tools.add(ToolRow(id = toolID, name = name ?: "tool", status = status))
            }
            st.copy(live = live.copy(tools = tools))
        }
    }

    private fun finishSession(sid: String?) {
        if (sid != null) _sessions.update { it.copy(active = it.active - sid) }
        if (sid == currentSessionId()) {
            _session.update { it.copy(busy = false) }
            viewModelScope.launch { refreshMessages() }
        }
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
                _session.update {
                    SessionState(
                        loading = false,
                        session = info,
                        messages = msgs,
                        busy = _sessions.value.active.contains(id),
                        permissions = perms,
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
                _session.update { it.copy(messages = msgs, session = info, live = null) }
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(Settings(context.applicationContext)) as T
    }
}

fun JsonObject.messageType(): String = str("type").orEmpty()
fun JsonObject.cost(): Double = num("cost") ?: 0.0