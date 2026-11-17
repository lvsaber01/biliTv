package com.lvsaber.bilitv.data

/** PGC models intentionally remain separate from UGC's bvid/cid/page model. */
enum class PgcCategory(val seasonType: Int, val label: String) {
    JAPANESE_ANIME(1, "日本动画"),
    GUOCHUANG(4, "国创动画");

    companion object {
        fun fromSeasonType(value: Int): PgcCategory? = entries.firstOrNull { it.seasonType == value }
    }
}

data class PgcSeason(
    val seasonId: Long,
    val mediaId: Long = 0,
    val title: String,
    val coverUrl: String = "",
    val category: PgcCategory,
    val areas: List<String> = emptyList(),
    val description: String = "",
    val subtitle: String = "",
    val score: String = "",
    val indexShow: String = "",
    val badge: String = "",
    val totalEpisodes: Int = 0,
    val isFinished: Boolean = false,
    val newestEpisode: String = "",
    val newestEpisodeId: Long = 0,
    val followStatus: Int = 0,
    val followCount: Long = 0,
    val viewCount: Long = 0,
    val verifiedScopeByServerFilter: Boolean = false,
) {
    val scopeVerified: Boolean
        get() = category == PgcCategory.GUOCHUANG || verifiedScopeByServerFilter || areas.any { area ->
            area.contains("日本", ignoreCase = true) || area.contains("Japan", ignoreCase = true)
        }
}

data class PgcSeasonRef(
    val seasonId: Long,
    val title: String,
    val coverUrl: String = "",
    val order: Int = 0,
)

data class PgcEpisode(
    val seasonId: Long,
    val episodeId: Long,
    val cid: Long,
    val aid: Long = 0,
    val bvid: String = "",
    val index: String = "",
    val title: String = "",
    val longTitle: String = "",
    val coverUrl: String = "",
    val durationSeconds: Int = 0,
    val pubTime: String = "",
    val status: Int = 0,
    val badge: String = "",
    val requiresVip: Boolean = false,
    val isMainContent: Boolean = true,
) {
    val displayTitle: String get() = listOf(index, title).filter(String::isNotBlank).distinct().joinToString(" · ")
        .ifBlank { "第 $episodeId 集" }
}

data class PgcExtraSection(val title: String, val episodes: List<PgcEpisode>)

data class PgcSeasonDetail(
    val season: PgcSeason,
    val seasons: List<PgcSeasonRef>,
    val episodes: List<PgcEpisode>,
    val extraSections: List<PgcExtraSection> = emptyList(),
)

data class PgcPlayableRef(
    val seasonId: Long,
    val episodeId: Long,
    val cid: Long,
    val aid: Long = 0,
    val bvid: String = "",
    val seasonTitle: String,
    val episodeTitle: String,
    val coverUrl: String = "",
    val category: PgcCategory,
    val areas: List<String> = emptyList(),
) {
    val displayTitle: String get() = "$seasonTitle · $episodeTitle"
    val scopeVerified: Boolean get() = category == PgcCategory.GUOCHUANG ||
        areas.any { it.contains("日本", ignoreCase = true) || it.contains("Japan", ignoreCase = true) }
}

data class PgcProgress(
    val playable: PgcPlayableRef,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
) {
    val canResume: Boolean get() = !completed && playable.episodeId > 0 && positionMs > 0 &&
        (durationMs <= 0 || positionMs < durationMs)
    val fraction: Int get() = if (completed) 1000 else if (durationMs > 0)
        (positionMs.toDouble() / durationMs * 1000).toInt().coerceIn(0, 1000) else 0
    val label: String get() = if (completed) "已看完 · ${playable.episodeTitle}" else
        "看到 ${WatchProgress.formatPosition(positionMs)} · ${playable.episodeTitle}"
}

data class PgcTimelineItem(
    val season: PgcSeason,
    val episode: PgcEpisode,
    val dayOfWeek: Int = 0,
    val date: String = "",
    val airTime: String = "",
)

