package com.jellycine.app.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.jellycine.app.BuildConfig
import com.jellycine.data.preferences.DownloadPreferences
import com.jellycine.data.preferences.NetworkPreferences
import com.jellycine.data.repository.AuthRepository
import com.jellycine.data.repository.AuthRepositoryProvider
import com.jellycine.data.security.SecureSessionStore
import com.jellycine.player.preferences.PlayerPreferences
import com.jellycine.shared.preferences.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupEnvelope(
    val magic: String = MAGIC,
    val version: Int = 1,
    val isEncrypted: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val serverCount: Int = 0,
    val salt: String? = null,
    val iv: String? = null,
    val data: String
) {
    companion object {
        const val MAGIC = "JELLYCINE_CONFIG_BACKUP"
    }
}

@Serializable
data class ConfigBackupPayload(
    val createdAt: Long = System.currentTimeMillis(),
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val servers: List<AuthRepository.SavedServer> = emptyList(),
    val activeServerId: String? = null,
    val tokens: Map<String, String> = emptyMap(),
    val credentials: Map<String, CredentialBackup> = emptyMap(),
    val playerPreferences: Map<String, String> = emptyMap(),
    val interfacePreferences: Map<String, String> = emptyMap(),
    val networkPreferences: Map<String, String> = emptyMap(),
    val downloadPreferences: Map<String, String> = emptyMap(),
    val seerrPreferences: Map<String, String> = emptyMap()
)

@Serializable
data class CredentialBackup(
    val username: String,
    val password: String
)

data class BackupEnvelopeInfo(
    val isEncrypted: Boolean,
    val createdAt: Long,
    val appVersionName: String,
    val serverCount: Int
)

data class BackupPreview(
    val createdAt: Long,
    val appVersionName: String,
    val serverCount: Int,
    val hasCredentials: Boolean,
    val payload: ConfigBackupPayload
)

class ConfigBackupManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val authRepository = AuthRepositoryProvider.getInstance(appContext)
    private val secureSessionStore = SecureSessionStore(appContext)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    suspend fun exportConfig(
        password: String?,
        includeCredentials: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val savedServers = authRepository.getSavedServers().first()
            val activeServerId = authRepository.getActiveServerId().first()

            val tokens = mutableMapOf<String, String>()
            val credentials = mutableMapOf<String, CredentialBackup>()

            if (includeCredentials) {
                savedServers.forEach { server ->
                    val token = secureSessionStore.getToken(server.id)
                    if (!token.isNullOrBlank()) {
                        tokens[server.id] = token
                    }
                    val cred = secureSessionStore.getCredentials(server.id)
                        ?: server.sourceUrl?.let { secureSessionStore.getCredentials(it) }
                    if (cred != null) {
                        credentials[server.id] = CredentialBackup(cred.first, cred.second)
                    }
                }
            }

            val playerPrefs = appContext.getSharedPreferences("jellycine_player_prefs", Context.MODE_PRIVATE)
            val interfacePrefs = appContext.getSharedPreferences("jellycine_download_prefs", Context.MODE_PRIVATE)
            val networkPrefs = appContext.getSharedPreferences("network_preferences", Context.MODE_PRIVATE)
            val downloadPrefs = appContext.getSharedPreferences("download_preferences", Context.MODE_PRIVATE)
            val seerrPrefs = appContext.getSharedPreferences("seerr_prefs", Context.MODE_PRIVATE)

            val payload = ConfigBackupPayload(
                createdAt = System.currentTimeMillis(),
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                servers = savedServers,
                activeServerId = activeServerId,
                tokens = tokens,
                credentials = credentials,
                playerPreferences = dumpPreferences(playerPrefs),
                interfacePreferences = dumpPreferences(interfacePrefs),
                networkPreferences = dumpPreferences(networkPrefs),
                downloadPreferences = dumpPreferences(downloadPrefs),
                seerrPreferences = dumpPreferences(seerrPrefs)
            )

            val payloadJson = json.encodeToString(payload)

            val envelope = if (!password.isNullOrBlank()) {
                val (ciphertext, salt, iv) = encryptAesGcm(payloadJson, password)
                BackupEnvelope(
                    isEncrypted = true,
                    createdAt = payload.createdAt,
                    appVersionName = payload.appVersionName,
                    appVersionCode = payload.appVersionCode,
                    serverCount = payload.servers.size,
                    salt = salt,
                    iv = iv,
                    data = ciphertext
                )
            } else {
                BackupEnvelope(
                    isEncrypted = false,
                    createdAt = payload.createdAt,
                    appVersionName = payload.appVersionName,
                    appVersionCode = payload.appVersionCode,
                    serverCount = payload.servers.size,
                    data = payloadJson
                )
            }

            Result.success(json.encodeToString(envelope))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun inspectBackup(jsonString: String): Result<BackupEnvelopeInfo> {
        return try {
            val trimmed = jsonString.trim()
            val envelope = json.decodeFromString<BackupEnvelope>(trimmed)
            if (envelope.magic != BackupEnvelope.MAGIC) {
                return Result.failure(IllegalArgumentException("不是有效的 JellyCine 备份文件"))
            }
            Result.success(
                BackupEnvelopeInfo(
                    isEncrypted = envelope.isEncrypted,
                    createdAt = envelope.createdAt,
                    appVersionName = envelope.appVersionName,
                    serverCount = envelope.serverCount
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("解析备份文件结构失败: ${e.message}", e))
        }
    }

    suspend fun parseAndVerify(
        jsonString: String,
        password: String? = null
    ): Result<BackupPreview> = withContext(Dispatchers.Default) {
        try {
            val envelope = json.decodeFromString<BackupEnvelope>(jsonString.trim())
            if (envelope.magic != BackupEnvelope.MAGIC) {
                return@withContext Result.failure(IllegalArgumentException("不是有效的 JellyCine 备份文件"))
            }

            val rawPayloadJson = if (envelope.isEncrypted) {
                if (password.isNullOrBlank()) {
                    return@withContext Result.failure(PasswordRequiredException())
                }
                val salt = envelope.salt ?: return@withContext Result.failure(IllegalArgumentException("备份文件缺少盐值信息"))
                val iv = envelope.iv ?: return@withContext Result.failure(IllegalArgumentException("备份文件缺少 IV 向量"))
                decryptAesGcm(envelope.data, password, salt, iv)
            } else {
                envelope.data
            }

            val payload = json.decodeFromString<ConfigBackupPayload>(rawPayloadJson)
            Result.success(
                BackupPreview(
                    createdAt = payload.createdAt,
                    appVersionName = payload.appVersionName,
                    serverCount = payload.servers.size,
                    hasCredentials = payload.tokens.isNotEmpty() || payload.credentials.isNotEmpty(),
                    payload = payload
                )
            )
        } catch (e: PasswordRequiredException) {
            Result.failure(e)
        } catch (e: javax.crypto.AEADBadTagException) {
            Result.failure(DecryptionFailedException("解密失败：密码不正确或备份已损坏"))
        } catch (e: Exception) {
            val msg = e.message ?: "未知解析错误"
            if (msg.contains("tag", ignoreCase = true) || msg.contains("cipher", ignoreCase = true)) {
                Result.failure(DecryptionFailedException("解密失败：密码不正确"))
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun applyBackup(payload: ConfigBackupPayload): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 1. Restore SharedPreferences
            val playerPrefs = appContext.getSharedPreferences("jellycine_player_prefs", Context.MODE_PRIVATE)
            val interfacePrefs = appContext.getSharedPreferences("jellycine_download_prefs", Context.MODE_PRIVATE)
            val networkPrefs = appContext.getSharedPreferences("network_preferences", Context.MODE_PRIVATE)
            val downloadPrefs = appContext.getSharedPreferences("download_preferences", Context.MODE_PRIVATE)
            val seerrPrefs = appContext.getSharedPreferences("seerr_prefs", Context.MODE_PRIVATE)

            restorePreferences(playerPrefs, payload.playerPreferences)
            restorePreferences(interfacePrefs, payload.interfacePreferences)
            restorePreferences(networkPrefs, payload.networkPreferences)
            restorePreferences(downloadPrefs, payload.downloadPreferences)
            restorePreferences(seerrPrefs, payload.seerrPreferences)

            // 2. Restore tokens & credentials
            payload.tokens.forEach { (serverId, token) ->
                if (serverId.isNotBlank() && token.isNotBlank()) {
                    secureSessionStore.putToken(serverId, token)
                }
            }
            payload.credentials.forEach { (key, cred) ->
                if (key.isNotBlank() && cred.username.isNotBlank()) {
                    secureSessionStore.saveCredentials(key, cred.username, cred.password)
                }
            }

            // 3. Restore saved servers & active server
            if (payload.servers.isNotEmpty()) {
                authRepository.importSavedServers(
                    newServers = payload.servers,
                    preferredActiveServerId = payload.activeServerId
                ).getOrThrow()
            }

            Result.success(payload.servers.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Helper: Dump SharedPreferences with type tag
    private fun dumpPreferences(prefs: SharedPreferences): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            val serialized = when (value) {
                is Boolean -> "b:$value"
                is Int -> "i:$value"
                is Long -> "l:$value"
                is Float -> "f:$value"
                is String -> "s:$value"
                is Set<*> -> "set:" + value.filterIsInstance<String>().joinToString("\u0000")
                else -> null
            }
            if (serialized != null) {
                result[key] = serialized
            }
        }
        return result
    }

    // Helper: Restore SharedPreferences from type tag
    private fun restorePreferences(prefs: SharedPreferences, data: Map<String, String>) {
        if (data.isEmpty()) return
        val editor = prefs.edit()
        for ((key, raw) in data) {
            val colonIdx = raw.indexOf(':')
            if (colonIdx <= 0) continue
            val type = raw.substring(0, colonIdx)
            val value = raw.substring(colonIdx + 1)
            when (type) {
                "b" -> editor.putBoolean(key, value.toBooleanStrictOrNull() ?: false)
                "i" -> value.toIntOrNull()?.let { editor.putInt(key, it) }
                "l" -> value.toLongOrNull()?.let { editor.putLong(key, it) }
                "f" -> value.toFloatOrNull()?.let { editor.putFloat(key, it) }
                "s" -> editor.putString(key, value)
                "set" -> {
                    val set = if (value.isEmpty()) emptySet() else value.split("\u0000").toSet()
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    // Helper: AES-256-GCM encryption with PBKDF2 key derivation
    private fun encryptAesGcm(
        plaintext: String,
        password: String
    ): Triple<String, String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val keyBytes = deriveKey(password, salt)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Triple(
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    // Helper: AES-256-GCM decryption with PBKDF2 key derivation
    private fun decryptAesGcm(
        ciphertextBase64: String,
        password: String,
        saltBase64: String,
        ivBase64: String
    ): String {
        val salt = Base64.decode(saltBase64, Base64.DEFAULT)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.DEFAULT)
        val keyBytes = deriveKey(password, salt)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}

class PasswordRequiredException : Exception("此配置文件已加密，需要输入密码解密")
class DecryptionFailedException(message: String) : Exception(message)
