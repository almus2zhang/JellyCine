package com.jellycine.app.ui.screens.player

import android.annotation.SuppressLint
import android.media.AudioManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.jellycine.app.player.mpv.MpvPlayerController

@SuppressLint("ClickableViewAccessibility")
@UnstableApi
@Composable
fun MpvVideoSurface(
    player: MpvPlayerController,
    lifecycle: Lifecycle.Event,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    resizeMode: Int,
    audioManager: AudioManager,
    @Suppress("UNUSED_PARAMETER") isHdr: Boolean,
    onToggleControls: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    getCurrentVolumeLevel: () -> Float,
    getCurrentBrightnessLevel: () -> Float,
    onZoomChange: (Boolean) -> Unit,
    onTransform: (scaleMultiplier: Float, deltaX: Float, deltaY: Float) -> Unit,
    onTransformEnd: () -> Unit,
    onResetTransform: () -> Unit,
    onTogglePlayPause: () -> Unit,
    getCurrentPosition: () -> Long = { 0L },
    getDuration: () -> Long = { 0L },
    onSlideSeek: (targetPositionMs: Long, deltaMs: Long, currentPositionMs: Long, durationMs: Long) -> Unit = { _, _, _, _ -> },
    onSlideSeekEnd: (targetPositionMs: Long) -> Unit = {},
    onSlideSeekCancel: () -> Unit = {},
    modifier: Modifier
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val gestureHelper = GestureHelper(
                    context = context,
                    touchView = this,
                    audioManager = audioManager,
                    onShowControls = onToggleControls,
                    onSeek = onSeek,
                    onVolumeChange = onVolumeChange,
                    onBrightnessChange = onBrightnessChange,
                    getCurrentVolumeLevel = getCurrentVolumeLevel,
                    getCurrentBrightnessLevel = getCurrentBrightnessLevel,
                    onZoomChange = onZoomChange,
                    onTogglePlayPause = onTogglePlayPause,
                    onTransform = onTransform,
                    onTransformEnd = onTransformEnd,
                    onResetTransform = onResetTransform,
                    getCurrentPosition = getCurrentPosition,
                    getDuration = getDuration,
                    onSlideSeek = onSlideSeek,
                    onSlideSeekEnd = onSlideSeekEnd,
                    onSlideSeekCancel = onSlideSeekCancel
                )
                setOnTouchListener { _, event -> gestureHelper.handleTouchEvent(event) }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val frame = holder.surfaceFrame
                        player.attachSurface(
                            surface = holder.surface,
                            width = frame.width(),
                            height = frame.height()
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        player.resizeSurface(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        val activity = context as? android.app.Activity
                        val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            activity?.isInPictureInPictureMode == true
                        } else false
                        if (!isInPip) {
                            player.pause()
                        }
                        player.detachSurface()
                    }
                })
            }
        },
        update = {
            player.applySubtitlePreferences()
            player.setZoomMode(resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            player.setVideoTransform(scale, offsetX, offsetY)
            val activity = it.context as? android.app.Activity
            val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true
            } else false
            if ((lifecycle == Lifecycle.Event.ON_PAUSE || lifecycle == Lifecycle.Event.ON_STOP) && !isInPip) {
                player.pause()
            }
        },
        modifier = modifier
    )
}
