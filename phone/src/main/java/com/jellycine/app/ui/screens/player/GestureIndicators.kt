package com.jellycine.app.ui.screens.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellycine.player.core.PlayerConstants.GESTURE_INDICATOR_PADDING_DP
import com.jellycine.player.core.PlayerState
import java.util.Locale
import kotlin.math.abs

enum class SeekSide {
    LEFT, CENTER, RIGHT
}

data class SlideSeekState(
    val targetPositionMs: Long,
    val deltaMs: Long,
    val currentPositionMs: Long,
    val durationMs: Long
)

@Composable
fun GestureIndicators(
    modifier: Modifier = Modifier,
    volumeLevel: Float? = null,
    brightnessLevel: Float? = null,
    seekPosition: String? = null,
    seekSide: SeekSide = SeekSide.CENTER,
    zoomScale: Float? = null,
    slideSeekState: SlideSeekState? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Volume indicator (right side)
        AnimatedVisibility(
            visible = volumeLevel != null,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 2 }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            volumeLevel?.let { level ->
                VolumeIndicator(
                    level = level,
                    modifier = Modifier.padding(end = GESTURE_INDICATOR_PADDING_DP.dp)
                )
            }
        }

        // Brightness indicator (left side)
        AnimatedVisibility(
            visible = brightnessLevel != null,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it / 2 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 2 }),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            brightnessLevel?.let { level ->
                BrightnessIndicator(
                    level = level,
                    modifier = Modifier.padding(start = GESTURE_INDICATOR_PADDING_DP.dp)
                )
            }
        }

        // Seek indicator (positioned based on seekSide)
        AnimatedVisibility(
            visible = seekPosition != null && slideSeekState == null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(
                when (seekSide) {
                    SeekSide.LEFT -> Alignment.CenterStart
                    SeekSide.CENTER -> Alignment.Center
                    SeekSide.RIGHT -> Alignment.CenterEnd
                }
            )
        ) {
            seekPosition?.let { position ->
                SeekIndicator(
                    position = position,
                    modifier = Modifier.padding(
                        start = if (seekSide == SeekSide.LEFT) GESTURE_INDICATOR_PADDING_DP.dp else 0.dp,
                        end = if (seekSide == SeekSide.RIGHT) GESTURE_INDICATOR_PADDING_DP.dp else 0.dp
                    )
                )
            }
        }

        // Slide seek indicator (center of screen)
        AnimatedVisibility(
            visible = slideSeekState != null,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.88f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            slideSeekState?.let { state ->
                SlideSeekIndicator(slideSeekState = state)
            }
        }

        // Zoom indicator (center)
        AnimatedVisibility(
            visible = zoomScale != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            zoomScale?.let { scaleValue ->
                ZoomIndicator(scale = scaleValue)
            }
        }
    }
}

@Composable
private fun ZoomIndicator(
    scale: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.ZoomIn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "${(scale * 100).toInt()}%",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VolumeIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    val volumeIcon = when {
        level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
        level <= 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

    GestureIndicatorCard(
        icon = volumeIcon,
        value = "${(level * 100).toInt()}%",
        progress = level,
        modifier = modifier
    )
}

@Composable
private fun BrightnessIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    val brightnessIcon = when {
        level <= 0.3f -> Icons.Filled.BrightnessLow
        level <= 0.7f -> Icons.Filled.BrightnessMedium
        else -> Icons.Filled.BrightnessHigh
    }

    GestureIndicatorCard(
        icon = brightnessIcon,
        value = "${(level * 100).toInt()}%",
        progress = level,
        modifier = modifier
    )
}

@Composable
private fun SeekIndicator(
    position: String,
    modifier: Modifier = Modifier
) {
    // No background - transparent like volume/brightness indicators
    Row(
        modifier = modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Show different icon based on seek direction
        val icon = if (position.startsWith("+")) {
            Icons.Filled.FastForward
        } else {
            Icons.Filled.FastRewind
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = position,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SlideSeekIndicator(
    slideSeekState: SlideSeekState,
    modifier: Modifier = Modifier
) {
    val isForward = slideSeekState.deltaMs >= 0
    val absDeltaSec = abs(slideSeekState.deltaMs) / 1000
    val deltaMinutes = absDeltaSec / 60
    val deltaRemainingSec = absDeltaSec % 60
    val deltaText = if (deltaMinutes > 0) {
        String.format(Locale.US, "%s%d:%02d", if (isForward) "+" else "-", deltaMinutes, deltaRemainingSec)
    } else {
        String.format(Locale.US, "%s%ds", if (isForward) "+" else "-", deltaRemainingSec)
    }

    val targetTimeText = formatTime(slideSeekState.targetPositionMs)
    val totalDurationText = formatTime(slideSeekState.durationMs.coerceAtLeast(0L))
    val progress = if (slideSeekState.durationMs > 0L) {
        (slideSeekState.targetPositionMs.toFloat() / slideSeekState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.85f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Delta time with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = if (isForward) Color(0xFF22D3EE) else Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = deltaText,
                    color = if (isForward) Color(0xFF22D3EE) else Color(0xFFFF9800),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Target time / Total duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = targetTimeText,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = " / ",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = totalDurationText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Sleek mini progress bar
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(
                            if (isForward) Color(0xFF22D3EE) else Color(0xFFFF9800),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun GestureIndicatorCard(
    icon: ImageVector,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp)
        )

        Box(
            modifier = Modifier
                .width(6.dp)
                .height(120.dp)
                .background(
                    Color.White.copy(alpha = 0.3f),
                    RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(progress)
                    .align(Alignment.BottomCenter)
                    .background(
                        Color.White,
                        RoundedCornerShape(3.dp)
                    )
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun RippleAnimation(
    isVisible: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ripple_scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ripple_alpha"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .alpha(alpha)
                .background(
                    Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(60.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(48.dp)
                    .alpha(1f)
            )
        }
    }
}
