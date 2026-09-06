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

        NetworkModule.dynamic302RecoveryHandler = { _ ->
            val sourceUrl = getActiveSourceUrl()
            if (is302Url(sourceUrl)) {
                refreshActive302Session(force = true).getOrNull()
            } else {
                null
            }
        }
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
        val PREVIOUS_SERVER_URL_KEY = stringPreferencesKey("previous_server_url")
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

            if (is302Url(targetServer.sourceUrl)) {
                val sourceUrl = targetServer.sourceUrl!!
                val resolvedResult = resolve302ServerUrl(sourceUrl)
                if (resolvedResult.isFailure) {
                    return Result.failure(Exception("无法获取 302 服务器地址: ${resolvedResult.exceptionOrNull()?.message}"))
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
                        return Result.failure(Exception(authResult.exceptionOrNull()?.message ?: "302 自动登录失败"))
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

    fun getActiveSourceUrl(): String? = cachedSnapshot?.sourceUrl

    suspend fun refreshActive302Session(force: Boolean = false): Result<String> = refreshSessionMutex.withLock {
        try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val active = activeServer(preferences)
            val sourceUrl = preferences[SOURCE_URL_KEY]?.takeIf { it.isNotBlank() } ?: active?.sourceUrl
            if (!is302Url(sourceUrl)) {
                return@withLock Result.failure(Exception("当前活跃服务器未配置 302 动态地址"))
            }

            val resolvedResult = resolve302ServerUrl(sourceUrl!!)
            if (resolvedResult.isFailure) {
                return@withLock Result.failure(
                    resolvedResult.exceptionOrNull() ?: Exception("无法获取 302 服务器地址")
                )
            }
            val newRealUrl = resolvedResult.getOrThrow()
            val credentials = secureSessionStore.getCredentials(sourceUrl)
                ?: active?.id?.let { secureSessionStore.getCredentials(it) }
                ?: return@withLock Result.failure(Exception("未找到该 302 服务器的已保存凭据"))

            val currentUrl = active?.serverUrl
            val hasToken = active?.id?.let { secureSessionStore.hasToken(it) } == true
            if (force || !sameServerUrl(currentUrl, newRealUrl) || !hasToken) {
                val authResult = authenticateUser(
                    serverUrl = newRealUrl,
                    username = credentials.first,
                    password = credentials.second,
                    sourceUrl = sourceUrl
                )
                if (authResult.isSuccess) {
                    Result.success(newRealUrl)
                } else {
                    Result.failure(
                        authResult.exceptionOrNull() ?: Exception("302 动态地址认证失败")
                    )
                }
            } else {
                Result.success(newRealUrl)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkOrRefreshActiveSession(): Boolean {
        return try {
            val preferences = dataStore.data.first()
            val active = activeServer(preferences)
            val sourceUrl = preferences[SOURCE_URL_KEY]?.takeIf { it.isNotBlank() } ?: active?.sourceUrl
            if (!is302Url(sourceUrl)) {
                return true
            }
            refreshActive302Session(force = false).isSuccess
        } catch (_: Exception) {
            true
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

    fun is302Url(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("302:", ignoreCase = true) ||
            trimmed.startsWith("302：") ||
            trimmed.startsWith("301:", ignoreCase = true) ||
            trimmed.startsWith("301：")
    }

    fun is301Url(url: String?): Boolean = is302Url(url)

    fun extract302TargetUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("302:", ignoreCase = true) -> trimmed.substring(4).trim()
            trimmed.startsWith("302：") -> trimmed.substring(4).trim()
            trimmed.startsWith("301:", ignoreCase = true) -> trimmed.substring(4).trim()
            trimmed.startsWith("301：") -> trimmed.substring(4).trim()
            else -> trimmed
        }
    }

    fun extract301TargetUrl(url: String): String = extract302TargetUrl(url)

    private data class HttpResponseSnapshot(
        val statusCode: Int,
        val location: String?,
        val body: String
    )

    private fun executeHttpSocketRequest(urlString: String): HttpResponseSnapshot {
        val uri = java.net.URI(urlString)
        val scheme = uri.scheme?.lowercase(java.util.Locale.US) ?: "http"
        val host = uri.host ?: throw java.net.MalformedURLException("Invalid host: $urlString")
        var port = uri.port
        if (port == -1) {
            port = if (scheme == "https") 443 else 80
        }
        var path = uri.rawPath
        if (path.isNullOrEmpty()) path = "/"
        if (uri.rawQuery != null) path += "?" + uri.rawQuery

        val socket: java.net.Socket
        if (scheme == "https") {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            val sc = javax.net.ssl.SSLContext.getInstance("TLS")
            sc.init(null, trustAll, java.security.SecureRandom())

            // To bypass SNI filtering / reject on non-standard servers (e.g. Lucky/NAS), resolve IP and strip hostname
            val ip = java.net.InetAddress.getByName(host)
            val ipOnly = java.net.InetAddress.getByAddress(ip.address)

            val raw = java.net.Socket()
            raw.connect(java.net.InetSocketAddress(ipOnly, port), 8000)
            raw.soTimeout = 8000

            val ssl = sc.socketFactory.createSocket(raw, null, port, true) as javax.net.ssl.SSLSocket
            try {
                val p = ssl.sslParameters
                p.serverNames = null
                ssl.sslParameters = p
            } catch (_: Exception) {}
            ssl.startHandshake()
            socket = ssl
        } else {
            socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 8000)
            socket.soTimeout = 8000
        }

        socket.use { s ->
            val out = s.getOutputStream()
            val hostHeader = if (port == 80 || port == 443) host else "$host:$port"
            val req = "GET $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "User-Agent: JellyCine/1.3.3\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.UTF_8))
            out.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: throw java.io.IOException("服务器无响应")
            var statusCode = 200
            val parts = statusLine.split(" ")
            if (parts.size >= 2) {
                statusCode = parts[1].toIntOrNull() ?: 200
            }

            var location: String? = null
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isEmpty()) break
                if (headerLine.startsWith("Location:", ignoreCase = true)) {
                    location = headerLine.substring(9).trim()
                }
            }

            val body = java.lang.StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                body.append(line).append("\n")
                if (body.length > 65536) break
            }

            return HttpResponseSnapshot(
                statusCode = statusCode,
                location = location,
                body = body.toString()
            )
        }
    }

    fun sanitizeMediaServerUrl(url: String): String {
        var clean = url.trim()
        val malformedPortRegex = Regex(""":(\d+)(web/.*|web.*)""", RegexOption.IGNORE_CASE)
        val match = malformedPortRegex.find(clean)
        if (match != null) {
            clean = clean.replace(match.value, ":${match.groupValues[1]}")
        }
        val hashIdx = clean.indexOf('#')
        if (hashIdx != -1) {
            clean = clean.substring(0, hashIdx)
        }
        val queryIdx = clean.indexOf('?')
        if (queryIdx != -1) {
            clean = clean.substring(0, queryIdx)
        }
        val webIndex = clean.indexOf("/web", ignoreCase = true)
        if (webIndex != -1) {
            clean = clean.substring(0, webIndex)
        } else if (clean.endsWith("/index.html", ignoreCase = true)) {
            clean = clean.substring(0, clean.length - "/index.html".length)
        } else if (clean.endsWith("/web", ignoreCase = true)) {
            clean = clean.substring(0, clean.length - "/web".length)
        }
        return trimTrailingSlash(clean)
    }

    private fun resolveRedirectUrl(baseUrl: String, location: String): String {
        val loc = location.trim()
        if (loc.startsWith("http://", ignoreCase = true) || loc.startsWith("https://", ignoreCase = true)) {
            return loc
        }
        val uri = try {
            java.net.URI(baseUrl)
        } catch (_: Exception) {
            null
        }
        val normalizedBase = if (uri == null || uri.rawPath.isNullOrEmpty()) {
            if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        } else {
            baseUrl
        }
        return try {
            java.net.URI(normalizedBase).resolve(loc).toString()
        } catch (_: Exception) {
            val scheme = uri?.scheme ?: "http"
            val authority = uri?.rawAuthority ?: uri?.host ?: ""
            if (loc.startsWith("/")) {
                "$scheme://$authority$loc"
            } else {
                val baseWithSlash = if (normalizedBase.endsWith("/")) normalizedBase else "$normalizedBase/"
                baseWithSlash + loc
            }
        }
    }

    private fun isWebClientRedirect(target: String): Boolean {
        val trimmed = target.trim()
        return trimmed.contains("/web/", ignoreCase = true) ||
            trimmed.contains("/web/index.html", ignoreCase = true) ||
            trimmed.contains("web/index.html", ignoreCase = true) ||
            trimmed.endsWith("/web", ignoreCase = true) ||
            trimmed.equals("web", ignoreCase = true) ||
            trimmed.startsWith("web/", ignoreCase = true)
    }

    private fun fetch302WithRedirects(startUrl: String, maxHops: Int = 5): String {
        var currentUrl = sanitizeMediaServerUrl(startUrl)
        val visited = mutableSetOf<String>()

        for (hop in 0 until maxHops) {
            visited.add(currentUrl.trimEnd('/'))
            val response = executeHttpSocketRequest(currentUrl)
            val statusCode = response.statusCode
            val location = response.location

            if ((statusCode in 301..303 || statusCode == 307 || statusCode == 308) && !location.isNullOrBlank()) {
                val nextUrl = resolveRedirectUrl(currentUrl, location)
                if (isWebClientRedirect(location) || isWebClientRedirect(nextUrl)) {
                    return sanitizeMediaServerUrl(nextUrl)
                }
                if (nextUrl.contains("0.0.0.0") || visited.contains(nextUrl.trimEnd('/'))) {
                    break
                }
                currentUrl = nextUrl
                continue
            }

            // Not a redirect (e.g. 200 OK)
            val bodyStr = response.body.trim()
            for (line in bodyStr.lines()) {
                val candidate = line.trim()
                if (candidate.isNotBlank() &&
                    (candidate.startsWith("http://", ignoreCase = true) ||
                     candidate.startsWith("https://", ignoreCase = true) ||
                     (candidate.contains(":") && !candidate.contains("<") && !candidate.contains("{") && !candidate.contains(" ")))
                ) {
                    return sanitizeMediaServerUrl(candidate)
                }
            }

            // If body has no candidate address, but we followed at least one redirect:
            if (hop > 0) {
                return sanitizeMediaServerUrl(currentUrl)
            }

            if (bodyStr.isNotBlank() && !bodyStr.contains("<html", ignoreCase = true)) {
                return sanitizeMediaServerUrl(bodyStr)
            }

            return sanitizeMediaServerUrl(currentUrl)
        }

        return sanitizeMediaServerUrl(currentUrl)
    }

    suspend fun resolveDirectRedirect(url: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val sanitized = sanitizeMediaServerUrl(url)
        val normalized = when {
            sanitized.startsWith("http://", ignoreCase = true) || sanitized.startsWith("https://", ignoreCase = true) -> sanitized
            else -> "http://$sanitized"
        }
        var currentUrl = normalized
        val visited = mutableSetOf<String>()

        try {
            for (hop in 0 until 5) {
                visited.add(currentUrl.trimEnd('/'))
                val response = try {
                    executeHttpSocketRequest(currentUrl)
                } catch (_: Exception) {
                    break
                }
                val statusCode = response.statusCode
                val location = response.location

                if ((statusCode in 301..303 || statusCode == 307 || statusCode == 308) && !location.isNullOrBlank()) {
                    val nextUrl = resolveRedirectUrl(currentUrl, location)
                    if (isWebClientRedirect(location) || isWebClientRedirect(nextUrl)) {
                        return@withContext sanitizeMediaServerUrl(nextUrl)
                    }
                    if (nextUrl.contains("0.0.0.0") || visited.contains(nextUrl.trimEnd('/'))) {
                        break
                    }
                    currentUrl = nextUrl
                    continue
                }
                break
            }

            val result = sanitizeMediaServerUrl(currentUrl)
            if (result.isNotBlank() && (result.startsWith("http://", ignoreCase = true) || result.startsWith("https://", ignoreCase = true))) {
                result
            } else {
                sanitized
            }
        } catch (_: Exception) {
            sanitized
        }
    }

    suspend fun resolve302ServerUrl(url: String): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val targetUrlRaw = extract302TargetUrl(url)
            val sanitizedTarget = sanitizeMediaServerUrl(targetUrlRaw)
            if (sanitizedTarget.isBlank()) {
                return@withContext Result.failure(Exception(string(R.string.auth_error_invalid_url_scheme)))
            }

            val urlsToTry = when {
                sanitizedTarget.startsWith("http://", ignoreCase = true) || sanitizedTarget.startsWith("https://", ignoreCase = true) ->
                    listOf(sanitizedTarget)
                else ->
                    listOf("https://$sanitizedTarget", "http://$sanitizedTarget")
            }

            var lastException: Exception? = null
            for (targetUrl in urlsToTry) {
                try {
                    val realAddress = fetch302WithRedirects(targetUrl).trim()
                    if (realAddress.isNotBlank()) {
                        val sanitized = sanitizeMediaServerUrl(realAddress)
                        val fullAddress = if (!sanitized.startsWith("http://", ignoreCase = true) && !sanitized.startsWith("https://", ignoreCase = true)) {
                            "http://$sanitized"
                        } else {
                            sanitized
                        }
                        return@withContext Result.success(trimTrailingSlash(fullAddress.trim()))
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }
            Result.failure(lastException ?: Exception("无法获取 302 服务器地址"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolve301ServerUrl(url: String): Result<String> = resolve302ServerUrl(url)

    suspend fun resolveServerUrl(inputUrl: String): Result<String> {
        val sanitized = sanitizeMediaServerUrl(inputUrl)
        return if (is302Url(sanitized)) {
            resolve302ServerUrl(sanitized)
        } else {
            val redirected = resolveDirectRedirect(sanitized)
            Result.success(sanitizeMediaServerUrl(redirected))
        }
    }

    suspend fun testServerConnection(serverUrl: String): Result<ServerInfo> {
        legacyStorageMigrated()
        return try {
            val sanitized = sanitizeMediaServerUrl(serverUrl)
            val resolvedUrl = if (is302Url(sanitized)) {
                resolve302ServerUrl(sanitized).getOrElse { error ->
                    return Result.failure(Exception("无法获取 302 服务器地址: ${error.message}"))
                }
            } else {
                resolveDirectRedirect(sanitized)
            }
            val finalCleanUrl = sanitizeMediaServerUrl(resolvedUrl)

            if (!finalCleanUrl.startsWith("http://") && !finalCleanUrl.startsWith("https://")) {
                return Result.failure(Exception(string(R.string.auth_error_invalid_url_scheme)))
            }

            val resolved = NetworkModule.serverEndpoint(
                    context = context,
                    serverUrl = finalCleanUrl,
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
                        val oldServerUrl = prefs[SERVER_URL_KEY]
                        if (!oldServerUrl.isNullOrBlank() && !sameServerUrl(oldServerUrl, endpoint.baseUrl)) {
                            prefs[PREVIOUS_SERVER_URL_KEY] = oldServerUrl
                        }
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