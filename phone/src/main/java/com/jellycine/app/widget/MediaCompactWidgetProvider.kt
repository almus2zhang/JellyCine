package com.jellycine.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import com.jellycine.app.R
import com.jellycine.app.ui.activity.JellyCineActivity
import com.jellycine.data.model.BaseItemDto
import com.jellycine.data.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MediaCompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetDataManager.unbindWidgetId(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PIN_SUCCESS) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val mediaId = intent.getStringExtra(EXTRA_PINNED_MEDIA_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && !mediaId.isNullOrBlank()) {
                WidgetDataManager.bindWidgetId(context, appWidgetId, mediaId)
                val manager = AppWidgetManager.getInstance(context)
                updateAppWidget(context, manager, appWidgetId)
            }
        }
    }

    companion object {
        private const val TAG = "MediaWidgetProvider"
        const val ACTION_PIN_SUCCESS = "com.jellycine.app.widget.ACTION_PIN_SUCCESS"
        const val EXTRA_PINNED_MEDIA_ID = "extra_pinned_media_id"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_media_compact)
            val item = WidgetDataManager.getMediaForWidgetId(context, appWidgetId)

            if (item == null) {
                views.setTextViewText(R.id.widget_title, "JellyCine")
                views.setTextViewText(R.id.widget_subtitle, "暂无正在播放媒体")
                views.setProgressBar(R.id.widget_progress_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_progress_text, "0%")

                val launchIntent = Intent(context, JellyCineActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_poster_container, pendingIntent)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            // 标题组织
            val titleText = if (item.isMovie) {
                item.title
            } else {
                if (item.seasonEpisode.isNotBlank()) {
                    "${item.seasonEpisode} · ${item.title}"
                } else {
                    item.title
                }
            }
            views.setTextViewText(R.id.widget_title, titleText)

            // 副标题
            val subText = item.episodeTitle?.takeIf { it.isNotBlank() } ?: if (item.isMovie) "电影" else "剧集"
            views.setTextViewText(R.id.widget_subtitle, subText)

            // 进度
            val percent = item.progressPercent.coerceIn(0, 100)
            views.setProgressBar(R.id.widget_progress_bar, 100, percent, false)
            views.setTextViewText(R.id.widget_progress_text, "$percent%")

            // 海报渲染
            val posterBitmap = WidgetDataManager.loadCachedBitmap(item.localPosterPath)
            if (posterBitmap != null) {
                val rounded = getRoundedPoster(posterBitmap)
                views.setImageViewBitmap(R.id.widget_poster, rounded)
            } else {
                views.setImageViewResource(R.id.widget_poster, R.drawable.bg_widget_poster)
            }

            // 点击起播 Intent
            val playIntent = Intent(context, JellyCineActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("shortcut_play_media_id", item.mediaId)
                if (!item.seriesId.isNullOrBlank()) {
                    putExtra("shortcut_play_series_id", item.seriesId)
                }
                putExtra("shortcut_title", titleText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val playPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 点击海报或整卡均可直接播放
            views.setOnClickPendingIntent(R.id.widget_poster_container, playPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_root, playPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, MediaCompactWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
                if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                    for (appWidgetId in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, appWidgetId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update all widgets: ${e.message}")
            }
        }

        fun updateProgress(
            context: Context,
            mediaId: String,
            seriesId: String?,
            positionMs: Long,
            durationMs: Long
        ) {
            val updated = WidgetDataManager.updateProgress(context, mediaId, seriesId, positionMs, durationMs)
            if (updated) {
                updateAllWidgets(context)
            }
        }

        suspend fun addMediaAndPinWidget(
            context: Context,
            item: BaseItemDto,
            mediaRepository: MediaRepository,
            showToast: Boolean = true
        ): Boolean = withContext(Dispatchers.IO) {
            val itemId = item.id ?: return@withContext false
            val isEpisode = item.type.equals("Episode", ignoreCase = true)
            val isSeries = item.type.equals("Series", ignoreCase = true)
            val isMovie = !isEpisode && !isSeries

            // 解析真实可播放集数
            val resolvedEpisode = if (isSeries) {
                mediaRepository.getNextUpItems(seriesId = itemId, limit = 1).getOrNull()?.firstOrNull()
                    ?: mediaRepository.getEpisodes(seriesId = itemId).getOrNull()?.firstOrNull()
            } else null

            val targetPlayableId = when {
                isSeries -> resolvedEpisode?.id ?: itemId
                else -> itemId
            }

            val targetSeriesId = when {
                isEpisode -> item.seriesId
                isSeries -> item.id
                else -> null
            }

            // 计算 SxxExx
            val seasonEpisode = when {
                isEpisode -> {
                    val s = item.parentIndexNumber
                    val e = item.indexNumber
                    when {
                        s != null && e != null -> String.format(Locale.ROOT, "S%02dE%02d", s, e)
                        e != null -> String.format(Locale.ROOT, "E%02d", e)
                        else -> "S01E01"
                    }
                }
                isSeries -> {
                    val s = resolvedEpisode?.parentIndexNumber
                    val e = resolvedEpisode?.indexNumber
                    when {
                        s != null && e != null -> String.format(Locale.ROOT, "S%02dE%02d", s, e)
                        e != null -> String.format(Locale.ROOT, "E%02d", e)
                        else -> "S01E01"
                    }
                }
                else -> ""
            }

            val title = when {
                isEpisode -> item.seriesName?.takeIf { it.isNotBlank() } ?: item.name.orEmpty()
                else -> item.name.orEmpty().ifBlank { "JellyCine" }
            }

            val epTitle = when {
                isEpisode -> item.name
                isSeries -> resolvedEpisode?.name
                else -> null
            }

            // 获取海报图片
            val posterItemId = when {
                isEpisode -> item.seriesId?.takeIf { it.isNotBlank() } ?: itemId
                else -> itemId
            }
            val posterUrl = mediaRepository.getImageUrlString(
                itemId = posterItemId,
                imageType = "Primary",
                width = 300,
                height = 450
            )

            // 缓存海报本地文件
            val localPath = WidgetDataManager.cachePosterBitmap(context, targetPlayableId, posterUrl)

            // 读取已有播放进度
            val userData = (resolvedEpisode ?: item).userData
            val positionMs = (userData?.playbackPositionTicks ?: 0L) / 10000L
            val percent = (userData?.playedPercentage ?: 0.0).toInt().coerceIn(0, 100)

            val widgetItem = WidgetMediaItem(
                mediaId = targetPlayableId,
                seriesId = targetSeriesId,
                title = title,
                seasonEpisode = seasonEpisode,
                episodeTitle = epTitle,
                posterUrl = posterUrl,
                localPosterPath = localPath,
                positionMs = positionMs,
                progressPercent = percent,
                isMovie = isMovie,
                updatedAt = System.currentTimeMillis()
            )

            // 保存到小组件媒体库
            WidgetDataManager.addOrUpdateMedia(context, widgetItem)

            // 刷新已有桌面组件
            updateAllWidgets(context)

            // 尝试请求固定小组件到桌面 (Android 8.0+)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MediaCompactWidgetProvider::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                val successCallbackIntent = Intent(context, MediaCompactWidgetProvider::class.java).apply {
                    action = ACTION_PIN_SUCCESS
                    putExtra(EXTRA_PINNED_MEDIA_ID, targetPlayableId)
                }
                val successPendingIntent = PendingIntent.getBroadcast(
                    context,
                    targetPlayableId.hashCode(),
                    successCallbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                val success = appWidgetManager.requestPinAppWidget(component, null, successPendingIntent)
                withContext(Dispatchers.Main) {
                    if (showToast) {
                        Toast.makeText(
                            context,
                            if (success) "已添加到小组件清单，请在桌面确认添加" else "已添加到小组件清单",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return@withContext success
            } else {
                withContext(Dispatchers.Main) {
                    if (showToast) {
                        Toast.makeText(
                            context,
                            "已加入小组件清单，可长按桌面添加 JellyCine 小组件",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return@withContext true
            }
        }

        private fun getRoundedPoster(src: Bitmap): Bitmap {
            val width = src.width
            val height = src.height
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = Rect(0, 0, width, height)
            val rectF = RectF(rect)
            val radius = width * 0.12f

            canvas.drawRoundRect(rectF, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(src, rect, rect, paint)
            return output
        }
    }
}
