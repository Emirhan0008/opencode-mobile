package dev.opencode.mobile.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.opencode.mobile.AppVisibility
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.OcEvent
import dev.opencode.mobile.net.arr
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import dev.opencode.mobile.net.text
import dev.opencode.mobile.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.Call

/**
 * Ön plan servisi: uygulama arka planda/ölüyken bile SSE akışını açık tutar ve
 * bildirimleri TEK kaynaktan atar. ViewModel'den bağımsızdır; bağlantıyı
 * [Settings.connection] akışından kendisi okur.
 *
 * Sürekli (ongoing) bir durum bildirimi gösterir ("bağlı" / "bağlanıyor… (N)" /
 * "çevrimdışı"). İzin istekleri için ise Onayla/Reddet eylem düğmeli bildirim
 * atar; düğmeler servise PendingIntent.getService ile gelir ve burada
 * [Api.replyPermission] çağrılır.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var sseCall: Call? = null

    @Volatile private var api: Api? = null
    private var apiBaseUrl: String? = null
    private var apiToken: String? = null
    private var sseAttempt = 0

    // Bildirim tercihleri (Settings akışlarından canlı okunur). Varsayılan: açık.
    @Volatile private var notifyPermissions = true
    @Volatile private var notifyForms = true
    @Volatile private var notifyWork = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        // Hızlı başlangıç: servis hemen ön plana geçmeli.
        val notification = Notifier.ongoing(this, FOREGROUND_ID, TITLE, "OpenCode — başlatılıyor…")
        startForegroundCompat(notification)
        observeConnection()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Eylem düğmelerinden gelen izin yanıtlarını işle.
        if (intent != null && intent.action == ACTION_PERMISSION_REPLY) {
            val sid = intent.getStringExtra(EXTRA_SESSION_ID)
            val rid = intent.getStringExtra(EXTRA_REQUEST_ID)
            val decision = intent.getStringExtra(EXTRA_DECISION) ?: intent.getStringExtra(EXTRA_ACTION)
            if (sid != null && rid != null && decision != null) {
                scope.launch {
                    // Servis daha yeni başladıysa api hâlâ kurulmamış olabilir;
                    // yanıt sessizce düşmesin diye depodaki bağlantıdan geçici istemci.
                    val target = api ?: run {
                        val conn = Settings(applicationContext).connection.first()
                        conn?.let { Api(it.baseUrl, it.token) }
                    }
                    val ok = target != null && runCatching { target.replyPermission(sid, rid, decision) }.isSuccess
                    // Yalnızca yanıt gerçekten ulaştıysa bildirimi kaldır; aksi halde
                    // dokunulabilir kalsın (yeniden denenebilir).
                    if (ok) runCatching { Notifier.cancel(this@WatchService, rid.hashCode()) }
                }
            }
        }
        // Android öldürürse yeniden başlat ve yeniden bağlan.
        return START_STICKY
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        connectionJob = null
        sseCall?.cancel()
        sseCall = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- bağlantı

    /** Bildirim tercihlerini [Settings] akışlarından izler (VM'den bağımsız). */
    private fun observeSettings() {
        val settings = Settings(applicationContext)
        scope.launch { settings.notifyPermissions.collect { notifyPermissions = it } }
        scope.launch { settings.notifyForms.collect { notifyForms = it } }
        scope.launch { settings.notifyWork.collect { notifyWork = it } }
    }

    /** Bağlantıyı [Settings.connection] akışından izler (VM'den bağımsız). */
    private fun observeConnection() {
        val settings = Settings(applicationContext)
        connectionJob = scope.launch {
            settings.connection.collect { conn ->
                if (conn == null) {
                    teardownSse()
                    api = null
                    apiBaseUrl = null
                    apiToken = null
                    setStatus("OpenCode — çevrimdışı")
                } else {
                    connect(conn.baseUrl, conn.token)
                }
            }
        }
    }

    private fun connect(baseUrl: String, token: String) {
        val url = baseUrl.trimEnd('/')
        val current = api
        // Aynı bağlantı zaten kuruluysa gereksiz yeniden bağlanma (ör. tema
        // değişimi dataStore emit'i tetikler).
        if (current != null && apiBaseUrl == url && apiToken == token) return
        teardownSse()
        api = Api(url, token)
        apiBaseUrl = url
        apiToken = token
        sseAttempt = 0
        subscribeEvents()
    }

    private fun teardownSse() {
        reconnectJob?.cancel()
        reconnectJob = null
        sseCall?.cancel()
        sseCall = null
    }

    /**
     * SSE akışına abone olur. Kopuklukta üstel backoff (2^n sn, en fazla 30) ile
     * yeniden bağlanır. `while(true)` yok; geri-çağrı + gecikmeli coroutine deseni.
     */
    private fun subscribeEvents() {
        reconnectJob?.cancel()
        reconnectJob = null
        sseCall?.cancel()
        val current = api ?: return
        if (sseAttempt == 0) setStatus("OpenCode — bağlanıyor… (1)")
        sseCall = current.events(
            onOpen = {
                sseAttempt = 0
                setStatus("OpenCode — bağlı")
            },
            onEvent = { ev -> handleEvent(ev) },
            onError = {
                if (api !== current) return@events
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    sseAttempt += 1
                    val wait = minOf(1 shl minOf(sseAttempt, 5), 30)   // 2^attempt sn, en fazla 30
                    setStatus("OpenCode — bağlanıyor… ($sseAttempt)")
                    delay(wait * 1000L)
                    if (api === current) subscribeEvents()
                }
            },
        )
    }

    // --------------------------------------------------------- olay işleme

    private fun handleEvent(ev: OcEvent) {
        val d = ev.data
        val sid = d.str("sessionID")
        when (ev.type) {
            // Öndeyken uygulama içi arayüz zaten gösterir — bildirim atma.
            // Ayrıca ilgili tercih kapalıysa hiç bildirme.
            "permission.asked" -> if (notifyPermissions && !AppVisibility.visible) notifyPermission(d, sid)
            // Yanıtlanınca (uygulama içinden olsa bile) bekleyen bildirimi iptal et.
            "permission.replied" -> {
                val rid = d.str("requestID") ?: d.str("id")
                if (rid != null) runCatching { Notifier.cancel(this, rid.hashCode()) }
            }
            "form.created" -> if (notifyForms && !AppVisibility.visible) notifyForm(d, sid)
            "session.idle" -> if (notifyWork && !AppVisibility.visible) notifyFinished(sid, ok = true)
            "session.execution.failed" -> if (notifyWork && !AppVisibility.visible) notifyFinished(sid, ok = false)
            // session.execution.started: BİLDİRME (gürültü).
            else -> Unit
        }
    }

    private fun notifyPermission(d: JsonObject, sid: String?) {
        val req = unwrap(d, "request")
        val rid = req.str("id") ?: return
        val sessionID = sid ?: d.str("sessionID") ?: return
        val action = req.str("action") ?: "izin"
        val res = req.arr("resources").mapNotNull { it.text() }.take(3)
        val text = (listOf(action) + res).joinToString("\n")
        runCatching {
            Notifier.notifyActions(
                this,
                rid.hashCode(),
                "İzin isteği",
                text,
                permissionActions(sessionID, rid),
                sessionId = sessionID,
            )
        }
    }

    private fun notifyForm(d: JsonObject, sid: String?) {
        val form = unwrap(d, "form")
        val fid = form.str("id") ?: return
        val title = form.str("title") ?: "Bir soru yanıtı bekliyor"
        runCatching { Notifier.notify(this, fid.hashCode(), "Soru bekliyor", title, sessionId = sid) }
    }

    /**
     * Oturum bitişi bildirimi. SADECE üst düzey oturumlarda atar (alt ajan
     * oturumları sessiz kalır). Üst düzey tespiti: oturum kaydında `parentID`
     * alanı yok/boş. Ad için `api.session(id)` çağrılır.
     */
    private fun notifyFinished(sid: String?, ok: Boolean) {
        if (sid == null) return
        scope.launch {
            val info = runCatching { api?.session(sid) }.getOrNull()
            val parent = info?.str("parentID")
            if (!parent.isNullOrBlank()) return@launch   // alt ajan — sessiz
            val name = info?.str("title")?.takeIf { it.isNotBlank() } ?: sid.take(8)
            val text = if (ok) "$name işi bitti" else "$name başarısız oldu"
            runCatching {
                Notifier.notify(
                    this@WatchService,
                    sid.hashCode(),
                    if (ok) "Görev tamamlandı" else "Görev başarısız",
                    text,
                    sessionId = sid,
                )
            }
        }
    }

    /** Bazı olaylar nesneyi doğrudan, bazıları sarmalı (`{form: …}` / `{request: …}`) taşır. */
    private fun unwrap(d: JsonObject, key: String): JsonObject =
        if (d.str("id") != null && (key != "form" || d.arr("fields").isNotEmpty())) d else d.obj(key)

    /** İzin bildirimi için Onayla ("once") / Reddet ("reject") eylemleri. */
    private fun permissionActions(sessionID: String, requestID: String): List<NotificationCompat.Action> =
        listOf(
            permissionAction("Onayla", sessionID, requestID, "once"),
            permissionAction("Reddet", sessionID, requestID, "reject"),
        )

    private fun permissionAction(
        label: String,
        sessionID: String,
        requestID: String,
        decision: String,
    ): NotificationCompat.Action {
        val intent = Intent(this, WatchService::class.java).apply {
            action = ACTION_PERMISSION_REPLY
            putExtra(EXTRA_ACTION, decision)
            putExtra(EXTRA_SESSION_ID, sessionID)
            putExtra(EXTRA_REQUEST_ID, requestID)
            putExtra(EXTRA_DECISION, decision)
        }
        val pending = PendingIntent.getService(
            this,
            (requestID + decision).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, label, pending)
    }

    // ------------------------------------------------------------ bildirim

    /** Sürekli durum bildirimini (aynı id) günceller. */
    private fun setStatus(text: String) {
        runCatching { Notifier.ongoing(this, FOREGROUND_ID, TITLE, text) }
    }

    /** SDK sürümüne göre tipli/tipsiz `startForeground`. */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val FOREGROUND_ID = 1001
        private const val TITLE = "OpenCode"

        private const val ACTION_PERMISSION_REPLY = "dev.opencode.mobile.action.PERMISSION_REPLY"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SESSION_ID = "sessionID"
        private const val EXTRA_REQUEST_ID = "requestID"
        private const val EXTRA_DECISION = "decision"

        /** Servisi başlatır (ön plan). Arka plandan başlatma yasağına karşı korumalı. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
            }
        }

        /** Servisi durdurur (istemci bağlantıyı kesince). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WatchService::class.java)) }
        }
    }
}
