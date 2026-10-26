package com.lvsaber.bilitv.data

/** Bounded Referer fallback policy for Bilibili's video ranking endpoint. */
internal object RankingRequestPolicy {
    private const val RANKING_REFERER_PREFIX = "https://www.bilibili.com/v/popular/rank/"
    private const val ALL_RANK_SLUG = "all"
    private val validSlug = Regex("[a-zA-Z0-9_-]+")

    /** Each retry changes one thing and the bounded sequence never exceeds three requests. */
    fun refererAttempts(slug: String?): List<String?> = buildList {
        val path = normalizedSlug(slug)
        add("$RANKING_REFERER_PREFIX$path")
        if (path != ALL_RANK_SLUG) add("$RANKING_REFERER_PREFIX$ALL_RANK_SLUG")
        add(null)
    }

    private fun normalizedSlug(slug: String?): String =
        slug?.takeIf(validSlug::matches) ?: ALL_RANK_SLUG
}

/** Safely supplements an optional signed-in cookie with Bilibili's anonymous device fingerprint. */
internal object RankingCookiePolicy {
    fun containsDeviceId(cookie: String?): Boolean = cookieParts(cookie).any { it.substringBefore('=') == "buvid3" }

    fun fromFingerprint(buvid3: String?, buvid4: String?): String? = listOf(
        "buvid3" to buvid3,
        "buvid4" to buvid4,
    ).mapNotNull { (name, value) ->
        value?.takeIf { it.isNotBlank() && it.none { char -> char.isWhitespace() || char == ';' } }
            ?.let { "$name=$it" }
    }.takeIf { it.isNotEmpty() }?.joinToString("; ")

    fun merge(existing: String?, anonymous: String?): String? {
        val base = cookieParts(existing)
        val names = base.mapTo(mutableSetOf()) { it.substringBefore('=') }
        val additions = cookieParts(anonymous).filter { names.add(it.substringBefore('=')) }
        return (base + additions).joinToString("; ").ifBlank { null }
    }

    private fun cookieParts(cookie: String?): List<String> = cookie.orEmpty().split(';')
        .map(String::trim)
        .filter { it.isNotEmpty() && it.contains('=') }
}
