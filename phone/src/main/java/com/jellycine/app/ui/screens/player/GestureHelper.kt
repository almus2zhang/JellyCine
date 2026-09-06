package com.jellycine.app.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.media3.common.Player
import kotlin.math.abs

import com.jellycine.player.core.PlayerConstants.GESTURE_EXCLUSION_AREA_HORIZONTAL
import com.jellycine.player.core.PlayerConstants.GESTURE_EXCLUSION_AREA_VERTICAL
import com.jellycine.player.core.PlayerConstants.FULL_SWIPE_RANGE_SCREEN_RATIO
import com.jellycine.player.core.PlayerConstants.ZOOM_SCALE_BASE
import com.jellycine.player.core.PlayerConstants.ZOOM_SCALE_THRESHOLD
import com.jellycine.player.preferences.PlayerPreferences

class GestureHelper(
    private val context: Context,
    private val touchView: View,
    private val audioManager: AudioManager,
    private val onShowControls: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onVolumeChange: (Float) -> Unit,
    private val onBrightnessChange: (Float) -> Unit,
    private val getCurrentVolumeLevel: () -> Float,
    private val getCurrentBrightnessLevel: () -> Float,
    private val onZoomChange: (Boolean) -> Unit,
    private val onTogglePlayPause: () -> Unit = {},
    private val getPlayer: () -> Player? = { null },
    private val onTransform: (scaleMultiplier: Float, deltaX: Float, deltaY: Float) -> Unit = { _, _, _ -> },
    private val onTransformEnd: () -> Unit = {},
    private val onResetTransform: () -> Unit = {},
    private val getCurrentPosition: () -> Long = { 0L },
    private val getDuration: () -> Long = { 0L },
    private val onSlideSeek: (targetPositionMs: Long, deltaMs: Long, currentPositionMs: Long, durationMs: Long) -> Unit = { _, _, _, _ -> },
    private val onSlideSeekEnd: (targetPositionMs: Long) -> Unit = {},
    private val onSlideSeekCancel: () -> Unit = {},
    private val onLongPressSpeedStart: (speed: Float) -> Unit = {},
    private val onLongPressSpeedEnd: () -> Unit = {}
) {
    private val playerPreferences = PlayerPreferences(context)
    // Gesture state tracking
    private var swipeGestureValueTrackerVolume = -1f
    private var swipeGestureValueTrackerBrightness = -1f
    private var swipeGestureValueTrackerProgress = 0L
    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false
    private var isSlideSeeking = false
    private var isLongPressingSpeed = false
    private var slideSeekStartPositionMs = 0L
    private var slideSeekDurationMs = 0L
    private var slideSeekTargetPositionMs = 0L
    private var lastScaleEvent: Long = 0
    private var currentNumberOfPointers: Int = 0
    private var isZoomEnabled = false
    private var screenWidth = 0
    private var screenHeight = 0

    // Two-finger free transform state
    private var prevSpan = 0f
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    private var isTransforming = false
    private var twoFingerDownTime = 0L
    private var twoFingerMoved = false
    private var lastTwoFingerTapTime = 0L

    // Constants

    private fun seekBackwardDeltaMs(): Long {
        return playerPreferences.getSeekBackwardIntervalSeconds() * 1000L
    }

    private fun seekForwardDeltaMs(): Long {
        return playerPreferences.getSeekForwardIntervalSeconds() * 1000L
    }

    // Single tap and double tap detector
    private val tapGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLongPressingSpeed) return false
                onShowControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLongPressingSpeed) return false
                if (!playerPreferences.arePlayerGesturesEnabled()) return false

                val viewWidth = touchView.measuredWidth
                val areaWidth = viewWidth / 5 // Divide into 5 parts: 2:1:2
                
                val leftmostAreaStart = 0
                val middleAreaStart = areaWidth * 2
                val rightmostAreaStart = middleAreaStart + areaWidth

                when (e.x.toInt()) {
                    in leftmostAreaStart until middleAreaStart -> {
                        if (!playerPreferences.isProgressSeekGestureEnabled()) return false
                        onSeek(-seekBackwardDeltaMs())
                    }
                    in middleAreaStart until rightmostAreaStart -> {
                        val player = getPlayer()
                        if (player != null) {
                            player.playWhenReady = !player.playWhenReady
                        } else {
                            onTogglePlayPause()
                        }
                    }
                    in rightmostAreaStart until viewWidth -> {
                        if (!playerPreferences.isProgressSeekGestureEnabled()) return false
                        onSeek(seekForwardDeltaMs())
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isLongPressSpeedGestureEnabled()
                ) {
                    return
                }
                if (inExclusionArea(e)) return
                if (swipeGestureProgressOpen || swipeGestureVolumeOpen || swipeGestureBrightnessOpen || isTransforming) {
                    return
                }

                isLongPressingSpeed = true
                touchView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val speed = playerPreferences.getLongPressPlaybackSpeed()
                onLongPressSpeedStart(speed)
            }
        }
    )

    private val seekGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null || isLongPressingSpeed) return false
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isProgressSeekGestureEnabled()
                ) {
                    return false
                }
                if (inExclusionArea(firstEvent)) return false

                // Check if touch begins in the vertically middle area of the screen (e.g. 18% .. 82%)
                val isMiddleVertical = if (screenHeight > 0) {
                    firstEvent.y in (screenHeight * 0.18f)..(screenHeight * 0.82f)
                } else true

                if (!isMiddleVertical && !swipeGestureProgressOpen) return false

                // Check if swipe is horizontal
                if (abs(distanceX) > abs(distanceY)) {
                    val deltaX = currentEvent.x - firstEvent.x
                    val swipeThreshold = 30f
                    if ((abs(deltaX) > swipeThreshold || swipeGestureProgressOpen) &&
                        !swipeGestureBrightnessOpen && !swipeGestureVolumeOpen &&
                        (SystemClock.elapsedRealtime() - lastScaleEvent) > 200
                    ) {
                        if (!swipeGestureProgressOpen) {
                            swipeGestureProgressOpen = true
                            isSlideSeeking = true
                            slideSeekStartPositionMs = getCurrentPosition()
                            slideSeekDurationMs = getDuration()
                        }

                        val maxSeekSpanMs = playerPreferences.getSlideSeekDurationSeconds() * 1000L
                        val viewWidth = if (touchView.measuredWidth > 0) touchView.measuredWidth else screenWidth
                        val progressRatio = if (viewWidth > 0) deltaX / viewWidth else 0f
                        val deltaMs = (progressRatio * maxSeekSpanMs).toLong()
                        val duration = if (slideSeekDurationMs > 0L) slideSeekDurationMs else getDuration()
                        if (slideSeekDurationMs <= 0L && duration > 0L) {
                            slideSeekDurationMs = duration
                        }
                        val targetMs = if (duration > 0L) {
                            (slideSeekStartPositionMs + deltaMs).coerceIn(0L, duration)
                        } else {
                            (slideSeekStartPositionMs + deltaMs).coerceAtLeast(0L)
                        }
                        slideSeekTargetPositionMs = targetMs
                        val effectiveDeltaMs = targetMs - slideSeekStartPositionMs
                        swipeGestureValueTrackerProgress = effectiveDeltaMs

                        onSlideSeek(
                            targetMs,
                            effectiveDeltaMs,
                            slideSeekStartPositionMs,
                            if (duration > 0L) duration else slideSeekDurationMs
                        )
                        return true
                    }
                }
                return true
            }
        }
    )

    // Volume and brightness gesture detector
    private val vbGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null || isLongPressingSpeed) return false
                if (inExclusionArea(firstEvent)) {
                    return false
                }
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isVolumeBrightnessGesturesEnabled()
                ) {
                    return false
                }

                if (abs(distanceY) < abs(distanceX)) {
                    return false
                }
                if (swipeGestureProgressOpen || isSlideSeeking) {
                    return false
                }

                val viewCenterX = touchView.measuredWidth / 2
                val distanceFull = touchView.measuredHeight * FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    if (swipeGestureValueTrackerVolume == -1f) {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        swipeGestureValueTrackerVolume = getCurrentVolumeLevel() * maxVolume
                    }
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val change = ratioChange * maxVolume
                    swipeGestureValueTrackerVolume = (swipeGestureValueTrackerVolume + change)
                        .coerceIn(0f, maxVolume.toFloat())

                    val volumePercent = (swipeGestureValueTrackerVolume / maxVolume.toFloat())
                    onVolumeChange(volumePercent)
                    swipeGestureVolumeOpen = true
                } else {
                    if (swipeGestureValueTrackerBrightness == -1f) {
                        swipeGestureValueTrackerBrightness = getCurrentBrightnessLevel()
                    }
                    
                    val newBrightness = (swipeGestureValueTrackerBrightness + ratioChange)
                        .coerceIn(0.01f, 1f)
                    swipeGestureValueTrackerBrightness = newBrightness

                    onBrightnessChange(ratioChange)
                    swipeGestureBrightnessOpen = true
                }
                return true
            }
        }
    )

    // Zoom gesture detector
    private val zoomGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return playerPreferences.arePlayerGesturesEnabled() &&
                    playerPreferences.isZoomGestureEnabled()
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isZoomGestureEnabled()
                ) {
                    return false
                }
                lastScaleEvent = SystemClock.elapsedRealtime()
                val scaleFactor = detector.scaleFactor
                
                if (abs(scaleFactor - ZOOM_SCALE_BASE) > ZOOM_SCALE_THRESHOLD) {
                    val enableZoom = scaleFactor > 1
                    updateZoomMode(enableZoom)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        }
    ).apply {
        isQuickScaleEnabled = false
    }

    private fun updateZoomMode(enabled: Boolean) {
        isZoomEnabled = enabled
        onZoomChange(enabled)
    }

    private fun releaseAction(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isTransforming) {
                isTransforming = false
                prevSpan = 0f
                onTransformEnd()
            }

            if (swipeGestureVolumeOpen) {
                swipeGestureVolumeOpen = false
                swipeGestureValueTrackerVolume = -1f
            }
            
            if (swipeGestureBrightnessOpen) {
                swipeGestureBrightnessOpen = false
                swipeGestureValueTrackerBrightness = -1f
            }
            
            if (swipeGestureProgressOpen || isSlideSeeking) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (slideSeekTargetPositionMs != slideSeekStartPositionMs || swipeGestureValueTrackerProgress != 0L) {
                        onSlideSeekEnd(slideSeekTargetPositionMs)
                    } else {
                        onSlideSeekCancel()
                    }
                } else {
                    onSlideSeekCancel()
                }
                swipeGestureProgressOpen = false
                isSlideSeeking = false
                swipeGestureValueTrackerProgress = 0L
                slideSeekTargetPositionMs = 0L
                slideSeekStartPositionMs = 0L
                slideSeekDurationMs = 0L
            }
            
            if (isLongPressingSpeed) {
                isLongPressingSpeed = false
                onLongPressSpeedEnd()
            }
            
            currentNumberOfPointers = 0
        }
    }

    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        val exclusionVertical = GESTURE_EXCLUSION_AREA_VERTICAL * Resources.getSystem().displayMetrics.density
        val exclusionHorizontal = GESTURE_EXCLUSION_AREA_HORIZONTAL * Resources.getSystem().displayMetrics.density

        val inExclusion = firstEvent.y < exclusionVertical ||
            firstEvent.y > screenHeight - exclusionVertical ||
            firstEvent.x < exclusionHorizontal ||
            firstEvent.x > screenWidth - exclusionHorizontal

        return inExclusion
    }

    private fun handleTwoFingerTransform(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    prevSpan = kotlin.math.hypot(x0 - x1, y0 - y1)
                    prevFocusX = (x0 + x1) / 2f
                    prevFocusY = (y0 + y1) / 2f
                    isTransforming = true
                    twoFingerDownTime = SystemClock.elapsedRealtime()
                    twoFingerMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    val currentSpan = kotlin.math.hypot(x0 - x1, y0 - y1)
                    val currentFocusX = (x0 + x1) / 2f
                    val currentFocusY = (y0 + y1) / 2f

                    if (!isTransforming) {
                        prevSpan = currentSpan
                        prevFocusX = currentFocusX
                        prevFocusY = currentFocusY
                        isTransforming = true
                        twoFingerDownTime = SystemClock.elapsedRealtime()
                        twoFingerMoved = false
                    } else if (prevSpan > 0f) {
                        val scaleMultiplier = currentSpan / prevSpan
                        val deltaX = currentFocusX - prevFocusX
                        val deltaY = currentFocusY - prevFocusY

                        if (abs(scaleMultiplier - 1f) > 0.005f || kotlin.math.hypot(deltaX, deltaY) > 2f) {
                            twoFingerMoved = true
                            onTransform(scaleMultiplier, deltaX, deltaY)
                            prevSpan = currentSpan
                            prevFocusX = currentFocusX
                            prevFocusY = currentFocusY
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isTransforming) {
                    isTransforming = false
                    prevSpan = 0f
                    val now = SystemClock.elapsedRealtime()
                    if (!twoFingerMoved && (now - twoFingerDownTime) < 350L) {
                        if (now - lastTwoFingerTapTime < 450L) {
                            onResetTransform()
                            lastTwoFingerTapTime = 0L
                        } else {
                            lastTwoFingerTapTime = now
                        }
                    } else {
                        onTransformEnd()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (isTransforming) {
                    isTransforming = false
                    prevSpan = 0f
                    onTransformEnd()
                }
            }
        }
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        currentNumberOfPointers = event.pointerCount

        if (screenWidth == 0 || screenHeight == 0) {
            screenWidth = touchView.width
            screenHeight = touchView.height
        }

        if (event.pointerCount >= 2) {
            if (isLongPressingSpeed) {
                isLongPressingSpeed = false
                onLongPressSpeedEnd()
            }
            if (swipeGestureVolumeOpen || swipeGestureBrightnessOpen || swipeGestureProgressOpen || isSlideSeeking) {
                if (isSlideSeeking) {
                    onSlideSeekCancel()
                }
                swipeGestureVolumeOpen = false
                swipeGestureBrightnessOpen = false
                swipeGestureProgressOpen = false
                isSlideSeeking = false
                swipeGestureValueTrackerVolume = -1f
                swipeGestureValueTrackerBrightness = -1f
                swipeGestureValueTrackerProgress = 0L
                slideSeekTargetPositionMs = 0L
                slideSeekStartPositionMs = 0L
                slideSeekDurationMs = 0L
            }
        }

        when (event.pointerCount) {
            1 -> {
                if (isTransforming) {
                    isTransforming = false
                    prevSpan = 0f
                    onTransformEnd()
                }
                tapGestureDetector.onTouchEvent(event)
                if (playerPreferences.arePlayerGesturesEnabled()) {
                    vbGestureDetector.onTouchEvent(event)
                    seekGestureDetector.onTouchEvent(event)
                }
            }
            2 -> {
                if (playerPreferences.arePlayerGesturesEnabled() &&
                    playerPreferences.isZoomGestureEnabled()
                ) {
                    if (playerPreferences.isFreeZoomAndPanEnabled()) {
                        handleTwoFingerTransform(event)
                    } else {
                        zoomGestureDetector.onTouchEvent(event)
                    }
                }
            }
        }

        releaseAction(event)
        return true
    }
}
