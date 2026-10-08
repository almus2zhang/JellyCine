package com.jellycine.app.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import java.lang.ref.WeakReference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import com.jellycine.app.player.mpv.MPVPlayer
import com.jellycine.app.player.mpv.MpvPlayerController
import com.jellycine.app.player.mpv.MpvWarmPool
import com.jellycine.data.model.AudioTranscodeMode
import com.jellycine.data.model.BaseItemDto
import com.jellycine.data.model.MediaSource
import com.jellycine.data.model.MediaStream
import com.jellycine.data.model.PlaybackRequest
import com.jellycine.data.repository.MediaRepository
import com.jellycine.detail.CodecCapabilityManager
import com.jellycine.player.audio.SpatializerHelper
import com.jellycine.player.core.PlaybackMarkerUtils
import com.jellycine.player.core.PlayerState
import com.jellycine.player.core.PlayerTrack
import com.jellycine.player.core.PlayerUtils
import com.jellycine.player.core.RemoteTrailerUrl
import com.jellycine.player.core.SubtitleTrackInfo
import com.jellycine.player.preferences.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jellycine.app.download.DownloadRepository
import com.jellycine.app.download.DownloadRepositoryProvider
import com.jellycine.shared.playback.UserDataRefreshSignals
import java.io.File
import javax.inject.Inject

/**
 * Player ViewModel
 */
