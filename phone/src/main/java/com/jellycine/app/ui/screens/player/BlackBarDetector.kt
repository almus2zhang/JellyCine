package com.jellycine.app.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
    val hasCrop: Boolean get() = topFraction > 0.02f || bottomFraction > 0.02f || leftFraction > 0.02f || rightFraction > 0.02f
}

data class ScalingTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

object BlackBarDetector {

    /**
     * Captures a lightweight snapshot from the SurfaceView and detects black bars
     * while excluding subtitles from the analysis.
     */
    fun detect(
        surfaceView: SurfaceView,
        onResult: (CropBoundaries) -> Unit
    ) {
        val width = 160
        val height = 90
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
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
                        onResult(boundaries)
                    } else {
                        bitmap.recycle()
                        onResult(CropBoundaries())
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            bitmap.recycle()
            onResult(CropBoundaries())
        }
    }

    /**
     * Intelligently analyzes a bitmap to find letterbox and pillarbox black bars.
     * Crucially excludes the subtitle area (center bottom 25%) to prevent subtitles
     * from interfering with the black bar boundary detection.
     */
    fun analyzeBitmap(bitmap: Bitmap): CropBoundaries {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return CropBoundaries()

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isPixelBlack(x: Int, y: Int): Boolean {
            val pixel = pixels[y * w + x]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            return luma < 24.0
        }

        // 1. Detect Top Black Bar (top never has subtitles in standard layout)
        val sampleCols = listOf(
            (w * 0.10f).toInt(),
            (w * 0.25f).toInt(),
            (w * 0.50f).toInt(),
            (w * 0.75f).toInt(),
            (w * 0.90f).toInt()
        )

        var topBlackBarHeight = 0
        for (y in 0 until (h * 0.45f).toInt()) {
            val isRowBlack = sampleCols.all { x -> isPixelBlack(x, y) }
            if (isRowBlack) {
                topBlackBarHeight = y + 1
            } else {
                break
            }
        }

        // 2. Detect Bottom Black Bar, EXCLUDING SUBTITLES:
        // Subtitles are located in the horizontal center (25% to 75% width) and bottom 25% height.
        // Therefore, we ONLY check the side columns (8% and 92% width) from the bottom up!
        // The side columns never contain subtitles, so subtitles cannot disguise black bars as content!
        val sideCols = listOf(
            (w * 0.08f).toInt(),
            (w * 0.15f).toInt(),
            (w * 0.85f).toInt(),
            (w * 0.92f).toInt()
        )

        var bottomBlackBarHeight = 0
        for (y in (h - 1) downTo (h * 0.55f).toInt()) {
            val isRowBlackAtSides = sideCols.all { x -> isPixelBlack(x, y) }
            if (isRowBlackAtSides) {
                bottomBlackBarHeight = h - y
            } else {
                break
            }
        }

        // Letterbox bars in movie rips are almost always symmetric.
        // Reconcile bottom bar with top bar symmetry:
        val finalTopBar = if (topBlackBarHeight >= (h * 0.03f)) topBlackBarHeight else 0
        val finalBottomBar = if (bottomBlackBarHeight >= (h * 0.03f)) {
            if (finalTopBar > 0 && kotlin.math.abs(bottomBlackBarHeight - finalTopBar) <= 4) {
                finalTopBar
            } else {
                bottomBlackBarHeight
            }
        } else if (finalTopBar > 0) {
            // If bottom had subtitles spanning wide, rely on top symmetry
            finalTopBar
        } else {
            0
        }

        // 3. Detect Left & Right Pillarbox Bars
        // Check rows in the vertical center: 30%, 40%, 50%, 60% (never containing subtitles)
        val middleRows = listOf(
            (h * 0.30f).toInt(),
            (h * 0.40f).toInt(),
            (h * 0.50f).toInt(),
            (h * 0.60f).toInt()
        )

        var leftBarWidth = 0
        for (x in 0 until (w * 0.40f).toInt()) {
            val isColBlack = middleRows.all { y -> isPixelBlack(x, y) }
            if (isColBlack) {
                leftBarWidth = x + 1
            } else {
                break
            }
        }

        var rightBarWidth = 0
        for (x in (w - 1) downTo (w * 0.60f).toInt()) {
            val isColBlack = middleRows.all { y -> isPixelBlack(x, y) }
            if (isColBlack) {
                rightBarWidth = w - x
            } else {
                break
            }
        }

        val finalLeftBar = if (leftBarWidth >= (w * 0.03f)) leftBarWidth else 0
        val finalRightBar = if (rightBarWidth >= (w * 0.03f)) {
            if (finalLeftBar > 0 && kotlin.math.abs(rightBarWidth - finalLeftBar) <= 4) {
                finalLeftBar
            } else {
                rightBarWidth
            }
        } else if (finalLeftBar > 0) {
            finalLeftBar
        } else {
            0
        }

        return CropBoundaries(
            topFraction = finalTopBar.toFloat() / h,
            bottomFraction = finalBottomBar.toFloat() / h,
            leftFraction = finalLeftBar.toFloat() / w,
            rightFraction = finalRightBar.toFloat() / w
        )
    }

    /**
     * Calculates the exact scale and translation offsets required to fill the screen
     * horizontally or vertically after removing the detected black bars.
     */
    fun calculateScaling(
        modeIndex: Int, // 0: 横向全屏, 1: 纵向全屏, 2: 默认全屏
        boundaries: CropBoundaries,
        screenWidth: Float,
        screenHeight: Float,
        videoAspect: Float
    ): ScalingTransform {
        if (modeIndex == 2 || screenWidth <= 0f || screenHeight <= 0f) {
            return ScalingTransform(scale = 1.0f, offsetX = 0f, offsetY = 0f)
        }

        val screenAspect = screenWidth / screenHeight
        val activeWidthFrac = boundaries.activeWidthFraction
        val activeHeightFrac = boundaries.activeHeightFraction

        val centerOffsetY = (boundaries.topFraction - boundaries.bottomFraction) / 2f
        val centerOffsetX = (boundaries.leftFraction - boundaries.rightFraction) / 2f

        val scale = when (modeIndex) {
            0 -> { // 横向全屏 (Fill Width)
                if (videoAspect >= screenAspect) {
                    (1.0f / activeWidthFrac).coerceIn(1.0f, 4.0f)
                } else {
                    val containerWidthInFit = screenHeight * videoAspect
                    val activeWidthInFit = containerWidthInFit * activeWidthFrac
                    (screenWidth / activeWidthInFit).coerceIn(1.0f, 4.0f)
                }
            }
            1 -> { // 纵向全屏 (Fill Height)
                if (videoAspect <= screenAspect) {
                    (1.0f / activeHeightFrac).coerceIn(1.0f, 4.0f)
                } else {
                    val containerHeightInFit = screenWidth / videoAspect
                    val activeHeightInFit = containerHeightInFit * activeHeightFrac
                    (screenHeight / activeHeightInFit).coerceIn(1.0f, 4.0f)
                }
            }
            else -> 1.0f
        }

        val offX = centerOffsetX * screenWidth * scale
        val offY = centerOffsetY * screenHeight * scale

        return ScalingTransform(scale = scale, offsetX = offX, offsetY = offY)
    }
}
