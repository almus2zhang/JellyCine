package com.jellycine.app.ui.screens.startup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jellycine.app.ui.screens.auth.ServerSwitchDialogsHost
import com.jellycine.app.ui.screens.auth.ServerSwitchViewModel
import com.jellycine.app.ui.screens.auth.rememberServerSwitchDialogsState
import com.jellycine.auth.AuthStateManager
import com.jellycine.data.repository.AuthRepositoryProvider
import com.jellycine.shared.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class ConnectionStatus {
    CONNECTING,
    SUCCESS,
    FAILED
}

@Composable
fun StartupConnectingScreen(
    onConnected: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToDownloads: () -> Unit
) {
    val context = LocalContext.current
    val authStateManager = remember { AuthStateManager.getInstance(context) }
    val authRepository = remember { AuthRepositoryProvider.getInstance(context) }
    val serverSwitchViewModel: ServerSwitchViewModel = viewModel {
        ServerSwitchViewModel(context.applicationContext as android.app.Application)
    }

    val sessionSnapshot by authRepository.observeActiveSession().collectAsState(
        initial = authRepository.getActiveSessionSnapshot()
    )
    val serverSwitchUiState by serverSwitchViewModel.uiState.collectAsState()
    val serverSwitchDialogsState = rememberServerSwitchDialogsState()

    var status by remember { mutableStateOf(ConnectionStatus.CONNECTING) }
    var statusMessage by remember { mutableStateOf("") }
    var connectionAttempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val activeSavedServer = remember(sessionSnapshot.savedServers, sessionSnapshot.activeServerId) {
        sessionSnapshot.savedServers.firstOrNull { it.id == sessionSnapshot.activeServerId }
            ?: sessionSnapshot.savedServers.firstOrNull()
    }

    val serverDisplayName = remember(activeSavedServer, sessionSnapshot) {
        activeSavedServer?.displayName?.takeIf { it.isNotBlank() }
            ?: sessionSnapshot.serverName?.takeIf { it.isNotBlank() }
            ?: activeSavedServer?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: activeSavedServer?.serverUrl
            ?: sessionSnapshot.serverUrl
            ?: "Media Server"
    }

    val rawUrl = activeSavedServer?.sourceUrl ?: activeSavedServer?.serverUrl ?: sessionSnapshot.serverUrl.orEmpty()
    val is301 = rawUrl.startsWith("301:", ignoreCase = true)
    val isTxt = rawUrl.startsWith("txt:", ignoreCase = true)

    val modeBadgeText = when {
        is301 -> stringResource(R.string.startup_mode_301)
        isTxt -> stringResource(R.string.startup_mode_txt)
        else -> stringResource(R.string.startup_mode_direct)
    }

    val defaultConnectingText = stringResource(R.string.startup_connecting_subtitle)
    val resolvingText = stringResource(R.string.startup_resolving_address)
    val successText = stringResource(R.string.startup_connection_success)
    val failedText = stringResource(R.string.startup_connection_failed)

    // Connection attempt loop
    LaunchedEffect(connectionAttempt, activeSavedServer?.id) {
        if (sessionSnapshot.savedServers.isEmpty()) {
            onNavigateToAuth()
            return@LaunchedEffect
        }

        status = ConnectionStatus.CONNECTING
        statusMessage = if (is301 || isTxt) resolvingText else defaultConnectingText

        val connected = withTimeoutOrNull(10000L) {
            try {
                authStateManager.checkAuthenticationState()
            } catch (_: Exception) {
                false
            }
        } ?: false

        if (connected) {
            status = ConnectionStatus.SUCCESS
            statusMessage = successText
            delay(350)
            onConnected()
        } else {
            status = ConnectionStatus.FAILED
            statusMessage = failedText
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Logo
            Image(
                painter = painterResource(com.jellycine.shared.R.drawable.ic_splash_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Server Card
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141418),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222228)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = Color(0xFF22D3EE),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = serverDisplayName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Mode Tag
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    is301 -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                                    isTxt -> Color(0xFFA855F7).copy(alpha = 0.2f)
                                    else -> Color(0xFF10B981).copy(alpha = 0.2f)
                                }
                            ) {
                                Text(
                                    text = modeBadgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        is301 -> Color(0xFFFBBF24)
                                        isTxt -> Color(0xFFC084FC)
                                        else -> Color(0xFF34D399)
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            if (rawUrl.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = rawUrl.removePrefix("301:").removePrefix("txt:"),
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Status indicator & text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                when (status) {
                    ConnectionStatus.CONNECTING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF22D3EE),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    ConnectionStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    ConnectionStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                }

                Text(
                    text = statusMessage,
                    color = when (status) {
                        ConnectionStatus.CONNECTING -> Color.White.copy(alpha = 0.85f)
                        ConnectionStatus.SUCCESS -> Color(0xFF34D399)
                        ConnectionStatus.FAILED -> Color(0xFFF87171)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(0.92f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch server button - ALWAYS visible!
                OutlinedButton(
                    onClick = {
                        serverSwitchDialogsState.openServers()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1A1A20),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = Color(0xFF22D3EE)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.startup_switch_server),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // If failed, show Retry button
                if (status == ConnectionStatus.FAILED) {
                    Button(
                        onClick = {
                            connectionAttempt++
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.startup_retry),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Offline Downloads shortcut if in error state
            if (status == ConnectionStatus.FAILED) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onNavigateToDownloads,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.startup_offline_downloads),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Server Switch Dialogs Host
        ServerSwitchDialogsHost(
            state = serverSwitchDialogsState,
            savedServers = sessionSnapshot.savedServers,
            activeServerId = sessionSnapshot.activeServerId,
            currentServerName = sessionSnapshot.serverName,
            currentServerUrl = sessionSnapshot.serverUrl,
            isSwitching = serverSwitchUiState.isSwitching,
            onAddServer = {
                onNavigateToAuth()
            },
            onAddUser = { serverUrl, serverName ->
                onNavigateToAuth()
            },
            onServerSelected = { selectedServer, onDismiss ->
                serverSwitchViewModel.switchServer(
                    serverId = selectedServer.id,
                    activeServerId = sessionSnapshot.activeServerId,
                    onSwitchComplete = {
                        onDismiss()
                        connectionAttempt++
                    },
                    onSwitchFailed = {
                        onDismiss()
                        status = ConnectionStatus.FAILED
                        statusMessage = failedText
                    }
                )
            },
            onRenameServer = { serverId, newName ->
                serverSwitchViewModel.renameServer(serverId, newName)
            },
            showRemoveAction = true
        )
    }
}
