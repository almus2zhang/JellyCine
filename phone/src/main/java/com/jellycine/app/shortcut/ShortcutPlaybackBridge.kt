package com.jellycine.app.shortcut

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ShortcutPlaybackBridge {

    data class PlaybackRequest(
        val mediaId: String,
        val seriesId: String?,
        val title: String?,
        val systemServerId: String? = null
    )

    // replay = 0 防止在 App 普通冷启动时错误回放历史请求导致崩溃
    private val _pendingRequest = MutableSharedFlow<PlaybackRequest>(extraBufferCapacity = 5)
    val pendingRequest: SharedFlow<PlaybackRequest> = _pendingRequest.asSharedFlow()

    @Volatile
    private var activeRequest: PlaybackRequest? = null

    fun handleIntent(intent: Intent?) {
        val mediaId = intent?.getStringExtra("shortcut_play_media_id")?.takeIf { it.isNotBlank() } ?: return
        val seriesId = intent.getStringExtra("shortcut_play_series_id")?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra("shortcut_title")
        val systemServerId = intent.getStringExtra("shortcut_system_server_id")?.takeIf { it.isNotBlank() }
        val request = PlaybackRequest(
            mediaId = mediaId,
            seriesId = seriesId,
            title = title,
            systemServerId = systemServerId
        )
        activeRequest = request
        _pendingRequest.tryEmit(request)
        // 及时移除 extra，防止旋转屏幕或 activity 重建时重复触发
        intent.removeExtra("shortcut_play_media_id")
    }

    /**
     * 一次性消费冷启动意图，消费后立即置空，杜绝普通启动或界面重组误触发
     */
    fun consumeActiveRequest(): PlaybackRequest? {
        val req = activeRequest
        activeRequest = null
        return req
    }

    fun clear() {
        activeRequest = null
        _pendingRequest.resetReplayCache()
    }
}
