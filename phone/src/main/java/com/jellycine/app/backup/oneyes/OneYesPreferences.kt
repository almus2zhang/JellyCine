package com.jellycine.app.backup.oneyes

import android.content.Context
import android.content.SharedPreferences
import com.jellycine.data.security.SecureSessionStore

class OneYesPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureSessionStore = SecureSessionStore(appContext)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, OneYesClient.DEFAULT_SERVER_URL) ?: OneYesClient.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL, value.trim().lowercase()).apply()

    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_PASSWORD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMEMBER_PASSWORD, value).apply()
            if (!value) {
                clearSavedPassword()
            }
        }

    fun getSavedPassword(): String? {
        if (!rememberPassword) return null
        return secureSessionStore.getCredentials(CRED_KEY)?.second
    }

    fun savePassword(password: String) {
        if (rememberPassword && password.isNotBlank()) {
            secureSessionStore.saveCredentials(CRED_KEY, email, password)
        }
    }

    fun clearSavedPassword() {
        secureSessionStore.removeCredentials(CRED_KEY)
    }

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var lastSyncVersion: Int
        get() = prefs.getInt(KEY_LAST_SYNC_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SYNC_VERSION, value).apply()

    companion object {
        private const val PREFS_NAME = "jellycine_oneyes_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_EMAIL = "email"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_VERSION = "last_sync_version"
        private const val CRED_KEY = "oneyes_cloud_hub_auth"
    }
}
