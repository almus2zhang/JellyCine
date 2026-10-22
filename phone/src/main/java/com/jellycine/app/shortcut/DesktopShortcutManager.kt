package com.jellycine.app.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.jellycine.app.ui.activity.JellyCineActivity
import com.jellycine.data.model.BaseItemDto
import com.jellycine.data.repository.MediaRepository
import com.jellycine.shared.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DesktopShortcutManager {

    private const val TAG = "DesktopShortcutManager"
    private const val PREFIX_SHORTCUT_ID = "jellycine_media_"

    fun getShortcutId(item: BaseItemDto): String {
        val rootId = item.seriesId?.takeIf { it.isNotBlank() } ?: item.id
        return "$PREFIX_SHORTCUT_ID$rootId"
    }

    fun getDisplayName(item: BaseItemDto): String {
        return if (item.type.equals("Episode", ignoreCase = true) && !item.seriesName.isNullOrBlank()) {
            item.seriesName!!
        } else {
            item.name.orEmpty()
        }
    }

    fun getEpisodeSubtitle(item: BaseItemDto): String {
        return if (item.type.equals("Episode", ignoreCase = true)) {
            val season = item.parentIndexNumber
            val episode = item.indexNumber
            when {
                season != null && episode != null -> "S${season}:E${episode}"
                episode != null -> "第${episode}集"
                else -> item.episodeTitle.orEmpty()
            }
        } else {
            item.productionYear?.toString().orEmpty()
        }
    }

    suspend fun createMediaShortcut(
        context: Context,
        item: BaseItemDto,
        mediaRepository: MediaRepository,
        showToast: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            withContext(Dispatchers.Main) {
                if (showToast) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.shortcut_not_supported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return@withContext false
        }

        val shortcutId = getShortcutId(item)
        val shortLabel = getDisplayName(item).ifBlank { "JellyCine" }
        val subtitle = getEpisodeSubtitle(item)
        val longLabel = if (subtitle.isNotBlank()) "$shortLabel · $subtitle" else shortLabel

        // Determine poster image item ID (use series Primary poster for episodes)
        val itemId = item.id ?: return@withContext false
        val isEpisode = item.type.equals("Episode", ignoreCase = true)
        val isSeries = item.type.equals("Series", ignoreCase = true)
        val posterItemId = when {
            isEpisode -> item.seriesId?.takeIf { it.isNotBlank() } ?: itemId
            else -> itemId
        }

        // Fetch poster URL
        val posterUrl = mediaRepository.getImageUrlString(
            itemId = posterItemId,
            imageType = "Primary",
            width = 300,
            height = 450
        ) ?: mediaRepository.getImageUrlString(
            itemId = itemId,
            imageType = "Thumb",
            width = 300,
            height = 300
        ) ?: mediaRepository.getImageUrlString(
            itemId = itemId,
            imageType = "Backdrop",
            width = 400,
            height = 225
        )

        // Load Bitmap and create clean launcher-styled rounded icon
        val posterBitmap = fetchBitmap(posterUrl)
        val iconBitmap = createSquaredIcon(posterBitmap)
        val iconCompat = if (iconBitmap != null) {
            IconCompat.createWithBitmap(iconBitmap)
        } else {
            IconCompat.createWithResource(context, com.jellycine.shared.R.mipmap.ic_launcher)
        }

        // Target series ID for playback resolution
        val seriesId = when {
            isEpisode -> item.seriesId
            isSeries -> item.id
            else -> null
        }

        val launchIntent = Intent(context, JellyCineActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("shortcut_play_media_id", item.id)
            if (!seriesId.isNullOrBlank()) {
                putExtra("shortcut_play_series_id", seriesId)
            }
            putExtra("shortcut_title", shortLabel)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(iconCompat)
            .setIntent(launchIntent)
            .build()

        val success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)

        withContext(Dispatchers.Main) {
            if (showToast) {
                val messageRes = if (success) R.string.shortcut_created_success else R.string.shortcut_not_supported
                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        }
        success
    }

    suspend fun updateShortcutProgress(
        context: Context,
        item: BaseItemDto,
        positionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return@withContext

        val shortcutId = getShortcutId(item)
        val shortLabel = getDisplayName(item)
        if (shortLabel.isBlank()) return@withContext

        val subtitle = getEpisodeSubtitle(item)
        val percent = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
        val progressText = if (percent > 0) "已看 $percent%" else ""

        val longLabelParts = listOfNotNull(
            shortLabel.takeIf { it.isNotBlank() },
            subtitle.takeIf { it.isNotBlank() },
            progressText.takeIf { it.isNotBlank() }
        )
        val longLabel = longLabelParts.joinToString(" · ")

        val isEpisode = item.type.equals("Episode", ignoreCase = true)
        val isSeries = item.type.equals("Series", ignoreCase = true)
        val seriesId = when {
            isEpisode -> item.seriesId
            isSeries -> item.id
            else -> null
        }

        val launchIntent = Intent(context, JellyCineActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("shortcut_play_media_id", item.id)
            if (!seriesId.isNullOrBlank()) {
                putExtra("shortcut_play_series_id", seriesId)
            }
            putExtra("shortcut_title", shortLabel)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val updatedShortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIntent(launchIntent)
            .build()

        try {
            ShortcutManagerCompat.updateShortcuts(context, listOf(updatedShortcut))
        } catch (e: Exception) {
            Log.d(TAG, "Update shortcut progress ignored or unsupported: ${e.message}")
        }
    }

    suspend fun resolvePlayableMediaId(
        mediaRepository: MediaRepository,
        mediaId: String,
        seriesId: String?
    ): String = withContext(Dispatchers.IO) {
        val targetSeriesId = seriesId ?: run {
            val item = mediaRepository.getItemById(mediaId).getOrNull()
            if (item?.type.equals("Series", ignoreCase = true)) mediaId else null
        }

        if (!targetSeriesId.isNullOrBlank()) {
            try {
                // 1. Try to get the next up / in-progress episode
                val nextUpResult = mediaRepository.getNextUpItems(seriesId = targetSeriesId, limit = 1)
                val nextUpEpisode = nextUpResult.getOrNull()?.firstOrNull()
                val nextId = nextUpEpisode?.id
                if (!nextId.isNullOrBlank()) {
                    return@withContext nextId
                }

                // 2. Fallback to first episode of the series if not started
                val showEpisodesResult = mediaRepository.getEpisodes(seriesId = targetSeriesId)
                val firstEpisode = showEpisodesResult.getOrNull()?.firstOrNull()
                val firstId = firstEpisode?.id
                if (!firstId.isNullOrBlank()) {
                    return@withContext firstId
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve latest episode for series $targetSeriesId: ${e.message}")
            }
        }
        mediaId
    }

    private fun fetchBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                val bytes = response.body!!.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createSquaredIcon(src: Bitmap?): Bitmap? {
        if (src == null) return null
        val targetSize = 256
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Center-crop the poster into targetSize x targetSize
        val srcW = src.width
        val srcH = src.height
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = 1f // square

        val cropRect: Rect = if (srcRatio > dstRatio) {
            val cropW = (srcH * dstRatio).toInt()
            val startX = (srcW - cropW) / 2
            Rect(startX, 0, startX + cropW, srcH)
        } else {
            val cropH = (srcW / dstRatio).toInt()
            val startY = (srcH - cropH) / 2
            Rect(0, startY, srcW, startY + cropH)
        }

        // Rounded rectangle mask (22% squircle radius standard for modern Android launcher icons)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rectF = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())
        val cornerRadius = targetSize * 0.22f
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)

        // Draw cropped poster inside rounded mask
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, cropRect, rectF, paint)

        // Reset xfermode
        paint.xfermode = null

        // Add a subtle translucent border for better contrast on light wallpapers
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = android.graphics.Color.argb(40, 255, 255, 255)
        }
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

        return output
    }
}
