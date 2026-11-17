package com.lvsaber.bilitv.data

import java.util.concurrent.ConcurrentHashMap

/** Short-lived process cache for the Bangumi landing-page rails. */
object PgcHomeFeedCache {
    private data class Entry(val items: List<PgcSeason>, val savedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(key: String, nowMs: Long = System.currentTimeMillis()): List<PgcSeason>? {
        val entry = entries[key] ?: return null
        if (nowMs - entry.savedAtMs !in 0..TTL_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.items
    }

    fun put(key: String, items: List<PgcSeason>, nowMs: Long = System.currentTimeMillis()) {
        entries[key] = Entry(items.toList(), nowMs)
    }

    internal fun clearForTest() = entries.clear()

    const val TTL_MS = 3 * 60 * 1000L
}