@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
        private const val MPV_FALLBACK_FIRST_FRAME_TIMEOUT_MS = 2_500L
    }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private val _preferredStreamIndexes = MutableStateFlow(PreferredStreamIndexes())
    val preferredStreamIndexes: StateFlow<PreferredStreamIndexes> = _preferredStreamIndexes.asStateFlow()
    private val _playbackCompletedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackCompletedEvents: SharedFlow<String> = _playbackCompletedEvents.asSharedFlow()

    var exoPlayer: ExoPlayer? by mutableStateOf(null)
        private set
    var mpvPlayer: MpvPlayerController? by mutableStateOf(null)
        private set
    private var activePlayerEngine: String = PlayerPreferences.DEFAULT_PLAYER_ENGINE

    private val trackSelectionCoordinator = PlayerTrackSelection()
    private var playbackSession = PlaybackSessionContext()
    private val playbackReporter = PlayerPlaybackReporter(
        mediaRepository = mediaRepository,
        scope = viewModelScope,
        positionProvider = { getCurrentPosition() },
        isPausedProvider = { !isPlayingNow() }
    )
    private var spatializerHelper: SpatializerHelper? = null
    private var playerContext: Context? = null
    private var apiMediaStreams: List<MediaStream>? = null
    private var defaultAudioStreamIndex: Int? = null
    private var defaultSubtitleStreamIndex: Int? = null
    private var hasHandledPlaybackCompletion = false
    private var videoTranscodingAllowed: Boolean? = null
    private var audioTranscodingAllowed: Boolean? = null
    private var audioDiagnosticsSignature: String? = null
    private var downloadRepository: DownloadRepository? = null
    private var communityPlaybackSegmentsJob: Job? = null
    private var spatialAudioAnalysisJob: Job? = null
    private var currentItemDetails: BaseItemDto? = null
    private var currentPlaybackMediaSource: MediaSource? = null
    private var currentStreamingUrl: String? = null
    var discordPosterUrl: String? = null
        private set
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodePrefetchSignature: String? = null
    private var mpvWatchdogJob: Job? = null
    private var hasRenderedFirstFrame = false
    private var mpvExternalSubtitleUrls: Map<Int, String> = emptyMap()
    private var remotePlaybackRequestKey: String? = null
    private var activeSurfaceView: WeakReference<SurfaceView>? = null
    private var detectedVideoCropBoundaries: CropBoundaries? = null
    private var initialCropDetectionJob: Job? = null

    fun setSurfaceView(surfaceView: SurfaceView) {
        activeSurfaceView = WeakReference(surfaceView)
    }

    private fun isMpvPlayback(): Boolean {
        return activePlayerEngine == PlayerPreferences.PLAYER_ENGINE_MPV
    }

    private fun resolveMpvHdrFormatLabel(): String {
        val hints = listOfNotNull(
            currentItemDetails?.name,
            currentItemDetails?.originalTitle,
            currentItemDetails?.path,
            currentPlaybackMediaSource?.name,
            currentPlaybackMediaSource?.path,
            _playerState.value.mediaTitle
        ).toTypedArray()
        return CodecCapabilityManager.detectBestSourceHDRFormat(apiMediaStreams, *hints)
            .ifBlank { if (MPVPlayer.isHdr(apiMediaStreams)) "HDR" else "" }
    }

    private fun resolveExoHdrFormatLabel(): String {
        val hints = listOfNotNull(
            currentItemDetails?.name,
            currentItemDetails?.originalTitle,
            currentItemDetails?.path,
            currentPlaybackMediaSource?.name,
            currentPlaybackMediaSource?.path,
            _playerState.value.mediaTitle
        ).toTypedArray()
        val runtimeFormat = PlayerMetadata.currentPlaybackHdrFormat(exoPlayer)
        val sourceFormat = CodecCapabilityManager.detectBestSourceHDRFormat(apiMediaStreams, *hints)

        return when {
            runtimeFormat.isNotBlank() -> runtimeFormat
            sourceFormat.equals("Dolby Vision", ignoreCase = true) -> ""
            sourceFormat.isNotBlank() && PlayerMetadata.hasSelectedVideoTrack(exoPlayer) -> sourceFormat
            else -> ""
        }
    }

    private fun downloadLocationUri(location: String): Uri {
        val parsed = Uri.parse(location)
        return if (parsed.scheme.isNullOrBlank()) {
            Uri.fromFile(File(location))
        } else {
            parsed
        }
    }

    fun initializePlayer(
        context: Context,
        mediaId: String,
        initialItemDetails: BaseItemDto? = null,
        preferredAudioStreamIndex: Int? = null,
        preferredSubtitleStreamIndex: Int? = null,
        initialSeekPositionMs: Long? = null,
        startPlayback: Boolean = true,
        forcedPlayerEngine: String? = null
    ) {
        viewModelScope.launch {
            try {
                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = startPlayback,
                    hasStartedPlayback = false,
                    error = null
                )
                _playerState.value = _playerState.value.copy(
                    recapStartMs = null,
                    recapEndMs = null,
                    introStartMs = null,
                    introEndMs = null,
                    creditsStartMs = null,
                    creditsEndMs = null,
                    previewStartMs = null,
                    previewEndMs = null,
                    chapterMarkers = emptyList()
                )

                playerContext = context
                hasHandledPlaybackCompletion = false
                remotePlaybackRequestKey = null
                val playerPreferences = PlayerPreferences(context)
                val savedSubtitleDelay = playerPreferences.getSubtitleDelay(mediaId)
                _playerState.value = _playerState.value.copy(subtitleDelay = savedSubtitleDelay)
                activePlayerEngine = forcedPlayerEngine ?: playerPreferences.getPlayerEngine()
                val resolvedPreferredAudioStreamIndex = preferredAudioStreamIndex
                    ?: playerPreferences.getPreferredAudioStreamIndex(mediaId)
                val activePreferredSubtitleStreamIndex = preferredSubtitleStreamIndex
                    ?: playerPreferences.getPreferredSubtitleStreamIndex(mediaId)
                val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
                val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
                val audioTranscodeMode = if (isAudioTranscodingAllowed) {
                    playerPreferences.getAudioTranscodeMode()
                } else {
                    AudioTranscodeMode.AUTO
                }
                val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
                    playerPreferences.getMaxStreamingBitrate()
                } else {
                    null
                }
                val maxStreamingHeight = if (isVideoTranscodingAllowed) {
                    playerPreferences.getStreamingQualityMaxHeight()
                } else {
                    null
                }
                trackSelectionCoordinator.resetPendingSelections(
                    preferredAudioStreamIndex = resolvedPreferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = activePreferredSubtitleStreamIndex
                )
                _preferredStreamIndexes.value = PreferredStreamIndexes(
                    audioStreamIndex = resolvedPreferredAudioStreamIndex,
                    subtitleStreamIndex = activePreferredSubtitleStreamIndex
                )

                audioDiagnosticsSignature = null
                currentItemDetails = null
                cancelNextEpisodePrefetch()
                playbackReporter.reset()
                communityPlaybackSegmentsJob?.cancel()
                communityPlaybackSegmentsJob = null
                spatialAudioAnalysisJob?.cancel()
                spatialAudioAnalysisJob = null
                cancelMpvWatchdog()
                hasRenderedFirstFrame = false
                spatializerHelper = SpatializerHelper(context)
                downloadRepository = DownloadRepositoryProvider.getInstance(context)
                val offlinePath = downloadRepository?.getOfflineFilePath(mediaId)
                val hasOfflineFile = !offlinePath.isNullOrBlank()
                val offlineItemDetails = if (hasOfflineFile) {
                    downloadRepository?.offlineItemMetadata(mediaId)
                } else {
                    null
                }

                // Get item details to check for resume position
                val itemDetails = if (initialItemDetails?.id == mediaId) {
                    initialItemDetails
                } else if (hasOfflineFile) {
                    offlineItemDetails ?: mediaRepository.getItemById(mediaId).getOrNull()
                } else {
                    mediaRepository.getItemById(mediaId).getOrNull()
                }
                currentItemDetails = itemDetails
                val resumePositionTicks = itemDetails?.userData?.playbackPositionTicks
                val storedResumePositionMs = if (resumePositionTicks != null && resumePositionTicks > 0) {
                    resumePositionTicks / 10000L
                } else {
                    null
                }
                val mediaTitle = itemDetails?.name ?: "Unknown Title"
                val logoSourceId = when {
                    itemDetails?.imageTags?.containsKey("Logo") == true && !itemDetails.id.isNullOrBlank() -> itemDetails.id
                    !itemDetails?.parentLogoItemId.isNullOrBlank() && !itemDetails?.parentLogoImageTag.isNullOrBlank() -> itemDetails?.parentLogoItemId
                    else -> null
                }
                val mediaLogoUrl = logoSourceId?.let { sourceId ->
                    mediaRepository.getImageUrlString(
                        itemId = sourceId,
                        imageType = "Logo",
                        width = 320,
                        quality = 90,
                        enableImageEnhancers = false
                    )
                } ?: itemDetails?.let { mediaRepository.getTmdbLogoUrl(it) }
                val posterItemId = if (itemDetails?.type.equals("Episode", ignoreCase = true)) {
                    itemDetails?.seriesId ?: itemDetails?.id
                } else {
                    itemDetails?.id
                }
                discordPosterUrl = posterItemId?.let { id ->
                    mediaRepository.getImageUrlString(
                        itemId = id,
                        imageType = "Primary",
                        width = 300,
                        quality = 80
                    )
                }
                val seasonEpisodeLabel = itemDetails?.let { item ->
                    val isEpisodeItem = item.type.equals("Episode", ignoreCase = true)
                    val season = item.parentIndexNumber
                    val episode = item.indexNumber
                    if (isEpisodeItem && season != null && episode != null) {
                        val episodeName = item.episodeTitle
                            ?.takeIf { it.isNotBlank() }
                            ?: item.name?.takeIf { it.isNotBlank() }
                        buildString {
                            append("S")
                            append(season)
                            append(":E")
                            append(episode)
                            episodeName?.let {
                                append(" - ")
                                append(it)
                            }
                        }
                    } else {
                        null
                    }
                }
                val chapterMarkers = PlaybackMarkerUtils.buildChapterMarkers(itemDetails?.chapters)
                val playerStartPositionMs = initialSeekPositionMs ?: storedResumePositionMs
                val introSegment = PlaybackMarkerUtils.extractIntroWindow(itemDetails?.chapters)

                var primaryMediaSource: MediaSource? = null
                var sessionPlaySessionId: String? = null
                var sessionMediaSourceId: String? = null
                var sessionMediaSourceContainer: String? = null
                var sessionMediaSourceBitrateKbps: Int? = null
                var sessionPlayMethod = PlayMethod.DIRECT_PLAY
                var sessionIsOfflinePlayback = false
                var streamingMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
                var playbackRequest: PlaybackRequest? = null
                defaultAudioStreamIndex = null
                defaultSubtitleStreamIndex = null

                val mediaItem = if (hasOfflineFile) {
                    val localFilePath = requireNotNull(offlinePath)
                    sessionIsOfflinePlayback = true
                    sessionPlayMethod = PlayMethod.OFFLINE
                    if (downloadRepository?.isTranscodedDownload(mediaId) == true) {
                        activePlayerEngine = PlayerPreferences.PLAYER_ENGINE_MPV
                    }

                    val offlineItem = offlineItemDetails ?: itemDetails
                    apiMediaStreams = offlineItem?.let {
                        PlayerTrack.resolveApiMediaStreams(
                            itemDetails = it,
                            playbackMediaSource = null
                        )
                    }
                    defaultAudioStreamIndex = offlineItem?.mediaSources?.firstOrNull()?.defaultAudioStreamIndex
                    defaultSubtitleStreamIndex = offlineItem?.mediaSources?.firstOrNull()?.defaultSubtitleStreamIndex

                    val offlineSubtitles = downloadRepository?.getOfflineSubtitlePaths(mediaId).orEmpty()
                    if (offlineSubtitles.isEmpty()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            downloadRepository?.ensureOfflineSubtitles(mediaId)
                        }
                    }

                    val subtitleConfigs = offlineSubtitles.mapNotNull { (streamIndex, subPath) ->
                        val stream = apiMediaStreams?.firstOrNull { it.index == streamIndex }
                        val uri = downloadLocationUri(subPath)
                        val mimeType = stream?.let { subtitleMimeType(it, subPath) } ?: MimeTypes.APPLICATION_SUBRIP
                        val isDefault = stream?.isDefault == true || (activePreferredSubtitleStreamIndex == streamIndex)
                        MediaItem.SubtitleConfiguration.Builder(uri)
                            .setMimeType(mimeType)
                            .setLanguage(stream?.language)
                            .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                            .setLabel(
                                stream?.displayTitle
                                    ?: stream?.title
                                    ?: stream?.language
                                    ?: "Subtitle $streamIndex"
                            )
                            .build()
                    }

                    MediaItem.Builder()
                        .setUri(downloadLocationUri(localFilePath))
                        .setSubtitleConfigurations(subtitleConfigs)
                        .build()
                } else {
                    sessionIsOfflinePlayback = false

                    // Get playback info first to obtain session details
                    val playbackInfoResult = mediaRepository.getPlaybackInfo(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        subtitleStreamIndex = activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode
                    )
                    if (playbackInfoResult.isFailure) {
                        val error = playbackInfoResult.exceptionOrNull()?.message ?: "Failed to get playback info"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    val playbackInfo = playbackInfoResult.getOrNull()
                    if (playbackInfo == null) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Playback info is null")
                        return@launch
                    }

                    primaryMediaSource = playbackInfo.mediaSources?.firstOrNull()
                    currentPlaybackMediaSource = primaryMediaSource
                    
                    apiMediaStreams = PlayerTrack.resolveApiMediaStreams(
                        itemDetails = itemDetails,
                        playbackMediaSource = primaryMediaSource
                    )
                    
                    defaultAudioStreamIndex = primaryMediaSource?.defaultAudioStreamIndex
                    defaultSubtitleStreamIndex = primaryMediaSource?.defaultSubtitleStreamIndex
                    sessionPlaySessionId = playbackInfo.playSessionId
                    sessionMediaSourceId = primaryMediaSource?.id
                    sessionMediaSourceContainer = primaryMediaSource?.container
                    sessionMediaSourceBitrateKbps = primaryMediaSource?.bitrate?.div(1000)
                    sessionPlayMethod = when {
                        primaryMediaSource?.supportsDirectPlay == true -> PlayMethod.DIRECT_PLAY
                        primaryMediaSource?.supportsDirectStream == true -> PlayMethod.DIRECT_STREAM
                        else -> PlayMethod.TRANSCODE
                    }
                    val playbackRequestResult = mediaRepository.getPlaybackRequest(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        maxStreamingHeight = maxStreamingHeight,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        subtitleStreamIndex = activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode,
                        playbackInfo = playbackInfo,
                        includeAccessToken = isMpvPlayback()
                    )
                    if (playbackRequestResult.isFailure) {
                        val error = playbackRequestResult.exceptionOrNull()?.message ?: "Failed to get playback request"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    playbackRequest = playbackRequestResult.getOrNull()
                    val streamingUrl = playbackRequest?.url
                    currentStreamingUrl = streamingUrl
                    if (streamingUrl.isNullOrEmpty()) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Failed to get playback URL")
                        return@launch
                    }
                    val streamUri = Uri.parse(streamingUrl)
                    val streamPlaySessionId = streamUri.getQueryParameter("PlaySessionId")
                        ?: streamUri.getQueryParameter("playSessionId")
                    if (!streamPlaySessionId.isNullOrBlank()) {
                        sessionPlaySessionId = streamPlaySessionId
                    }
                    sessionPlayMethod = getPlayMethod(
                        streamingUrl = streamingUrl,
                        fallback = sessionPlayMethod
                    )

                    val activeSubtitleStreamIndex = (
                        activePreferredSubtitleStreamIndex
                            ?: primaryMediaSource?.defaultSubtitleStreamIndex
                        )?.takeIf { it >= 0 }
                    
                    val activeSubtitleStream = apiMediaStreams
                        ?.firstOrNull { stream ->
                            stream.type.equals("Subtitle", ignoreCase = true) &&
                                stream.index == activeSubtitleStreamIndex
                        }

                    val streamingMediaItem = streamingMediaItem(
                        streamingUrl = streamingUrl,
                        itemId = mediaId,
                        mediaSourceId = sessionMediaSourceId,
                        selectedSubtitleStream = activeSubtitleStream,
                        requestHeaders = playbackRequest?.requestHeaders.orEmpty()
                    )
                    if (!isMpvPlayback()) {
                        streamingMediaSource = PlayerUtils.createStreamingMediaSource(
                            context = context,
                            mediaItem = streamingMediaItem,
                            requestHeaders = playbackRequest?.requestHeaders.orEmpty()
                        )
                    }
                    streamingMediaItem
                }

                playbackSession = PlaybackSessionContext(
                    mediaId = mediaId,
                    playSessionId = sessionPlaySessionId,
                    mediaSourceId = sessionMediaSourceId,
                    mediaSourceContainer = sessionMediaSourceContainer,
                    mediaSourceBitrateKbps = sessionMediaSourceBitrateKbps,
                    playMethod = sessionPlayMethod,
                    isOfflinePlayback = sessionIsOfflinePlayback
                )
                playbackReporter.updateSession(playbackSession)
                
                mpvExternalSubtitleUrls = if (sessionIsOfflinePlayback) {
                    downloadRepository?.getOfflineSubtitlePaths(mediaId).orEmpty()
                } else {
                    MPVPlayer.externalSubtitleUrls(
                        playbackRequest = playbackRequest,
                        mediaStreams = apiMediaStreams.orEmpty(),
                        itemId = mediaId,
                        mediaSourceId = sessionMediaSourceId
                    )
                }

                if (isMpvPlayback()) {
                    val selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex
                        ?: defaultAudioStreamIndex
                    val selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex
                        ?: defaultSubtitleStreamIndex
                    val selectedSecondarySubtitleStreamIndex = _preferredStreamIndexes.value.secondarySubtitleStreamIndex
                    val selectedVideoStream = apiMediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }
                    val hints = listOfNotNull(
                        currentItemDetails?.name,
                        currentItemDetails?.originalTitle,
                        currentItemDetails?.path,
                        currentPlaybackMediaSource?.name,
                        currentPlaybackMediaSource?.path,
                        mediaTitle
                    ).toTypedArray()
                    val isDv = selectedVideoStream?.let { CodecCapabilityManager.isDolbyVision(it, *hints) } ?: false
                    val dvProfile = selectedVideoStream?.let { CodecCapabilityManager.detectDolbyVisionProfile(it, *hints) }
                    val isHdr = resolveMpvHdrFormatLabel().isNotBlank()
                    val deviceHdrSupport = com.jellycine.player.video.HdrCapabilityManager.getDeviceHdrSupport(context)

                    mpvPlayer = createMpvPlayer(context).also { player ->
                        player.setSubtitleDelay(savedSubtitleDelay)
                        val adaptation = player.applyPlaybackAdaptation(
                            isDolbyVision = isDv,
                            dvProfile = dvProfile,
                            isHdr = isHdr,
                            deviceHdrSupport = deviceHdrSupport
                        )
                        if (adaptation == "dv_p5_software") {
                            viewModelScope.launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context.applicationContext,
                                    context.getString(com.jellycine.shared.R.string.player_dv_p5_notice),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        player.load(
                            url = mediaItem.localConfiguration?.uri?.toString().orEmpty(),
                            subtitleUrls = mpvExternalSubtitleUrls.values.toList(),
                            audioTrackId = MPVPlayer.audioTrackId(
                                apiMediaStreams,
                                selectedAudioStreamIndex
                            ),
                            subtitleTrackId = MPVPlayer.subtitleTrackId(
                                apiMediaStreams,
                                selectedSubtitleStreamIndex
                            ),
                            selectedSubtitleUrl = selectedSubtitleStreamIndex?.let(
                                mpvExternalSubtitleUrls::get
                            ),
                            startPositionMs = playerStartPositionMs,
                            startPlayback = startPlayback,
                            secondarySubtitleTrackId = MPVPlayer.subtitleTrackId(
                                apiMediaStreams,
                                selectedSecondarySubtitleStreamIndex
                            ),
                            selectedSecondarySubtitleUrl = selectedSecondarySubtitleStreamIndex?.let(
                                mpvExternalSubtitleUrls::get
                            )
                        )
                    }
                } else {
                    exoPlayer = PlayerUtils.createPlayer(
                        context = context
                    )
                    exoPlayer?.apply {
                        addListener(playerListener)
                        if (streamingMediaSource != null) {
                            setMediaSource(streamingMediaSource!!)
                        } else {
                            setMediaItem(mediaItem)
                        }
                        prepare()

                        if (playerStartPositionMs != null && playerStartPositionMs > 0) {
                            seekTo(playerStartPositionMs)
                        }

                        playWhenReady = startPlayback
                    }
                }

                // Spatial audio analysis and device capabilities
                val usesMpv = isMpvPlayback()
                if (!usesMpv) {
                    updateTrackInformation()
                }
                val hdrFormat = if (usesMpv) {
                    resolveMpvHdrFormatLabel()
                } else {
                    resolveExoHdrFormatLabel()
                }
                val isHdrPlayback = hdrFormat.isNotBlank()
                
                // Apply start maximized setting if enabled
                applyStartMaximizedSetting(context)

                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = startPlayback,
                    hasStartedPlayback = false,
                    mediaTitle = mediaTitle,
                    mediaLogoUrl = mediaLogoUrl,
                    seasonEpisodeLabel = seasonEpisodeLabel,
                    chapterMarkers = chapterMarkers,
                    introStartMs = introSegment?.startMs,
                    introEndMs = introSegment?.endMs,
                    isVideoTranscodingAllowed = isVideoTranscodingAllowed,
                    isAudioTranscodingAllowed = isAudioTranscodingAllowed,
                    currentAudioTranscodeMode = audioTranscodeMode,
                    spatializationResult = null,
                    isSpatialAudioEnabled = false,
                    spatialAudioFormat = "",
                    isHdrEnabled = isHdrPlayback,
                    hdrFormat = hdrFormat,
                    canDelete = itemDetails?.canDelete == true,
                    subtitleFontSizeScale = PlayerPreferences(context).getSubtitleFontSizeScale(),
                    subtitleTextColor = PlayerPreferences(context).getSubtitleTextColor(),
                    subtitleBottomPositionPercent = PlayerPreferences(context).getSubtitleBottomEdgePositionPercent()
                )
                if (usesMpv) {
                    updateApiTrackInformation()
                }
                if (itemDetails != null) {
                    applyCommunityPlaybackSegments(mediaId = mediaId, itemDetails = itemDetails)
                }
                analyzeSpatialAudioAsync(
                    context = context,
                    mediaId = mediaId,
                    mediaStreams = apiMediaStreams,
                    helper = spatializerHelper
                )

            } catch (e: Exception) {
                Log.e(TAG, "Player initialization failed", e)
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    fun initializeRemotePlayer(
        context: Context,
        mediaId: String,
        remoteUrl: String,
        title: String? = null,
        startPlayback: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val mediaTitle = title?.takeIf { it.isNotBlank() } ?: "Trailer"
                releasePlayer()
                remotePlaybackRequestKey = mediaId
                _playerState.value = PlayerState(
                    isLoading = true,
                    playWhenReady = startPlayback,
                    mediaTitle = mediaTitle
                )

                playerContext = context
                activePlayerEngine = PlayerPreferences.PLAYER_ENGINE_EXO
                hasHandledPlaybackCompletion = false
                hasRenderedFirstFrame = false
                currentItemDetails = null
                currentPlaybackMediaSource = null
                apiMediaStreams = null
                defaultAudioStreamIndex = null
                defaultSubtitleStreamIndex = null
                playbackSession = PlaybackSessionContext()
                playbackReporter.updateSession(playbackSession)

                val playbackStream = RemoteTrailerUrl.resolve(remoteUrl)
                currentStreamingUrl = playbackStream.url
                if (remotePlaybackRequestKey != mediaId) return@launch
                fun mediaSource(url: String, mimeType: String?) =
                    PlayerUtils.createStreamingMediaSource(
                        context = context,
                        mediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(url))
                            .setMimeType(mimeType)
                            .build()
                    )

                val videoSource = mediaSource(playbackStream.url, playbackStream.mimeType)
                val playbackMediaSource = playbackStream.audioUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { audioUrl ->
                        MergingMediaSource(
                            videoSource,
                            mediaSource(audioUrl, playbackStream.audioMimeType)
                        )
                    }
                    ?: videoSource

                exoPlayer = PlayerUtils.createPlayer(
                    context = context,
                    bufferOverride = PlayerUtils.PlaybackBufferOverride(
                        minBufferMs = 30_000,
                        maxBufferMs = 180_000,
                        bufferForPlaybackMs = 2_500,
                        bufferForPlaybackAfterRebufferMs = 5_000
                    )
                )
                exoPlayer?.apply {
                    addListener(playerListener)
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setForceHighestSupportedBitrate(true)
                        .build()
                    setMediaSource(playbackMediaSource)
                    prepare()
                    playWhenReady = startPlayback
                }

                applyStartMaximizedSetting(context)
            } catch (e: Exception) {
                Log.e(TAG, "Remote trailer initialization failed", e)
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unable to play remote trailer",
                    playWhenReady = false,
                    isPlaying = false
                )
            }
        }
    }

    private fun analyzeSpatialAudioAsync(
        context: Context,
        mediaId: String,
        mediaStreams: List<MediaStream>?,
        helper: SpatializerHelper?
    ) {
        spatialAudioAnalysisJob?.cancel()
        val primaryAudioStream = mediaStreams
            ?.firstOrNull { it.type == "Audio" }
            ?: return

        spatialAudioAnalysisJob = viewModelScope.launch {
            val spatializationResult = withContext(Dispatchers.Default) {
                CodecCapabilityManager.canSpatializeAudioStream(
                    context = context,
                    audioStream = primaryAudioStream,
                    spatializerHelper = helper
                )
            }
            if (playbackSession.mediaId != mediaId) return@launch

            _playerState.value = _playerState.value.copy(
                spatializationResult = spatializationResult,
                isSpatialAudioEnabled = spatializationResult.canSpatialize,
                spatialAudioFormat = spatializationResult.spatialFormat
            )
        }
    }

    private fun updateMpvWatchdog() {
        val player = exoPlayer
        val mediaId = playbackSession.mediaId
        val shouldWatch = player != null &&
            mediaId != null &&
            !isMpvPlayback() &&
            !hasRenderedFirstFrame &&
            currentMediaHasVideo() &&
            player.playWhenReady &&
            player.playbackState == Player.STATE_READY

        if (!shouldWatch) {
            cancelMpvWatchdog()
            return
        }
        if (mpvWatchdogJob?.isActive == true) return

        cancelMpvWatchdog()
        mpvWatchdogJob = viewModelScope.launch {
            delay(MPV_FALLBACK_FIRST_FRAME_TIMEOUT_MS)
            val currentPlayer = exoPlayer ?: return@launch
            if (
                playbackSession.mediaId == mediaId &&
                !isMpvPlayback() &&
                !hasRenderedFirstFrame &&
                currentMediaHasVideo() &&
                currentPlayer.playWhenReady &&
                currentPlayer.playbackState == Player.STATE_READY
            ) {
                triggerMpvFallback()
            }
        }
    }

    private fun triggerMpvFallback(): Boolean {
        if (isMpvPlayback()) return false
        val context = playerContext ?: return false
        val mediaId = playbackSession.mediaId ?: return false

        cancelMpvWatchdog()

        val itemDetails = currentItemDetails
        val resumePositionMs = getCurrentPosition()
        val shouldResumePlaying = _playerState.value.playWhenReady || isPlayingNow()
        val preferredAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex
        val preferredSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex

        releasePlayer()
        initializePlayer(
            context = context,
            mediaId = mediaId,
            initialItemDetails = itemDetails,
            preferredAudioStreamIndex = preferredAudioStreamIndex,
            preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying,
            forcedPlayerEngine = PlayerPreferences.PLAYER_ENGINE_MPV
        )
        return true
    }

    private fun cancelMpvWatchdog() {
        mpvWatchdogJob?.cancel()
        mpvWatchdogJob = null
    }

    private fun currentMediaHasVideo(): Boolean =
        apiMediaStreams.isNullOrEmpty() || apiMediaStreams.orEmpty().any { stream ->
            stream.type.equals("Video", ignoreCase = true)
        }

    private fun applyCommunityPlaybackSegments(mediaId: String, itemDetails: BaseItemDto) {
        if (!itemDetails.type.equals("Episode", ignoreCase = true)) return

        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = viewModelScope.launch {
            val playbackSegments = mediaRepository.getCommunityPlaybackSegments(itemDetails).getOrNull()
                ?: return@launch
            if (playbackSession.mediaId != mediaId) return@launch

            val currentState = _playerState.value
            _playerState.value = currentState.copy(
                recapStartMs = currentState.recapStartMs ?: playbackSegments.recap?.startMs,
                recapEndMs = currentState.recapEndMs ?: playbackSegments.recap?.endMs,
                introStartMs = currentState.introStartMs ?: playbackSegments.intro?.startMs,
                introEndMs = currentState.introEndMs ?: playbackSegments.intro?.endMs,
                creditsStartMs = currentState.creditsStartMs ?: playbackSegments.credits?.startMs,
                creditsEndMs = currentState.creditsEndMs ?: playbackSegments.credits?.endMs,
                previewStartMs = currentState.previewStartMs ?: playbackSegments.preview?.startMs,
                previewEndMs = currentState.previewEndMs ?: playbackSegments.preview?.endMs
            )
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        mpvPlayer?.seekTo(position)
        _playerState.value = _playerState.value.copy(currentPosition = position)
    }

    fun play() {
        exoPlayer?.play()
        mpvPlayer?.play()
        if (isMpvPlayback()) {
            _playerState.value = _playerState.value.copy(isPlaying = true, playWhenReady = true)
        }
    }

    fun pause() {
        exoPlayer?.pause()
        mpvPlayer?.pause()
        if (isMpvPlayback()) {
            _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
        }
        persistPosition()
    }

    private var speedBeforeLongPress: Float = 1.0f
    private var wasPlayingBeforeLongPress: Boolean = true

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        mpvPlayer?.setPlaybackSpeed(speed)
    }

    fun getPlaybackSpeed(): Float {
        return exoPlayer?.playbackParameters?.speed
            ?: mpvPlayer?.getPlaybackSpeed()
            ?: 1.0f
    }

    fun startLongPressSpeed(speed: Float) {
        speedBeforeLongPress = getPlaybackSpeed()
        wasPlayingBeforeLongPress = isPlayingNow()
        setPlaybackSpeed(speed)
        if (!wasPlayingBeforeLongPress) {
            play()
        }
    }

    fun endLongPressSpeed() {
        setPlaybackSpeed(speedBeforeLongPress)
        if (!wasPlayingBeforeLongPress) {
            pause()
        }
    }

    fun seekToProgress(progress: Float) {
        val duration = getDuration()
        if (duration > 0L) {
            seekTo((duration * progress).toLong())
        }
    }

    fun seekBy(deltaMs: Long) {
        val currentPosition = getCurrentPosition()
        val duration = getDuration()
        val targetPosition = if (duration > 0L) {
            (currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (currentPosition + deltaMs).coerceAtLeast(0L)
        }
        seekTo(targetPosition)
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: mpvPlayer?.currentPosition ?: 0L

    fun getBufferedPosition(): Long = if (isMpvPlayback()) {
        mpvPlayer?.bufferedPosition ?: 0L
    } else {
        exoPlayer?.bufferedPosition?.coerceAtLeast(0L) ?: 0L
    }

    fun getCacheSpeed(): Long {
        if (isMpvPlayback()) {
            return mpvPlayer?.cacheSpeedBytes ?: 0L
        }
        val exo = exoPlayer
        if (exo != null) {
            return com.jellycine.player.core.PlayerUtils.getDownloadSpeedBps()
        }
        return 0L
    }

    fun getVideoAspectRatio(): Float? {
        if (isMpvPlayback()) {
            val aspect = mpvPlayer?.videoAspectRatio
            if (aspect != null && aspect > 0f) return aspect
        } else {
            val videoSize = exoPlayer?.videoSize
            if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                return (videoSize.width.toFloat() * pixelRatio) / videoSize.height.toFloat()
            }
        }
        val mediaStreams = currentItemDetails?.mediaStreams
        val videoStream = mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }
        val w = videoStream?.width
        val h = videoStream?.height
        if (w != null && h != null && w > 0 && h > 0) {
            return w.toFloat() / h.toFloat()
        }
        return null
    }

    fun isPlayingNow(): Boolean = exoPlayer?.isPlaying == true || mpvPlayer?.isPlaying == true

    fun getDuration(): Long {
        val playerDuration = exoPlayer?.duration?.coerceAtLeast(0L) ?: mpvPlayer?.duration ?: 0L
        if (playerDuration > 0L) return playerDuration
        return currentItemDetails?.runTimeTicks?.takeIf { it > 0L }?.div(10_000L) ?: 0L
    }

    fun updateNextEpisodeCache(
        context: Context,
        nextEpisodeId: String?,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?
    ) {
        val playerPreferences = PlayerPreferences(context)
        val targetEpisodeId = nextEpisodeId?.takeIf { it.isNotBlank() }
        if (
            targetEpisodeId == null ||
            targetEpisodeId == playbackSession.mediaId ||
            !playerPreferences.isCacheNextEpisodeEnabled()
        ) {
            cancelNextEpisodePrefetch()
            return
        }
        val prefetchSignature = buildString {
            append(targetEpisodeId)
            append('|')
            append(preferredAudioStreamIndex ?: "auto")
            append('|')
            append(preferredSubtitleStreamIndex ?: "auto")
            append('|')
            append(playerPreferences.getStreamingQuality())
            append('|')
            append(playerPreferences.getAudioTranscodeMode().name)
        }

        if (
            nextEpisodePrefetchSignature == prefetchSignature &&
            nextEpisodePrefetchJob?.isActive == true
        ) {
            return
        }

        cancelNextEpisodePrefetch()
        nextEpisodePrefetchSignature = prefetchSignature
        nextEpisodePrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                prefetchNextEpisode(
                    context = context.applicationContext,
                    nextEpisodeId = targetEpisodeId,
                    preferredAudioStreamIndex = preferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
                    playerPreferences = playerPreferences
                )
            }.onFailure { error ->
                Log.d(TAG, "Skipping next-episode cache prefetch for $targetEpisodeId", error)
            }
        }
    }

    private suspend fun prefetchNextEpisode(
        context: Context,
        nextEpisodeId: String,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?,
        playerPreferences: PlayerPreferences
    ) {
        val nextDownloadRepository = downloadRepository ?: DownloadRepositoryProvider.getInstance(context)
        val offlinePath = nextDownloadRepository.getOfflineFilePath(nextEpisodeId)
        if (!offlinePath.isNullOrBlank()) {
            return
        }

        val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
        val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
        val audioTranscodeMode = if (isAudioTranscodingAllowed) {
            playerPreferences.getAudioTranscodeMode()
        } else {
            AudioTranscodeMode.AUTO
        }
        val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
            playerPreferences.getMaxStreamingBitrate()
        } else {
            null
        }
        val maxStreamingHeight = if (isVideoTranscodingAllowed) {
            playerPreferences.getStreamingQualityMaxHeight()
        } else {
            null
        }

        val playbackInfo = mediaRepository.getPlaybackInfo(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode
        ).getOrNull() ?: return

        val playbackRequest = mediaRepository.getPlaybackRequest(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            maxStreamingHeight = maxStreamingHeight,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode,
            playbackInfo = playbackInfo
        ).getOrNull() ?: return

        val streamingUrl = playbackRequest.url?.takeIf { it.isNotBlank() } ?: return
        val nextMediaItem = streamingMediaItem(streamingUrl = streamingUrl)
        val localConfiguration = nextMediaItem.localConfiguration ?: return
        val prefetchBytes = nextEpisodePrefetchBytes(
            playerPreferences = playerPreferences,
            sourceBitrate = playbackInfo.mediaSources?.firstOrNull()?.bitrate,
            maxStreamingBitrate = maxStreamingBitrate
        )

        PlayerUtils.prefetchStreamingMedia(
            context = context,
            streamUri = localConfiguration.uri,
            cacheKey = localConfiguration.customCacheKey,
            maxBytes = prefetchBytes,
            requestHeaders = playbackRequest.requestHeaders
        )
    }

    private fun nextEpisodePrefetchBytes(
        playerPreferences: PlayerPreferences,
        sourceBitrate: Int?,
        maxStreamingBitrate: Int?
    ): Long {
        val bitrateBitsPerSecond = when {
            maxStreamingBitrate != null && maxStreamingBitrate > 0 -> maxStreamingBitrate.toLong()
            sourceBitrate != null && sourceBitrate > 0 -> sourceBitrate.toLong()
            else -> 8_000_000L
        }.coerceAtLeast(2_000_000L)
        val prefetchWindowSeconds = minOf(playerPreferences.getPlayerCacheTimeSeconds(), 45)
        val desiredBytes = bitrateBitsPerSecond
            .times(prefetchWindowSeconds.toLong())
            .div(8L)
        val cacheBudgetBytes = playerPreferences.getPlayerCacheSizeMb()
            .toLong()
            .times(1024L * 1024L)
            .div(3L)

        return minOf(desiredBytes, cacheBudgetBytes).coerceAtLeast(8L * 1024L * 1024L)
    }

    private fun cancelNextEpisodePrefetch() {
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = null
        nextEpisodePrefetchSignature = null
    }

    private fun getPlayMethod(
        streamingUrl: String,
        fallback: PlayMethod
    ): PlayMethod {
        val streamUri = Uri.parse(streamingUrl)
        val path = streamUri.encodedPath.orEmpty().lowercase()
        val isTranscodingUrl =
            path.contains("master.m3u8") ||
                path.contains("transcode") ||
                path.contains("transcoding")

        if (isTranscodingUrl) {
            return PlayMethod.TRANSCODE
        }

        return when (streamUri.getQueryParameter("static")?.lowercase()) {
            "true" -> PlayMethod.DIRECT_PLAY
            "false" -> PlayMethod.DIRECT_STREAM
            else -> fallback
        }
    }

    private suspend fun isVideoTranscodingAllowedForUser(): Boolean {
        videoTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isVideoTranscodingAllowed?.let {
            videoTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableVideoPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        videoTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isVideoTranscodingAllowed = allowed)
        return allowed
    }

    private suspend fun isAudioTranscodingAllowedForUser(): Boolean {
        audioTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isAudioTranscodingAllowed?.let {
            audioTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableAudioPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        audioTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isAudioTranscodingAllowed = allowed)
        return allowed
    }

    fun togglePlayPause() {
        if (exoPlayer != null || mpvPlayer != null) {
            if (isPlayingNow()) pause() else play()
            playbackReporter.onPlaybackPauseStateChanged()
        }
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
        mpvPlayer?.setVolume(volume)
        _playerState.value = _playerState.value.copy(volume = volume)
    }

    fun setBrightness(brightness: Float) {
        _playerState.value = _playerState.value.copy(brightness = brightness)
    }

    fun toggleControls() {
        _playerState.value = _playerState.value.copy(showControls = !_playerState.value.showControls)
    }

    fun releasePlayer() {
        persistPosition()
        playbackReporter.reportPlaybackStopped()
        cancelNextEpisodePrefetch()
        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = null
        spatialAudioAnalysisJob?.cancel()
        spatialAudioAnalysisJob = null
        cancelMpvWatchdog()
        initialCropDetectionJob?.cancel()
        initialCropDetectionJob = null
        detectedVideoCropBoundaries = null
        exoPlayer?.apply {
            removeListener(playerListener)
            release()
        }
        exoPlayer = null
        mpvPlayer?.release()
        mpvPlayer = null
        spatializerHelper?.cleanup()
        spatializerHelper = null
        playbackSession = PlaybackSessionContext()
        playbackReporter.reset()
        trackSelectionCoordinator.clear()
        apiMediaStreams = null
        currentPlaybackMediaSource = null
        currentStreamingUrl = null
        defaultAudioStreamIndex = null
        defaultSubtitleStreamIndex = null
        mpvExternalSubtitleUrls = emptyMap()
        playerContext = null
        downloadRepository = null
        hasHandledPlaybackCompletion = false
        hasRenderedFirstFrame = false
        audioDiagnosticsSignature = null
        remotePlaybackRequestKey = null
        _preferredStreamIndexes.value = PreferredStreamIndexes()
        _playerState.value = PlayerState()
    }

    private fun handlePlaybackCompleted() {
        if (hasHandledPlaybackCompletion) return
        hasHandledPlaybackCompletion = true
        persistPosition(markCompleted = true)
        playbackReporter.reportPlaybackStopped()
        playbackSession.mediaId?.let { completedMediaId ->
            _playbackCompletedEvents.tryEmit(completedMediaId)
        }
    }

    private fun persistPosition(markCompleted: Boolean = false) {
        val session = playbackSession
        if (!session.isOfflinePlayback) return
        val mediaId = session.mediaId ?: return
        downloadRepository?.updatePlaybackPosition(
            itemId = mediaId,
            positionMs = getCurrentPosition(),
            markCompleted = markCompleted
        )
    }

    fun clearError() {
        _playerState.value = _playerState.value.copy(error = null)
    }

    /**
     * Toggle lock state - when locked, disable all gestures and hide controls
     */
    fun toggleLock() {
        val currentState = _playerState.value
        _playerState.value = currentState.copy(
            isLocked = !currentState.isLocked,
            showControls = if (!currentState.isLocked) false else currentState.showControls
        )
    }

    /**
     * Update track information from ExoPlayer
     */
    private fun updateTrackInformation() {
        exoPlayer?.let { player ->
            try {
                val hdrFormat = resolveExoHdrFormatLabel()
                val isHdrPlayback = hdrFormat.isNotBlank()
                val selectedAudioSignature = buildSelectedAudioSignature(player)
                if (selectedAudioSignature != null && selectedAudioSignature != audioDiagnosticsSignature) {
                    PlayerUtils.logAudioPlaybackDiagnostics(player, reason = "track_changed")
                    audioDiagnosticsSignature = selectedAudioSignature
                }
                val resolvedTracks = PlayerTrack.currentTrackState(
                    exoPlayer = player,
                    mediaStreams = apiMediaStreams,
                    isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE,
                    selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                    selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
                    defaultAudioStreamIndex = defaultAudioStreamIndex,
                    defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
                )
                val syncedPreferredIndexes = trackSelectionCoordinator.syncPreferredIndexesFromCurrentTracks(
                    context = playerContext,
                    mediaId = playbackSession.mediaId,
                    currentAudioTrack = resolvedTracks.currentAudioTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentPublished = _preferredStreamIndexes.value
                )
                if (syncedPreferredIndexes != _preferredStreamIndexes.value) {
                    _preferredStreamIndexes.value = syncedPreferredIndexes
                }

                _playerState.value = _playerState.value.copy(
                    availableAudioTracks = resolvedTracks.availableAudioTracks,
                    currentAudioTrack = resolvedTracks.currentAudioTrack,
                    availableSubtitleTracks = resolvedTracks.availableSubtitleTracks,
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack,
                    availableVideoTracks = resolvedTracks.availableVideoTracks,
                    currentVideoTrack = resolvedTracks.availableVideoTracks.firstOrNull(),
                    isHdrEnabled = isHdrPlayback,
                    hdrFormat = hdrFormat
                )
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to update track information", e)
            }
        }
    }

    @UnstableApi
    private fun buildSelectedAudioSignature(player: ExoPlayer): String? {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            val trackIndex = (0 until group.mediaTrackGroup.length)
                .firstOrNull(group::isTrackSelected)
                ?: return@forEachIndexed
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            return "$groupIndex:$trackIndex|${format.channelCount}|${format.bitrate}|${format.sampleRate}|${format.codecs ?: format.sampleMimeType.orEmpty()}"
        }
        return null
    }

    private fun applyPendingTrackSelectionsIfNeeded() {
        val player = exoPlayer ?: return
        val appliedAnySelection = trackSelectionCoordinator.applyInitialSelections(
            player = player,
            mediaStreams = apiMediaStreams,
            isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE
        )
        if (appliedAnySelection) {
            viewModelScope.launch {
                delay(250)
                updateTrackInformation()
            }
        }
    }

    private fun updateApiTrackInformation() {
        val trackState = MPVPlayer.trackState(
            mediaStreams = apiMediaStreams,
            selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
            selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
        )
        val hdrFormat = resolveMpvHdrFormatLabel()

        val videoStream = apiMediaStreams?.firstOrNull { it.type?.equals("Video", ignoreCase = true) == true }
        val currentVideo = trackState.availableVideoTracks.firstOrNull() ?: videoStream?.let {
            com.jellycine.player.core.VideoTrackInfo(
                id = it.index?.toString() ?: "0",
                label = "Video",
                width = it.width ?: 0,
                height = it.height ?: 0,
                codec = it.codec
            )
        }

        val secSubtitle = _playerState.value.currentSecondarySubtitleTrack
            ?: _preferredStreamIndexes.value.secondarySubtitleStreamIndex?.let { secIndex ->
                trackState.availableSubtitleTracks.firstOrNull { it.streamIndex == secIndex }
            }

        _playerState.value = _playerState.value.copy(
            availableAudioTracks = trackState.availableAudioTracks,
            currentAudioTrack = trackState.currentAudioTrack,
            availableSubtitleTracks = trackState.availableSubtitleTracks,
            currentSubtitleTrack = trackState.currentSubtitleTrack,
            currentSecondarySubtitleTrack = secSubtitle,
            availableVideoTracks = trackState.availableVideoTracks,
            currentVideoTrack = currentVideo,
            isHdrEnabled = hdrFormat.isNotBlank(),
            hdrFormat = hdrFormat
        )
    }

    private suspend fun createMpvPlayer(context: Context): MpvPlayerController {
        val preferences = PlayerPreferences(context)
        val listener = createMpvListener()
        return MpvWarmPool.acquire(
            context = context,
            listener = listener
        ) ?: withContext(Dispatchers.Default) {
            MpvPlayerController(
                context = context,
                hardwareDecoding = preferences.getMpvHardwareDecoding(),
                videoOutput = preferences.getMpvVideoOutput(),
                audioOutput = preferences.getMpvAudioOutput(),
                listener = listener
            )
        }
    }

    private fun createMpvListener(): MpvPlayerController.Listener {
        return object : MpvPlayerController.Listener {
            override fun onBuffering() {
                _playerState.value = _playerState.value.copy(isLoading = true)
            }

            override fun onReady() {
                val wasPlaying = _playerState.value.isPlaying
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    isPlaying = isPlayingNow(),
                    playWhenReady = isPlayingNow(),
                    hasStartedPlayback = true,
                    duration = getDuration()
                )
                // Refine DV profile if MPV detects it from stream at runtime
                val currentHdr = _playerState.value.hdrFormat
                val isDv = currentHdr.contains("Dolby Vision", ignoreCase = true) ||
                    currentHdr.startsWith("DV", ignoreCase = true)
                if (isDv && !currentHdr.contains("P", ignoreCase = true)) {
                    val runtimeProfile = mpvPlayer?.detectRuntimeDvProfile()
                    if (runtimeProfile != null) {
                        _playerState.value = _playerState.value.copy(
                            hdrFormat = "Dolby Vision P$runtimeProfile",
                            isHdrEnabled = true
                        )
                    }
                }
                if (!playbackReporter.hasReportedStart() && isPlayingNow()) {
                    playbackReporter.reportPlaybackStatus()
                }
                if (wasPlaying != isPlayingNow()) {
                    playbackReporter.onPlaybackPauseStateChanged()
                }
                if (!wasPlaying) {
                    scheduleInitialCropDetection()
                }
            }

            override fun onEnded() {
                _playerState.value = _playerState.value.copy(
                    isPlaying = false,
                    playWhenReady = false,
                    isLoading = false
                )
                handlePlaybackCompleted()
            }
        }
    }

    /**
     * Select audio track by ID
     */
    fun selectAudioTrack(trackId: String) {
        if (trackId == _playerState.value.currentAudioTrack?.id) return
        val selectedTrack = _playerState.value.availableAudioTracks.firstOrNull { it.id == trackId } ?: return
        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectAudioTrack(mpvPlayer, selectedTrack) ?: return
            val (preferences, mediaId) = currentMediaPreferences() ?: return
            preferences.setPreferredAudioStreamIndex(mediaId, streamIndex)
            _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(audioStreamIndex = streamIndex)
            _playerState.value = _playerState.value.copy(currentAudioTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            playbackTrackSelection(
                audioStreamIndex = selectedTrack.streamIndex,
                subtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectAudioTrack(player, playerTrackId)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    /**
     * Select subtitle track by ID
     */
    fun selectSubtitleTrack(trackId: String) {
        Log.d(TAG, "Selecting subtitle track: $trackId")
        if (trackId == _playerState.value.currentSubtitleTrack?.id) {
            Log.d(TAG, "Subtitle track $trackId already selected")
            return
        }
        val selectedTrack = _playerState.value.availableSubtitleTracks.firstOrNull { it.id == trackId } ?: run {
            Log.w(TAG, "Could not find subtitle track with id $trackId in available tracks")
            return
        }
        
        Log.d(TAG, "Selected track info: label=${selectedTrack.label} streamIndex=${selectedTrack.streamIndex} requiresRestart=${selectedTrack.requiresPlaybackRestart}")

        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectSubtitleTrack(
                controller = mpvPlayer,
                track = selectedTrack,
                externalSubtitleUrls = mpvExternalSubtitleUrls
            ) ?: return
            val (preferences, mediaId) = currentMediaPreferences() ?: return
            preferences.setPreferredSubtitleStreamIndex(
                mediaId,
                streamIndex.takeUnless { it < 0 }
            )
            _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(
                subtitleStreamIndex = streamIndex.takeUnless { it < 0 }
            )
            _playerState.value = _playerState.value.copy(currentSubtitleTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            Log.d(TAG, "Subtitle selection requires playback restart for stream index: ${selectedTrack.streamIndex}")
            playbackTrackSelection(
                audioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                subtitleStreamIndex = selectedTrack.streamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            Log.d(TAG, "Applying subtitle selection to ExoPlayer: $playerTrackId")
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectSubtitleTrack(player, playerTrackId)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    /**
     * Select secondary subtitle track by ID (dual subtitle / danmaku)
     */
    fun selectSecondarySubtitleTrack(trackId: String) {
        Log.d(TAG, "Selecting secondary subtitle track: $trackId")
        if (trackId == _playerState.value.currentSecondarySubtitleTrack?.id) {
            Log.d(TAG, "Secondary subtitle track $trackId already selected")
            return
        }
        val selectedTrack = if (trackId == "off") {
            _playerState.value.availableSubtitleTracks.firstOrNull { it.id == "off" || it.streamIndex == -1 }
                ?: SubtitleTrackInfo(id = "off", label = "Off", language = null, streamIndex = -1)
        } else {
            _playerState.value.availableSubtitleTracks.firstOrNull { it.id == trackId }
        } ?: run {
            Log.w(TAG, "Could not find secondary subtitle track with id $trackId in available tracks")
            return
        }

        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectSecondarySubtitleTrack(
                controller = mpvPlayer,
                track = selectedTrack,
                externalSubtitleUrls = mpvExternalSubtitleUrls
            )
            val isOff = selectedTrack.id == "off" || (streamIndex ?: -1) < 0
            _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(
                secondarySubtitleStreamIndex = if (isOff) null else streamIndex
            )
            _playerState.value = _playerState.value.copy(
                currentSecondarySubtitleTrack = if (isOff) null else selectedTrack
            )
            return
        } else {
            playerContext?.let { ctx ->
                viewModelScope.launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx.applicationContext,
                        "第二字幕/弹幕需要使用 MPV 播放核心，请在播放器设置中切换为 MPV",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Adjust or set subtitle timing delay in seconds.
     * Positive value displays subtitles later; negative value displays subtitles earlier.
     */
    fun setSubtitleDelay(seconds: Double) {
        val rounded = (kotlin.math.round(seconds * 10.0) / 10.0).coerceIn(-60.0, 60.0)
        Log.d(TAG, "Setting subtitle delay: ${rounded}s")
        _playerState.value = _playerState.value.copy(subtitleDelay = rounded)
        if (isMpvPlayback()) {
            mpvPlayer?.setSubtitleDelay(rounded)
        }
        val (preferences, mediaId) = currentMediaPreferences() ?: return
        preferences.setSubtitleDelay(mediaId, rounded)
    }

    fun adjustSubtitleDelay(delta: Double) {
        setSubtitleDelay(_playerState.value.subtitleDelay + delta)
    }

    fun resetSubtitleDelay() {
        setSubtitleDelay(0.0)
    }

    /**
     * Delete currently playing media from the server.
     */
    fun deleteCurrentMedia(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val mediaId = playbackSession.mediaId ?: return
        viewModelScope.launch {
            releasePlayer()
            val result = mediaRepository.deleteItem(mediaId)
            if (result.isSuccess) {
                UserDataRefreshSignals.notifyUserDataChanged(mediaId)
                onSuccess()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Delete failed"
                onError(errorMsg)
            }
        }
    }

    private fun currentMediaPreferences(): Pair<PlayerPreferences, String>? {
        val context = playerContext ?: return null
        val mediaId = playbackSession.mediaId ?: return null
        return PlayerPreferences(context) to mediaId
    }

    private fun playbackTrackSelection(
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ) {
        val context = playerContext ?: return
        val mediaId = playbackSession.mediaId ?: return
        val resumePositionMs = getCurrentPosition()
        val shouldResumePlaying = isPlayingNow()

        PlayerPreferences(context).apply {
            setPreferredAudioStreamIndex(mediaId, audioStreamIndex)
            setPreferredSubtitleStreamIndex(mediaId, subtitleStreamIndex)
        }

        releasePlayer()
        initializePlayer(
            context = context,
            mediaId = mediaId,
            initialItemDetails = currentItemDetails,
            preferredAudioStreamIndex = audioStreamIndex,
            preferredSubtitleStreamIndex = subtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying
        )
    }

    private var currentAspectRatio by mutableIntStateOf(2)
    private val aspectRatioModes = listOf("横向全屏", "纵向全屏", "默认全屏")

    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    /**
     * Toggle between horizontal fullscreen, vertical fullscreen, and default fullscreen modes.
     * Uses BlackBarDetector to detect black bars excluding subtitles and scales accordingly.
     */
    fun cycleAspectRatio(
        screenWidth: Float = 0f,
        screenHeight: Float = 0f,
        onToast: ((String) -> Unit)? = null
    ) {
        val nextMode = (currentAspectRatio + 1) % aspectRatioModes.size
        setAspectRatioMode(nextMode, screenWidth, screenHeight, onToast)
    }
    
    /**
     * Get current resize mode for VideoSurface
     */
    fun getCurrentResizeMode(): Int = currentResizeMode
    
    /**
     * Handle pinch-to-zoom gesture to set appropriate resize mode
     */
    fun handlePinchZoom(isZooming: Boolean) {
        if (isZooming && currentAspectRatio == 2) {
            setAspectRatioMode(0)
        } else if (!isZooming && currentAspectRatio != 2) {
            setAspectRatioMode(2)
        }
    }

    /**
     * Update video scale and translation offsets for free zoom and pan
     */
    fun updateVideoTransform(scaleMultiplier: Float, deltaX: Float, deltaY: Float) {
        val current = _playerState.value
        val newScale = (current.videoScale * scaleMultiplier).coerceIn(0.5f, 5.0f)
        val newOffsetX = current.videoOffsetX + deltaX
        val newOffsetY = current.videoOffsetY + deltaY
        _playerState.value = current.copy(
            videoScale = newScale,
            videoOffsetX = newOffsetX,
            videoOffsetY = newOffsetY
        )
        mpvPlayer?.setVideoTransform(newScale, newOffsetX, newOffsetY)
    }

    /**
     * Called when transform gesture ends. Snaps back to 1.0x if very close to center.
     */
    fun onTransformEnd() {
        val current = _playerState.value
        if (kotlin.math.abs(current.videoScale - 1f) < 0.05f &&
            kotlin.math.hypot(current.videoOffsetX, current.videoOffsetY) < 40f
        ) {
            resetVideoTransform()
        }
    }

    /**
     * Reset video scale to 1.0x and translation offsets to (0, 0)
     */
    fun resetVideoTransform() {
        _playerState.value = _playerState.value.copy(
            videoScale = 1f,
            videoOffsetX = 0f,
            videoOffsetY = 0f
        )
        mpvPlayer?.setVideoTransform(1f, 0f, 0f)
    }

    /**
     * Apply start maximized setting based on user preference
     */
    private fun applyStartMaximizedSetting(context: Context) {
        val playerPreferences = PlayerPreferences(context)
        val startMaximized = playerPreferences.isStartMaximizedEnabled()
        
        setAspectRatioMode(if (startMaximized) 0 else 2)
    }

    /**
     * Accurately resolve actual video aspect ratio from MPV, ExoPlayer, or stream metadata.
     */
    fun getVideoAspect(): Float {
        // 1. MPV player runtime video aspect
        mpvPlayer?.videoAspectRatio?.takeIf { it > 0.1f }?.let { return it }

        // 2. ExoPlayer runtime video size
        exoPlayer?.videoSize?.let {
            if (it.width > 0 && it.height > 0) {
                return it.width.toFloat() / it.height.toFloat()
            }
        }

        // 3. Current video track in state
        _playerState.value.currentVideoTrack?.let {
            if (it.width > 0 && it.height > 0) {
                return it.width.toFloat() / it.height.toFloat()
            }
        }

        // 4. API media stream metadata
        apiMediaStreams?.firstOrNull { it.type?.equals("Video", ignoreCase = true) == true }?.let { stream ->
            val w = stream.width ?: 0
            val h = stream.height ?: 0
            if (w > 0 && h > 0) {
                return w.toFloat() / h.toFloat()
            }
            stream.aspectRatio?.let { aspectStr ->
                val parts = aspectStr.split(":")
                if (parts.size == 2) {
                    val num = parts[0].toFloatOrNull()
                    val den = parts[1].toFloatOrNull()
                    if (num != null && den != null && den > 0f) {
                        return num / den
                    }
                }
            }
        }

        // 5. Default fallback to 16:9 standard video
        return 16f / 9f
    }

    private fun scheduleInitialCropDetection() {
        initialCropDetectionJob?.cancel()
        initialCropDetectionJob = viewModelScope.launch(Dispatchers.Default) {
            delay(800)
            if (detectedVideoCropBoundaries != null && detectedVideoCropBoundaries!!.hasCrop) return@launch
            val mpv = mpvPlayer
            val ctx = playerContext
            var detected: CropBoundaries? = null
            if (mpv != null && ctx != null) {
                try {
                    val tempFile = File(ctx.cacheDir, "crop_detect.jpg")
                    if (mpv.takeVideoSnapshot(tempFile)) {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                        if (bmp != null) {
                            val b = BlackBarDetector.analyzeBitmap(bmp)
                            bmp.recycle()
                            tempFile.delete()
                            if (b.hasCrop) detected = b
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed initial MPV snapshot crop analysis", e)
                }
            }

            val surfaceView = activeSurfaceView?.get()
            if (detected == null && surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
                withContext(Dispatchers.Main) {
                    if (_playerState.value.videoScale == 1.0f) {
                        BlackBarDetector.detect(surfaceView) { boundaries ->
                            if (boundaries.hasCrop) {
                                Log.d(TAG, "Auto-detected initial crop boundaries from default view: $boundaries")
                                detectedVideoCropBoundaries = boundaries
                                if (currentAspectRatio != 2) {
                                    setAspectRatioMode(currentAspectRatio)
                                }
                            }
                        }
                    }
                }
                return@launch
            }

            if (detected != null && detected.hasCrop) {
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Auto-detected initial crop boundaries from MPV snapshot: $detected")
                    detectedVideoCropBoundaries = detected
                    if (currentAspectRatio != 2) {
                        setAspectRatioMode(currentAspectRatio)
                    }
                }
            }
        }
    }

    fun setAspectRatioMode(
        modeIndex: Int,
        screenWidth: Float = 0f,
        screenHeight: Float = 0f,
        onToast: ((String) -> Unit)? = null
    ) {
        val nextMode = modeIndex.coerceIn(0, aspectRatioModes.lastIndex)
        currentAspectRatio = nextMode
        val modeTitle = aspectRatioModes[nextMode]

        if (nextMode == 2) {
            // 默认全屏
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            _playerState.value = _playerState.value.copy(
                aspectRatioMode = modeTitle,
                videoScale = 1f,
                videoOffsetX = 0f,
                videoOffsetY = 0f
            )
            mpvPlayer?.setVideoTransform(1f, 0f, 0f)
            mpvPlayer?.setZoomMode(false)
            onToast?.invoke(modeTitle)
            return
        }

        // 横向全屏 (0) 或 纵向全屏 (1)
        currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        val surfaceView = activeSurfaceView?.get()

        val rawW = if (screenWidth > 0f) screenWidth else surfaceView?.width?.toFloat() ?: 1920f
        val rawH = if (screenHeight > 0f) screenHeight else surfaceView?.height?.toFloat() ?: 1080f
        // Ensure orientation of dimensions matches landscape orientation
        val (sWidth, sHeight) = if (rawW < rawH && (surfaceView == null || surfaceView.width >= surfaceView.height)) {
            rawH to rawW
        } else {
            rawW to rawH
        }

        val videoAspect = getVideoAspect()

        fun applyTransform(boundaries: CropBoundaries) {
            val transform = BlackBarDetector.calculateScaling(
                modeIndex = nextMode,
                boundaries = boundaries,
                screenWidth = sWidth,
                screenHeight = sHeight,
                videoAspect = videoAspect
            )
            _playerState.value = _playerState.value.copy(
                aspectRatioMode = modeTitle,
                videoScale = transform.scale,
                videoOffsetX = transform.offsetX,
                videoOffsetY = transform.offsetY
            )
            mpvPlayer?.setVideoTransform(transform.scale, transform.offsetX, transform.offsetY)
        }

        // 1. Immediately apply transform using cached crop boundaries (from default view)
        val baseBoundaries = detectedVideoCropBoundaries ?: CropBoundaries()
        applyTransform(baseBoundaries)
        onToast?.invoke(modeTitle)

        // 2. If we don't have detected boundaries yet, asynchronously detect in background
        if (detectedVideoCropBoundaries == null || !detectedVideoCropBoundaries!!.hasCrop) {
            val mpv = mpvPlayer
            val ctx = playerContext
            viewModelScope.launch(Dispatchers.Default) {
                var detectedBoundaries: CropBoundaries? = null
                if (mpv != null && ctx != null) {
                    try {
                        val tempFile = File(ctx.cacheDir, "crop_detect.jpg")
                        if (mpv.takeVideoSnapshot(tempFile)) {
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            val bmp = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                            if (bmp != null) {
                                val b = BlackBarDetector.analyzeBitmap(bmp)
                                bmp.recycle()
                                tempFile.delete()
                                if (b.hasCrop) {
                                    detectedBoundaries = b
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed MPV snapshot crop analysis", e)
                    }
                }

                // IMPORTANT: Only sample SurfaceView if videoScale is 1.0f (default unscaled view)!
                // Never sample a zoomed/stretched SurfaceView because it distorts black bars detection!
                if (detectedBoundaries == null && surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
                    withContext(Dispatchers.Main) {
                        if (_playerState.value.videoScale == 1.0f) {
                            BlackBarDetector.detect(surfaceView) { boundaries ->
                                if (boundaries.hasCrop) {
                                    detectedVideoCropBoundaries = boundaries
                                    if (currentAspectRatio == nextMode) {
                                        applyTransform(boundaries)
                                    }
                                }
                            }
                        }
                    }
                    return@launch
                }

                if (detectedBoundaries != null && detectedBoundaries.hasCrop) {
                    withContext(Dispatchers.Main) {
                        detectedVideoCropBoundaries = detectedBoundaries
                        if (currentAspectRatio == nextMode) {
                            applyTransform(detectedBoundaries)
                        }
                    }
                }
            }
        }
    }

    /**
     * Update subtitle styling (font size, color, position) in real-time
     */
    fun updateSubtitleStyle(
        fontSizeScale: Int? = null,
        textColor: String? = null,
        positionPercent: Int? = null
    ) {
        val context = playerContext ?: return
        val prefs = PlayerPreferences(context)
        val current = _playerState.value

        val newScale = fontSizeScale ?: current.subtitleFontSizeScale
        val newColor = textColor ?: current.subtitleTextColor
        val newPos = positionPercent ?: current.subtitleBottomPositionPercent

        if (fontSizeScale != null) prefs.setSubtitleFontSizeScale(fontSizeScale)
        if (textColor != null) prefs.setSubtitleTextColor(textColor)
        if (positionPercent != null) prefs.setSubtitleBottomEdgePositionPercent(positionPercent)

        _playerState.value = current.copy(
            subtitleFontSizeScale = newScale,
            subtitleTextColor = newColor,
            subtitleBottomPositionPercent = newPos,
            subtitleConfigVersion = current.subtitleConfigVersion + 1
        )

        mpvPlayer?.updateSubtitleStyle(
            fontSizeScale = newScale,
            textColor = newColor,
            positionPercent = newPos
        )
    }

    /**
     * Seek backward by the configured interval
     */
    fun seekBackward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekBackwardIntervalSeconds()
        seekBy(deltaMs = -(seconds * 1000L))
    }

    /**
     * Seek forward by the configured interval
     */
    fun seekForward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekForwardIntervalSeconds()
        seekBy(deltaMs = seconds * 1000L)
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            applyPendingTrackSelectionsIfNeeded()
            updateTrackInformation()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentState = _playerState.value
            val wasPlaying = currentState.isPlaying
            val playWhenReady = exoPlayer?.playWhenReady == true
            val isNowPlaying = playbackState == Player.STATE_READY && playWhenReady
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }
            
            _playerState.value = currentState.copy(
                isLoading = shouldShowLoading,
                isPlaying = isNowPlaying,
                playWhenReady = playWhenReady,
                hasStartedPlayback = currentState.hasStartedPlayback || hasRenderedFirstFrame,
                duration = getDuration().takeIf { it > 0L } ?: currentState.duration
            )

            if (
                playbackState == Player.STATE_READY && isNowPlaying && !hasReportedStart &&
                (hasRenderedFirstFrame || !currentMediaHasVideo())
            ) {
                playbackReporter.reportPlaybackStatus()
            }

            if (wasPlaying != isNowPlaying) {
                playbackReporter.onPlaybackPauseStateChanged()
            }

            if (playbackState == Player.STATE_READY) {
                hasHandledPlaybackCompletion = false
                applyPendingTrackSelectionsIfNeeded()
                updateTrackInformation()
            }
            updateMpvWatchdog()

            if (playbackState == Player.STATE_ENDED) {
                handlePlaybackCompleted()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val currentState = _playerState.value
            val playbackState = exoPlayer?.playbackState ?: Player.STATE_IDLE
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }
            _playerState.value = currentState.copy(
                playWhenReady = playWhenReady,
                isPlaying = playWhenReady && playbackState == Player.STATE_READY,
                isLoading = shouldShowLoading
            )
            updateMpvWatchdog()
        }

        override fun onRenderedFirstFrame() {
            cancelMpvWatchdog()
            hasRenderedFirstFrame = true
            _playerState.value = _playerState.value.copy(
                isLoading = false,
                hasStartedPlayback = true,
                duration = getDuration().takeIf { it > 0L } ?: _playerState.value.duration
            )
            if (
                exoPlayer?.playWhenReady == true &&
                exoPlayer?.playbackState == Player.STATE_READY &&
                !playbackReporter.hasReportedStart()
            ) {
                playbackReporter.reportPlaybackStatus()
            }
            scheduleInitialCropDetection()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            hasRenderedFirstFrame = false
            if (triggerMpvFallback()) {
                return
            }
            _playerState.value = _playerState.value.copy(
                error = error.message ?: "Playback error occurred",
                isLoading = false,
                playWhenReady = false,
                isPlaying = false
            )

            if (playbackReporter.hasReportedStart()) {
                playbackReporter.reportPlaybackStopped(failed = true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _playerState.value = _playerState.value.copy(
                currentPosition = newPosition.positionMs,
                duration = getDuration()
            )

            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                playbackReporter.onPlaybackPositionDiscontinuity()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    fun getHdrFormatInfo(): String {
        return PlayerMetadata.buildHdrFormatInfo(
            context = playerContext,
            exoPlayer = exoPlayer
        )
    }

    /**
     * Get unified media metadata information for the modern bubble dialog
     */
    fun getMediaMetadataInfo(): MediaMetadataInfo {
        val speedBytes = if (isMpvPlayback()) mpvPlayer?.cacheSpeedBytes ?: 0L else 0L
        val speedText = if (isMpvPlayback()) {
            formatSpeed(speedBytes)
        } else {
            "N/A (ExoPlayer)"
        }
        val currentPos = getCurrentPosition()
        val bufferedPos = getBufferedPosition()
        val bufferSec = ((bufferedPos - currentPos).coerceAtLeast(0L) / 1000.0)
        val bufferText = String.format(Locale.US, "%.1f s", bufferSec)

        val serverReasons = currentPlaybackMediaSource?.transcodingReasons.orEmpty()
        val reasons = if (serverReasons.isNotEmpty()) {
            serverReasons
        } else if (playbackSession.playMethod == PlayMethod.TRANSCODE) {
            buildList {
                val audioMode = _playerState.value.currentAudioTranscodeMode
                if (audioMode != AudioTranscodeMode.AUTO) {
                    add("AudioTranscodeMode: ${audioMode.name}")
                }
                if (currentPlaybackMediaSource?.supportsDirectPlay == false) {
                    add("ServerDoesNotSupportDirectPlay")
                }
                if (isEmpty()) {
                    add("ServerTranscode")
                }
            }
        } else {
            emptyList()
        }

        return PlayerMetadata.buildMediaMetadataInfo(
            context = playerContext,
            exoPlayer = exoPlayer,
            mediaStreams = apiMediaStreams,
            mediaSourceContainer = playbackSession.mediaSourceContainer,
            mediaSourceBitrateKbps = playbackSession.mediaSourceBitrateKbps,
            playMethodDisplayName = playbackSession.playMethod.displayName,
            playerEngine = if (isMpvPlayback()) "MPV" else "ExoPlayer",
            streamUrl = currentStreamingUrl,
            transcodeReasons = reasons,
            cacheSpeedText = speedText,
            bufferedDurationText = bufferText
        )
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    fun getSourceVideoHeight(): Int? {
        return PlayerMetadata.getSourceVideoHeight(apiMediaStreams)
    }

}