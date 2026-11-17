package com.lvsaber.bilitv.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Process cache with stale-while-revalidate and per-feed in-flight request coalescing. */
object PgcHomeFeedCache {
    private data class Entry(val items: List<PgcHomeFeedItem>, val savedAtMs: Long, val complete: Boolean)
    private data class Listener(
        val onProgress: (List<PgcHomeFeedItem>) -> Unit,
        val onComplete: (Result<List<PgcHomeFeedItem>>) -> Unit,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Any()
    private val inFlight = mutableMapOf<String, MutableList<Listener>>()

    fun get(key: String, nowMs: Long = System.currentTimeMillis()): List<PgcHomeFeedItem>? {
        val entry = entries[key] ?: return null
        if (nowMs - entry.savedAtMs !in 0..TTL_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.items
    }

    fun put(key: String, items: List<PgcHomeFeedItem>, nowMs: Long = System.currentTimeMillis()) {
        entries[key] = Entry(items.toList(), nowMs, complete = true)
    }

    /** Returns true when this caller joined or started a refresh; fresh cache needs no request. */
    fun refreshIfStale(
        key: String,
        executor: Executor,
        loader: (publishProgress: (List<PgcHomeFeedItem>) -> Unit) -> List<PgcHomeFeedItem>,
        onProgress: (List<PgcHomeFeedItem>) -> Unit = {},
        onComplete: (Result<List<PgcHomeFeedItem>>) -> Unit,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(lock) {
            inFlight[key]?.let { listeners ->
                listeners += Listener(onProgress, onComplete)
                return true
            }
            val entry = entries[key]
            if (entry?.complete == true && nowMs - entry.savedAtMs in 0..REFRESH_AFTER_MS) return false
            inFlight[key] = mutableListOf(Listener(onProgress, onComplete))
        }

        val task = Runnable {
            val result = runCatching {
                loader { partial ->
                    val snapshot = partial.toList()
                    entries[key] = Entry(snapshot, System.currentTimeMillis(), complete = false)
                    val listeners = synchronized(lock) { inFlight[key].orEmpty().map { it.onProgress } }
                    listeners.forEach { listener -> runCatching { listener(snapshot) } }
                }.toList().also { put(key, it) }
            }
            val listeners = synchronized(lock) { inFlight.remove(key).orEmpty().toList() }
            listeners.forEach { listener -> runCatching { listener.onComplete(result) } }
        }
        try {
            executor.execute(task)
        } catch (error: Exception) {
            val listeners = synchronized(lock) { inFlight.remove(key).orEmpty().toList() }
            val result = Result.failure<List<PgcHomeFeedItem>>(error)
            listeners.forEach { listener -> runCatching { listener.onComplete(result) } }
        }
        return true
    }

    internal fun clearForTest() {
        entries.clear()
        synchronized(lock) { inFlight.clear() }
    }

    const val REFRESH_AFTER_MS = 3 * 60 * 1000L
    const val TTL_MS = 30 * 60 * 1000L
}
