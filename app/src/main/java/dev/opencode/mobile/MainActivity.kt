package dev.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.ConnectScreen
import dev.opencode.mobile.ui.OpenCodeTheme
import dev.opencode.mobile.ui.SessionScreen
import dev.opencode.mobile.ui.SessionsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(androidx.compose.ui.platform.LocalContext.current))

    val screen by vm.screen.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val session by vm.session.collectAsState()
    val globalPermissions by vm.globalPermissions.collectAsState()
    var connectError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }

    when (val s = screen) {
        Screen.Connect -> ConnectScreen(
            connecting = connecting,
            error = connectError,
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
            onRefresh = vm::refreshSessions,
            onOpen = vm::openSession,
            onNew = { title, dir ->
                vm.newSession(title, dir) { id -> if (id != null) vm.openSession(id) }
            },
            onDisconnect = vm::disconnect,
        )

        is Screen.Session -> SessionScreen(
            state = session,
            onBack = vm::goBack,
            onSend = vm::sendPrompt,
            onInterrupt = vm::interrupt,
            onPermission = vm::replyPermission,
        )
    }
}