data class PgcTimelineDay(val date: String, val dateSeconds: Long, val dayOfWeek: Int, val items: List<PgcTimelineItem>)
data class PgcIndexPage(val items: List<PgcSeason>, val hasNext: Boolean)
data class PgcSearchPage(val items: List<PgcSeason>, val hasNext: Boolean)

object PgcPagingPolicy {
    const val BATCH_SIZE = 24
    const val MAX_RANKING_ITEMS = 99

    fun shouldLoadMore(
        lastVisiblePosition: Int,
        itemCount: Int,
        spanCount: Int,
        hasMore: Boolean,
        isLoading: Boolean,
        prefetchRows: Int = 2,
    ): Boolean {
        if (itemCount <= 0 || lastVisiblePosition < 0 || !hasMore || isLoading) return false
        val threshold = spanCount.coerceAtLeast(1) * prefetchRows.coerceAtLeast(1)
        return lastVisiblePosition >= (itemCount - threshold).coerceAtLeast(0)
    }

    fun nextRankingLimit(currentLimit: Int): Int =
        (currentLimit.coerceAtLeast(0) + BATCH_SIZE).coerceAtMost(MAX_RANKING_ITEMS)

    fun nextVisibleCount(currentCount: Int, availableCount: Int, batchSize: Int = BATCH_SIZE): Int =
        (currentCount.coerceAtLeast(0) + batchSize.coerceAtLeast(1)).coerceAtMost(availableCount.coerceAtLeast(0))
}

data class PgcHistoryCandidate(
    val seasonId: Long, val episodeId: Long, val cid: Long, val aid: Long, val bvid: String,
    val seasonTitle: String, val episodeTitle: String, val coverUrl: String,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long, val completed: Boolean,
) {
    fun verified(season: PgcSeason): PgcProgress = PgcProgress(
        PgcPlayableRef(season.seasonId, episodeId, cid, aid, bvid,
            seasonTitle.ifBlank { season.title }, episodeTitle, coverUrl.ifBlank { season.coverUrl }, season.category, season.areas),
        positionMs, durationMs, updatedAt, completed,
    )
}
data class PgcHistoryPage(
    val entries: List<PgcProgress>, val next: HistoryCursor?, val hasMore: Boolean,
    val unclassified: List<PgcHistoryCandidate> = emptyList(),
)

object PgcJsonParser {
    fun parseSeasonCard(
        json: org.json.JSONObject,
        fallbackType: Int = 0,
        allowUnknownScope: Boolean = false,
        verifiedScopeByServerFilter: Boolean = false,
    ): PgcSeason? {
        val seasonId = json.optLong("season_id").takeIf { it > 0 } ?: return null
        val seasonType = json.optInt("season_type", fallbackType)
        val category = PgcCategory.fromSeasonType(seasonType) ?: return null
        val areas = parseAreas(json)
        val userStatus = json.optJSONObject("user_status")
        val stat = json.optJSONObject("stat")
        val rating = json.optJSONObject("rating")
        val newEp = json.optJSONObject("new_ep")
        val item = PgcSeason(
            seasonId = seasonId,
            mediaId = json.optLong("media_id"),
            title = clean(json.optString("title").ifBlank { json.optString("season_title") }),
            coverUrl = https(json.optString("cover").ifBlank { json.optString("cover_url") }.ifBlank { json.optString("pic") }),
            category = category,
            areas = areas,
            description = clean(json.optString("evaluate").ifBlank { json.optString("desc") }),
            subtitle = clean(json.optString("subTitle").ifBlank { json.optString("subtitle") }),
            score = rating?.optDouble("score")?.takeIf { it > 0 }?.toString()
                ?: json.optString("score").ifBlank { json.optString("rating") },
            indexShow = clean(json.optString("index_show")),
            badge = clean(json.optString("badge_info").let { raw ->
                if (raw.isBlank()) json.optString("badge") else runCatching { org.json.JSONObject(raw).optString("text") }.getOrDefault(raw)
            }),
            totalEpisodes = json.optInt("total_count", json.optInt("ep_size")),
            isFinished = json.optInt("is_finish") == 1,
            newestEpisode = clean(newEp?.optString("index_show").orEmpty().ifBlank { json.optString("new_ep_index") }),
            newestEpisodeId = newEp?.optLong("id")?.takeIf { it > 0 } ?: json.optLong("newest_ep_id"),
            followStatus = json.optInt("follow_status", userStatus?.optInt("follow")
                ?: userStatus?.optInt("status") ?: json.optInt("is_follow")),
            followCount = stat?.optLong("follow") ?: json.optLong("follow"),
            viewCount = stat?.optLong("view") ?: json.optLong("play"),
            verifiedScopeByServerFilter = verifiedScopeByServerFilter,
        )
        return item.takeIf { it.title.isNotBlank() && (it.scopeVerified || allowUnknownScope) }
    }

