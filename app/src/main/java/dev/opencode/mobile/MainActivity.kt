package dev.opencode.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.ConnectScreen
import dev.opencode.mobile.ui.OpenCodeTheme
import dev.opencode.mobile.ui.SessionScreen
import dev.opencode.mobile.ui.SessionsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(LocalContext.current))
            val dark by vm.darkTheme.collectAsState()
            OpenCodeTheme(dark = dark) {
                Root(vm, dark)
            }
        }
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
}

@Composable
private fun Root(vm: AppViewModel, dark: Boolean) {
    val screen by vm.screen.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val session by vm.session.collectAsState()
    val agents by vm.agents.collectAsState()
    val models by vm.models.collectAsState()
    val commands by vm.commands.collectAsState()
    val globalPermissions by vm.globalPermissions.collectAsState()
    val shutdownPlan by vm.shutdownPlan.collectAsState()
    val watchStatus by vm.watchStatus.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val connState by vm.connState.collectAsState()
    val pendingActions by vm.pendingActions.collectAsState()
    var connectError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

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
        )

        Screen.Sessions -> SessionsScreen(
            state = sessions,
            permissionCount = globalPermissions.size,
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
            onRefreshWatch = vm::refreshShutdownStatus,
            connState = connState,
            onReconnect = vm::reconnectNow,
            pendingActions = pendingActions,
            onRemoveQueued = vm::removeFromQueue,
            onFlushQueue = vm::flushQueue,
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