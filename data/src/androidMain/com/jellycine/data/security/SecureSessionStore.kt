package com.jellycine.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.jellycine.data.network.canonicalServerUrlKey
import java.security.MessageDigest

object AuthSessionIds {
    fun buildServerId(serverUrl: String, userId: String): String {
        return "${canonicalServerUrlKey(serverUrl)}|${userId.trim()}"
    }
}

class SecureSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(serverId: String?): String? {
        if (serverId.isNullOrBlank()) return null
        return prefs.getString(tokenKey(serverId), null)?.takeIf { it.isNotBlank() }
    }

    fun putToken(serverId: String, accessToken: String) {
        if (serverId.isBlank() || accessToken.isBlank()) return
        prefs.edit().putString(tokenKey(serverId), accessToken).apply()
    }

    fun removeToken(serverId: String?) {
        if (serverId.isNullOrBlank()) return
        prefs.edit().remove(tokenKey(serverId)).apply()
    }

    fun hasToken(serverId: String?): Boolean = !getToken(serverId).isNullOrBlank()

    fun saveCredentials(key: String, username: String, password: String) {
        if (key.isBlank()) return
        prefs.edit()
            .putString(usernameKey(key), username)
            .putString(passwordKey(key), password)
            .apply()
    }

    fun getCredentials(key: String?): Pair<String, String>? {
        if (key.isNullOrBlank()) return null
        val username = prefs.getString(usernameKey(key), null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(passwordKey(key), "") ?: ""
        return Pair(username, password)
    }

    fun removeCredentials(key: String?) {
        if (key.isNullOrBlank()) return
        prefs.edit()
            .remove(usernameKey(key))
            .remove(passwordKey(key))
            .apply()
    }

    private fun tokenKey(serverId: String): String = "token_${sha256(serverId)}"
    private fun usernameKey(key: String): String = "user_${sha256(key)}"
    private fun passwordKey(key: String): String = "pass_${sha256(key)}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append("%02x".format(byte.toInt() and 0xff))
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "secure_auth_store"
    }
}