    fun parseDetail(root: org.json.JSONObject, requestedSeasonId: Long): PgcSeasonDetail {
        val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: org.json.JSONObject()
        val seasonId = data.optLong("season_id", requestedSeasonId)
        val seasonData = org.json.JSONObject(data.toString()).put("season_id", seasonId)
        // /pgc/view/web/season currently exposes `type`, not `season_type`.
        // The index/rank APIs use `season_type`; accept both without guessing
        // beyond the request's already-known category.
        val seasonType = data.optInt("season_type", data.optInt("type"))
        val card = parseSeasonCard(seasonData, seasonType)
            ?: throw BiliApiException(-422, "番剧分类信息不足或不在日本动画/国创范围")
        val rawSeasons = data.optJSONArray("seasons")
        val seasons = mutableListOf<PgcSeasonRef>()
        if (rawSeasons != null) {
            for (index in 0 until rawSeasons.length()) {
                val raw = rawSeasons.optJSONObject(index) ?: continue
                val id = raw.optLong("season_id")
                if (id > 0) seasons.add(PgcSeasonRef(id, clean(raw.optString("season_title").ifBlank { raw.optString("title") }),
                    https(raw.optString("cover")), index))
            }
        }
        if (seasons.none { it.seasonId == card.seasonId }) seasons.add(0, PgcSeasonRef(card.seasonId, card.title, card.coverUrl, 0))
        val mainEpisodes = parseEpisodes(data.optJSONArray("episodes"), seasonId, true)
        return PgcSeasonDetail(card, seasons, mainEpisodes)
    }

    fun parseSections(root: org.json.JSONObject, seasonId: Long, fallbackEpisodes: List<PgcEpisode>): PgcSeasonDetailParts {
        val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: org.json.JSONObject()
        val main = data.optJSONObject("main_section")?.optJSONArray("episodes")
        val episodes = parseEpisodes(main, seasonId, true).ifEmpty { fallbackEpisodes }
        val sections = buildList {
            val values = data.optJSONArray("section") ?: return@buildList
            for (index in 0 until values.length()) {
                val section = values.optJSONObject(index) ?: continue
                val list = parseEpisodes(section.optJSONArray("episodes"), seasonId, false)
                if (list.isNotEmpty()) add(PgcExtraSection(clean(section.optString("title")).ifBlank { "花絮 / 其他" }, list))
            }
        }
        return PgcSeasonDetailParts(episodes, sections)
    }

