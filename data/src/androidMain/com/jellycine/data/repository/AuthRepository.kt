package com.jellycine.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jellycine.data.R
import com.jellycine.data.datastore.DataStoreProvider
import com.jellycine.data.model.AuthenticationRequest
import com.jellycine.data.model.AuthenticationResult
import com.jellycine.data.model.QuickConnectDto
import com.jellycine.data.model.QuickConnectResult
import com.jellycine.data.model.ServerInfo
import com.jellycine.data.network.ServerEndpoint
import com.jellycine.data.network.ServerType
import com.jellycine.data.network.canonicalServerUrl
import com.jellycine.data.network.canonicalServerUrlKey
import com.jellycine.data.network.sameServerUrl
import com.jellycine.data.network.trimTrailingSlash
import com.jellycine.data.network.NetworkModule
import com.jellycine.data.preferences.NetworkPreferences
import com.jellycine.data.security.AuthSessionIds
import com.jellycine.data.security.LEGACY_ACCESS_TOKEN_KEY
import com.jellycine.data.security.SecureSessionStore
import com.jellycine.data.network.JellyCineJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class AuthRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.getDataStore(context)
    private val networkPreferences = NetworkPreferences(context)
    private val secureSessionStore = SecureSessionStore(context)
    private val seerrRepository = SeerrRepository(context)
    private val legacyMigrationMutex = Mutex()
    private val refreshSessionMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedSnapshot: ActiveSessionSnapshot? = null

    @Volatile
    private var migrationExecuted = false

    init {
        observeActiveSession()
            .onEach { cachedSnapshot = it }
            .launchIn(scope)
    }

    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val SERVER_NAME_KEY = stringPreferencesKey("server_name")
        private val SERVER_TYPE_KEY = stringPreferencesKey("server_type")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val IS_AUTHENTICATED_KEY = booleanPreferencesKey("is_authenticated")
        private val SAVED_SERVERS_KEY = stringPreferencesKey("saved_servers_v1")
        private val ACTIVE_SERVER_ID_KEY = stringPreferencesKey("active_server_id")
        private val SOURCE_URL_KEY = stringPreferencesKey("source_url")
    }

    @Serializable
    data class SavedServer(
        @SerialName("id")
        val id: String,
        @SerialName("serverUrl")
        val serverUrl: String,
        @SerialName("serverName")
        val serverName: String,
        @SerialName("serverTypeRaw")
        val serverTypeRaw: String,
        @SerialName("username")
        val username: String,
        @SerialName("userId")
        val userId: String,
        @SerialName("profileImageUrl")
        val profileImageUrl: String? = null,
        @SerialName("lastUsedAt")
        val lastUsedAt: Long,
        @SerialName("sourceUrl")
        val sourceUrl: String? = null
    )

    @Serializable
    private data class StoredSavedServer(
        @SerialName("id")
        val id: String,
        @SerialName("serverUrl")
        val serverUrl: String,
        @SerialName("serverName")
        val serverName: String,
        @SerialName("serverTypeRaw")
        val serverTypeRaw: String,
        @SerialName("username")
        val username: String,
        @SerialName("userId")
        val userId: String,
        @SerialName("profileImageUrl")
        val profileImageUrl: String? = null,
        @SerialName("lastUsedAt")
        val lastUsedAt: Long,
        @SerialName("accessToken")
        val accessToken: String? = null,
        @SerialName("sourceUrl")
        val sourceUrl: String? = null
    )

    data class ActiveSessionSnapshot(
        val serverName: String?,
        val serverUrl: String?,
        val serverType: String?,
        val username: String?,
        val savedServers: List<SavedServer>,
        val activeServerId: String?,
        val sourceUrl: String? = null
    )

    private fun defaultServerName(serverType: ServerType): String {
        return when (serverType) {
            ServerType.EMBY -> "Emby Server"
            ServerType.JELLYFIN -> "Jellyfin Server"
            ServerType.UNKNOWN -> "Media Server"
        }
    }

    private fun serverName(
        serverInfo: ServerInfo,
        serverType: ServerType
    ): String {
        return serverInfo.serverName
            ?.takeIf { it.isNotBlank() }
            ?: serverInfo.productName?.takeIf { it.isNotBlank() }
            ?: defaultServerName(serverType)
    }

    private fun buildServerId(serverUrl: String, userId: String): String {
        return AuthSessionIds.buildServerId(serverUrl, userId)
    }

    private fun currentServerId(preferences: Preferences): String? {
        val explicitId = preferences[ACTIVE_SERVER_ID_KEY]?.takeIf { it.isNotBlank() }
        if (explicitId != null) return explicitId

        val serverUrl = preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val userId = preferences[USER_ID_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return buildServerId(serverUrl = serverUrl, userId = userId)
    }

    private fun persistedSavedServers(raw: String?): List<StoredSavedServer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            JellyCineJson.decodeFromString<List<StoredSavedServer>>(raw)
                ?.filter {
                    it.id.isNotBlank() &&
                        it.serverUrl.isNotBlank() &&
                        it.userId.isNotBlank()
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun savedServers(raw: String?): List<SavedServer> {
        return persistedSavedServers(raw)
            .mapNotNull { storedServer ->
                storedServer.toSavedServerOrNull()
                    ?.takeIf { savedServer -> secureSessionStore.hasToken(savedServer.id) }
            }
    }

    private fun serializeSavedServers(savedServers: List<SavedServer>): String {
        return JellyCineJson.encodeToString(savedServers)
    }

    private fun upsertSavedServer(
        existing: List<SavedServer>,
        incoming: SavedServer
    ): List<SavedServer> {
        val withoutMatch = existing.filterNot { it.id == incoming.id }
        return (withoutMatch + incoming)
            .sortedByDescending { it.lastUsedAt }
    }

    private fun activeServer(preferences: Preferences): SavedServer? {
        val serverUrl = preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val userId = preferences[USER_ID_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val serverId = buildServerId(serverUrl = serverUrl, userId = userId)
        if (!secureSessionStore.hasToken(serverId)) return null
        val existingSavedServer = savedServers(preferences[SAVED_SERVERS_KEY])
            .firstOrNull { savedServer -> savedServer.id == serverId }
        val serverTypeRaw = preferences[SERVER_TYPE_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: ServerType.UNKNOWN.name
        val serverType = runCatching { ServerType.valueOf(serverTypeRaw) }
            .getOrDefault(ServerType.UNKNOWN)
        val serverName = preferences[SERVER_NAME_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: defaultServerName(serverType)
        val username = preferences[USERNAME_KEY].orEmpty()
        val sourceUrl = preferences[SOURCE_URL_KEY]?.takeIf { it.isNotBlank() } ?: existingSavedServer?.sourceUrl

        return SavedServer(
            id = buildServerId(serverUrl = serverUrl, userId = userId),
            serverUrl = serverUrl,
            serverName = serverName,
            serverTypeRaw = serverTypeRaw,
            username = username,
            userId = userId,
            profileImageUrl = existingSavedServer?.profileImageUrl,
            lastUsedAt = System.currentTimeMillis(),
            sourceUrl = sourceUrl
        )
    }

    val isAuthenticated: Flow<Boolean> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        (preferences[IS_AUTHENTICATED_KEY] ?: false) &&
            secureSessionStore.hasToken(currentServerId(preferences))
    }

    fun getServerUrl(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]
    }

    fun getServerName(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_NAME_KEY]
    }

    fun getServerType(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_TYPE_KEY]
    }

    fun getUsername(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    fun observeActiveSession(): Flow<ActiveSessionSnapshot> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val activeServer = activeServer(preferences)
        val currentSavedServers = if (activeServer != null && storedServers.none { it.id == activeServer.id }) {
            upsertSavedServer(storedServers, activeServer)
        } else {
            storedServers.sortedByDescending { it.lastUsedAt }
        }
        val selectedServerId = preferences[ACTIVE_SERVER_ID_KEY]
            ?.takeIf { candidateId ->
                candidateId.isNotBlank() && currentSavedServers.any { savedServer -> savedServer.id == candidateId }
            }
            ?: activeServer?.id
        val resolvedActiveServer = selectedServerId
            ?.let { candidateId ->
                currentSavedServers.firstOrNull { savedServer -> savedServer.id == candidateId }
            }
            ?: activeServer

        ActiveSessionSnapshot(
            serverName = preferences[SERVER_NAME_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.serverName,
            serverUrl = preferences[SERVER_URL_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.serverUrl,
            serverType = preferences[SERVER_TYPE_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.serverTypeRaw,
            username = preferences[USERNAME_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.username,
            savedServers = currentSavedServers,
            activeServerId = selectedServerId,
            sourceUrl = preferences[SOURCE_URL_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.sourceUrl
        )
    }

    fun getActiveSessionSnapshot(): ActiveSessionSnapshot {
        return cachedSnapshot ?: ActiveSessionSnapshot(
            serverName = null,
            serverUrl = null,
            serverType = null,
            username = null,
            savedServers = emptyList(),
            activeServerId = null
        )
    }

    fun getAccessToken(): Flow<String?> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        currentServerId(preferences)?.let(secureSessionStore::getToken)
    }

    fun getSavedServers(): Flow<List<SavedServer>> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val activeServer = activeServer(preferences)
        val currentSavedServers = if (activeServer != null && storedServers.none { it.id == activeServer.id }) {
            upsertSavedServer(storedServers, activeServer)
        } else {
            storedServers.sortedByDescending { it.lastUsedAt }
        }
        currentSavedServers
    }

    fun getActiveServerId(): Flow<String?> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        activeServer(preferences)?.id
            ?: preferences[ACTIVE_SERVER_ID_KEY]
                ?.takeIf { candidateId ->
                    candidateId.isNotBlank() && savedServers(preferences[SAVED_SERVERS_KEY]).any { it.id == candidateId }
                }
    }

    suspend fun savedServer() {
        legacyStorageMigrated()
        dataStore.edit { preferences ->
            val activeServer = activeServer(preferences) ?: return@edit
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val updatedServers = upsertSavedServer(existingServers, activeServer)
            preferences[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
            if (preferences[ACTIVE_SERVER_ID_KEY].isNullOrBlank()) {
                preferences[ACTIVE_SERVER_ID_KEY] = activeServer.id
            }
        }
    }

    suspend fun switchServer(serverId: String): Result<SavedServer> {
        if (serverId.isBlank()) {
            return Result.failure(Exception(string(R.string.auth_error_invalid_server_id)))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val targetServer = existingServers.firstOrNull { it.id == serverId }
                ?: activeServer(preferences)?.takeIf { it.id == serverId }
                ?: return Result.failure(Exception(string(R.string.auth_error_saved_server_not_found)))

            if (is301Url(targetServer.sourceUrl)) {
                val sourceUrl = targetServer.sourceUrl!!
                val resolvedResult = resolve301ServerUrl(sourceUrl)
                if (resolvedResult.isFailure) {
                    return Result.failure(Exception("无法获取 301 服务器地址: ${resolvedResult.exceptionOrNull()?.message}"))
                }
                val newRealUrl = resolvedResult.getOrThrow()
                val credentials = secureSessionStore.getCredentials(sourceUrl)
                    ?: secureSessionStore.getCredentials(targetServer.id)

                if (credentials != null) {
                    val authResult = authenticateUser(
                        serverUrl = newRealUrl,
                        username = credentials.first,
                        password = credentials.second,
                        sourceUrl = sourceUrl
                    )
                    if (authResult.isSuccess) {
                        val updatedPrefs = dataStore.data.first()
                        val updatedServers = savedServers(updatedPrefs[SAVED_SERVERS_KEY])
                        val switchedServer = updatedServers.firstOrNull { it.sourceUrl == sourceUrl }
                            ?: activeServer(updatedPrefs)
                            ?: targetServer
                        return Result.success(switchedServer)
                    } else {
                        return Result.failure(Exception(authResult.exceptionOrNull()?.message ?: "301 自动登录失败"))
                    }
                }
            }

            val accessToken = secureSessionStore.getToken(targetServer.id)
                ?: return Result.failure(Exception(string(R.string.auth_error_saved_session_expired)))

            val switchedServer = targetServer.copy(lastUsedAt = System.currentTimeMillis())

            dataStore.edit { prefs ->
                val latestServers = savedServers(prefs[SAVED_SERVERS_KEY])
                val updatedServers = upsertSavedServer(latestServers, switchedServer)
                prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                prefs[ACTIVE_SERVER_ID_KEY] = switchedServer.id
                prefs[SERVER_URL_KEY] = switchedServer.serverUrl
                prefs[SERVER_NAME_KEY] = switchedServer.serverName
                prefs[SERVER_TYPE_KEY] = switchedServer.serverTypeRaw
                prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                prefs[USER_ID_KEY] = switchedServer.userId
                prefs[USERNAME_KEY] = switchedServer.username
                prefs[SOURCE_URL_KEY] = switchedServer.sourceUrl ?: ""
                prefs[IS_AUTHENTICATED_KEY] = accessToken.isNotBlank() &&
                    switchedServer.userId.isNotBlank()
            }

            Result.success(switchedServer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkOrRefreshActiveSession(): Boolean {
        return refreshSessionMutex.withLock {
            try {
                legacyStorageMigrated()
                val preferences = dataStore.data.first()
                val active = activeServer(preferences)
                val sourceUrl = preferences[SOURCE_URL_KEY]?.takeIf { it.isNotBlank() } ?: active?.sourceUrl
                if (!is301Url(sourceUrl)) {
                    return@withLock true
                }

                val resolvedResult = resolve301ServerUrl(sourceUrl!!)
                if (resolvedResult.isFailure) {
                    return@withLock true
                }
                val newRealUrl = resolvedResult.getOrThrow()
                val credentials = secureSessionStore.getCredentials(sourceUrl)
                    ?: active?.id?.let { secureSessionStore.getCredentials(it) }
                    ?: return@withLock true

                val currentUrl = active?.serverUrl
                val hasToken = active?.id?.let { secureSessionStore.hasToken(it) } == true
                if (!sameServerUrl(currentUrl, newRealUrl) || !hasToken) {
                    val authResult = authenticateUser(
                        serverUrl = newRealUrl,
                        username = credentials.first,
                        password = credentials.second,
                        sourceUrl = sourceUrl
                    )
                    authResult.isSuccess
                } else {
                    true
                }
            } catch (e: Exception) {
                true
            }
        }
    }

    suspend fun removeSavedServer(serverId: String): Result<Unit> {
        if (serverId.isBlank()) {
            return Result.failure(Exception(string(R.string.auth_error_invalid_server_id)))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val activeServerId = preferences[ACTIVE_SERVER_ID_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: activeServer(preferences)?.id

            val removeServer = existingServers.firstOrNull { it.id == serverId }
                ?: return Result.failure(Exception(string(R.string.auth_error_saved_server_not_found)))

            if (removeServer.id == activeServerId) {
                return Result.failure(
                    Exception(string(R.string.auth_error_remove_active_server))
                )
            }

            val updatedServers = existingServers
                .filterNot { it.id == removeServer.id }
                .sortedByDescending { it.lastUsedAt }

            dataStore.edit { prefs ->
                prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                if (prefs[ACTIVE_SERVER_ID_KEY] == removeServer.id) {
                    prefs[ACTIVE_SERVER_ID_KEY] = ""
                }
            }
            secureSessionStore.removeToken(removeServer.id)
            secureSessionStore.removeCredentials(removeServer.id)
            if (!removeServer.sourceUrl.isNullOrBlank()) {
                secureSessionStore.removeCredentials(removeServer.sourceUrl)
            }
            seerrRepository.disconnect(removeServer.id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSavedServerProfileImage(
        serverId: String,
        profileImageUrl: String?
    ) {
        if (serverId.isBlank()) return

        legacyStorageMigrated()
        dataStore.edit { prefs ->
            val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
            val targetServer = existingServers.firstOrNull { savedServer -> savedServer.id == serverId }
                ?: return@edit
            val updatedServers = upsertSavedServer(
                existing = existingServers,
                incoming = targetServer.copy(profileImageUrl = profileImageUrl)
            )
            prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
        }
    }

    suspend fun updateActiveServerProfileImage(profileImageUrl: String?) {
        legacyStorageMigrated()
        val preferences = dataStore.data.first()
        val activeServerId = preferences[ACTIVE_SERVER_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: activeServer(preferences)?.id
            ?: return
        updateSavedServerProfileImage(
            serverId = activeServerId,
            profileImageUrl = profileImageUrl
        )
    }

    fun is301Url(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("301:", ignoreCase = true) || trimmed.startsWith("301：")
    }

    fun extract301TargetUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("301:", ignoreCase = true) -> trimmed.substring(4).trim()
            trimmed.startsWith("301：") -> trimmed.substring(4).trim()
            else -> trimmed
        }
    }

    suspend fun resolve301ServerUrl(url: String): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val targetUrlRaw = extract301TargetUrl(url)
            if (targetUrlRaw.isBlank()) {
                return@withContext Result.failure(Exception(string(R.string.auth_error_invalid_url_scheme)))
            }
            var targetUrl = targetUrlRaw
            if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                targetUrl = "https://$targetUrl"
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = okhttp3.Request.Builder()
                .url(targetUrl)
                .get()
                .header("User-Agent", "JellyCine/1.3.3")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val bodyString = response.body?.string()?.trim()
                if (bodyString.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("301 返回内容为空"))
                }
                val realAddress = bodyString.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (realAddress.isBlank()) {
                    return@withContext Result.failure(Exception("未在 301 响应中找到有效地址"))
                }
                val fullAddress = if (!realAddress.startsWith("http://", ignoreCase = true) && !realAddress.startsWith("https://", ignoreCase = true)) {
                    "http://$realAddress"
                } else {
                    realAddress
                }
                Result.success(trimTrailingSlash(fullAddress.trim()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolveServerUrl(inputUrl: String): Result<String> {
        val trimmed = inputUrl.trim()
        return if (is301Url(trimmed)) {
            resolve301ServerUrl(trimmed)
        } else {
            Result.success(trimmed)
        }
    }

    suspend fun testServerConnection(serverUrl: String): Result<ServerInfo> {
        legacyStorageMigrated()
        return try {
            val resolvedUrl = if (is301Url(serverUrl)) {
                resolve301ServerUrl(serverUrl).getOrElse { error ->
                    return Result.failure(Exception("无法获取 301 服务器地址: ${error.message}"))
                }
            } else {
                serverUrl
            }

            if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
                return Result.failure(Exception(string(R.string.auth_error_invalid_url_scheme)))
            }

            val resolved = NetworkModule.serverEndpoint(
                    context = context,
                    serverUrl = resolvedUrl,
                    storageDir = context.filesDir,
                    timeoutConfig = networkPreferences.getTimeoutConfig()
                ).getOrElse { error ->
                return Result.failure(Exception(error.message ?: string(R.string.auth_error_unable_to_connect)))
            }

            val normalizedServerInfo = resolved.serverInfo.copy(
                serverName = serverName(
                    serverInfo = resolved.serverInfo,
                    serverType = resolved.serverType
                )
            )

            Result.success(normalizedServerInfo)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception(string(R.string.auth_error_cannot_reach_server)))
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception(string(R.string.auth_error_connection_refused)))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception(string(R.string.auth_error_connection_timeout)))
        } catch (e: javax.net.ssl.SSLException) {
            Result.failure(Exception(string(R.string.auth_error_ssl_failed)))
        } catch (e: java.security.cert.CertificateException) {
            Result.failure(Exception(string(R.string.auth_error_certificate_failed)))
        } catch (e: java.io.IOException) {
            Result.failure(Exception(string(R.string.auth_error_network, e.message ?: string(R.string.auth_error_unable_to_connect))))
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("Failed to connect", ignoreCase = true) == true ->
                    string(R.string.auth_error_connect_failed)
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    string(R.string.auth_error_connection_timeout_unavailable)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    string(R.string.auth_error_connection_refused_short)
                else -> e.message ?: string(R.string.auth_error_unknown_connection)
            }
            Result.failure(Exception(errorMessage))
        }
    }

    private suspend fun authEndpoint(
        serverUrl: String,
        preferences: Preferences
    ): Result<ServerEndpoint> {
        legacyStorageMigrated()
        val savedServerUrl = preferences[SERVER_URL_KEY]
        val savedServerType = preferences[SERVER_TYPE_KEY]?.let {
            runCatching { ServerType.valueOf(it) }.getOrNull()
        }
        if (isSameServer(serverUrl, savedServerUrl) && savedServerUrl != null && savedServerType != null) {
            return Result.success(
                ServerEndpoint(
                    baseUrl = savedServerUrl,
                    serverType = savedServerType,
                    serverInfo = ServerInfo(
                        serverName = preferences[SERVER_NAME_KEY]
                    )
                )
            )
        }

        return NetworkModule.serverEndpoint(
            context = context,
            serverUrl = serverUrl,
            storageDir = context.filesDir,
            timeoutConfig = networkPreferences.getTimeoutConfig()
        ).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                Result.failure(Exception(error.message ?: string(R.string.data_error_server_endpoint_unresolved)))
            }
        )
    }

    suspend fun authenticateUser(
        serverUrl: String,
        username: String,
        password: String,
        sourceUrl: String? = null
    ): Result<AuthenticationResult> {
        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val savedServerUrl = preferences[SERVER_URL_KEY]
            val savedServerType = preferences[SERVER_TYPE_KEY]?.let {
                runCatching { ServerType.valueOf(it) }.getOrNull()
            }

            val endpoint = if (isSameServer(serverUrl, savedServerUrl) && savedServerUrl != null && savedServerType != null) {
                ServerEndpoint(
                    baseUrl = savedServerUrl,
                    serverType = savedServerType,
                    serverInfo = ServerInfo(
                        serverName = preferences[SERVER_NAME_KEY] ?: "",
                        productName = when (savedServerType) {
                            ServerType.EMBY -> "Emby"
                            ServerType.JELLYFIN -> "Jellyfin"
                            ServerType.UNKNOWN -> "Media Server"
                        }
                    )
                )
            } else {
                NetworkModule.serverEndpoint(
                    context = context,
                    serverUrl = serverUrl,
                    storageDir = context.filesDir,
                    timeoutConfig = networkPreferences.getTimeoutConfig()
                ).getOrElse { error ->
                    return Result.failure(Exception(error.message ?: string(R.string.data_error_server_endpoint_unresolved)))
                }
            }

            val serverName = serverName(
                serverInfo = endpoint.serverInfo,
                serverType = endpoint.serverType
            )
            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig()
            )

            val response = api.authenticateByName(AuthenticationRequest(username, password))
            if (response.isSuccessful && response.body() != null) {
                val authResult = response.body()!!
                val savedServer = SavedServer(
                    id = buildServerId(serverUrl = endpoint.baseUrl, userId = authResult.user.id),
                    serverUrl = endpoint.baseUrl,
                    serverName = serverName,
                    serverTypeRaw = endpoint.serverType.name,
                    username = username,
                    userId = authResult.user.id,
                    profileImageUrl = null,
                    lastUsedAt = System.currentTimeMillis(),
                    sourceUrl = sourceUrl
                )

                secureSessionStore.putToken(savedServer.id, authResult.accessToken)
                secureSessionStore.saveCredentials(savedServer.id, username, password)
                if (!sourceUrl.isNullOrBlank()) {
                    secureSessionStore.saveCredentials(sourceUrl, username, password)
                }

                try {
                    dataStore.edit { prefs ->
                        val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
                        val serversToRetain = if (!sourceUrl.isNullOrBlank()) {
                            val oldMatch = existingServers.firstOrNull { it.sourceUrl == sourceUrl && it.id != savedServer.id }
                            if (oldMatch != null) {
                                secureSessionStore.removeToken(oldMatch.id)
                            }
                            existingServers.filterNot { it.sourceUrl == sourceUrl }
                        } else {
                            existingServers
                        }
                        val updatedServers = upsertSavedServer(serversToRetain, savedServer)
                        prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                        prefs[ACTIVE_SERVER_ID_KEY] = savedServer.id
                        prefs[SERVER_URL_KEY] = endpoint.baseUrl
                        prefs[SERVER_NAME_KEY] = serverName
                        prefs[SERVER_TYPE_KEY] = endpoint.serverType.name
                        prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                        prefs[USER_ID_KEY] = authResult.user.id
                        prefs[USERNAME_KEY] = username
                        prefs[SOURCE_URL_KEY] = sourceUrl ?: ""
                        prefs[IS_AUTHENTICATED_KEY] = true
                    }
                } catch (error: Exception) {
                    secureSessionStore.removeToken(savedServer.id)
                    throw error
                }
                Result.success(authResult)
            } else {
                Result.failure(Exception(string(R.string.auth_error_authentication_failed, response.code())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initiateQuickConnect(serverUrl: String): Result<QuickConnectResult> {
        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrElse { error ->
                return Result.failure(error)
            }

            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig()
            )
            val response = api.initiateQuickConnect()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(string(R.string.auth_error_quick_connect_start_failed, response.code())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isQuickConnectSupported(serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return false
        return runCatching {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrNull()
            endpoint?.serverType != ServerType.EMBY
        }.getOrDefault(true)
    }

    suspend fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String
    ): Result<AuthenticationResult> {
        if (secret.isBlank()) {
            return Result.failure(Exception(string(R.string.auth_error_quick_connect_secret_missing)))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrElse { error ->
                return Result.failure(error)
            }

            val serverName = serverName(
                serverInfo = endpoint.serverInfo,
                serverType = endpoint.serverType
            )
            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig()
            )

            val response = api.authenticateWithQuickConnect(QuickConnectDto(secret = secret))
            if (response.isSuccessful && response.body() != null) {
                val authResult = response.body()!!
                val persistedUsername = authResult.user.name.trim().ifBlank { authResult.user.id }
                val savedServer = SavedServer(
                    id = buildServerId(serverUrl = endpoint.baseUrl, userId = authResult.user.id),
                    serverUrl = endpoint.baseUrl,
                    serverName = serverName,
                    serverTypeRaw = endpoint.serverType.name,
                    username = persistedUsername,
                    userId = authResult.user.id,
                    profileImageUrl = null,
                    lastUsedAt = System.currentTimeMillis()
                )

                secureSessionStore.putToken(savedServer.id, authResult.accessToken)
                try {
                    dataStore.edit { prefs ->
                        val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
                        val updatedServers = upsertSavedServer(existingServers, savedServer)
                        prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                        prefs[ACTIVE_SERVER_ID_KEY] = savedServer.id
                        prefs[SERVER_URL_KEY] = endpoint.baseUrl
                        prefs[SERVER_NAME_KEY] = serverName
                        prefs[SERVER_TYPE_KEY] = endpoint.serverType.name
                        prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                        prefs[USER_ID_KEY] = authResult.user.id
                        prefs[USERNAME_KEY] = persistedUsername
                        prefs[IS_AUTHENTICATED_KEY] = true
                    }
                } catch (error: Exception) {
                    secureSessionStore.removeToken(savedServer.id)
                    throw error
                }
                Result.success(authResult)
            } else {
                Result.failure(Exception(string(R.string.auth_error_authentication_failed, response.code())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        legacyStorageMigrated()
        var loggedOutServerId: String? = null
        dataStore.edit { preferences ->
            val activeServerId = currentServerId(preferences)
            if (activeServerId != null) {
                loggedOutServerId = activeServerId
                val updatedServers = savedServers(preferences[SAVED_SERVERS_KEY])
                    .filterNot { it.id == activeServerId }
                preferences[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                secureSessionStore.removeToken(activeServerId)
            }
            preferences[LEGACY_ACCESS_TOKEN_KEY] = ""
            preferences[USER_ID_KEY] = ""
            preferences[USERNAME_KEY] = ""
            preferences[SERVER_URL_KEY] = ""
            preferences[SERVER_NAME_KEY] = ""
            preferences[SERVER_TYPE_KEY] = ""
            preferences[ACTIVE_SERVER_ID_KEY] = ""
            preferences[SOURCE_URL_KEY] = ""
            preferences[IS_AUTHENTICATED_KEY] = false
        }
        seerrRepository.disconnect(loggedOutServerId)
    }

    private suspend fun legacyStorageMigrated() {
        if (migrationExecuted) return

        legacyMigrationMutex.withLock {
            if (migrationExecuted) return

            val preferences = dataStore.data.first()
            val storedServers = persistedSavedServers(preferences[SAVED_SERVERS_KEY])
            val activeServerId = currentServerId(preferences)
            val legacyAccessToken = preferences[LEGACY_ACCESS_TOKEN_KEY]?.takeIf { it.isNotBlank() }

            storedServers.forEach { storedServer ->
                storedServer.accessToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { secureSessionStore.putToken(storedServer.id, it) }
            }

            if (activeServerId != null && !legacyAccessToken.isNullOrBlank()) {
                secureSessionStore.putToken(activeServerId, legacyAccessToken)
            }

            val authenticatedServers = storedServers
                .mapNotNull { storedServer ->
                    storedServer.toSavedServerOrNull()
                        ?.takeIf { savedServer -> secureSessionStore.hasToken(savedServer.id) }
                }
                .sortedByDescending { it.lastUsedAt }

            val serializedServers = serializeSavedServers(authenticatedServers)
            if (
                preferences[LEGACY_ACCESS_TOKEN_KEY].orEmpty().isNotBlank() ||
                preferences[SAVED_SERVERS_KEY] != serializedServers
            ) {
                dataStore.edit { prefs ->
                    prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                    prefs[SAVED_SERVERS_KEY] = serializedServers
                }
            }

            migrationExecuted = true
        }
    }

    private fun StoredSavedServer.toSavedServerOrNull(): SavedServer? {
        if (
            id.isBlank() ||
            serverUrl.isBlank() ||
            userId.isBlank()
        ) {
            return null
        }

        return SavedServer(
            id = id,
            serverUrl = serverUrl,
            serverName = serverName,
            serverTypeRaw = serverTypeRaw,
            username = username,
            userId = userId,
            profileImageUrl = profileImageUrl,
            lastUsedAt = lastUsedAt,
            sourceUrl = sourceUrl
        )
    }

    private fun isSameServer(inputUrl: String, savedUrl: String?): Boolean {
        if (savedUrl.isNullOrBlank()) return false

        val normalizedInput = canonicalServerUrl(inputUrl)
        val normalizedSaved = canonicalServerUrl(savedUrl)
        val normalizedSavedWithoutEmby = normalizedSaved.removeSuffix("/emby")

        return normalizedInput.equals(normalizedSaved, ignoreCase = true) ||
            normalizedInput.equals(normalizedSavedWithoutEmby, ignoreCase = true)
    }

    private fun string(resId: Int, vararg formatArgs: Any): String =
        context.getString(resId, *formatArgs)
}