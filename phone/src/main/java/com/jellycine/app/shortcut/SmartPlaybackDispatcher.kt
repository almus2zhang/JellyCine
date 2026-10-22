package com.jellycine.app.shortcut

import android.content.Context
import android.util.Log
import com.jellycine.app.util.NetworkSubnetHelper
import com.jellycine.data.repository.AuthRepository
import com.jellycine.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmartPlaybackDispatcher {

    private const val TAG = "SmartPlaybackDispatcher"

    /**
     * 智能选路并返回可起播的 mediaId
     */
    suspend fun resolveAndPrepare(
        context: Context,
        request: ShortcutPlaybackBridge.PlaybackRequest,
        authRepository: AuthRepository,
        mediaRepository: MediaRepository
    ): String = withContext(Dispatchers.IO) {
        val mediaId = request.mediaId
        val seriesId = request.seriesId
        val targetSystemServerId = request.systemServerId

        try {
            val snapshot = authRepository.getActiveSessionSnapshot()
            val savedServers = snapshot.savedServers
            val currentServerId = snapshot.activeServerId
            val currentServer = savedServers.firstOrNull { it.id == currentServerId }

            // 1. 探测手机当前 WiFi IPv4
            val currentWifiIp = NetworkSubnetHelper.getWifiIpv4Address(context)
            Log.d(TAG, "Current WiFi IPv4: $currentWifiIp, targetSystemServerId: $targetSystemServerId")

            // 2. 如果存在 targetSystemServerId，从保存的配置中检索所有同服线路
            val sameServerCandidates = if (!targetSystemServerId.isNullOrBlank()) {
                savedServers.filter { it.systemServerId == targetSystemServerId }
            } else {
                emptyList()
            }

            var switched = false

            // 3. 优先级 1：同网段内网 IP (例如家里 WiFi 下直连局域网内网 Emby)
            if (!currentWifiIp.isNullOrBlank() && sameServerCandidates.isNotEmpty()) {
                val localSubnetMatch = sameServerCandidates.firstOrNull { candidate ->
                    val candidateHost = NetworkSubnetHelper.extractHost(candidate.serverUrl)
                    NetworkSubnetHelper.isPrivateIpv4(candidateHost) &&
                        NetworkSubnetHelper.isSameSubnet(currentWifiIp, candidateHost)
                }

                if (localSubnetMatch != null) {
                    if (currentServer?.id != localSubnetMatch.id) {
                        Log.i(TAG, "Priority 1: Switching to LAN subnet server: ${localSubnetMatch.displayName} (${localSubnetMatch.serverUrl})")
                        authRepository.switchServer(localSubnetMatch.id)
                        switched = true
                    }
                }
            }

            // 4. 优先级 2：如果不需切内网，检查当前配置是否就是该服务器（或能直接取得媒体）
            if (!switched) {
                val directTestItem = mediaRepository.getItemById(mediaId).getOrNull()
                val isCurrentServerMatch = directTestItem != null &&
                    (targetSystemServerId.isNullOrBlank() || directTestItem.serverId == targetSystemServerId)

                if (isCurrentServerMatch) {
                    Log.d(TAG, "Priority 2: Current active server can directly serve the media, using current config.")
                } else if (sameServerCandidates.isNotEmpty() && currentServer?.systemServerId != targetSystemServerId) {
                    // 当前配置并非目标服务器，自动切到候选线路中的第一个可用配置
                    val fallback = sameServerCandidates.firstOrNull()
                    if (fallback != null) {
                        Log.i(TAG, "Priority 3: Switching to fallback candidate server: ${fallback.displayName}")
                        authRepository.switchServer(fallback.id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Smart dispatch selection error: ${e.message}")
        }

        // 5. 解析真实可播放 ID (如果是剧集则获取 Next Up / 首集)
        DesktopShortcutManager.resolvePlayableMediaId(
            mediaRepository = mediaRepository,
            mediaId = mediaId,
            seriesId = seriesId
        )
    }
}
