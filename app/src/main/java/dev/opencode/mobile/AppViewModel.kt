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
import dev.opencode.mobile.voice.EvrenClient
import dev.opencode.mobile.voice.VoiceService
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

// ------------------------------------------------------------------- state

data class ToolRow(
    val id: String,
    val name: String,
    val status: String, // running | ok | error
    val input: String = "",
    val output: String = "",
    val error: String = "",
    /** Satırın belirdiği an (duvar saati, epoch ms). Olayda zaman olmadığı için
     *  uygulama olayı aldığı anda damgalar. */
    val startedAt: Long = 0L,
    /** Araç tamamlandığında dolar; çalışırken null. */
    val endedAt: Long? = null,
    /** `metadata.exit` — sıfırdan farklıysa "exit N" gösterilir. */
    val exit: Int? = null,
    /** `metadata.truncated` — çıktı kısaltıldıysa "kesildi". */
    val truncated: Boolean = false,
)

data class LiveMessage(
    val id: String,
    val reasoning: String = "",
    val text: String = "",
    val tools: List<ToolRow> = emptyList(),
)

/**
 * Telegram köprüsünün `/health` yanıtı. Köprü bilgisayarda çalışır; uygulama
 * yalnızca aynı ağdayken canlı durum alabilir. Yanıt sır içermez.
 */
