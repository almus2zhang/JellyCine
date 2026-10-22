package com.jellycine.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import com.jellycine.app.R
import com.jellycine.app.ui.activity.JellyCineActivity

class WidgetListProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            notifyDataChanged(context)
        }
    }

    companion object {
        private const val TAG = "WidgetListProvider"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val intent = Intent(context, WidgetListService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

            val views = RemoteViews(context.packageName, R.layout.widget_media_list)
            views.setRemoteAdapter(R.id.widget_list_view, intent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

            // 列表项点击起播的 PendingIntent 模板
            val playIntent = Intent(context, JellyCineActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val playPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list_view, playPendingIntent)

            // 点击顶部标题栏打开主界面
            val launchIntent = Intent(context, JellyCineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 20000,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_list_header, launchPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * 通知所有桌面列表微件刷新数据
         */
        fun notifyDataChanged(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, WidgetListProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
                if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                    for (id in appWidgetIds) {
                        updateWidget(context, appWidgetManager, id)
                    }
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list_view)
                    Log.d(TAG, "Notified ${appWidgetIds.size} list widgets of data changed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify list widgets: ${e.message}")
            }
        }

        /**
         * 尝试将列表型小组件固定到桌面 (Android 8.0+)
         */
        fun pinListWidget(context: Context): Boolean {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WidgetListProvider::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                val success = appWidgetManager.requestPinAppWidget(component, null, null)
                Toast.makeText(
                    context,
                    if (success) "已请求添加媒体清单小组件到桌面" else "请长按桌面空白处手动添加【JellyCine 媒体清单】小组件",
                    Toast.LENGTH_SHORT
                ).show()
                return success
            } else {
                Toast.makeText(
                    context,
                    "请长按桌面空白处手动添加【JellyCine 媒体清单】微件",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
        }
    }
}
