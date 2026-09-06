package com.jellycine.shared.util.image

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.key.Keyer
import coil3.intercept.Interceptor as CoilInterceptor
import coil3.request.Options
import coil3.Uri
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.jellycine.data.DataModuleConfig
import com.jellycine.data.datastore.DataStoreProvider
import com.jellycine.data.model.AuthHeaderDto
import com.jellycine.data.network.NetworkModule
import com.jellycine.data.network.ServerType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.jellycine.data.preferences.NetworkPreferences
import com.jellycine.data.security.AuthSessionIds
import com.jellycine.data.security.LEGACY_ACCESS_TOKEN_KEY
import com.jellycine.data.security.SecureSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

private class CanonicalImageInterceptor(
    private val diskCacheProvider: () -> DiskCache?
) : CoilInterceptor {
    override suspend fun intercept(chain: CoilInterceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data.toString()
        val canonicalKey = ImageLoaderConfig.getCanonicalServerImageKey(url)
        if (canonicalKey != null) {
            val diskCache = diskCacheProvider()
            if (diskCache != null) {
                val canonicalSnapshot = diskCache.openSnapshot(canonicalKey)
                if (canonicalSnapshot != null) {
                    canonicalSnapshot.close()
                } else {
                    val rawSnapshot = diskCache.openSnapshot(url)
                    if (rawSnapshot != null) {
                        try {
                            val editor = diskCache.openEditor(canonicalKey)
                            if (editor != null) {
                                rawSnapshot.data.toFile().copyTo(editor.data.toFile(), overwrite = true)
                                editor.commit()
                            }
                        } catch (_: Exception) {
                        } finally {
                            rawSnapshot.close()
                        }
                    }
                }
            }
            val newRequest = request.newBuilder()
                .memoryCacheKey(MemoryCache.Key(canonicalKey))
                .diskCacheKey(canonicalKey)
                .build()
            return chain.withRequest(newRequest).proceed()
        }
        return chain.proceed()
    }
}


object ImageLoaderConfig {
    private val SERVER_URL_KEY = stringPreferencesKey("server_url")
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val SERVER_TYPE_KEY = stringPreferencesKey("server_type")
    private const val BYTES_PER_MB = 1024L * 1024L

    private val deviceId by lazy { UUID.randomUUID().toString() }
    private const val IMAGE_STORE_DIR = "media_store/image_cache"

