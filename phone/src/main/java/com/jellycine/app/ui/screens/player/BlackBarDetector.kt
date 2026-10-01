package com.jellycine.app.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView

data class CropBoundaries(
    val topFraction: Float = 0f,
    val bottomFraction: Float = 0f,
    val leftFraction: Float = 0f,
    val rightFraction: Float = 0f
) {
    val activeWidthFraction: Float get() = (1.0f - leftFraction - rightFraction).coerceIn(0.1f, 1.0f)
    val activeHeightFraction: Float get() = (1.0f - topFraction - bottomFraction).coerceIn(0.1f, 1.0f)
    val hasCrop: Boolean get() = topFraction > 0.015f || bottomFraction > 0.015f || leftFraction > 0.015f || rightFraction > 0.015f
    val hasHorizontalCrop: Boolean get() = topFraction > 0.015f || bottomFraction > 0.015f
    val hasVerticalCrop: Boolean get() = leftFraction > 0.015f || rightFraction > 0.015f
}

data class ScalingTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

object BlackBarDetector {

    private const val TAG = "BlackBarDetector"
    private const val BLACK_LUMA_THRESHOLD = 36.0

    /**
     * Captures a snapshot from the SurfaceView with matching aspect ratio
     * and detects black bars while excluding subtitles from the analysis.
     */
    fun detect(
        surfaceView: SurfaceView,
        onResult: (CropBoundaries) -> Unit
    ) {
        val sW = surfaceView.width.coerceAtLeast(1)
        val sH = surfaceView.height.coerceAtLeast(1)
        val bmpW = 240
        val bmpH = ((bmpW.toFloat() * sH) / sW).toInt().coerceIn(60, 240)

        val bitmap = try {
            Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create bitmap for PixelCopy", e)
            onResult(CropBoundaries())
            return
        }

        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        val boundaries = analyzeBitmap(bitmap)
                        bitmap.recycle()
                        Log.d(TAG, "PixelCopy success, detected boundaries: $boundaries")
                        onResult(boundaries)
                    } else {
                        Log.w(TAG, "PixelCopy returned status: $copyResult")
                        bitmap.recycle()
                        onResult(CropBoundaries())
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy request failed", e)
            bitmap.recycle()
            onResult(CropBoundaries())
        }
    }

    /**
     * Analyzes a bitmap to find letterbox (top/bottom) and pillarbox (left/right) black bars.
     * Crucially excludes the subtitle area (center bottom 25%~75%) to prevent subtitles
     * from interfering with black bar detection.
     */
    fun analyzeBitmap(bitmap: Bitmap): CropBoundaries {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return CropBoundaries()

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isBlack(x: Int, y: Int): Boolean {
            val pixel = pixels[y * w + x]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            return luma < BLACK_LUMA_THRESHOLD
        }

        // 1. Detect Top Black Bar (top never has subtitles)
        // Scan middle 70% width: x from 15% to 85%
        val xStart = (w * 0.15f).toInt()
        val xEnd = (w * 0.85f).toInt().coerceAtMost(w - 1)
        val xCount = (xEnd - xStart + 1).coerceAtLeast(1)

        var topBlackBar = 0
        var consecutiveContentRows = 0
        for (y in 0 until (h * 0.45f).toInt()) {
            var blackCount = 0
            for (x in xStart..xEnd) {
                if (isBlack(x, y)) blackCount++
            }
            val blackRatio = blackCount.toFloat() / xCount
            if (blackRatio >= 0.88f) {
                topBlackBar = y + 1
                consecutiveContentRows = 0
            } else {
                consecutiveContentRows++
                if (consecutiveContentRows >= 2) break
            }
        }

        // 2. Detect Bottom Black Bar, EXCLUDING SUBTITLES:
        // Subtitles are centered (25% to 75% width).
        // Check only the two outer bands: 10%~24% and 76%~90%
        val leftBandStart = (w * 0.10f).toInt()
        val leftBandEnd = (w * 0.24f).toInt()
        val rightBandStart = (w * 0.76f).toInt()
        val rightBandEnd = (w * 0.90f).toInt().coerceAtMost(w - 1)
        val sideCount = ((leftBandEnd - leftBandStart + 1) + (rightBandEnd - rightBandStart + 1)).coerceAtLeast(1)

        var bottomBlackBar = 0
        consecutiveContentRows = 0
        for (y in (h - 1) downTo (h * 0.55f).toInt()) {
            var blackCount = 0
            for (x in leftBandStart..leftBandEnd) {
                if (isBlack(x, y)) blackCount++
            }
            for (x in rightBandStart..rightBandEnd) {
                if (isBlack(x, y)) blackCount++
            }
            val blackRatio = blackCount.toFloat() / sideCount
            if (blackRatio >= 0.88f) {
                bottomBlackBar = h - y
                consecutiveContentRows = 0
            } else {
                consecutiveContentRows++
                if (consecutiveContentRows >= 2) break
            }
        }

        // Letterbox bars in movie rips are almost always symmetric.
        // Reconcile bottom bar with top bar symmetry:
        val maxHorizontalBar = maxOf(topBlackBar, bottomBlackBar)
        val finalTop = if (topBlackBar >= (h * 0.02f) || bottomBlackBar >= (h * 0.02f)) {
            if (kotlin.math.abs(topBlackBar - bottomBlackBar) <= (h * 0.06f)) {
                maxHorizontalBar
            } else {
                topBlackBar
            }
        } else 0

        val finalBottom = if (topBlackBar >= (h * 0.02f) || bottomBlackBar >= (h * 0.02f)) {
            if (kotlin.math.abs(topBlackBar - bottomBlackBar) <= (h * 0.06f)) {
                maxHorizontalBar
            } else {
                if (bottomBlackBar > 0) bottomBlackBar else finalTop
            }
        } else 0

        // 3. Detect Left & Right Pillarbox Bars
        // Check rows in vertical center: 25% to 75%
        val yStart = (h * 0.25f).toInt()
        val yEnd = (h * 0.75f).toInt().coerceAtMost(h - 1)
        val yCount = (yEnd - yStart + 1).coerceAtLeast(1)

        var leftBar = 0
        var consecutiveContentCols = 0
        for (x in 0 until (w * 0.40f).toInt()) {
            var blackCount = 0
            for (y in yStart..yEnd) {
                if (isBlack(x, y)) blackCount++
            }
            val blackRatio = blackCount.toFloat() / yCount
            if (blackRatio >= 0.88f) {
                leftBar = x + 1
                consecutiveContentCols = 0
            } else {
                consecutiveContentCols++
                if (consecutiveContentCols >= 2) break
            }
        }

        var rightBar = 0
        consecutiveContentCols = 0
        for (x in (w - 1) downTo (w * 0.60f).toInt()) {
            var blackCount = 0
            for (y in yStart..yEnd) {
                if (isBlack(x, y)) blackCount++
            }
            val blackRatio = blackCount.toFloat() / yCount
            if (blackRatio >= 0.88f) {
                rightBar = w - x
                consecutiveContentCols = 0
            } else {
                consecutiveContentCols++
                if (consecutiveContentCols >= 2) break
            }
        }

        val maxVerticalBar = maxOf(leftBar, rightBar)
        val finalLeft = if (leftBar >= (w * 0.02f) || rightBar >= (w * 0.02f)) {
            if (kotlin.math.abs(leftBar - rightBar) <= (w * 0.06f)) maxVerticalBar else leftBar
        } else 0

        val finalRight = if (leftBar >= (w * 0.02f) || rightBar >= (w * 0.02f)) {
            if (kotlin.math.abs(leftBar - rightBar) <= (w * 0.06f)) maxVerticalBar else rightBar
        } else 0

        return CropBoundaries(
            topFraction = finalTop.toFloat() / h,
            bottomFraction = finalBottom.toFloat() / h,
            leftFraction = finalLeft.toFloat() / w,
            rightFraction = finalRight.toFloat() / w
        )
    }

    /**
     * Calculates the exact scale and translation offsets required to fill the screen
     * horizontally or vertically after removing the detected black bars.
     */
    fun calculateScaling(
        modeIndex: Int, // 0: 横向全屏 (切除左右黑边，横向画面到最大), 1: 纵向全屏 (切除上下黑边，纵向画面到最大), 2: 默认全屏
        boundaries: CropBoundaries,
        screenWidth: Float,
        screenHeight: Float,
        videoAspect: Float
    ): ScalingTransform {
        if (modeIndex == 2 || screenWidth <= 0f || screenHeight <= 0f) {
            return ScalingTransform(scale = 1.0f, offsetX = 0f, offsetY = 0f)
        }

        val screenAspect = screenWidth / screenHeight
        val activeWFrac = boundaries.activeWidthFraction
        val activeHFrac = boundaries.activeHeightFraction

        val centerOffsetY = (boundaries.topFraction - boundaries.bottomFraction) / 2f
        val centerOffsetX = (boundaries.leftFraction - boundaries.rightFraction) / 2f

        val scale = when (modeIndex) {
            0 -> {
                // 横向全屏 (Horizontal Fullscreen):
                // 核心目标：横向画面撑满屏幕宽度，切除所有左右黑边，让横向画面达到最大！
                val fillWidthScale = if (videoAspect <= screenAspect) {
                    // 视频比屏幕窄（如 16:9 在 20:9 屏幕），左右有黑边：放大直至撑满屏幕宽度
                    (screenAspect / videoAspect) / activeWFrac
                } else {
                    // 视频比屏幕更宽（如 2.35:1 在 16:9 屏幕），宽度已占满屏幕，若画面内部有黑边则切除
                    1.0f / activeWFrac
                }
                // 若画面内部还带有上下横向黑边，去黑边全屏时同时兼顾消除
                if (boundaries.hasHorizontalCrop) {
                    maxOf(fillWidthScale, 1.0f / activeHFrac)
                } else {
                    fillWidthScale
                }.coerceIn(1.0f, 3.5f)
            }
            1 -> {
                // 纵向全屏 (Vertical Fullscreen):
                // 核心目标：纵向画面撑满屏幕高度，切除所有上下黑边！
                val fillHeightScale = if (videoAspect >= screenAspect) {
                    // 视频比屏幕宽（如 2.35:1 在 16:9 屏幕），上下有黑边：放大直至撑满屏幕高度
                    (videoAspect / screenAspect) / activeHFrac
                } else {
                    // 视频高度已撑满屏幕高度，若画面内部有上下黑边则切除
                    1.0f / activeHFrac
                }
                // 若画面内部还带有左右黑边，去黑边全屏时同时兼顾消除
                if (boundaries.hasVerticalCrop) {
                    maxOf(fillHeightScale, 1.0f / activeWFrac)
                } else {
                    fillHeightScale
                }.coerceIn(1.0f, 3.5f)
            }
            else -> 1.0f
        }

        val offX = centerOffsetX * screenWidth * scale
        val offY = centerOffsetY * screenHeight * scale

        return ScalingTransform(scale = scale, offsetX = offX, offsetY = offY)
    }
}
