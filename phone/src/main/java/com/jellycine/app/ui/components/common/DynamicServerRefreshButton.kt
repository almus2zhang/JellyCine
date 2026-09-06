package com.jellycine.app.ui.components.common

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jellycine.data.repository.AuthRepositoryProvider
import com.jellycine.shared.R
import com.jellycine.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.launch

@Composable
fun DynamicServerRefreshButton(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    onRefreshed: ((newUrl: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val authRepository = remember { AuthRepositoryProvider.getInstance(context) }
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_angle"
    )

    val iconSize = if (size <= 34.dp) 20.dp else 24.dp

    Box(
        modifier = modifier
            .size(size)
            .clickable(enabled = !isRefreshing) {
                isRefreshing = true
                Toast.makeText(
                    context,
                    context.getString(R.string.dynamic_server_refreshing),
                    Toast.LENGTH_SHORT
                ).show()

                scope.launch {
                    try {
                        val result = authRepository.refreshActive302Session(force = true)
                        if (result.isSuccess) {
                            val newUrl = result.getOrNull().orEmpty()
                            Toast.makeText(
                                context,
                                context.getString(R.string.dynamic_server_refresh_success, newUrl),
                                Toast.LENGTH_SHORT
                            ).show()
                            UserDataRefreshSignals.notifyUserDataChanged(null)
                            onRefreshed?.invoke(newUrl)
                        } else {
                            val errorMsg = result.exceptionOrNull()?.message.orEmpty()
                            Toast.makeText(
                                context,
                                context.getString(R.string.dynamic_server_refresh_failed, errorMsg),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.dynamic_server_refresh_failed, e.message.orEmpty()),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isRefreshing = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = stringResource(id = R.string.dynamic_server_refresh_target_address),
            tint = if (isRefreshing) Color(0xFF22D3EE) else Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .size(iconSize)
                .rotate(if (isRefreshing) rotationAngle else 0f)
        )
    }
}
