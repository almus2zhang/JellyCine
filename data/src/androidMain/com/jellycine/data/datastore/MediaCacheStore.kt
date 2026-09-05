package com.jellycine.data.datastore

import android.util.AtomicFile
import android.util.LruCache
import com.jellycine.data.model.BaseItemDto
import com.jellycine.data.model.QueryResult
import com.jellycine.data.network.JellyCineJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Serializable
private data class PersistedQueryCache(
    val queryKey: String,
    val updatedAt: Long,
    val result: QueryResult<BaseItemDto>
)

@Serializable
private data class PersistedItemCache(
    val itemId: String,
    val updatedAt: Long,
    val item: BaseItemDto
)

class MediaCacheStore(
    private val filesDir: File
) {
    private val cacheDir: File by lazy {
        File(filesDir, "media_cache").apply { if (!exists()) mkdirs() }
    }
    private val queriesDir: File by lazy {
        File(cacheDir, "queries").apply { if (!exists()) mkdirs() }
    }
    private val itemsDir: File by lazy {
        File(cacheDir, "items").apply { if (!exists()) mkdirs() }
    }

    private val queryMemoryCache = LruCache<String, PersistedQueryCache>(60)
    private val itemMemoryCache = LruCache<String, PersistedItemCache>(300)

    private val queryLock = Mutex()
    private val itemLock = Mutex()

    private fun hashKey(key: String): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(key.toByteArray(StandardCharsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrElse {
            key.hashCode().toString()
        }
    }

    fun getQuery(queryKey: String, maxAgeMs: Long? = DEFAULT_QUERY_TTL_MS): QueryResult<BaseItemDto>? {
        if (queryKey.isBlank()) return null
        val now = System.currentTimeMillis()

        // 1. Check in-memory LRU cache
        queryMemoryCache.get(queryKey)?.let { cached ->
            val isExpired = maxAgeMs?.let { ttl -> (now - cached.updatedAt) > ttl } ?: false
            if (!isExpired) {
                return cached.result
            }
        }

        // 2. Check disk cache
        val file = File(queriesDir, "${hashKey(queryKey)}.json")
        if (!file.exists()) return null

        return runCatching {
            val rawJson = file.readText(StandardCharsets.UTF_8)
            val parsed = JellyCineJson.decodeFromString<PersistedQueryCache>(rawJson)
            val isExpired = maxAgeMs?.let { ttl -> (now - parsed.updatedAt) > ttl } ?: false
            if (isExpired) {
                file.delete()
                null
            } else {
                queryMemoryCache.put(queryKey, parsed)
                parsed.result
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    suspend fun saveQuery(queryKey: String, result: QueryResult<BaseItemDto>) {
        if (queryKey.isBlank()) return
        withContext(Dispatchers.IO) {
            val payload = PersistedQueryCache(
                queryKey = queryKey,
                updatedAt = System.currentTimeMillis(),
                result = result
            )
            queryMemoryCache.put(queryKey, payload)

            queryLock.withLock {
                runCatching {
                    val file = File(queriesDir, "${hashKey(queryKey)}.json")
                    writeAtomically(file, JellyCineJson.encodeToString(payload))
                }
            }
        }
    }

    fun getItem(itemId: String, maxAgeMs: Long? = DEFAULT_ITEM_TTL_MS): BaseItemDto? {
        if (itemId.isBlank()) return null
        val now = System.currentTimeMillis()

        // 1. Check in-memory LRU cache
        itemMemoryCache.get(itemId)?.let { cached ->
            val isExpired = maxAgeMs?.let { ttl -> (now - cached.updatedAt) > ttl } ?: false
            if (!isExpired) {
                return cached.item
            }
        }

        // 2. Check disk cache
        val file = File(itemsDir, "${hashKey(itemId)}.json")
        if (!file.exists()) return null

        return runCatching {
            val rawJson = file.readText(StandardCharsets.UTF_8)
            val parsed = JellyCineJson.decodeFromString<PersistedItemCache>(rawJson)
            val isExpired = maxAgeMs?.let { ttl -> (now - parsed.updatedAt) > ttl } ?: false
            if (isExpired) {
                file.delete()
                null
            } else {
                itemMemoryCache.put(itemId, parsed)
                parsed.item
            }
        }.getOrElse {
            file.delete()
            null
        }
    }

    suspend fun saveItem(item: BaseItemDto) {
        val itemId = item.id ?: return
        withContext(Dispatchers.IO) {
            val payload = PersistedItemCache(
                itemId = itemId,
                updatedAt = System.currentTimeMillis(),
                item = item
            )
            itemMemoryCache.put(itemId, payload)

            itemLock.withLock {
                runCatching {
                    val file = File(itemsDir, "${hashKey(itemId)}.json")
                    writeAtomically(file, JellyCineJson.encodeToString(payload))
                }
            }
        }
    }

    suspend fun saveItems(items: List<BaseItemDto>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            items.forEach { item ->
                item.id?.let { itemId ->
                    val payload = PersistedItemCache(
                        itemId = itemId,
                        updatedAt = System.currentTimeMillis(),
                        item = item
                    )
                    itemMemoryCache.put(itemId, payload)
                }
            }

            itemLock.withLock {
                items.forEach { item ->
                    item.id?.let { itemId ->
                        runCatching {
                            val file = File(itemsDir, "${hashKey(itemId)}.json")
                            val payload = PersistedItemCache(
                                itemId = itemId,
                                updatedAt = System.currentTimeMillis(),
                                item = item
                            )
                            writeAtomically(file, JellyCineJson.encodeToString(payload))
                        }
                    }
                }
            }
        }
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            queryMemoryCache.evictAll()
            itemMemoryCache.evictAll()
            queryLock.withLock {
                runCatching { queriesDir.deleteRecursively() }
                queriesDir.mkdirs()
            }
            itemLock.withLock {
                runCatching { itemsDir.deleteRecursively() }
                itemsDir.mkdirs()
            }
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            throw error
        }
    }

    companion object {
        const val DEFAULT_QUERY_TTL_MS = 7L * 24 * 3600 * 1000 // 7 days
        const val DEFAULT_ITEM_TTL_MS = 14L * 24 * 3600 * 1000 // 14 days
    }
}
