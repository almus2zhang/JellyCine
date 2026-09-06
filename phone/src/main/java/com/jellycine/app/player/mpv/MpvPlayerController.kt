package com.jellycine.app.player.mpv

import android.content.Context
import android.view.Surface
import com.jellycine.player.preferences.PlayerPreferences
import org.jellycine.mpv.MPVLib
import org.jellycine.mpv.MPVLib.MpvEvent
import org.jellycine.mpv.MPVLib.MpvFormat

class MpvPlayerController(
    context: Context,
    private val hardwareDecoding: String,
    private val videoOutput: String,
    private val audioOutput: String,
    listener: Listener
) : MPVLib.EventObserver {

    companion object {
        private const val DATASPACE_V0_SRGB = 142671872
        private const val DATASPACE_BT2020_PQ = 163971072
    }

    interface Listener {
        fun onBuffering()
        fun onReady()
        fun onEnded()
    }

    private val appContext = context.applicationContext
    private val mpv = MPVLib.create(appContext)
        ?: error("MPVLib.create() returned null")
    private var released = false
    private var ready = false
    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var bufferedPositionMs: Long = 0L
    private var cacheSpeedBps: Long = 0L
    private var playWhenReady = true
    private var pendingSubtitleUrls: List<String> = emptyList()
    private var pendingSelectedSubtitleUrl: String? = null
    private val playerPreferences = PlayerPreferences(context.applicationContext)
    @Volatile
    private var listener: Listener = listener

    val isPlaying: Boolean
        get() = ready && playWhenReady

    val currentPosition: Long
        get() = positionMs

    val bufferedPosition: Long
        get() {
            if (released) return 0L
            val cacheTime = mpv.getPropertyDouble("demuxer-cache-time")
            if (cacheTime != null && cacheTime > 0.0) {
                bufferedPositionMs = (cacheTime * 1000.0).toLong().coerceAtLeast(0L)
            } else {
                val cacheDuration = mpv.getPropertyDouble("demuxer-cache-duration")
                if (cacheDuration != null && cacheDuration >= 0.0) {
                    bufferedPositionMs = (positionMs + (cacheDuration * 1000.0).toLong()).coerceAtLeast(positionMs)
                }
            }
            return bufferedPositionMs.coerceAtLeast(positionMs).let {
                if (durationMs > 0L) it.coerceAtMost(durationMs) else it
            }
        }

    val cacheSpeedBytes: Long
        get() {
            if (released) return 0L
            val speed = mpv.getPropertyDouble("cache-speed")
                ?: mpv.getPropertyDouble("demuxer-cache-state/raw-input-rate")
            if (speed != null && speed >= 0.0) {
                cacheSpeedBps = speed.toLong()
            }
            return cacheSpeedBps
        }

    val duration: Long
        get() = durationMs

    val videoAspectRatio: Float?
        get() {
            if (released) return null
            val dwidth = mpv.getPropertyDouble("dwidth") ?: 0.0
            val dheight = mpv.getPropertyDouble("dheight") ?: 0.0
            if (dwidth > 0.0 && dheight > 0.0) {
                return (dwidth / dheight).toFloat()
            }
            val aspect = mpv.getPropertyDouble("video-params/aspect") ?: 0.0
            if (aspect > 0.0) {
                return aspect.toFloat()
            }
            return null
        }

    init {
        configureMpv()
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-duration", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("cache-speed", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
    }

    fun applyPlaybackAdaptation(
        isDolbyVision: Boolean,
        dvProfile: Int?,
        isHdr: Boolean,
        deviceHdrSupport: com.jellycine.player.video.HdrCapabilityManager.HdrSupport
    ): String? {
        if (released) return null

        val userHwdec = playerPreferences.getMpvHardwareDecoding()
        val userHdrToSdr = playerPreferences.getMpvHdrToSdrTonemapping()

        val needsToneMapping = when {
            isDolbyVision -> deviceHdrSupport != com.jellycine.player.video.HdrCapabilityManager.HdrSupport.DOLBY_VISION
            isHdr -> deviceHdrSupport == com.jellycine.player.video.HdrCapabilityManager.HdrSupport.SDR
            else -> false
        } || userHdrToSdr

        if (needsToneMapping) {
            mpv.setPropertyString("target-prim", "bt.709")
            mpv.setPropertyString("target-trc", "bt.1886")
            mpv.setPropertyString("tone-mapping-mode", "hybrid")
            mpv.setPropertyString("gamut-mapping-mode", "perceptual")
            mpv.setPropertyString("hdr-compute-peak", "yes")
        } else {
            mpv.setPropertyString("target-prim", "auto")
            mpv.setPropertyString("target-trc", "auto")
            mpv.setPropertyString("tone-mapping-mode", "auto")
            mpv.setPropertyString("gamut-mapping-mode", "auto")
        }

        var adaptationNotice: String? = null
        val targetHwdec = if (isDolbyVision && deviceHdrSupport != com.jellycine.player.video.HdrCapabilityManager.HdrSupport.DOLBY_VISION) {
            val effectiveProfile = dvProfile ?: 5 // Default to 5 for safety to avoid purple/green tint on non-DV screen
            if (effectiveProfile == 5) {
                adaptationNotice = "dv_p5_software"
                "no"
            } else {
                // Profile 7, 8 or compatible with HDR10
                if (userHwdec == "no") "no" else "mediacodec-copy"
            }
        } else {
            userHwdec
        }

        mpv.setPropertyString("hwdec", targetHwdec)
        return adaptationNotice
    }

    fun detectRuntimeDvProfile(): Int? {
        if (released) return null
        val colormatrix = mpv.getPropertyString("video-params/colormatrix").orEmpty().lowercase()
        if (colormatrix.contains("ipt") || colormatrix.contains("dolby") || colormatrix.contains("dovi")) {
            return 5
        }
        if (colormatrix.contains("2020") || colormatrix.contains("709")) {
            return 8
        }
        return null
    }

    fun load(
        url: String,
        subtitleUrls: List<String>,
        audioTrackId: String?,
        subtitleTrackId: String?,
        selectedSubtitleUrl: String?,
        startPositionMs: Long?,
        startPlayback: Boolean
    ) {
        if (released) return
        ready = false
        playWhenReady = startPlayback
        val startPositionSeconds = startPositionMs
            ?.takeIf { it > 0L }
            ?.let { it / 1000.0 }
        pendingSubtitleUrls = subtitleUrls
        pendingSelectedSubtitleUrl = selectedSubtitleUrl
        mpv.setPropertyBoolean("pause", true)
        listener.onBuffering()
        val loadOptions = buildList {
            startPositionSeconds?.let { add("start=$it") }
            audioTrackId?.let { add("aid=$it") }
            if (selectedSubtitleUrl == null) {
                subtitleTrackId?.let { add("sid=$it") }
            }
        }
        val loadCommand = if (loadOptions.isEmpty()) {
            arrayOf("loadfile", url, "replace")
        } else {
            arrayOf("loadfile", url, "replace", "-1", loadOptions.joinToString(","))
        }
        mpv.command(loadCommand)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (released) return
        this.surfaceWidth = width
        this.surfaceHeight = height
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setOptionString("vo", videoOutput)
        val preserveStyles = playerPreferences.isPreserveSubtitleStylesEnabled()
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", if (preserveStyles) "no" else "yes")
        mpv.setOptionString("sub-ass-override", if (preserveStyles) "no" else "strip")
        if (width > 0 && height > 0) {
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    fun resizeSurface(width: Int, height: Int) {
        if (!released && width > 0 && height > 0) {
            this.surfaceWidth = width
            this.surfaceHeight = height
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    fun setZoomMode(enabled: Boolean) {
        if (released) return
        mpv.setOptionString("panscan", if (enabled) "1" else "0")
        val preserveStyles = playerPreferences.isPreserveSubtitleStylesEnabled()
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", if (preserveStyles) "no" else "yes")
        mpv.setOptionString("sub-ass-override", if (preserveStyles) "no" else "strip")
    }

    fun setVideoTransform(scale: Float, offsetX: Float, offsetY: Float) {
        if (released) return
        val zoom = if (scale > 0f) kotlin.math.ln(scale.toDouble()) / kotlin.math.ln(2.0) else 0.0
        val panX = if (surfaceWidth > 0) (offsetX / surfaceWidth).toDouble() else 0.0
        val panY = if (surfaceHeight > 0) (offsetY / surfaceHeight).toDouble() else 0.0

        mpv.setPropertyDouble("video-zoom", zoom)
        mpv.setPropertyDouble("video-pan-x", panX)
        mpv.setPropertyDouble("video-pan-y", panY)
    }

    fun applySubtitlePreferences() {
        if (released) return
        val preserveStyles = playerPreferences.isPreserveSubtitleStylesEnabled()
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", if (preserveStyles) "no" else "yes")
        mpv.setOptionString("sub-ass-override", if (preserveStyles) "no" else "strip")
        mpv.setOptionString("sub-scale", subtitleScale(playerPreferences.getSubtitleTextSize()))
        mpv.setOptionString(
            "sub-color",
            mpvColor(
                color = playerPreferences.getSubtitleTextColor(),
                opacityPercent = playerPreferences.getSubtitleTextOpacityPercent()
            )
        )
        mpv.setOptionString(
            "sub-back-color",
            mpvBackgroundColor(playerPreferences.getSubtitleBackgroundColor())
        )
        mpv.setOptionString(
            "sub-pos",
            (100 - playerPreferences.getSubtitlePosition().coerceIn(0, 50)).toString()
        )
        applySubtitleEdge(playerPreferences.getSubtitleEdgeType())
    }

    fun detachSurface() {
        if (released) return
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    fun play() {
        if (released) return
        playWhenReady = true
        mpv.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (released) return
        playWhenReady = false
        mpv.setPropertyBoolean("pause", true)
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        this.positionMs = positionMs.coerceAtLeast(0L)
        this.bufferedPositionMs = this.positionMs
        mpv.command(arrayOf("seek", (this.positionMs / 1000.0).toString(), "absolute+keyframes"))
    }

    fun setVolume(volume: Float) {
        if (!released) {
            mpv.setPropertyDouble("volume", (volume.coerceIn(0f, 1f) * 100f).toDouble())
        }
    }

    fun selectAudioTrack(trackId: String) {
        if (!released) {
            mpv.setPropertyString("aid", trackId)
        }
    }

    fun selectSubtitleTrack(trackId: String, externalUrl: String?) {
        if (released) return
        if (trackId == "no") {
            mpv.setPropertyString("sid", "no")
        } else if (externalUrl != null) {
            mpv.command(arrayOf("sub-add", externalUrl, "select"))
        } else {
            mpv.setPropertyString("sid", trackId)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        eventProperty(property, value.toDouble())
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> positionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "duration" -> durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
            "demuxer-cache-time" -> {
                val cacheEndMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                if (cacheEndMs > 0L) {
                    bufferedPositionMs = cacheEndMs
                }
            }
            "demuxer-cache-duration" -> {
                val cacheDurationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                bufferedPositionMs = (positionMs + cacheDurationMs).coerceAtLeast(positionMs)
            }
            "cache-speed" -> cacheSpeedBps = value.toLong().coerceAtLeast(0L)
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "paused-for-cache" -> if (value) listener.onBuffering() else listener.onReady()
            "eof-reached" -> if (value) listener.onEnded()
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                durationMs = (mpv.getPropertyDouble("duration")?.times(1000.0))
                    ?.toLong()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                pendingSubtitleUrls
                    .filterNot { subtitleUrl -> subtitleUrl == pendingSelectedSubtitleUrl }
                    .forEach { subtitleUrl ->
                        mpv.command(arrayOf("sub-add", subtitleUrl, "auto"))
                    }
                pendingSelectedSubtitleUrl?.let { subtitleUrl ->
                    mpv.command(arrayOf("sub-add", subtitleUrl, "select"))
                }
                pendingSubtitleUrls = emptyList()
                pendingSelectedSubtitleUrl = null
            }
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                ready = true
                if (playWhenReady) {
                    mpv.setPropertyBoolean("pause", false)
                }
                val gamma = mpv.getPropertyString("video-params/gamma").orEmpty()
                val primaries = mpv.getPropertyString("video-params/primaries").orEmpty()
                val sigPeak = mpv.getPropertyString("video-params/sig-peak").orEmpty()
                val isHdrContent = gamma.contains("pq", ignoreCase = true) ||
                    gamma.contains("hlg", ignoreCase = true)
                val hdrToSdr = playerPreferences.getMpvHdrToSdrTonemapping()
                if (isHdrContent && !hdrToSdr) {
                    mpv.setSurfaceDataSpace(DATASPACE_BT2020_PQ)
                } else {
                    mpv.setSurfaceDataSpace(DATASPACE_V0_SRGB)
                }
                listener.onReady()
            }
            MpvEvent.MPV_EVENT_SHUTDOWN -> Unit
            else -> Unit
        }
    }

    private fun configureMpv() {
        val cacheTimeSeconds = playerPreferences.getPlayerCacheTimeSeconds().toString()
        val cacheSizeMb = playerPreferences.getPlayerCacheSizeMb()

        val shaderCacheDir = appContext.cacheDir.resolve("mpv-shaders")
        shaderCacheDir.mkdirs()
        mpv.setOptionString("gpu-shader-cache-dir", shaderCacheDir.path)
        mpv.setOptionString("config", "no")
        mpv.setOptionString("load-scripts", "no")
        mpv.setOptionString("load-auto-profiles", "no")
        mpv.setOptionString("load-stats-overlay", "no")
        mpv.setOptionString("load-console", "no")
        mpv.setOptionString("load-commands", "no")
        mpv.setOptionString("load-select", "no")
        mpv.setOptionString("load-positioning", "no")
        val upscaleFilter = playerPreferences.getMpvUpscaleFilter()
        val downscaleFilter = playerPreferences.getMpvDownscaleFilter()
        val toneMapping = playerPreferences.getMpvToneMapping()
        val smoothMotion = playerPreferences.getMpvSmoothMotion()
        val deband = playerPreferences.getMpvDeband()
        val dynamicPeak = playerPreferences.getMpvDynamicPeak()

        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("msg-level", "all=no")
        mpv.setOptionString("vo", videoOutput)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("scale", upscaleFilter)
        mpv.setOptionString("dscale", downscaleFilter)
        mpv.setOptionString("dither", "fruit")
        mpv.setOptionString("deband", if (deband) "yes" else "no")
        mpv.setOptionString("correct-downscaling", "yes")
        mpv.setOptionString("linear-downscaling", "yes")
        mpv.setOptionString("sigmoid-upscaling", "yes")
        val hdrToSdr = playerPreferences.getMpvHdrToSdrTonemapping()
        mpv.setOptionString("tone-mapping", toneMapping)
        mpv.setOptionString("hdr-compute-peak", if (dynamicPeak) "yes" else "no")
        mpv.setOptionString("hdr-peak-percentile", "99.995")
        mpv.setOptionString("hdr-contrast-recovery", "0.5")
        if (hdrToSdr) {
            mpv.setOptionString("target-prim", "bt.709")
            mpv.setOptionString("target-trc", "bt.1886")
            mpv.setOptionString("tone-mapping-mode", "hybrid")
            mpv.setOptionString("gamut-mapping-mode", "perceptual")
        } else {
            mpv.setOptionString("target-prim", "auto")
            mpv.setOptionString("target-trc", "auto")
            mpv.setOptionString("tone-mapping-mode", "auto")
            mpv.setOptionString("gamut-mapping-mode", "auto")
        }
        mpv.setOptionString("video-output-levels", "full")
        if (smoothMotion) {
            mpv.setOptionString("interpolation", "yes")
            mpv.setOptionString("tscale", "oversample")
            mpv.setOptionString("video-sync", "display-resample")
        } else {
            mpv.setOptionString("video-sync", "audio")
        }
        mpv.setOptionString("ao", audioOutput)
        mpv.setOptionString("hwdec", hardwareDecoding)
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("tls-verify", "no")
        mpv.setOptionString("keep-open", "no")
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("cache-secs", cacheTimeSeconds)
        mpv.setOptionString("index", "default")
        mpv.setOptionString("hr-seek", "no")
        mpv.setOptionString("demuxer-mkv-probe-start-time", "no")
        mpv.setOptionString("demuxer-mkv-probe-video-duration", "no")
        val backBytesMb = (cacheSizeMb / 4).coerceIn(32, 256)
        mpv.setOptionString("demuxer-readahead-secs", cacheTimeSeconds)
        mpv.setOptionString("demuxer-max-bytes", "${cacheSizeMb}MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "${backBytesMb}MiB")
        mpv.setOptionString("sub-visibility", "yes")
        mpv.setOptionString("sub-bitmap", "yes")
        mpv.setOptionString("sub-scale-with-window", "yes")
        mpv.setOptionString("sub-use-margins", "no")
        mpv.setOptionString("ytdl", "no")
        applySubtitlePreferences()
    }

    private fun subtitleScale(size: String): String {
        return when (size) {
            PlayerPreferences.SUBTITLE_TEXT_SIZE_SMALL -> "0.85"
            PlayerPreferences.SUBTITLE_TEXT_SIZE_LARGE -> "1.25"
            PlayerPreferences.SUBTITLE_TEXT_SIZE_EXTRA_LARGE -> "1.5"
            else -> "1.0"
        }
    }

    private fun mpvColor(color: String, opacityPercent: Int): String {
        val rgb = when (color) {
            PlayerPreferences.SUBTITLE_TEXT_COLOR_YELLOW -> "FFFF00"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_GREEN -> "00FF00"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_CYAN -> "00FFFF"
            PlayerPreferences.SUBTITLE_TEXT_COLOR_BLACK -> "000000"
            else -> "FFFFFF"
        }
        return "#${alphaHex(opacityPercent)}$rgb"
    }

    private fun mpvBackgroundColor(color: String): String {
        return when (color) {
            PlayerPreferences.SUBTITLE_BACKGROUND_BLACK -> "#CC000000"
            PlayerPreferences.SUBTITLE_BACKGROUND_WHITE -> "#CCFFFFFF"
            else -> "#00000000"
        }
    }

    private fun applySubtitleEdge(edgeType: String) {
        when (edgeType) {
            PlayerPreferences.SUBTITLE_EDGE_TYPE_OUTLINE -> {
                mpv.setOptionString("sub-border-size", "3")
                mpv.setOptionString("sub-shadow-offset", "0")
            }
            PlayerPreferences.SUBTITLE_EDGE_TYPE_DROP_SHADOW -> {
                mpv.setOptionString("sub-border-size", "0")
                mpv.setOptionString("sub-shadow-offset", "2")
            }
            else -> {
                mpv.setOptionString("sub-border-size", "0")
                mpv.setOptionString("sub-shadow-offset", "0")
            }
        }
        mpv.setOptionString("sub-border-color", "#FF000000")
        mpv.setOptionString("sub-shadow-color", "#CC000000")
    }

    private fun alphaHex(opacityPercent: Int): String {
        val alpha = ((opacityPercent.coerceIn(0, 100) / 100f) * 255f)
            .toInt()
            .coerceIn(0, 255)
        return alpha.toString(16).uppercase().padStart(2, '0')
    }
}