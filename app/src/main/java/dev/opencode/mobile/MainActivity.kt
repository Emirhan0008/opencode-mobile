package dev.opencode.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.net.str
import dev.opencode.mobile.ui.ConnectScreen
import dev.opencode.mobile.ui.OpenCodeTheme
import dev.opencode.mobile.ui.SessionScreen
import dev.opencode.mobile.ui.SessionsScreen
import dev.opencode.mobile.ui.SettingsScreen
import dev.opencode.mobile.ui.TelegramScreen
import dev.opencode.mobile.notify.Notifier

class MainActivity : ComponentActivity() {

    /** Bildirimden gelen oturum kimliği — açıldıktan sonra tüketilir. */
    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 (targetSdk 35) edge-to-edge zorunlu kılar; tüm sürümlerde tutarlı
        // davranış için açıkça etkinleştiriyoruz ve ekranlar inset boşluğu uyguluyor.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        pendingSessionId = intent?.getStringExtra(Notifier.EXTRA_SESSION_ID)
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(LocalContext.current))
            val dark by vm.darkTheme.collectAsState()
            OpenCodeTheme(dark = dark) {
                Root(vm, dark, pendingSessionId) { pendingSessionId = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.visible = false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001,
            )
        }
    }

    /**
     * Sesli komut açıldığında gerekli çalışma zamanı izinleri. Ekran kilitliyken
     * mikrofon ve kulaklık tuşu için ikisi de zorunlu; verilmezse servis kayıt
     * açamaz ve kulaklık tuşu sessizce çalışmaz.
     */
    fun requestVoicePermissions() {
        val wanted = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 1002)
        }
    }
}

