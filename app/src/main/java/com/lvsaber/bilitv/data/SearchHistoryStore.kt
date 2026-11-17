package com.lvsaber.bilitv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Parses the nested search-square response without coupling UI code to Bilibili's JSON shape. */
internal object SearchHotwordParser {
    fun parse(root: JSONObject, limit: Int = 10): List<String> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val entries = data.optJSONObject("trending")?.optJSONArray("list")
            ?: data.optJSONArray("list")
            ?: JSONArray()
        val maximum = limit.coerceAtLeast(0)
        if (maximum == 0) return emptyList()
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val keyword = item.optString("keyword").ifBlank { item.optString("show_name") }
                .trim().replace(Regex("\\s+"), " ")
            if (keyword.isBlank() || !seen.add(keyword.lowercase())) continue
            result += keyword
            if (result.size >= maximum) break
        }
        return result
    }
}

internal object SearchHistoryPolicy {
    const val MAX_ITEMS = 10

    fun normalize(keyword: String): String = keyword.trim().replace(Regex("\\s+"), " ").take(120)

    fun record(history: List<String>, keyword: String, limit: Int = MAX_ITEMS): List<String> {
        val normalized = normalize(keyword)
        val maximum = limit.coerceAtLeast(0)
        val candidates = buildList {
            if (normalized.isNotBlank()) add(normalized)
            addAll(history.map(::normalize))
        }
        val seen = mutableSetOf<String>()
        return candidates.asSequence()
            .filter(String::isNotBlank)
            .filter { seen.add(it.lowercase()) }
            .take(maximum)
            .toList()
    }
}

/** Device-local recent search terms. This is independent from the signed-in Bilibili account. */
class SearchHistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): List<String> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val values = buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return SearchHistoryPolicy.record(values, "", SearchHistoryPolicy.MAX_ITEMS)
    }

    fun record(keyword: String): List<String> {
        val updated = SearchHistoryPolicy.record(read(), keyword)
        preferences.edit().putString(KEY_HISTORY, JSONArray(updated).toString()).apply()
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val PREFERENCES = "search_history"
        const val KEY_HISTORY = "recent_terms"
    }
}