    fun parseTimeline(
        root: org.json.JSONObject,
        category: PgcCategory,
        allowUnknownScope: Boolean = false,
    ): List<PgcTimelineDay> {
        val rows = root.optJSONArray("result") ?: root.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
        return buildList {
            for (dayIndex in 0 until rows.length()) {
                val day = rows.optJSONObject(dayIndex) ?: continue
                val episodes = day.optJSONArray("episodes") ?: continue
                val items = buildList {
                    for (index in 0 until episodes.length()) {
                        val raw = episodes.optJSONObject(index) ?: continue
                        val seasonId = raw.optLong("season_id")
                        val episodeId = raw.optLong("episode_id", raw.optLong("ep_id"))
                        // The timeline endpoint is schedule metadata, not a playurl
                        // response: it omits cid and region for current entries.
                        if (seasonId <= 0 || episodeId <= 0) continue
                        val seasonJson = org.json.JSONObject(raw.toString()).put("season_id", seasonId)
                        if (!seasonJson.has("season_type")) seasonJson.put("season_type", category.seasonType)
                        val season = parseSeasonCard(seasonJson, category.seasonType, allowUnknownScope) ?: continue
                        if (season.category != category) continue
                        val episode = PgcEpisode(seasonId, episodeId, raw.optLong("cid"), raw.optLong("aid"), raw.optString("bvid"),
                            clean(raw.optString("pub_index").ifBlank { raw.optString("index") }.ifBlank { raw.optString("index_show") }),
                            clean(raw.optString("episode_title")), clean(raw.optString("title")),
                            https(raw.optString("ep_cover").ifBlank { raw.optString("cover") }),
                            raw.optInt("duration"), raw.optString("pub_time"), raw.optInt("status"),
                            clean(raw.optString("badge")), raw.optBoolean("need_vip"))
                        add(PgcTimelineItem(season, episode, day.optInt("day_of_week"), day.optString("date"), raw.optString("pub_time")))
                    }
                }
                add(PgcTimelineDay(day.optString("date"), day.optLong("date_ts"), day.optInt("day_of_week"), items))
            }
        }
    }

    /**
     * Timeline rows can only be shown after Japanese-region metadata is
     * verified via the season-detail endpoint. Guochuang is identified by its
     * dedicated timeline type and does not require a region lookup.
     */
    fun filterTimelineByVerifiedScopes(
        days: List<PgcTimelineDay>,
        verifiedSeasons: Map<Long, PgcSeason>,
    ): List<PgcTimelineDay> = days.mapNotNull { day ->
        val items = day.items.mapNotNull { item ->
            val season = if (item.season.scopeVerified) item.season else verifiedSeasons[item.season.seasonId]
            season?.takeIf { it.category == item.season.category && it.scopeVerified }
                ?.let { item.copy(season = it) }
        }
        day.copy(items = items).takeIf { items.isNotEmpty() }
    }

    fun applyVerifiedScope(card: PgcSeason, metadata: PgcSeason): PgcSeason? =
        metadata.takeIf { it.seasonId == card.seasonId && it.category == card.category && it.scopeVerified }
            ?.let { card.copy(areas = it.areas) }

