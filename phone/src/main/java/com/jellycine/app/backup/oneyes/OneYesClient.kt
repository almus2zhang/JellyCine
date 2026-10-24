package com.jellycine.app.backup.oneyes

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class OneYesConfigResponse(
    @SerialName("app_name") val appName: String = "",
    val exists: Boolean = false,
    val version: Int = 0,
    val remark: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("encrypted_data") val encryptedData: String? = null
)

@Serializable
data class OneYesPushRequest(
    @SerialName("encrypted_data") val encryptedData: String,
    val remark: String = "Client update"
)

@Serializable
data class OneYesPushResponse(
    @SerialName("app_name") val appName: String = "",
    val version: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class OneYesAuthRequest(
    val email: String,
    @SerialName("auth_key") val authKey: String
)

@Serializable
data class OneYesHistoryItem(
    val version: Int = 0,
    @SerialName("encrypted_data") val encryptedData: String = "",
    val remark: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class OneYesHistoryResponse(
    val history: List<OneYesHistoryItem> = emptyList()
)

class OneYesClient(
    serverUrl: String,
    email: String,
    password: String,
    val appName: String = DEFAULT_APP_NAME
) {
    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.10.66:8890"
        const val DEFAULT_APP_NAME = "jellycine"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    val serverUrl: String = serverUrl.trim().trimEnd('/')
    val email: String = email.trim().lowercase()

    private val authKeyB64: String
    private val dataKeyBytes: ByteArray

    init {
        require(this.email.isNotBlank()) { "邮箱不能为空" }
        require(password.isNotBlank()) { "主密码不能为空" }

        val authSalt = ("oneyes_auth_" + this.email).toByteArray(Charsets.UTF_8)
        val authBytes = derivePbkdf2Key(password, authSalt)
        this.authKeyB64 = Base64.encodeToString(authBytes, Base64.NO_WRAP)

        val dataSalt = ("oneyes_data_" + this.email).toByteArray(Charsets.UTF_8)
        this.dataKeyBytes = derivePbkdf2Key(password, dataSalt)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun derivePbkdf2Key(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encrypt(plaintext: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKeyBytes, "AES"), GCMParameterSpec(128, nonce))
        val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(12 + ciphertextWithTag.size)
        System.arraycopy(nonce, 0, combined, 0, 12)
        System.arraycopy(ciphertextWithTag, 0, combined, 12, ciphertextWithTag.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedB64: String): String {
        val raw = Base64.decode(encryptedB64, Base64.DEFAULT)
        if (raw.size < 12 + 16) {
            throw IllegalArgumentException("密文长度不足")
        }
        val nonce = raw.copyOfRange(0, 12)
        val ciphertextWithTag = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKeyBytes, "AES"), GCMParameterSpec(128, nonce))
        val decrypted = cipher.doFinal(ciphertextWithTag)
        return String(decrypted, Charsets.UTF_8)
    }

    suspend fun register(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = OneYesAuthRequest(email = email, authKey = authKeyB64)
            val reqBody = json.encodeToString(bodyObj).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$serverUrl/api/auth/register")
                .post(reqBody)
                .build()
            val resp = httpClient.newCall(request).execute()
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                Result.failure(Exception("注册失败 [${resp.code}]: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = OneYesAuthRequest(email = email, authKey = authKeyB64)
            val reqBody = json.encodeToString(bodyObj).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$serverUrl/api/auth/login")
                .post(reqBody)
                .build()
            val resp = httpClient.newCall(request).execute()
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                Result.failure(Exception("登录失败 [${resp.code}]: $err"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 自动登录或注册：
     * 1. 尝试登录；
     * 2. 若返回 401 且未注册，尝试自动注册；
     * 3. 若已注册但 401，说明密码错误。
     */
    suspend fun ensureAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        val loginRes = login()
        if (loginRes.isSuccess) {
            return@withContext Result.success(Unit)
        }
        val loginErrMsg = loginRes.exceptionOrNull()?.message ?: ""
        if (loginErrMsg.contains("401")) {
            val regRes = register()
            if (regRes.isSuccess) {
                return@withContext Result.success(Unit)
            }
            val regErrMsg = regRes.exceptionOrNull()?.message ?: ""
            if (regErrMsg.contains("already registered", ignoreCase = true) || regErrMsg.contains("已注册", ignoreCase = true)) {
                return@withContext Result.failure(Exception("认证失败：主密码错误"))
            }
            return@withContext Result.failure(Exception("账号认证失败: $regErrMsg"))
        }
        loginRes
    }

    suspend fun pull(): Result<Pair<String?, Int>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/api/config/$appName")
                .addHeader("X-Email", email)
                .addHeader("X-Auth-Key", authKeyB64)
                .get()
                .build()
            val resp = httpClient.newCall(request).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                return@withContext Result.failure(Exception("拉取云端配置失败 [${resp.code}]: $err"))
            }
            val respBody = resp.body?.string() ?: return@withContext Result.success(null to 0)
            val configResp = json.decodeFromString<OneYesConfigResponse>(respBody)
            if (!configResp.exists || configResp.encryptedData.isNullOrBlank()) {
                return@withContext Result.success(null to 0)
            }
            val decryptedJson = decrypt(configResp.encryptedData)
            Result.success(decryptedJson to configResp.version)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun push(plaintextJson: String, remark: String = "Client update"): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val encryptedData = encrypt(plaintextJson)
            val pushReq = OneYesPushRequest(encryptedData = encryptedData, remark = remark)
            val reqBody = json.encodeToString(pushReq).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$serverUrl/api/config/$appName")
                .addHeader("X-Email", email)
                .addHeader("X-Auth-Key", authKeyB64)
                .post(reqBody)
                .build()
            val resp = httpClient.newCall(request).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                return@withContext Result.failure(Exception("推送云端配置失败 [${resp.code}]: $err"))
            }
            val respBody = resp.body?.string() ?: throw IllegalStateException("空响应")
            val pushResp = json.decodeFromString<OneYesPushResponse>(respBody)
            Result.success(pushResp.version)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
