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

    private val _pendingRequest = MutableSharedFlow<PlaybackRequest>(replay = 1, extraBufferCapacity = 2)
    val pendingRequest: SharedFlow<PlaybackRequest> = _pendingRequest.asSharedFlow()

    fun handleIntent(intent: Intent?) {
        val mediaId = intent?.getStringExtra("shortcut_play_media_id")?.takeIf { it.isNotBlank() } ?: return
        val seriesId = intent.getStringExtra("shortcut_play_series_id")?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra("shortcut_title")
        val systemServerId = intent.getStringExtra("shortcut_system_server_id")?.takeIf { it.isNotBlank() }
        _pendingRequest.tryEmit(
            PlaybackRequest(
                mediaId = mediaId,
                seriesId = seriesId,
                title = title,
                systemServerId = systemServerId
            )
        )
        // Remove extra to avoid duplicate trigger on activity re-creation
        intent.removeExtra("shortcut_play_media_id")
    }

    fun clear() {
        _pendingRequest.resetReplayCache()
    }
}
