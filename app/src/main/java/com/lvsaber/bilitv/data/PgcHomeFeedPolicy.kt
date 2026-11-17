package com.lvsaber.bilitv.data

data class PgcHomeFeedItem(
    val season: PgcSeason,
    val broadcastDateSeconds: Long,
    val airTime: String,
)

/** Pure transformations for the compact Bangumi landing-page feed. */
object PgcHomeFeedPolicy {
    private const val SECONDS_PER_DAY = 24 * 60 * 60L

    fun fromTimeline(days: List<PgcTimelineDay>): List<PgcHomeFeedItem> = days.flatMap { day ->
        day.items.map { item ->
            PgcHomeFeedItem(
                season = item.season.copy(
                    indexShow = item.episode.displayTitle,
                    newestEpisode = item.airTime,
                ),
                broadcastDateSeconds = day.dateSeconds,
                airTime = item.airTime,
            )
        }
    }

    /** Interleaves categories by broadcast date and keeps the closest schedule entry per season. */
    fun merge(
        feeds: Collection<List<PgcHomeFeedItem>>,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): List<PgcHomeFeedItem> {
        val today = nowSeconds / SECONDS_PER_DAY
        return feeds.asSequence()
            .flatten()
            .sortedWith(
                compareBy<PgcHomeFeedItem> { dateOrder(it.broadcastDateSeconds, today) }
                    .thenBy { timeOfDayMinutes(it.airTime) ?: Int.MAX_VALUE }
                    .thenBy { it.season.title }
                    .thenBy { it.season.seasonId },
            )
            .distinctBy { it.season.seasonId }
            .toList()
    }

    fun filter(items: List<PgcHomeFeedItem>, category: PgcCategory?): List<PgcSeason> =
        items.asSequence()
            .filter { category == null || it.season.category == category }
            .map { it.season }
            .toList()

    fun continueWatching(progress: List<PgcProgress>, limit: Int = 12): List<PgcProgress> =
        progress.asSequence()
            .filter { it.canResume }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.playable.seasonId }
            .take(limit.coerceAtLeast(0))
            .toList()

    private fun dateOrder(dateSeconds: Long, today: Long): Long {
        if (dateSeconds <= 0L) return Long.MAX_VALUE
        val day = dateSeconds / SECONDS_PER_DAY
        val delta = day - today
        // Today and upcoming days first, then the most recently aired past days.
        return if (delta >= 0) delta else 8L + -delta
    }

    private fun timeOfDayMinutes(value: String): Int? {
        val match = TIME_PATTERN.find(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private val TIME_PATTERN = Regex("(\\d{1,2}):(\\d{2})")
}