@Composable
private fun Root(
    vm: AppViewModel,
    dark: Boolean,
    openSessionId: String?,
    onOpenSessionConsumed: () -> Unit,
) {
    val screen by vm.screen.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val session by vm.session.collectAsState()
    val agents by vm.agents.collectAsState()
    val models by vm.models.collectAsState()
    val commands by vm.commands.collectAsState()
    val globalPermissions by vm.globalPermissions.collectAsState()
    val globalForms by vm.globalForms.collectAsState()
    // Yalnız listede görünen oturumlara ait bekleyen soruları say: silinmiş oturumların
    // sunucuda kalan artık formları rozeti şişirmesin (rozet ↔ satır işareti tutarlı olsun).
    val questionSessions = remember(globalForms, sessions.sessions) {
        val ids = sessions.sessions.mapNotNull { it.str("id") }.toSet()
        globalForms.mapNotNull { it.str("sessionID") }.filter { it in ids }.toSet()
    }
    val shutdownPlan by vm.shutdownPlan.collectAsState()
    val watchStatus by vm.watchStatus.collectAsState()
    val shutdownSessionId by vm.shutdownSessionId.collectAsState()
    val telegramBot by vm.telegramBot.collectAsState()
    val telegramStatusUrl by vm.telegramStatusUrl.collectAsState()
    val telegramStatus by vm.telegramStatus.collectAsState()
    val telegramStatusError by vm.telegramStatusError.collectAsState()
    val telegramChecking by vm.telegramChecking.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val connState by vm.connState.collectAsState()
    val pendingActions by vm.pendingActions.collectAsState()
    val backgroundService by vm.backgroundService.collectAsState()
    val notifyPermissions by vm.notifyPermissions.collectAsState()
    val notifyForms by vm.notifyForms.collectAsState()
    val notifyWork by vm.notifyWork.collectAsState()
    val quota by vm.quota.collectAsState()
    val quotaError by vm.quotaError.collectAsState()
    val quotaLoading by vm.quotaLoading.collectAsState()
    val quotaBadge by vm.quotaBadge.collectAsState()
    val voiceEnabled by vm.voiceEnabled.collectAsState()
    val voiceConfirm by vm.voiceConfirm.collectAsState()
    val voiceAutoSummary by vm.voiceAutoSummary.collectAsState()
    val voiceName by vm.voiceName.collectAsState()
    val evrenBaseUrl by vm.evrenBaseUrl.collectAsState()
    val evrenAsrSet by vm.evrenAsrSet.collectAsState()
    val evrenTtsSet by vm.evrenTtsSet.collectAsState()
    val evrenSumSet by vm.evrenSumSet.collectAsState()
    val voiceTestResult by vm.voiceTestResult.collectAsState()
    val voiceTesting by vm.voiceTesting.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(voiceEnabled) {
        if (voiceEnabled) (activity as? MainActivity)?.requestVoicePermissions()
    }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    // Edge-to-edge: sistem çubuğu ikonları uygulama temasına uysun.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // Sistem geri tuşu: oturum ve ayarlar ekranlarından oturum listesine dön.
    BackHandler(enabled = screen !is Screen.Sessions) {
        when (screen) {
            Screen.Settings -> vm.closeSettings()
            Screen.Telegram -> vm.closeTelegram()
            is Screen.Session -> vm.goBack()
            else -> Unit
        }
    }

    // Bildirimden gelen oturum isteği (kolaylık) — varsa aç ve tüket.
    LaunchedEffect(openSessionId) {
        if (openSessionId != null) {
            vm.openSession(openSessionId)
            onOpenSessionConsumed()
        }
    }

    when (val s = screen) {
        Screen.Connect -> ConnectScreen(
            connecting = connecting,
            error = connectError,
            profiles = profiles,
            activeProfileId = activeProfileId,
            onSelectProfile = vm::selectProfile,
            onDeleteProfile = vm::deleteProfile,
            onConnectCode = { baseUrl, codeOrUrl ->
                connecting = true
                connectError = null
                vm.connectWithCode(baseUrl, codeOrUrl) { err ->
                    connecting = false
                    connectError = err
                }
            },
            onConnectPassword = { baseUrl, password ->
                connecting = true
                connectError = null
                vm.connectWithPassword(baseUrl, password) { err ->
                    connecting = false
                    connectError = err
                }
            },
            onOpenTelegram = vm::openTelegram,
        )

        Screen.Sessions -> SessionsScreen(
            state = sessions,
            permissionCount = globalPermissions.size,
            questionCount = questionSessions.size,
            questionSessions = questionSessions,
            darkTheme = dark,
            onToggleTheme = { vm.setDarkTheme(!dark) },
            onRefresh = vm::refreshSessions,
            onOpen = vm::openSession,
            onNew = { title, dir ->
                vm.newSession(title, dir) { id -> if (id != null) vm.openSession(id) }
            },
            onDisconnect = vm::disconnect,
            onDelete = vm::deleteSession,
            onRename = vm::renameSession,
            onFork = vm::forkSession,
            shutdownArmed = shutdownPlan != null,
            onArmShutdown = vm::armShutdown,
            onCancelShutdown = vm::cancelShutdown,
            shutdownAt = shutdownPlan?.split('|')?.getOrNull(2)?.takeIf { it != "-" },
            watchStatus = watchStatus,
            shutdownSessionId = shutdownSessionId,
            onPrepareShutdownSession = { vm.prepareShutdownSession() },
            onRefreshWatch = vm::refreshShutdownStatus,
            connState = connState,
            onReconnect = vm::reconnectNow,
            pendingActions = pendingActions,
            onRemoveQueued = vm::removeFromQueue,
            onFlushQueue = vm::flushQueue,
            onOpenSettings = vm::openSettings,
            onOpenTelegram = vm::openTelegram,
            quota = quota,
            quotaError = quotaError,
            quotaLoading = quotaLoading,
            quotaBadge = quotaBadge,
            onRefreshQuota = { vm.refreshQuota() },
        )

        Screen.Settings -> SettingsScreen(
            backgroundService = backgroundService,
            notifyPermissions = notifyPermissions,
            notifyForms = notifyForms,
            notifyWork = notifyWork,
            quotaBadge = quotaBadge,
            darkTheme = dark,
            onToggleBackground = vm::setBackgroundService,
            onTogglePermissions = vm::setNotifyPermissions,
            onToggleForms = vm::setNotifyForms,
            onToggleWork = vm::setNotifyWork,
            onToggleQuotaBadge = { vm.setQuotaBadge(it) },
            onToggleTheme = { vm.setDarkTheme(!dark) },
            onOpenTelegram = vm::openTelegram,
            voiceEnabled = voiceEnabled,
            voiceConfirm = voiceConfirm,
            voiceAutoSummary = voiceAutoSummary,
            voiceName = voiceName,
            evrenBaseUrl = evrenBaseUrl,
            evrenAsrSet = evrenAsrSet,
            evrenTtsSet = evrenTtsSet,
            evrenSumSet = evrenSumSet,
            voiceTestResult = voiceTestResult,
            voiceTesting = voiceTesting,
            onToggleVoice = vm::setVoiceEnabled,
            onToggleVoiceConfirm = vm::setVoiceConfirm,
            onToggleVoiceAutoSummary = vm::setVoiceAutoSummary,
            onChangeVoiceName = vm::setVoiceName,
            onChangeEvrenBase = vm::setEvrenBaseUrl,
            onChangeAsrKey = vm::setEvrenAsrKey,
            onChangeTtsKey = vm::setEvrenTtsKey,
            onChangeSumKey = vm::setEvrenSumKey,
            onTestVoice = vm::testVoiceConnection,
            onBack = vm::closeSettings,
        )

        Screen.Telegram -> TelegramScreen(
            botUsername = telegramBot,
            statusUrl = telegramStatusUrl,
            status = telegramStatus,
            error = telegramStatusError,
            checking = telegramChecking,
            onChangeBot = vm::setTelegramBot,
            onChangeStatusUrl = vm::setTelegramStatusUrl,
            onCheck = vm::checkTelegramStatus,
            onBack = vm::closeTelegram,
        )

        is Screen.Session -> SessionScreen(
            state = session,
            agents = agents,
            models = models,
            onBack = vm::goBack,
            onSend = vm::sendPrompt,
            onInterrupt = vm::interrupt,
            onPermission = vm::replyPermission,
            onSwitchAgent = vm::switchAgent,
            onSwitchModel = { providerID, modelID, variant ->
                vm.switchModel(providerID, modelID, variant)
            },
            onReplyForm = vm::replyForm,
            onCancelForm = vm::cancelForm,
            onRevert = vm::revertToMessage,
            onSwipe = vm::switchRelative,
            commands = commands,
            onRunCommand = vm::runCommand,
        )
    }
}