    private fun persistentImageCacheDir(context: Context): File {
        val dir = File(context.filesDir, IMAGE_STORE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun DiskCacheSize(context: Context): Long {
        val availableBytes = context.filesDir.usableSpace
        val percent = when {
            availableBytes > 64L * 1024 * 1024 * 1024 -> 0.05
            availableBytes > 16L * 1024 * 1024 * 1024 -> 0.05
            availableBytes > 4L * 1024 * 1024 * 1024 -> 0.04
            else -> 0.03
        }

        val calculatedSize = (availableBytes * percent).toLong()
        val finalSize = max(100L * 1024 * 1024, min(2048L * 1024 * 1024, calculatedSize))

        return finalSize
    }

    private fun configuredImageCacheBytes(context: Context): Long? {
        // Disk cache size is decoupled from memory cache to allow generous persistent caching
        return null
    }

    private fun getOptimalMemoryPercent(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMB = memoryInfo.totalMem / (1024L * 1024L)
        val isLargeHeap = activityManager.memoryClass != activityManager.largeMemoryClass

        val basePercent = when {
            totalRamMB >= 8192 -> if (isLargeHeap) 0.15 else 0.12
            totalRamMB >= 4096 -> if (isLargeHeap) 0.12 else 0.10
            totalRamMB >= 2048 -> if (isLargeHeap) 0.10 else 0.08
            else -> if (isLargeHeap) 0.08 else 0.06
        }

        return max(0.08, min(0.30, basePercent))
    }

    private fun ImageMemoryCacheBytes(context: Context): Long? {
        val configuredMb = NetworkPreferences(context).getImageMemoryCacheMb()
        if (configuredMb == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB) {
            return null
        }
        return configuredMb * BYTES_PER_MB
    }

    fun clearDiskCache(context: Context) {
        runCatching {
            persistentImageCacheDir(context).deleteRecursively()
        }
    }

    fun getCanonicalServerImageKey(url: String): String? {
        val lower = url.lowercase()
        val matchPrefix = when {
            lower.contains("/items/") -> "/items/"
            lower.contains("/users/") -> "/users/"
            lower.contains("/images/") -> "/images/"
            lower.contains("/api/v1/image") -> "/api/v1/image"
            else -> return null
        }
        val idx = lower.indexOf(matchPrefix)
        if (idx < 0) return null

        val pathAndQuery = url.substring(idx)
        return "canonical_server_image:$pathAndQuery"
    }

    private fun createAuthenticatedOkHttpClient(context: Context): OkHttpClient {
        val dataStore = DataStoreProvider.getDataStore(context)
        val secureSessionStore = SecureSessionStore(context)
        val authHeaderLock = Any()
        var cachedAuthHeader: String? = null
        var cachedAuthHeaderAt = 0L
        val authHeaderTtlMs = 1200L

        fun buildAuthHeader(): String {
            val now = System.currentTimeMillis()
            synchronized(authHeaderLock) {
                val existingHeader = cachedAuthHeader
                if (existingHeader != null && (now - cachedAuthHeaderAt) < authHeaderTtlMs) {
                    return existingHeader
                }

                val preferences = runBlocking {
                    runCatching { dataStore.data.first() }.getOrNull()
                }
                val serverUrl = preferences?.get(SERVER_URL_KEY)
                val userId = preferences?.get(USER_ID_KEY)
                val accessToken = if (!serverUrl.isNullOrBlank() && !userId.isNullOrBlank()) {
                    secureSessionStore.getToken(AuthSessionIds.buildServerId(serverUrl, userId))
                } else {
                    null
                } ?: preferences?.get(LEGACY_ACCESS_TOKEN_KEY)
                val serverType = preferences?.get(SERVER_TYPE_KEY)?.let {
                    runCatching { ServerType.valueOf(it) }.getOrNull()
                }
                val header = AuthHeaderDto.fromServerType(
                    serverType = serverType,
                    deviceId = deviceId,
                    version = DataModuleConfig.CLIENT_VERSION,
                    accessToken = accessToken
                ).asHeaderValue()
                cachedAuthHeader = header
                cachedAuthHeaderAt = now
                return header
            }
        }

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val authHeader = buildAuthHeader()
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", authHeader)
                .addHeader("X-Emby-Authorization", authHeader)
                .addHeader("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5")
                .build()

            var response: okhttp3.Response? = null
            var error: Exception? = null

            try {
                response = chain.proceed(newRequest)
            } catch (e: Exception) {
                error = e
            }

            val shouldRecover = error is java.io.IOException || (response != null && (response.code in 502..504 || (!response.isSuccessful && response.code >= 500)))
            if (shouldRecover) {
                val handler = NetworkModule.dynamic302RecoveryHandler
                if (handler != null) {
                    val newBaseUrl = runBlocking {
                        runCatching { handler.invoke(originalRequest.url.toString()) }.getOrNull()
                    }
                    if (!newBaseUrl.isNullOrBlank()) {
                        val newUri = newBaseUrl.toHttpUrlOrNull()
                        if (newUri != null && (newUri.host != originalRequest.url.host || newUri.port != originalRequest.url.port || newUri.scheme != originalRequest.url.scheme)) {
                            response?.close()
                            val refreshedAuthHeader = buildAuthHeader()
                            val rewrittenUrl = originalRequest.url.newBuilder()
                                .scheme(newUri.scheme)
                                .host(newUri.host)
                                .port(newUri.port)
                                .build()
                            val retryReq = originalRequest.newBuilder()
                                .url(rewrittenUrl)
                                .header("Authorization", refreshedAuthHeader)
                                .header("X-Emby-Authorization", refreshedAuthHeader)
                                .header("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8,*/*;q=0.5")
                                .build()
                            return@Interceptor chain.proceed(retryReq)
                        }
                    }
                }
            }

            if (error != null) {
                throw error
            }

            var res = response!!
            var retryCount = 0
            while (!res.isSuccessful && res.code >= 500 && retryCount < 2) {
                res.close()
                retryCount++
                res = chain.proceed(newRequest)
            }

            res
        }

        val imageCachingInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val urlString = request.url.toString()
            val isImage = urlString.contains("/Images/", ignoreCase = true) ||
                (urlString.contains("/Items/", ignoreCase = true) && urlString.contains("/Images", ignoreCase = true)) ||
                urlString.contains("image.tmdb.org", ignoreCase = true) ||
                urlString.contains("/api/v1/image", ignoreCase = true) ||
                response.header("Content-Type")?.startsWith("image/") == true

            if (response.isSuccessful && isImage) {
                response.newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Cache-Control")
                    .header("Cache-Control", "public, max-age=31536000, immutable")
                    .build()
            } else {
                response
            }
        }

        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .addInterceptor(authInterceptor)
            .addNetworkInterceptor(imageCachingInterceptor)
            .build()
    }

    fun createOptimizedImageLoader(context: Context): ImageLoader {
        val networkPreferences = NetworkPreferences(context)
        val imageCachingEnabled = networkPreferences.isImageCachingEnabled()
        val configuredMemoryCacheBytes = ImageMemoryCacheBytes(context)

        var diskCacheRef: DiskCache? = null

        val componentRegistry = ComponentRegistry.Builder()
            .add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { createAuthenticatedOkHttpClient(context) }
                )
            )
            .add(CanonicalImageInterceptor { diskCacheRef })
            .build()

        val builtDiskCache = DiskCache.Builder()
            .directory(persistentImageCacheDir(context).toOkioPath())
            .maxSizeBytes(configuredImageCacheBytes(context) ?: DiskCacheSize(context))
            .build()
        diskCacheRef = builtDiskCache

        val builder = ImageLoader.Builder(context)
            .components(componentRegistry)
            .memoryCache {
                val memoryCacheBuilder = MemoryCache.Builder()
                if (configuredMemoryCacheBytes != null) {
                    memoryCacheBuilder.maxSizeBytes(configuredMemoryCacheBytes)
                } else {
                    memoryCacheBuilder.maxSizePercent(context, getOptimalMemoryPercent(context))
                }
                memoryCacheBuilder.build()
            }
            .diskCache(builtDiskCache)

        if (imageCachingEnabled) {
            builder.memoryCachePolicy(CachePolicy.ENABLED)
            builder.diskCachePolicy(CachePolicy.ENABLED)
            builder.networkCachePolicy(CachePolicy.ENABLED)
        } else {
            builder.memoryCachePolicy(CachePolicy.DISABLED)
            builder.diskCachePolicy(CachePolicy.DISABLED)
            builder.networkCachePolicy(CachePolicy.DISABLED)
        }

        return builder.build()
    }

}
