package com.jellycine.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jellycine.app.R

class WidgetListService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetListRemoteViewsFactory(applicationContext, intent)
    }

    private class WidgetListRemoteViewsFactory(
        private val context: Context,
        intent: Intent
    ) : RemoteViewsFactory {

        private var items: List<WidgetMediaItem> = emptyList()

        override fun onCreate() {
            items = WidgetDataManager.getMediaList(context)
        }

        override fun onDataSetChanged() {
            items = WidgetDataManager.getMediaList(context)
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            if (position !in items.indices) {
                return RemoteViews(context.packageName, R.layout.widget_media_list_item)
            }

            val item = items[position]
            val views = RemoteViews(context.packageName, R.layout.widget_media_list_item)

            // 标题
            val titleText = if (item.isMovie) {
                item.title
            } else {
                if (item.seasonEpisode.isNotBlank()) {
                    "${item.seasonEpisode} · ${item.title}"
                } else {
                    item.title
                }
            }
            views.setTextViewText(R.id.item_title, titleText)

            // 副标题
            val subText = item.episodeTitle?.takeIf { it.isNotBlank() } ?: if (item.isMovie) "电影" else "剧集"
            views.setTextViewText(R.id.item_subtitle, subText)

            // 进度
            val percent = item.progressPercent.coerceIn(0, 100)
            views.setProgressBar(R.id.item_progress_bar, 100, percent, false)
            views.setTextViewText(R.id.item_progress_text, "$percent%")

            // 海报
            val posterBitmap = WidgetDataManager.loadCachedBitmap(item.localPosterPath)
            if (posterBitmap != null) {
                val rounded = getRoundedPoster(posterBitmap)
                views.setImageViewBitmap(R.id.item_poster, rounded)
            } else {
                views.setImageViewResource(R.id.item_poster, R.drawable.bg_widget_poster)
            }

            // 点击起播 FillInIntent
            val fillInIntent = Intent().apply {
                putExtra("shortcut_play_media_id", item.mediaId)
                if (!item.seriesId.isNullOrBlank()) {
                    putExtra("shortcut_play_series_id", item.seriesId)
                }
                if (!item.systemServerId.isNullOrBlank()) {
                    putExtra("shortcut_system_server_id", item.systemServerId)
                }
                putExtra("shortcut_title", titleText)
            }
            views.setOnClickFillInIntent(R.id.item_root, fillInIntent)

            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false

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
