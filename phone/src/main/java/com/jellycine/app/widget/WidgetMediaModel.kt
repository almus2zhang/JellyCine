package com.jellycine.app.widget

import kotlinx.serialization.Serializable

@Serializable
data class WidgetMediaItem(
    val mediaId: String,
    val seriesId: String? = null,
    val title: String,
    val seasonEpisode: String = "",
    val episodeTitle: String? = null,
    val posterUrl: String? = null,
    val localPosterPath: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progressPercent: Int = 0,
    val isMovie: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