data class TelegramStatus(
    val username: String? = null,
    val allowed: Int = 0,
    val uptimeSeconds: Long = 0L,
    val opencode: String? = null,
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
    data object Settings : Screen

    /** Telegram yedek erişim ekranı. */
    data object Telegram : Screen
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

    /** Tüm oturumlardaki bekleyen sorular (form). Ana ekran göstergesi için. */
    private val _globalForms = MutableStateFlow<List<JsonObject>>(emptyList())
    val globalForms: StateFlow<List<JsonObject>> = _globalForms.asStateFlow()

    private val _agents = MutableStateFlow<List<JsonObject>>(emptyList())
    val agents: StateFlow<List<JsonObject>> = _agents.asStateFlow()

    private val _models = MutableStateFlow<List<JsonObject>>(emptyList())
    val models: StateFlow<List<JsonObject>> = _models.asStateFlow()

    /** Evren kotası yanıtı (yoksa null — şerit gösterilmez). */
    private val _quota = MutableStateFlow<JsonObject?>(null)
    val quota: StateFlow<JsonObject?> = _quota.asStateFlow()

    /** Kota sorgusu başarısız mı (şeritte "alınamadı" göstergesi). */
    private val _quotaError = MutableStateFlow(false)
    val quotaError: StateFlow<Boolean> = _quotaError.asStateFlow()

    /** Kota sorgusu sürüyor mu. */
    private val _quotaLoading = MutableStateFlow(false)
    val quotaLoading: StateFlow<Boolean> = _quotaLoading.asStateFlow()

    private val _commands = MutableStateFlow<List<JsonObject>>(emptyList())
    val commands: StateFlow<List<JsonObject>> = _commands.asStateFlow()

    private val _darkTheme = MutableStateFlow(true)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    /** Arka plan bağlantı servisi (ana anahtar) — kapalıyken servis çalışmaz. */
    private val _backgroundService = MutableStateFlow(true)
    val backgroundService: StateFlow<Boolean> = _backgroundService.asStateFlow()

    /** İzin isteği bildirimleri açık mı. */
    private val _notifyPermissions = MutableStateFlow(true)
    val notifyPermissions: StateFlow<Boolean> = _notifyPermissions.asStateFlow()

    /** Soru (form) bildirimleri açık mı. */
    private val _notifyForms = MutableStateFlow(true)
    val notifyForms: StateFlow<Boolean> = _notifyForms.asStateFlow()

    /** Görev bitişi bildirimleri açık mı. */
    private val _notifyWork = MutableStateFlow(true)
    val notifyWork: StateFlow<Boolean> = _notifyWork.asStateFlow()

    /** Evren kota şeridi açık mı. */
    private val _quotaBadge = MutableStateFlow(true)
    val quotaBadge: StateFlow<Boolean> = _quotaBadge.asStateFlow()

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

    /** Telegram yedek erişim: bot kullanıcı adı + durum ucu + son sorgu sonucu. */
    private val _telegramBot = MutableStateFlow("")
    val telegramBot: StateFlow<String> = _telegramBot.asStateFlow()

    private val _telegramStatusUrl = MutableStateFlow("")
    val telegramStatusUrl: StateFlow<String> = _telegramStatusUrl.asStateFlow()

    private val _telegramStatus = MutableStateFlow<TelegramStatus?>(null)
    val telegramStatus: StateFlow<TelegramStatus?> = _telegramStatus.asStateFlow()

    private val _telegramStatusError = MutableStateFlow<String?>(null)
    val telegramStatusError: StateFlow<String?> = _telegramStatusError.asStateFlow()

    private val _telegramChecking = MutableStateFlow(false)
    val telegramChecking: StateFlow<Boolean> = _telegramChecking.asStateFlow()

    /** Kapatma gözcüsü için ayrılmış oturum (varsa tüm gözcü işleri buraya gider). */
    private val _shutdownSessionId = MutableStateFlow("")
    val shutdownSessionId: StateFlow<String> = _shutdownSessionId.asStateFlow()

    private val _ensuringShutdownSession = MutableStateFlow(false)
    val ensuringShutdownSession: StateFlow<Boolean> = _ensuringShutdownSession.asStateFlow()

    // ------------------------------------------------------------------ sesli komut

    /** Sesli komut servisi açık mı (kulaklık tuşu → STT → onay → prompt → özet). */
    private val _voiceEnabled = MutableStateFlow(false)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    private val _voiceConfirm = MutableStateFlow(true)
    val voiceConfirm: StateFlow<Boolean> = _voiceConfirm.asStateFlow()

    private val _voiceAutoSummary = MutableStateFlow(true)
    val voiceAutoSummary: StateFlow<Boolean> = _voiceAutoSummary.asStateFlow()

    private val _voiceName = MutableStateFlow("deniz")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    /** En son sesli okunan metin (özet ya da doğrudan okunan yanıt). */
    private val _voiceLastSpoken = MutableStateFlow("")
    val voiceLastSpoken: StateFlow<String> = _voiceLastSpoken.asStateFlow()

    private val _evrenBaseUrl = MutableStateFlow("")
    val evrenBaseUrl: StateFlow<String> = _evrenBaseUrl.asStateFlow()

    /** Anahtarlar ekranda GÖSTERİLMEZ; yalnızca "dolu mu" bilgisi tutulur. */
    private val _evrenAsrSet = MutableStateFlow(false)
    val evrenAsrSet: StateFlow<Boolean> = _evrenAsrSet.asStateFlow()

    private val _evrenTtsSet = MutableStateFlow(false)
    val evrenTtsSet: StateFlow<Boolean> = _evrenTtsSet.asStateFlow()

    private val _evrenSumSet = MutableStateFlow(false)
    val evrenSumSet: StateFlow<Boolean> = _evrenSumSet.asStateFlow()

    /** "Bağlantıyı test et" sonucu: null = denenmedi, "" = başarılı, aksi hâlde hata. */
    private val _voiceTestResult = MutableStateFlow<String?>(null)
    val voiceTestResult: StateFlow<String?> = _voiceTestResult.asStateFlow()

    private val _voiceTesting = MutableStateFlow(false)
    val voiceTesting: StateFlow<Boolean> = _voiceTesting.asStateFlow()

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
            settings.backgroundService.collect { _backgroundService.value = it }
        }
        viewModelScope.launch {
            settings.notifyPermissions.collect { _notifyPermissions.value = it }
        }
        viewModelScope.launch {
            settings.notifyForms.collect { _notifyForms.value = it }
        }
        viewModelScope.launch {
            settings.notifyWork.collect { _notifyWork.value = it }
        }
        viewModelScope.launch {
            settings.quotaBadge.collect { _quotaBadge.value = it }
        }
        viewModelScope.launch {
            settings.shutdownPlan.collect { plan ->
                _shutdownPlan.value = plan
                // Depodan gelen plan (uygulama açılışı) için gözcü canlılığını sorgula —
                // bayat banner temizliği. Bağlantı sonra kurulursa attach() tetikler.
                if (plan != null && api != null) refreshShutdownStatus()
            }
        }
        viewModelScope.launch {
            settings.telegramBot.collect { _telegramBot.value = it }
        }
        viewModelScope.launch {
            settings.telegramStatusUrl.collect { _telegramStatusUrl.value = it }
        }
        viewModelScope.launch {
            settings.shutdownSession.collect { _shutdownSessionId.value = it }
        }
        viewModelScope.launch {
            settings.voiceConfirm.collect { _voiceConfirm.value = it }
        }
        viewModelScope.launch {
            settings.voiceAutoSummary.collect { _voiceAutoSummary.value = it }
        }
        viewModelScope.launch {
            settings.voiceName.collect { _voiceName.value = it }
        }
        viewModelScope.launch {
            settings.voiceLastSpoken.collect { _voiceLastSpoken.value = it }
        }
        viewModelScope.launch {
            settings.evrenBaseUrl.collect { _evrenBaseUrl.value = it }
        }
        viewModelScope.launch {
            settings.evrenAsrKey.collect { _evrenAsrSet.value = it.isNotBlank() }
        }
        viewModelScope.launch {
            settings.evrenTtsKey.collect { _evrenTtsSet.value = it.isNotBlank() }
        }
        viewModelScope.launch {
            settings.evrenSumKey.collect { _evrenSumSet.value = it.isNotBlank() }
        }
        viewModelScope.launch {
            // Ana anahtar: açılınca ön plan servisini başlat, kapanınca durdur.
            settings.voiceEnabled.collect { enabled ->
                _voiceEnabled.value = enabled
                if (enabled) VoiceService.start(appContext) else VoiceService.stop(appContext)
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
        // Ana anahtar (backgroundService) kapalıysa servis hiç başlatılmaz.
        if (_backgroundService.value) WatchService.start(appContext)
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

    /**
     * Arka plan bağlantı servisini açar/kapatır (ana anahtar). Kapatınca servis
     * durur (arka plan bildirimi de durur); açınca bağlantı varsa hemen başlatılır.
     */
    fun setBackgroundService(enabled: Boolean) {
        _backgroundService.value = enabled
        viewModelScope.launch { settings.setBackgroundService(enabled) }
        if (enabled) {
            if (_connected.value) WatchService.start(appContext)
        } else {
            WatchService.stop(appContext)
        }
    }

    fun setNotifyPermissions(enabled: Boolean) {
        _notifyPermissions.value = enabled
        viewModelScope.launch { settings.setNotifyPermissions(enabled) }
    }

    fun setNotifyForms(enabled: Boolean) {
        _notifyForms.value = enabled
        viewModelScope.launch { settings.setNotifyForms(enabled) }
    }

    fun setNotifyWork(enabled: Boolean) {
        _notifyWork.value = enabled
        viewModelScope.launch { settings.setNotifyWork(enabled) }
    }

    fun setQuotaBadge(enabled: Boolean) {
        _quotaBadge.value = enabled
        viewModelScope.launch { settings.setQuotaBadge(enabled) }
        // Şerit ilk kez açılıyorsa veriyi hemen getir.
        if (enabled) refreshQuota()
    }

    // ------------------------------------------------------------- sesli komut

    /** Ana anahtar: sesli komut servisini açar/kapatır. */
    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        viewModelScope.launch { settings.setVoiceEnabled(enabled) }
        if (enabled) VoiceService.start(appContext) else VoiceService.stop(appContext)
    }

    fun setVoiceConfirm(enabled: Boolean) {
        _voiceConfirm.value = enabled
        viewModelScope.launch { settings.setVoiceConfirm(enabled) }
    }

    fun setVoiceAutoSummary(enabled: Boolean) {
        _voiceAutoSummary.value = enabled
        viewModelScope.launch { settings.setVoiceAutoSummary(enabled) }
    }

    fun setVoiceName(name: String) {
        _voiceName.value = name
        viewModelScope.launch { settings.setVoiceName(name) }
    }

    fun setEvrenBaseUrl(url: String) {
        viewModelScope.launch { settings.setEvrenBaseUrl(url) }
    }

    fun setEvrenAsrKey(key: String) {
        _evrenAsrSet.value = key.isNotBlank()
        viewModelScope.launch { settings.setEvrenAsrKey(key) }
    }

    fun setEvrenTtsKey(key: String) {
        _evrenTtsSet.value = key.isNotBlank()
        viewModelScope.launch { settings.setEvrenTtsKey(key) }
    }

    fun setEvrenSumKey(key: String) {
        _evrenSumSet.value = key.isNotBlank()
        viewModelScope.launch { settings.setEvrenSumKey(key) }
    }

    /** EVREN anahtarlarını canlı dener: /v1/models yanıtı + özet modeli. */
    fun testVoiceConnection() {
        if (_voiceTesting.value) return
        _voiceTesting.value = true
        _voiceTestResult.value = null
        viewModelScope.launch {
            val base = settings.evrenBaseUrl.first()
            val asr = settings.evrenAsrKey.first()
            val tts = settings.evrenTtsKey.first()
            val sum = settings.evrenSumKey.first()
            if (asr.isBlank() || tts.isBlank()) {
                _voiceTestResult.value = "ASR ve TTS anahtarları gerekli"
                _voiceTesting.value = false
                return@launch
            }
            val client = EvrenClient(base, asr, tts, sum)
            val ok = withContext(Dispatchers.IO) { client.ping() }
            _voiceTestResult.value = if (ok) "" else "Bağlantı başarısız — adres/anahtar?"
            _voiceTesting.value = false
        }
    }

    /** Ayarlar ekranını açar. */
    fun openSettings() {
        _screen.value = Screen.Settings
    }

    /** Ayarlar ekranından oturumlar listesine döner. */
    fun closeSettings() {
        _screen.value = Screen.Sessions
    }

    // ------------------------------------------------------------- telegram

    /** Telegram yedek erişim ekranını açar. */
    fun openTelegram() {
        _screen.value = Screen.Telegram
    }

    /** Telegram ekranından döner: bağlantı varsa oturumlara, yoksa connect ekranına. */
    fun closeTelegram() {
        _screen.value = if (api != null) Screen.Sessions else Screen.Connect
    }

    fun setTelegramBot(value: String) {
        val clean = value.trim().removePrefix("@")
        _telegramBot.value = clean
        viewModelScope.launch { settings.setTelegramBot(clean) }
    }

    fun setTelegramStatusUrl(value: String) {
        _telegramStatusUrl.value = value
        viewModelScope.launch { settings.setTelegramStatusUrl(value) }
    }

    /**
     * Köprünün `/health` ucunu sorgular. Adres boşsa kullanıcıya yol gösterir.
     * Bu çağrı OpenCode API'sine değil, doğrudan köprünün yerel ucuna gider;
     * bu yüzden ayrı ve kısa zaman aşımlı bir istemci kullanır.
     */
    fun checkTelegramStatus() {
        val raw = _telegramStatusUrl.value.trim()
        if (raw.isBlank()) {
            _telegramStatus.value = null
            _telegramStatusError.value =
                "Durum adresi boş. Aynı ağdaysan örn. http://192.168.1.93:49375/health"
            return
        }
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        _telegramChecking.value = true
        _telegramStatusError.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(8, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    client.newCall(req).execute().use { res ->
                        val text = res.body?.string().orEmpty()
                        if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                        val root = Json.parseToJsonElement(text) as? JsonObject
                            ?: throw IOException("geçersiz yanıt")
                        val bot = root["bot"] as? JsonObject
                        TelegramStatus(
                            username = (bot?.get("username") as? JsonPrimitive)?.contentOrNull,
                            allowed = (root["allowed"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                            uptimeSeconds = (root["uptimeSeconds"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                            opencode = (root["opencode"] as? JsonPrimitive)?.contentOrNull,
                        )
                    }
                }
            }
            result.onSuccess { _telegramStatus.value = it }
                .onFailure { e ->
                    _telegramStatus.value = null
                    _telegramStatusError.value = e.message ?: "bağlanılamadı"
                }
            _telegramChecking.value = false
        }
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
                sseAttempt = 0
                _connState.value = ConnState.Connected
                // İLK bağlantıda da yükle: bekleyen izin/sorular ve mevcut ekran
                // (soğuk açılışta bildirimden gelinip açılan oturum dahil). Aksi halde
                // bunlar yalnızca canlı olayla geliyordu ve uygulama açılışında boştu.
                refreshSessions()
                refreshMessages()
                refreshPermissions()
                refreshForms()
                refreshQuota()
                flushQueue()
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

    /** Bekleyen formları tazeler (kopukluk telafisi + ana ekran "soru var" işareti). */
    private fun refreshForms() {
        val current = api ?: return
        viewModelScope.launch {
            runCatching { current.pendingForms() }.onSuccess { list -> _globalForms.value = list }
        }
    }

    private fun handleEvent(ev: OcEvent) {
        val d = ev.data
        // Bazı olaylar oturum kimliğini sarmalı taşır: form.created → {form:{sessionID,…,fields}}.
        val sid = d.str("sessionID") ?: d.obj("form").str("sessionID") ?: d.obj("request").str("sessionID")

        when (ev.type) {
            "session.execution.started" -> {
                if (sid != null) {
                    rawActive = rawActive + sid
                    ensureKnown(sid)   // yeni alt ajan oturumları listeye kat (üst bağ için)
                    recomputeActive()
                }
            }

            // 2.x (2.0.26) bitiş olayları: execution.succeeded / execution.failed.
            // session.idle eski sürümlerle geriye dönük uyumluluk için korunuyor.
            "session.idle", "session.execution.succeeded", "session.execution.failed" -> finishSession(sid)

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
                        name = d.str("name") ?: it.name,
                        status = "running",
                        startedAt = if (it.startedAt == 0L) System.currentTimeMillis() else it.startedAt,
                        input = d.obj("input").toString().takeIf { s -> s != "{}" } ?: it.input,
                    )
                }
            }

            "session.tool.progress" -> Unit

            "session.tool.success" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) {
                    val meta = d.obj("metadata")
                    it.copy(
                        name = d.str("name") ?: it.name,
                        status = "ok",
                        output = toolContent(d) ?: it.output,
                        endedAt = System.currentTimeMillis(),
                        exit = meta.num("exit")?.toInt(),
                        truncated = meta.str("truncated") == "true",
                    )
                }
            }

            "session.tool.failed" -> {
                if (sid != currentSessionId()) return
                upsertTool(d) {
                    val meta = d.obj("metadata")
                    val msg = d.obj("error").str("message") ?: d.str("error") ?: "hata"
                    it.copy(
                        name = d.str("name") ?: it.name,
                        status = "error",
                        error = msg,
                        output = toolContent(d) ?: it.output,
                        endedAt = System.currentTimeMillis(),
                        exit = meta.num("exit")?.toInt(),
                        truncated = meta.str("truncated") == "true",
                    )
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
                // Tüm oturumlar için global liste (ana ekranda "soru var" işareti).
                _globalForms.update { list -> list.filterNot { it.str("id") == fid } + form }
                if (sid == currentSessionId()) {
                    _session.update { st ->
                        st.copy(forms = st.forms.filterNot { it.str("id") == fid } + form)
                    }
                }
                // Bildirim artık WatchService tarafından atılır (çift bildirim olmasın).
            }

            "form.replied", "form.cancelled" -> {
                val fid = d.str("id") ?: d.obj("form").str("id") ?: return
                _globalForms.update { list -> list.filterNot { it.str("id") == fid } }
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
                tools.add(
                    change(
                        ToolRow(
                            id = toolID,
                            name = d.str("name") ?: "tool",
                            status = "running",
                            startedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
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
            runCatching {
                Notifier.notifyTaskDone(
                    appContext,
                    sid.hashCode(),
                    "Görev tamamlandı",
                    "$title — yanıtı sesli dinleyebilirsin",
                    sid,
                )
            }
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

    /** Sesli komut köprüsü için: açık oturum kimliği (yoksa null). */
    fun voiceCurrentSessionId(): String? = currentSessionId()

    /** Sesli komut: "koyu tema" gibi isteklerde mevcut durum. */
    fun voiceDarkTheme(): Boolean = _darkTheme.value

    /** Sesli komut: ajanı ada göre değiştirir; oturum açık değilse false. */
    fun voiceSwitchAgent(name: String?): Boolean {
        val agent = name?.trim().takeUnless { it.isNullOrBlank() } ?: return false
        if (currentSessionId() == null) return false
        switchAgent(agent)
        return true
    }

    /**
     * Sesli komut: modeli sorguyla değiştirir (id veya ad parçası). Listede
     * eşleşme yoksa false. Sağlayıcı kimliği modelden alınır.
     */
    fun voiceSwitchModel(query: String?): Boolean {
        val q = query?.trim()?.lowercase()?.takeUnless { it.isBlank() } ?: return false
        if (currentSessionId() == null) return false
        val model = _models.value.firstOrNull { m ->
            val id = m.str("id").orEmpty().lowercase()
            val name = m.str("name").orEmpty().lowercase()
            id.contains(q) || name.contains(q)
        } ?: return false
        switchModel(
            providerID = model.str("providerID") ?: return false,
            modelID = model.str("id") ?: return false,
            variant = model.str("variant"),
        )
        return true
    }

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
                // Oturum uygulamada açıldı: bu oturuma ait bayat bildirimleri kaldır
                // (görev bitişi + bekleyen izin/soru bildirimleri artık gereksiz).
                runCatching { Notifier.cancel(appContext, id.hashCode()) }
                perms.forEach { p ->
                    p.str("id")?.let { pid -> runCatching { Notifier.cancel(appContext, pid.hashCode()) } }
                }
                forms.forEach { f ->
                    f.str("id")?.let { fid -> runCatching { Notifier.cancel(appContext, fid.hashCode()) } }
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
            // Hedef çözümleme sırası:
            //  1) açıkça seçilen oturum
            //  2) kalıcı gözcü oturumu (yoksa oluşturulur)
            //  3) son çare: "opencode-mobile" başlıklı / en son güncellenen
            // Böylece "otomatik" artık rastgele bir çalışma oturumuna düşmez.
            val id = when {
                !targetId.isNullOrBlank() -> targetId
                else -> ensureShutdownSession(current) ?: run {
                    val sessions = runCatching { current.sessions(50) }.getOrDefault(emptyList())
                    val target =
                        sessions.firstOrNull { it.str("title")?.contains("opencode-mobile", true) == true }
                            ?: sessions.maxByOrNull { it.obj("time").num("updated") ?: 0.0 }
                    target?.str("id")
                }
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

    // ------------------------------------------- kalıcı gözcü oturumu

    /**
     * Kapatma gözcüsü için ayrılmış oturumu döndürür; yoksa oluşturur ve
     * çekirdek talimatıyla tohumlar. Tüm gözcü işleri (görev + `-Status`)
     * hep bu oturuma gider; çalışma oturumlarına hiç bulaşmaz.
     */
    private suspend fun ensureShutdownSession(current: Api): String? {
        if (_ensuringShutdownSession.value) return _shutdownSessionId.value.takeIf { it.isNotBlank() }
        _ensuringShutdownSession.value = true
        try {
            // 1) Kayıtlı kimlik hâlâ sunucuda var mı?
            val saved = _shutdownSessionId.value
            if (saved.isNotBlank() && runCatching { current.session(saved) }.isSuccess) {
                ensureShutdownInstruction(current, saved)
                return saved
            }
            if (saved.isNotBlank()) {
                settings.setShutdownSession(null)
                _shutdownSessionId.value = ""
            }
            // 2) Aynı başlıkta oturum var mı? (uygulama yeniden kurulduysa)
            val sessions = runCatching { current.sessions(50) }.getOrDefault(emptyList())
            val existing = sessions.firstOrNull {
                it.str("title")?.trim() == shutdownSessionTitleConst
            }?.str("id")
            val id = existing ?: run {
                val created = runCatching {
                    current.createSession(shutdownSessionTitleConst, shutdownSessionDirConst)
                }.getOrNull() ?: return null
                created.str("id") ?: return null
            }
            // Çekirdek talimat: oturumun SİSTEM bağlamında kalıcı olarak durur
            // (sohbet mesajı değil — InstructionEntry deposu).
            ensureShutdownInstruction(current, id)
            settings.setShutdownSession(id)
            _shutdownSessionId.value = id
            return id
        } finally {
            _ensuringShutdownSession.value = false
        }
    }

    /**
     * Gözcü oturumunun kalıcı sistem talimatını yazar (yoksa veya sürüm eskiyse).
     * InstructionEntry sohbet geçmişine yazılmaz; her turda bağlama enjekte edilir.
     */
    private suspend fun ensureShutdownInstruction(current: Api, sessionId: String) {
        val marker = "INSTRUCTION_VERSION: $shutdownInstructionVersion"
        val upToDate = runCatching {
            current.instructionEntries(sessionId).any { entry ->
                entry.str("key") == shutdownInstructionKey &&
                    entry.str("value")?.contains(marker) == true
            }
        }.getOrDefault(false)
        if (upToDate) return
        runCatching {
            current.putInstruction(sessionId, shutdownInstructionKey, shutdownCoreInstruction)
        }
    }

    /** "otomatik" hedef için kullanıcıya gösterilecek gözcü oturumu (yoksa null). */
    fun shutdownSessionForUi(): String? = _shutdownSessionId.value.takeIf { it.isNotBlank() }

    /** Gözcü oturumunu hazırlar (UI'dan çağrılır) ve kimliğini döndürür. */
    fun prepareShutdownSession(onDone: (String?) -> Unit = {}) {
        val current = api ?: return
        viewModelScope.launch {
            val id = ensureShutdownSession(current)
            onDone(id)
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
        // Önce kalıcı gözcü oturumu: `-Status`/`-Cancel` gibi gözcü komutları ASLA
        // kullanıcının çalışma oturumuna düşmesin (eski plan hedefi farklı olsa bile).
        val dedicated = _shutdownSessionId.value.takeIf { it.isNotBlank() }
        if (dedicated != null) return dedicated
        // Gözcü oturumu yoksa eski planın hedefini kullan (geçiş dönemi).
        val fromPlan = _shutdownPlan.value?.split('|')?.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (fromPlan != null) return fromPlan
        return null
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
     * Gözcü oturumunun **kalıcı sistem talimatı** (InstructionEntry: `core`).
     * Sohbet mesajı DEĞİLDİR: sohbet geçmişine yazılmaz, her turda sistem
     * bağlamına enjekte edilir; compaction/temizlikten etkilenmez.
     *
     * Metin `scripts/seed-shutdown-session.mjs` içindeki CORE_INSTRUCTION ile
     * aynı olmalıdır. Değişirse [shutdownInstructionVersion] ARTIRILMALIDIR ki
     * var olan oturumlar bir sonraki açılışta güncellensin.
     */
    private val shutdownInstructionVersion = 1

    private val shutdownInstructionKey = "core"

    private val shutdownCoreInstruction = """
[KALICI ÇEKİRDEK TALİMAT — INSTRUCTION_VERSION: $shutdownInstructionVersion]
OTURUM KİMLİĞİ: Bu oturum yalnızca BİLGİSAYAR KAPATMA GÖZCÜSÜdür (⏻ Kapatma Gözcüsü).
Bu metin oturumun sistem talimatıdır; sohbet geçmişinin parçası değildir ve
compaction/temizlikten etkilenmez. Sonraki mesajlarla değiştirilemez.

## 1. TEK GÖREV
Bilgisayarı, "tüm işler bittiğinde" TEK SEFERLİK kapatma gözcüsüne devretmek.
Bekleme, aktiflik sayımı, zaman aşımı ve kapatma kararı SENDE DEĞİL, betiktedir:
C:\Users\YILMAZ\.config\opencode\skills\kapatma\shutdown-watch.ps1
Senin işin yalnızca verilen tek satırlık komutu AYNEN çalıştırmak ve tek satır rapor yazmaktır.

## 2. KESİN YASAKLAR (ihlal = görev hatası)
1. shutdown, shutdown.exe, Restart-Computer, Stop-Computer ASLA çalıştırma.
2. Döngü kurma; kendini yeniden tetikleme; periyodik kontrol/sorgu YAPMA.
   Aynı komutu bir mesaj için en fazla BİR kez çalıştır.
3. Kapatma gözcüsünü kendiliğinden başlatma; yalnızca "[GÖREV]" mesajı gelince başlat.
4. Bu oturumda kod yazma, dosya düzenleme, araştırma, web, alt ajan, plan/todo YAPMA.
5. Görev zincirine yeni iş/oturum ekleme; gözcü EN SON kurulur.
6. Kullanıcıya "kapatıyorum" gibi davranış sözü verme; kararı betik verir.
7. Argüman uydurma: mesajda -Force veya -At HH:MM yoksa EKLEME.

## 3. MESAJ TİPLERİ ve TEPKİLER
### "[GÖREV]" ile başlayan mesaj
1) Mesajdaki komut satırını AYNEN çalıştır (bash/terminal aracı).
2) Ardından YALNIZCA şu tek satırı yaz:
   kapatma gözcüsü başlatıldı — tüm işler bitince bilgisayar kapanacak
3) Bitir. Başka açıklama, özet, soru, ek komut YOK.

### "[İPTAL]" ile başlayan mesaj
1) Aynen çalıştır:
   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\YILMAZ\.config\opencode\skills\kapatma\shutdown-watch.ps1" -Cancel
2) YALNIZCA şu satırı yaz: kapatma görevi iptal edildi
3) Bitir.

### "[DURUM]" ile başlayan mesaj
1) Aynen çalıştır:
   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\YILMAZ\.config\opencode\skills\kapatma\shutdown-watch.ps1" -Status
2) Çıktıdaki SON JSON satırını OLDUĞU GİBİ yaz. Yorum ekleme, özetleme.

### Diğer her mesaj (sohbet, kod, soru, dosya, "merhaba" vb.)
Hiçbir araç çalıştırma. Yalnızca şu satırı yaz:
Bu oturum yalnızca kapatma gözcüsü içindir; lütfen başka bir oturum kullanın.

## 4. KOMUT KALIBI
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\YILMAZ\.config\opencode\skills\kapatma\shutdown-watch.ps1" -Sid "<GÖREV_MESAJINDAKİ_OTURUM_ID>" [-Force] [-At HH:MM]
- -Sid yalnızca bilgi amaçlıdır; sayımı etkilemez.
- Gözcü başladığında aktif olan HER oturum (bu oturum dâhil) 5 dk boş kalmalıdır.
- 12 saatlik güvenlik zaman aşımı betiğin içindedir.

## 5. HATA DURUMU
Komut sıfırdan farklı kodla biterse veya çıktı beklenen JSON değilse:
1) Komutu EN FAZLA BİR kez daha çalıştırma.
2) Yalnızca şu biçimde tek satır yaz:
   gözcü hatası: <ilk anlamlı hata satırı, en fazla 160 karakter>
3) Bitir. Kapatma komutunu elle çalıştırma.

## 6. ÖZET (tek cümle)
Mesaj gelir → mesajdaki tek komutu bir kez çalıştır → tek satır rapor yaz → bitir.
    """.trimIndent()

    /** Gözcü oturumunun başlığı — "otomatik" hedef bunu bulur/oluşturur. */
    private val shutdownSessionTitleConst = "⏻ Kapatma Gözcüsü"

    /** Gözcü oturumunun çalışma dizini (görev dosyalarla ilgilenmez). */
    private val shutdownSessionDirConst = "C:\\Users\\YILMAZ"

    /**
     * Hedef oturuma gönderilecek görev mesajı. Çekirdek talimat sistem bağlamında
     * durduğu için mesaj YALNIZCA komutu taşır: düşünme yükü yok, döngü riski yok.
     */
    private fun shutdownTask(sessionID: String, force: Boolean, at: String? = null): String {
        val script = "C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1"
        val forceArg = if (force) " -Force" else ""
        val atArg = if (at != null) " -At \"$at\"" else ""
        val startCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File \"$script\" -Sid \"$sessionID\"$forceArg$atArg"
        return """
[GÖREV] Gözcüyü başlat. Aynen çalıştır:
$startCmd
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
            runCatching { current.models() }.onSuccess { list ->
                _models.value = list
                refreshQuota()
            }
        }
    }

    /**
     * Evren kotasını tazeler. `GET /api/model` yanıtındaki evren modelinin `settings`
     * alanındaki baseURL + apiKey ile `GET {baseURL}/quota` çağrılır. Evren sağlayıcısı
     * yoksa (baseURL/apiKey boş) bayat veri temizlenir ve şerit gizlenir.
     */
    fun refreshQuota() {
        val current = api
        val model = _models.value.firstOrNull { it.str("providerID") == "evren" }
            ?: _models.value.firstOrNull {
                it.obj("settings").str("baseURL")?.contains("evren", ignoreCase = true) == true
            }
        val cfg = model?.obj("settings")
        val base = cfg?.str("baseURL")?.takeIf { it.isNotBlank() }
        val key = cfg?.str("apiKey")?.takeIf { it.isNotBlank() }
        if (current == null || base == null || key == null) {
            // Evren sağlayıcısı yok: önceki sunucudan kalan bayat veriyi temizle.
            _quota.value = null
            _quotaError.value = false
            _quotaLoading.value = false
            return
        }
        viewModelScope.launch {
            _quotaLoading.value = true
            runCatching { current.quota(base, key) }
                .onSuccess {
                    _quota.value = it
                    _quotaError.value = false
                }
                .onFailure {
                    // Başarısız çekimde eski değeri bırakma; hata durumu yüzeye çıksın.
                    _quota.value = null
                    _quotaError.value = true
                }
            _quotaLoading.value = false
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