    fun parseHistory(root: org.json.JSONObject, cursor: HistoryCursor? = null): PgcHistoryPage {
        val data = root.optJSONObject("data") ?: org.json.JSONObject()
        val list = data.optJSONArray("list") ?: org.json.JSONArray()
        val unclassified = mutableListOf<PgcHistoryCandidate>()
        val entries = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val history = item.optJSONObject("history") ?: org.json.JSONObject()
                if (history.optString("business") !in setOf("pgc", "bangumi", "show")) continue
                val ep = item.optJSONObject("episode") ?: item.optJSONObject("ep") ?: org.json.JSONObject()
                val epId = history.optLong("epid", history.optLong("ep_id", item.optLong("ep_id", ep.optLong("ep_id", ep.optLong("id", history.optLong("oid"))))))
                val seasonId = history.optLong("season_id", history.optLong("sid", item.optLong("season_id", ep.optLong("season_id"))))
                val cid = history.optLong("cid", item.optLong("cid", ep.optLong("cid")))
                if (epId <= 0 || seasonId <= 0 || cid <= 0) continue
                val category = PgcCategory.fromSeasonType(item.optInt("season_type", ep.optInt("season_type")))
                    ?: when {
                        item.optString("season_type_name").contains("国创") || ep.optString("season_type_name").contains("国创") -> PgcCategory.GUOCHUANG
                        item.optString("season_type_name").contains("番剧") || ep.optString("season_type_name").contains("番剧") -> PgcCategory.JAPANESE_ANIME
                        else -> null
                    }
                val areas = item.optJSONArray("areas")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()
                val seasonTitle = clean(item.optString("season_title", ep.optString("season_title")))
                val episodeTitle = clean(item.optString("long_title").ifBlank { item.optString("title") }.ifBlank { ep.optString("long_title") })
                val cover = https(item.optString("cover").ifBlank { ep.optString("cover") })
                val duration = item.optLong("duration").coerceAtLeast(0) * 1000
                val position = item.optLong("progress").coerceAtLeast(0) * 1000
                val updatedAt = item.optLong("view_at") * 1000
                val completed = item.optLong("progress") == -1L || WatchProgress.nearEnd(position, duration)
                if (category == null) {
                    unclassified += PgcHistoryCandidate(seasonId, epId, cid, history.optLong("aid", ep.optLong("aid")),
                        history.optString("bvid", ep.optString("bvid")), seasonTitle, episodeTitle, cover,
                        position, duration, updatedAt, completed)
                    continue
                }
                val ref = PgcPlayableRef(seasonId, epId, cid, history.optLong("aid", ep.optLong("aid")),
                    history.optString("bvid", ep.optString("bvid")), seasonTitle, episodeTitle, cover, category, areas)
                if (category == PgcCategory.JAPANESE_ANIME && areas.isNotEmpty() && areas.none { it.contains("日本") || it.contains("Japan", true) }) continue
                add(PgcProgress(ref, position, duration, updatedAt, completed))
            }
        }
        val c = data.optJSONObject("cursor")
        val next = c?.let { HistoryCursor(it.optLong("max"), it.optLong("view_at"), it.optString("business", "pgc")) }
        return PgcHistoryPage(entries, next, list.length() > 0 && next != null && next.max > 0 && next != cursor, unclassified)
    }

    private fun parseEpisodes(array: org.json.JSONArray?, seasonId: Long, main: Boolean): List<PgcEpisode> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index) ?: continue
                val epId = raw.optLong("ep_id", raw.optLong("id"))
                val cid = raw.optLong("cid")
                if (epId <= 0 || cid <= 0) continue
                val badge = raw.optString("badge_info").let { encoded ->
                    if (encoded.startsWith("{")) runCatching { org.json.JSONObject(encoded).optString("text") }.getOrDefault("") else encoded
                }.ifBlank { raw.optString("badge") }
                add(PgcEpisode(seasonId, epId, cid, raw.optLong("aid"), raw.optString("bvid"),
                    clean(raw.optString("index")), clean(raw.optString("index_title").ifBlank { raw.optString("title") }),
                    clean(raw.optString("long_title")), https(raw.optString("cover")), raw.optInt("duration") / 1000,
                    raw.optString("pub_time"), raw.optInt("status"), clean(badge),
                    raw.optBoolean("need_vip") || raw.optInt("badge_type") > 0 || badge.contains("会员"), main))
            }
        }
    }

    private fun parseAreas(json: org.json.JSONObject): List<String> = json.optJSONArray("areas")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val area = array.optJSONObject(index)
            area?.optString("name")?.takeIf(String::isNotBlank)
                ?: array.optString(index).takeIf { it.isNotBlank() && it != "null" }
        }
    }.orEmpty().ifEmpty {
        json.optString("area").split(',', '、', '/').map(String::trim).filter(String::isNotBlank)
    }

    private fun clean(value: String): String = value.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").trim()
    private fun https(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://")}"
        else -> value
    }
}

data class PgcSeasonDetailParts(val episodes: List<PgcEpisode>, val extraSections: List<PgcExtraSection>)
