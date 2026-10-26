package com.lvsaber.bilitv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Thin API gateway. UI code only talks to typed methods, while endpoint
 * variants, WBI signing, cookies, headers and error classification stay here.
 */
class BiliApiClient(context: Context) {
    private val credentialStore = CredentialStore(context.applicationContext)
    @Volatile private var wbiKeys: Pair<String, String>? = null
    private val rankingCache = ConcurrentHashMap<Int, List<VideoSummary>>()

    fun getPopularVideos(page: Int = 1, pageSize: Int = 20): List<VideoSummary> {
        val root = get("/x/web-interface/popular", mapOf("ps" to pageSize.toString(), "pn" to page.toString()))
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return list.toVideoSummaries()
    }

    /** Loads the current finite leaderboard for a Bilibili video partition. */
    fun getRegionVideos(rid: Int): List<VideoSummary> = loadRanking(rid, null)

    /** Loads one 12-item slice and keeps finite ranking responses in memory for subsequent slices. */
    fun getBrowseBatch(rail: BrowseRail, page: Int, refreshRanking: Boolean = false): BrowseFetchResult {
        require(page > 0) { "页码必须大于 0" }
        return when (rail.source) {
            BrowseSource.POPULAR -> {
                val videos = getPopularVideos(page, RailBuffer.BATCH_SIZE)
                BrowseFetchResult(videos, videos.size < RailBuffer.BATCH_SIZE)
            }
            BrowseSource.ALL_RANKING, BrowseSource.REGION_RANKING -> {
                val rid = if (rail.source == BrowseSource.ALL_RANKING) 0 else
                    rail.rankingRid ?: throw IllegalArgumentException("排行榜轨道缺少 rankingRid")
                val list = cachedRanking(rid, rail.rankingRefererSlug, refreshRanking && page == 1)
                BrowseBatchSlicer.slice(list, page)
            }
            BrowseSource.REGION_RECOMMEND -> {
                val regionId = rail.regionFeedId ?: throw IllegalArgumentException("推荐轨道缺少 regionFeedId")
                val root = try {
                    get("/x/web-interface/region/feed/rcmd", mapOf(
                        "from_region" to regionId.toString(), "display_id" to page.toString(),
                        "request_cnt" to RailBuffer.BATCH_SIZE.toString(), "device" to "web", "plat" to "30",
                    ))
                } catch (error: BiliApiException) {
                    if (error.apiCode == 62013) return BrowseFetchResult(emptyList(), true)
                    throw error
                }
                val videos = (root.optJSONObject("data")?.optJSONArray("archives") ?: JSONArray()).toVideoSummaries()
                BrowseFetchResult(videos, videos.isEmpty())
            }
            BrowseSource.KEYWORD_SEARCH -> {
                val keyword = rail.keyword ?: throw IllegalArgumentException("主题搜索轨道缺少关键词")
                val videos = searchVideos(keyword, page, RailBuffer.BATCH_SIZE, rail.searchTid)
                BrowseFetchResult(videos, videos.isEmpty())
            }
        }
    }

    private fun cachedRanking(rid: Int, refererSlug: String?, refresh: Boolean): List<VideoSummary> {
        if (!refresh) rankingCache[rid]?.let { return it }
        synchronized(rankingCache) {
            if (!refresh) rankingCache[rid]?.let { return it }
            // Keep the previous value until a refresh succeeds; an API error must not erase usable cards.
            val latest = loadRanking(rid, refererSlug)
            rankingCache[rid] = latest
            return latest
        }
    }

    private fun loadRanking(rid: Int, refererSlug: String?): List<VideoSummary> {
        val params = mapOf("rid" to rid.toString(), "type" to "all", "web_location" to "333.934")
        val root = try {
            get("/x/web-interface/ranking/v2", params, HOME_REFERER)
        } catch (error: BiliApiException) {
            if (error.apiCode != -352) throw error
            val path = refererSlug?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: "all"
            get("/x/web-interface/ranking/v2", params, "$RANKING_REFERER_PREFIX$path")
        }
        val data = root.optJSONObject("data") ?: throw BiliApiException(-500, "排行榜数据为空")
        val list = data.optJSONArray("list") ?: throw BiliApiException(-500, "排行榜列表缺失")
        return list.toVideoSummaries()
    }

    fun searchVideos(keyword: String, page: Int = 1, pageSize: Int = 20, tid: Int? = null): List<VideoSummary> {
        val params = linkedMapOf(
            "keyword" to keyword,
            "search_type" to "video",
            "page" to page.toString(),
            "order" to "totalrank",
            "page_size" to pageSize.toString(),
        )
        tid?.let { params["tids"] = it.toString() }
        val signedRoot = runCatching { getSigned("/x/web-interface/wbi/search/type", params) }.getOrNull()
        val root = signedRoot ?: get(
            "/x/web-interface/search/type",
            params,
        )
        val data = root.optJSONObject("data")
        val result = data?.optJSONArray("result") ?: JSONArray()
        return result.toVideoSummaries()
    }

    fun getVideoDetail(bvid: String): VideoDetail {
        val data = get("/x/web-interface/view", mapOf("bvid" to bvid)).optJSONObject("data")
            ?: throw BiliApiException(-500, "视频详情为空")
        val owner = data.optJSONObject("owner")
        val stat = data.optJSONObject("stat")
        val pages = data.optJSONArray("pages") ?: JSONArray()
        val firstCid = data.optLong("cid", pages.optJSONObject(0)?.optLong("cid") ?: 0L)
        val summary = VideoSummary(
            bvid = data.optString("bvid", bvid),
            aid = data.optLong("aid"),
            cid = firstCid,
            title = cleanHtml(data.optString("title")),
            coverUrl = data.optString("pic").toHttps(),
            author = owner?.optString("name").orEmpty(),
            duration = formatDuration(data.optInt("duration")),
            description = data.optString("desc"),
            views = stat?.optLong("view") ?: 0L,
            likes = stat?.optLong("like") ?: 0L,
            danmaku = stat?.optLong("danmaku") ?: 0L,
            authorMid = owner?.optLong("mid") ?: 0L,
            publishTimeSeconds = data.optLong("pubdate").takeIf { it > 0L },
        )
        val pageList = buildList {
            for (index in 0 until pages.length()) {
                val page = pages.optJSONObject(index) ?: continue
                add(
                    VideoPage(
                        page = page.optInt("page", index + 1),
                        cid = page.optLong("cid"),
                        title = page.optString("part").ifBlank { "第 ${index + 1} P" },
                        durationSeconds = page.optInt("duration"),
                    ),
                )
            }
        }
        return VideoDetail(summary, pageList, summary.description)
    }

    /**
     * Loads the public-facing UP profile. The WBI space endpoint is preferred;
     * the card endpoint remains a compatibility fallback for guests or when
     * the space endpoint is temporarily protected by a new web check.
     */
    fun getUserProfile(mid: Long): UserProfile {
        require(mid > 0) { "UP 主 mid 无效" }
        val info = runCatching {
            getSigned("/x/space/wbi/acc/info", mapOf("mid" to mid.toString()))
        }.getOrElse {
            get("/x/web-interface/card", mapOf("mid" to mid.toString()))
        }
        val relationStat = runCatching { get("/x/relation/stat", mapOf("vmid" to mid.toString())) }.getOrNull()
        val navnum = runCatching { get("/x/space/navnum", mapOf("mid" to mid.toString())) }.getOrNull()
        val relation = if (hasSession()) runCatching { get("/x/relation", mapOf("fid" to mid.toString())) }.getOrNull() else null
        return parseUserProfile(info, relationStat, navnum, relation, mid)
    }

    internal fun parseUserProfile(
        infoRoot: JSONObject,
        relationStatRoot: JSONObject? = null,
        navnumRoot: JSONObject? = null,
        relationRoot: JSONObject? = null,
        fallbackMid: Long = 0L,
    ): UserProfile {
        val rootData = infoRoot.optJSONObject("data") ?: JSONObject()
        val data = rootData.optJSONObject("card") ?: rootData
        val mid = data.optLong("mid", fallbackMid)
        val official = data.optJSONObject("official") ?: data.optJSONObject("official_verify")
        val relationData = relationStatRoot?.optJSONObject("data")
        val navnumData = navnumRoot?.optJSONObject("data")
        val relationDataForUser = relationRoot?.optJSONObject("data")
        val attribute = relationDataForUser?.optInt("attribute", 0) ?: 0
        return UserProfile(
            mid = mid,
            name = data.optString("name").ifBlank { data.optString("uname") }.ifBlank { "未知 UP 主" },
            avatarUrl = data.optString("face").ifBlank { data.optString("avatar") }.toHttps(),
            signature = cleanHtml(data.optString("sign").ifBlank { data.optString("desc") }),
            verification = official?.optString("desc").orEmpty().ifBlank { data.optString("verify_desc") },
            level = data.optInt("level", 0),
            followerCount = relationData?.optLong("follower", data.optLong("fans", 0L)) ?: data.optLong("fans", 0L),
            followingCount = relationData?.optLong("following", data.optLong("attention", 0L)) ?: data.optLong("attention", 0L),
            videoCount = navnumData?.optLong("video", 0L) ?: 0L,
            isFollowing = attribute and 2 == 2,
            liveStatus = data.optJSONObject("live")?.optInt("live_status") == 1,
        )
    }

    /** Loads one page of the user's video submissions. */
    fun getUserVideos(mid: Long, order: String = "pubdate", page: Int = 1, pageSize: Int = 24): UserVideoPage {
        require(mid > 0) { "UP 主 mid 无效" }
        val params = linkedMapOf(
            "mid" to mid.toString(),
            "order" to order,
            "pn" to page.toString(),
            "ps" to pageSize.coerceIn(1, 50).toString(),
            "tid" to "0",
            "keyword" to "",
        )
        val root = runCatching { getSigned("/x/space/wbi/arc/search", params) }
            .recoverCatching { get("/x/space/arc/search", params) }
            .getOrElse {
                get(
                    "/x/series/recArchivesByKeywords",
                    mapOf(
                        "mid" to mid.toString(),
                        "keywords" to "",
                        "ps" to pageSize.coerceIn(1, 100).toString(),
                        "pn" to page.toString(),
                        "orderby" to if (order == "click") "views" else "pubdate",
                    ),
                )
            }
        return parseUserVideoPage(root, page, pageSize)
    }

    internal fun parseUserVideoPage(root: JSONObject, page: Int, pageSize: Int): UserVideoPage {
        val data = root.optJSONObject("data") ?: JSONObject()
        val listObject = data.optJSONObject("list")
        val array = listObject?.optJSONArray("vlist") ?: data.optJSONArray("vlist") ?: data.optJSONArray("archives") ?: JSONArray()
        val videos = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val bvid = item.optString("bvid")
                if (bvid.isBlank()) continue
                add(
                    VideoSummary(
                        bvid = bvid,
                        aid = item.optLong("aid"),
                        cid = item.optLong("cid"),
                        title = cleanHtml(item.optString("title")),
                        coverUrl = item.optString("pic").toHttps(),
                        author = item.optString("author").ifBlank { item.optString("uname") },
                        duration = item.optString("length").ifBlank { formatDuration(item.optInt("duration")) },
                        description = cleanHtml(item.optString("description")),
                        views = item.optLong("play"),
                        likes = item.optLong("favorites"),
                        danmaku = item.optLong("video_review"),
                        authorMid = item.optLong("mid"),
                        publishTimeSeconds = item.optLong("created").takeIf { it > 0L }
                            ?: item.optLong("pubdate").takeIf { it > 0L },
                    ),
                )
            }
        }
        val pageData = data.optJSONObject("page")
        val total = pageData?.optInt("count", pageData.optInt("total", 0))
            ?: data.optInt("count", videos.size)
        return UserVideoPage(videos, page, pageSize, total)
    }

    fun getRelation(mid: Long): Boolean {
        if (!hasSession()) return false
        val data = get("/x/relation", mapOf("fid" to mid.toString())).optJSONObject("data") ?: return false
        return data.optInt("attribute", 0) and 2 == 2
    }

    fun modifyRelation(mid: Long, follow: Boolean) {
        if (mid <= 0) throw BiliApiException(-400, "UP 主 mid 无效")
        postForm(
            "/x/relation/modify",
            mapOf("fid" to mid.toString(), "act" to if (follow) "1" else "2", "re_src" to "11", "csrf" to csrf()),
        )
    }

    fun getFollowingProfiles(page: Int = 1, pageSize: Int = 24): List<UserProfile> {
        val navigation = getNavigation()
        val mid = navigation.optLong("mid")
        if (mid <= 0L) throw BiliApiException(-101, "请先登录")
        val data = get(
            "/x/relation/followings",
            mapOf("vmid" to mid.toString(), "pn" to page.toString(), "ps" to pageSize.coerceIn(1, 50).toString(), "order" to "desc"),
        ).optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val userMid = item.optLong("mid")
                if (userMid <= 0L) continue
                add(
                    UserProfile(
                        mid = userMid,
                        name = item.optString("uname").ifBlank { item.optString("name") },
                        avatarUrl = item.optString("face").toHttps(),
                        signature = cleanHtml(item.optString("sign")),
                        verification = item.optJSONObject("official_verify")?.optString("desc").orEmpty(),
                        isFollowing = true,
                        liveStatus = item.optJSONObject("live")?.optInt("live_status") == 1,
                    ),
                )
            }
        }
    }

    /**
     * Requests either the legacy progressive MP4 URL or a DASH video/audio pair.
     * The MP4 path remains the compatibility fallback for older devices and
     * videos where Bilibili does not return a DASH manifest anonymously.
     */
    fun getPlaySource(
        bvid: String,
        cid: Long,
        settings: AppSettings = AppSettings(),
        device: PlaybackDeviceCapabilities = PlaybackDeviceCapabilities.unrestricted(),
    ): PlaySource {
        val dashRequested = settings.videoFormat == AppSettings.VideoFormat.DASH ||
            // Automatic quality must see the DASH codec set. The legacy MP4
            // response commonly tops out at 720p for anonymous/current API calls.
            settings.defaultQuality == 0 ||
            settings.defaultQuality in DASH_QUALITY_IDS ||
            settings.videoCodec != AppSettings.VideoCodec.AUTO
        val requestedQuality = if (dashRequested) settings.defaultQuality else settings.defaultQuality.takeIf { it > 0 } ?: 80
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            // qn is ignored for DASH, but keeping the requested value helps
            // older endpoint fallbacks and MP4 responses.
            "qn" to requestedQuality.toString(),
            "fnver" to "0",
            // Ask for the full DASH capability set. The response still only
            // contains qualities granted to this account/video.
            "fnval" to if (dashRequested) DASH_CAPABILITY_FLAGS.toString() else "1",
            "fourk" to "1",
        )
        fun requestPlaySource(requestParams: Map<String, String>): JSONObject =
            runCatching { getSigned("/x/player/wbi/playurl", requestParams) }
                .recoverCatching { get("/x/player/playurl", requestParams) }
                .getOrThrow()

        val root = runCatching { requestPlaySource(params) }
            .recoverCatching {
                if (!dashRequested || params["fnval"] == "16") throw it
                requestPlaySource(params.toMutableMap().apply { this["fnval"] = "16" })
            }
            .getOrThrow()
        val data = root.optJSONObject("data") ?: throw BiliApiException(-500, "播放数据为空")
        val serverQualities = data.toPlaybackQualities()

        if (dashRequested) {
            val dash = data.optJSONObject("dash")
            val videoTracks = dash?.optJSONArray("video")?.toVideoTracks().orEmpty()
                .map { track ->
                    val support = device.supports(track)
                    track.copy(deviceSupported = support.supported, deviceReason = support.reason)
                }
            val audioTracks = dash?.optJSONArray("audio")?.toAudioTracks().orEmpty()
            // The quality chooser is intentionally resolution-only. For an
            // explicit codec, omit resolutions that have no such DASH track.
            val qualityTracks = PlaybackTrackSelector.selectableTracks(videoTracks, settings.videoCodec)
            val qualities = mergeQualities(
                serverQualities,
                qualityTracks,
                device,
                requireConcreteTrack = settings.videoCodec != AppSettings.VideoCodec.AUTO,
            )
            val selectedVideo = PlaybackTrackSelector.select(videoTracks, settings)
            if (settings.videoCodec != AppSettings.VideoCodec.AUTO && (selectedVideo == null || audioTracks.isEmpty())) {
                throw BiliApiException(-415, "当前视频没有可用的${settings.videoCodec.label}轨道，请切换编码或画质")
            }
            val selectedAudio = audioTracks.maxByOrNull { it.bandwidth }
            if (selectedVideo != null && selectedAudio != null) {
                val routeSet = PlaybackRouteSet.fromUrls(
                    listOf(selectedVideo.url) + selectedVideo.backupUrls,
                    listOf(selectedAudio.url) + selectedAudio.backupUrls,
                )
                return PlaySource(
                    url = "",
                    quality = qualityLabel(selectedVideo.id, selectedVideo.height, selectedVideo.isHdr, selectedVideo.isDolbyVision),
                    mimeType = "video/mp4",
                    videoTrack = selectedVideo,
                    audioTrack = selectedAudio,
                    isDash = true,
                    availableAudio = audioTracks.sortedByDescending { it.bandwidth },
                    availableQualities = qualities,
                    routeSet = routeSet,
                )
            }
            if (videoTracks.isNotEmpty() && !settings.experimentalQualityEnabled && videoTracks.none { it.deviceSupported }) {
                throw BiliApiException(-415, "设备未检测到可播放的画质，可在设置开启实验性高规格播放")
            }
        }

        val durl = data.optJSONArray("durl")
        val first = durl?.optJSONObject(0)
        val url = first?.optString("url").orEmpty()
        if (url.isBlank()) throw BiliApiException(-404, "当前视频没有可用的 MP4 播放地址")
        val routeSet = PlaybackRouteSet.fromUrls(
            listOf(url) + (first?.optStringArray("backup_url", "backupUrl").orEmpty()),
        )
        val actualQuality = data.optInt("quality").takeIf { it > 0 } ?: requestedQuality
        return PlaySource(
            url = url,
            quality = qualityLabel(actualQuality, 0, actualQuality in HDR_QUALITY_IDS, actualQuality == 126),
            mimeType = "video/mp4",
            availableQualities = serverQualities.map { quality -> quality.withDevice(device) },
            routeSet = routeSet,
        )
    }

    private fun JSONArray.toVideoTracks(): List<VideoTrack> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val url = item.optString("baseUrl").ifBlank { item.optString("base_url") }
            if (url.isBlank()) continue
            add(
                VideoTrack(
                    id = item.optInt("id"),
                    codecs = item.optString("codecs"),
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    bandwidth = item.optInt("bandwidth"),
                    url = url.toHttps(),
                    backupUrls = item.optStringArray("backupUrl", "backup_url"),
                    frameRate = item.optString("frameRate").ifBlank { item.optString("frame_rate") }
                        .substringBefore('/').toFloatOrNull() ?: 0f,
                    mimeType = item.optString("mimeType").ifBlank { "video/mp4" },
                    isHdr = item.optInt("id") in HDR_QUALITY_IDS || item.optString("hdr_type").isNotBlank(),
                    isDolbyVision = item.optInt("id") == 126 || item.optString("codecs").contains("dv", ignoreCase = true) ||
                        item.optString("hdr_type").contains("dolby", ignoreCase = true),
                ),
            )
        }
    }

    private fun JSONArray.toAudioTracks(): List<AudioTrack> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val url = item.optString("baseUrl").ifBlank { item.optString("base_url") }
            if (url.isBlank()) continue
            add(
                AudioTrack(
                    id = item.optInt("id"),
                    codecs = item.optString("codecs"),
                    bandwidth = item.optInt("bandwidth"),
                    url = url.toHttps(),
                    backupUrls = item.optStringArray("backupUrl", "backup_url"),
                ),
            )
        }
    }

    private fun JSONObject.optStringArray(vararg keys: String): List<String> {
        val array = keys.asSequence().mapNotNull { optJSONArray(it) }.firstOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it.toHttps()) }
            }
        }
    }

    private fun JSONObject.toPlaybackQualities(): List<PlaybackQuality> {
        val formats = optJSONArray("support_formats")
        val result = buildList {
            if (formats != null) {
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val id = item.optInt("quality")
                    if (id <= 0) continue
                    val codecs = item.optJSONArray("codecs")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    }.orEmpty()
                    val label = item.optString("new_description")
                        .ifBlank { item.optString("display_desc") }
                        .ifBlank { item.optString("description") }
                        .ifBlank { qualityLabel(id, qualityTargetHeight(id), id in HDR_QUALITY_IDS, id == 126) }
                    add(
                        PlaybackQuality(
                            id = id,
                            label = label,
                            height = qualityTargetHeight(id).takeUnless { it == Int.MAX_VALUE } ?: 0,
                            codecs = codecs,
                            needsLogin = item.optBoolean("need_login"),
                            needsVip = item.optBoolean("need_vip"),
                            isHdr = id in HDR_QUALITY_IDS,
                            isDolbyVision = id == 126,
                        ),
                    )
                }
            }
            if (isEmpty()) {
                val qualities = optJSONArray("accept_quality") ?: JSONArray()
                val descriptions = optJSONArray("accept_description")
                for (index in 0 until qualities.length()) {
                    val id = qualities.optInt(index)
                    if (id <= 0) continue
                    add(
                        PlaybackQuality(
                            id = id,
                            label = descriptions?.optString(index).orEmpty().ifBlank {
                                qualityLabel(id, qualityTargetHeight(id), id in HDR_QUALITY_IDS, id == 126)
                            },
                            height = qualityTargetHeight(id).takeUnless { it == Int.MAX_VALUE } ?: 0,
                            isHdr = id in HDR_QUALITY_IDS,
                            isDolbyVision = id == 126,
                        ),
                    )
                }
            }
        }
        return result.distinctBy { it.id }.sortedByDescending { it.id }
    }

    private fun mergeQualities(
        server: List<PlaybackQuality>,
        tracks: List<VideoTrack>,
        device: PlaybackDeviceCapabilities,
        requireConcreteTrack: Boolean = false,
    ): List<PlaybackQuality> {
        // DASH quality entries without a concrete track URL are entitlement
        // metadata only; do not present them as selectable in the player.
        val ids = when {
            tracks.isNotEmpty() -> tracks.map { it.id }.distinct()
            requireConcreteTrack -> emptyList()
            else -> server.map { it.id }.distinct()
        }
        return ids.mapNotNull { id ->
            val matching = tracks.filter { it.id == id }
            val base = server.firstOrNull { it.id == id }
            val track = matching.maxByOrNull { it.bandwidth }
            val codecs = (base?.codecs.orEmpty() + matching.map { it.codecs }).filter(String::isNotBlank).distinct()
            val support = when {
                matching.any { it.deviceSupported } -> DeviceSupport(true, null)
                matching.isNotEmpty() -> DeviceSupport(false, matching.first().deviceReason)
                base != null -> device.supports(base)
                else -> DeviceSupport(true, null)
            }
            (base ?: PlaybackQuality(id, qualityLabel(id, track?.height ?: 0, id in HDR_QUALITY_IDS, id == 126))).copy(
                width = track?.width?.takeIf { it > 0 } ?: base?.width ?: 0,
                height = track?.height?.takeIf { it > 0 } ?: base?.height ?: 0,
                codecs = codecs,
                isHdr = base?.isHdr == true || matching.any { it.isHdr },
                isDolbyVision = base?.isDolbyVision == true || matching.any { it.isDolbyVision },
                deviceSupported = support.supported,
                deviceReason = support.reason,
            )
        }.sortedWith(compareByDescending<PlaybackQuality> { it.height }.thenByDescending { it.id })
    }

    private fun PlaybackQuality.withDevice(device: PlaybackDeviceCapabilities): PlaybackQuality {
        val support = device.supports(this)
        return copy(deviceSupported = support.supported, deviceReason = support.reason)
    }

    private fun qualityLabel(id: Int, height: Int, hdr: Boolean, dolbyVision: Boolean): String {
        val resolution = when {
            height >= 4000 || id == 127 -> "8K"
            height >= 2000 || id in setOf(120, 125, 126, 129) -> "4K"
            id == 112 -> "1080P+"
            id == 116 -> "1080P60"
            id == 74 -> "720P60"
            height > 0 -> "${height}P"
            id >= 80 -> "1080P"
            id >= 64 -> "720P"
            id >= 32 -> "480P"
            else -> "360P"
        }
        return when {
            dolbyVision -> "Dolby Vision · $resolution"
            hdr -> "HDR · $resolution"
            else -> resolution
        }
    }

    private fun qualityTargetHeight(id: Int): Int = PlaybackTrackSelector.qualityTargetHeight(id)

    fun getSubtitleInfo(bvid: String, cid: Long): JSONObject {
        val params = mapOf("bvid" to bvid, "cid" to cid.toString())
        return runCatching { getSigned("/x/player/wbi/v2", params) }
            .getOrElse { get("/x/player/v2", params) }
    }

    fun getPlayerInfo(bvid: String, cid: Long): PlayerInfo {
        val root = getSubtitleInfo(bvid, cid)
        return parsePlayerInfo(root)
    }

    internal fun parsePlayerInfo(root: JSONObject): PlayerInfo {
        val data = root.optJSONObject("data") ?: JSONObject()
        val subtitles = buildList {
            val list = data.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val url = item.optString("subtitle_url").toHttps()
                if (url.isBlank()) continue
                add(
                    SubtitleTrack(
                        id = item.optLong("id", index.toLong()),
                        language = item.optString("lan").ifBlank { item.optString("lan_doc") },
                        label = item.optString("lan_doc").ifBlank { item.optString("lan").ifBlank { "字幕 ${index + 1}" } },
                        url = url,
                    ),
                )
            }
        }
        val chapters = buildList {
            val list = data.optJSONArray("view_points") ?: data.optJSONArray("chapters") ?: JSONArray()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val start = (item.optDouble("from", item.optDouble("start", 0.0)) * 1000).toLong().coerceAtLeast(0)
                val end = (item.optDouble("to", item.optDouble("end", 0.0)) * 1000).toLong().coerceAtLeast(start)
                val title = item.optString("content").ifBlank { item.optString("title") }.trim()
                if (title.isNotBlank()) add(VideoChapter(start, end, title))
            }
        }
        return PlayerInfo(subtitles, chapters)
    }

    fun getSubtitleCues(url: String): List<SubtitleCue> {
        if (url.isBlank()) return emptyList()
        val connection = openConnection(url.toHttps())
        val body = try {
            check(connection.responseCode in 200..299) { "字幕请求失败：HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }
        } finally { connection.disconnect() }
        return parseSubtitleCues(body)
    }

    internal fun parseSubtitleCues(body: String): List<SubtitleCue> {
        val list = runCatching { JSONObject(body).optJSONArray("body") }.getOrNull() ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val content = item.optString("content").trim()
                if (content.isBlank()) continue
                add(
                    SubtitleCue(
                        fromMs = (item.optDouble("from", 0.0) * 1000).toLong().coerceAtLeast(0),
                        toMs = (item.optDouble("to", 0.0) * 1000).toLong().coerceAtLeast(0),
                        content = content,
                    ),
                )
            }
        }
    }

    fun getHistory(pageSize: Int = 20): List<VideoSummary> {
        val root = runCatching {
            get("/x/web-interface/history/cursor", mapOf("ps" to pageSize.toString(), "type" to "archive"))
        }.getOrElse {
            get("/x/v2/history", mapOf("ps" to pageSize.toString(), "type" to "archive"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return list.toHistorySummaries()
    }

    fun getHistoryPage(cursor: HistoryCursor? = null, expectedAccount: String? = null): HistoryPage {
        val params = mutableMapOf("ps" to "24", "type" to "archive")
        cursor?.let { params["max"] = it.max.toString(); params["view_at"] = it.viewAt.toString(); params["business"] = it.business }
        val cookie = credentialStore.readCookie()
        if (expectedAccount != null && AccountIdentity.fromCookie(cookie) != expectedAccount) throw BiliApiException(-101, "账号已变化，请重新加载")
        val root = requestRoot(BASE_URL, "/x/web-interface/history/cursor", params, cookie)
        if (root.optInt("code") != 0) throw BiliApiException(root.optInt("code"), root.optString("message"))
        val data = root.optJSONObject("data") ?: JSONObject()
        return parseHistoryPage(data, cursor)
    }

    internal fun parseHistoryPage(data: JSONObject, cursor: HistoryCursor? = null): HistoryPage {
        val list = data.optJSONArray("list") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val history = item.optJSONObject("history") ?: continue
                val bvid = history.optString("bvid")
                if (bvid.isBlank() || history.optString("business") != "archive") continue
                val cid = history.optLong("cid")
                val duration = item.optLong("duration").coerceAtLeast(0) * 1000
                val progress = item.optLong("progress")
                val video = VideoSummary(bvid, history.optLong("oid"), cid, cleanHtml(item.optString("title")),
                    item.optString("cover").toHttps(), item.optString("author_name"), formatDuration((duration / 1000).toInt()), "", 0, 0, 0)
                add(WatchProgress(video, cid, history.optInt("page", 1).coerceAtLeast(1), if (progress == -1L) duration else progress.coerceAtLeast(0) * 1000,
                    duration, item.optLong("view_at") * 1000, progress == -1L || WatchProgress.nearEnd(progress * 1000, duration)))
            }
        }
        val c = data.optJSONObject("cursor")
        val next = c?.let { HistoryCursor(it.optLong("max"), it.optLong("view_at"), it.optString("business")) }
        return HistoryPage(entries, next, list.length() > 0 && next != null && next.max > 0 && next != cursor)
    }

    fun getFavoriteFolders(video: VideoSummary? = null): List<FavoriteFolder> {
        val navigation = getNavigation()
        val mid = navigation.optLong("mid")
        if (mid == 0L) throw BiliApiException(-101, "请先登录")
        val params = mutableMapOf("up_mid" to mid.toString())
        video?.let {
            params["rid"] = it.aid.toString()
            params["type"] = "2"
        }
        val list = get(
            "/x/v3/fav/folder/created/list-all",
            params,
        ).optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                add(FavoriteFolder(item.optLong("id"), item.optString("title"), item.optInt("media_count"), item.optInt("fav_state") == 1))
            }
        }
    }

    fun updateFavorite(video: VideoSummary, addFolderIds: List<Long>, removeFolderIds: List<Long>) {
        if (video.aid <= 0) throw BiliApiException(-400, "视频缺少 avid，无法收藏")
        val params = linkedMapOf(
            "rid" to video.aid.toString(),
            "type" to "2",
        )
        if (addFolderIds.isNotEmpty()) params["add_media_ids"] = addFolderIds.joinToString(",")
        if (removeFolderIds.isNotEmpty()) params["del_media_ids"] = removeFolderIds.joinToString(",")
        params["csrf"] = csrf()
        postForm("/x/v3/fav/resource/deal", params)
    }

    fun addToWatchLater(video: VideoSummary) {
        postForm("/x/v2/history/toview/add", mapOf("bvid" to video.bvid, "csrf" to csrf()))
    }

    fun getWatchLater(): List<VideoSummary> {
        val list = get("/x/v2/history/toview", emptyMap()).optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return list.toVideoSummaries()
    }

    fun getFavoriteVideos(folderId: Long, page: Int = 1, pageSize: Int = 20): List<VideoSummary> {
        val list = get(
            "/x/v3/fav/resource/list",
            mapOf("media_id" to folderId.toString(), "pn" to page.toString(), "ps" to pageSize.toString(), "platform" to "web"),
        ).optJSONObject("data")?.optJSONArray("medias") ?: JSONArray()
        return list.toVideoSummaries()
    }

    fun getDanmakuXml(cid: Long): String {
        val connection = openConnection("https://comment.bilibili.com/$cid.xml")
        return try {
            check(connection.responseCode in 200..299) { "弹幕请求失败：HTTP ${connection.responseCode}" }
            val encoding = connection.contentEncoding
            connection.inputStream.use { DanmakuCodec.decode(DanmakuCodec.readBounded(it), encoding) }
        } finally { connection.disconnect() }
    }

    fun getNavigation(): JSONObject = requestRoot("/x/web-interface/nav").optJSONObject("data") ?: JSONObject()

    fun generateQrLogin(): QrLoginTicket {
        val data = requestRoot(
            PASSPORT_BASE_URL,
            "/x/passport-login/web/qrcode/generate",
            mapOf("source" to "main_web"),
        ).also { root ->
            if (root.optInt("code", 0) != 0) throw BiliApiException(root.optInt("code"), root.optString("message", "二维码生成失败"))
        }.optJSONObject("data") ?: throw BiliApiException(-500, "二维码生成失败")
        return QrLoginTicket(data.optString("url"), data.optString("qrcode_key"))
    }

    /** Returns the nested QR status code and saves the session cookie on success. */
    fun pollQrLogin(ticket: QrLoginTicket): QrLoginStatus {
        val response = requestRoot(
            PASSPORT_BASE_URL,
            "/x/passport-login/web/qrcode/poll",
            mapOf("qrcode_key" to ticket.key, "source" to "main_web"),
        )
        val data = response.optJSONObject("data")
        val headers = response.optJSONObject("__headers")
        val statusCode = data?.optInt("code", response.optInt("code")) ?: response.optInt("code")
        if (statusCode == 0 && response.optInt("code") == 0) {
            headers?.optString("set-cookie")?.takeIf { it.isNotBlank() }?.let(credentialStore::saveCookie)
        }
        return QrLoginStatus(
            code = statusCode,
            message = data?.optString("message").orEmpty().ifBlank { response.optString("message") },
            loggedIn = statusCode == 0 && headers?.optString("set-cookie").orEmpty().isNotBlank(),
        )
    }

    fun hasSession(): Boolean = !credentialStore.readCookie().isNullOrBlank()

    fun clearSession() = credentialStore.clear()

    private fun csrf(): String = credentialStore.readCookie().orEmpty().split(';')
        .asSequence().map(String::trim).firstOrNull { it.startsWith("bili_jct=") }
        ?.substringAfter('=')?.takeIf { it.isNotBlank() }
        ?: throw BiliApiException(-111, "登录 Cookie 缺少 CSRF，需重新扫码登录")

    private fun get(path: String, params: Map<String, String>, referer: String = HOME_REFERER): JSONObject {
        val response = requestRoot(path, params, referer)
        val code = response.optInt("code", 0)
        if (code != 0) {
            throw BiliApiException(code, response.optString("message", "Bilibili API error"))
        }
        return response
    }

    private fun getSigned(path: String, params: Map<String, String>): JSONObject {
        val (imgKey, subKey) = getWbiKeys()
        val signed = BiliWbiSigner.sign(params, imgKey, subKey, System.currentTimeMillis() / 1000)
        return get(path, signed)
    }

    private fun getWbiKeys(): Pair<String, String> {
        wbiKeys?.let { return it }
        val data = requestRoot("/x/web-interface/nav").optJSONObject("data")
        val image = data?.optJSONObject("wbi_img")
        val result = extractKey(image?.optString("img_url")) to extractKey(image?.optString("sub_url"))
        if (result.first.isBlank() || result.second.isBlank()) {
            throw BiliApiException(-500, "无法获取 WBI 密钥")
        }
        wbiKeys = result
        return result
    }

    private fun requestRoot(
        path: String,
        params: Map<String, String> = emptyMap(),
        referer: String = HOME_REFERER,
    ): JSONObject {
        return requestRoot(BASE_URL, path, params, credentialStore.readCookie(), referer)
    }

    private fun postForm(path: String, params: Map<String, String>): JSONObject {
        val url = "$BASE_URL$path"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            val body = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.setRequestProperty("Origin", "https://www.bilibili.com")
            credentialStore.readCookie()?.let { connection.setRequestProperty("Cookie", it) }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val response = readResponse(connection)
            val root = runCatching { JSONObject(response.body) }.getOrElse {
                throw BiliApiException(response.httpCode, "Bilibili 返回了非 JSON 数据", response.httpCode)
            }
            if (root.optInt("code", 0) != 0) throw BiliApiException(root.optInt("code"), root.optString("message", "操作失败"))
            root
        } finally { connection.disconnect() }
    }

    private fun requestRoot(
        baseUrl: String,
        path: String,
        params: Map<String, String>,
        cookie: String? = credentialStore.readCookie(),
        referer: String = HOME_REFERER,
    ): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val url = if (query.isBlank()) "$baseUrl$path" else "$baseUrl$path?$query"
        val connection = openConnection(url, cookie, referer)
        val response = readResponse(connection)
        val root = runCatching { JSONObject(response.body) }.getOrElse {
            throw BiliApiException(response.httpCode, "Bilibili 返回了非 JSON 数据", response.httpCode)
        }
        if (response.cookies.isNotEmpty()) {
            val header = response.cookies.joinToString("; ")
            root.put("__headers", JSONObject().put("set-cookie", header))
        }
        return root
    }

    private fun openConnection(
        url: String,
        cookie: String? = credentialStore.readCookie(),
        referer: String = HOME_REFERER,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Origin", "https://www.bilibili.com")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        connection.setRequestProperty("Sec-Fetch-Site", "same-site")
        connection.setRequestProperty("Sec-Fetch-Mode", "cors")
        connection.setRequestProperty("Sec-Fetch-Dest", "empty")
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val httpCode = connection.responseCode
        val stream = if (httpCode in 200..399) connection.inputStream else connection.errorStream
        val body = stream?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
        val cookies = connection.headerFields.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value
            .orEmpty()
            .map { it.substringBefore(';') }
            .filter { it.contains('=') }
        connection.disconnect()
        return HttpResponse(httpCode, body, cookies)
    }

    private fun extractKey(url: String?): String = url
        ?.substringAfterLast('/')
        ?.substringBefore('.')
        .orEmpty()

    private fun encode(value: String): String = java.net.URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
        .replace("%7E", "~")

    private fun JSONArray.toVideoSummaries(): List<VideoSummary> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val bvid = item.optString("bvid")
            if (bvid.isBlank()) continue
            val owner = item.optJSONObject("owner")
            val stat = item.optJSONObject("stat")
            add(
                VideoSummary(
                    bvid = bvid,
                    aid = item.optLong("aid"),
                    cid = item.optLong("cid"),
                    title = cleanHtml(item.optString("title")),
                    coverUrl = item.optString("pic").ifBlank { item.optString("cover") }.toHttps(),
                    author = owner?.optString("name") ?: item.optJSONObject("author")?.optString("name")
                        ?: item.optString("author").ifBlank { item.optString("uname") },
                    duration = if (item.opt("duration") is Number) formatDuration(item.optInt("duration")) else item.optString("duration"),
                    description = cleanHtml(item.optString("description").ifBlank { item.optString("desc") }),
                    views = stat?.optLong("view") ?: item.optLong("play"),
                    likes = stat?.optLong("like") ?: item.optLong("favorites"),
                    danmaku = stat?.optLong("danmaku") ?: item.optLong("video_review"),
                    authorMid = owner?.optLong("mid") ?: item.optLong("mid"),
                    publishTimeSeconds = item.optLong("pubdate").takeIf { it > 0L },
                ),
            )
        }
    }

    private fun JSONArray.toHistorySummaries(): List<VideoSummary> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val history = item.optJSONObject("history") ?: continue
            val bvid = history.optString("bvid")
            if (bvid.isBlank()) continue
            add(
                VideoSummary(
                    bvid = bvid,
                    aid = history.optLong("oid"),
                    cid = history.optLong("cid"),
                    title = cleanHtml(item.optString("title")),
                    coverUrl = item.optString("cover").toHttps(),
                    author = item.optString("author_name"),
                    duration = formatDuration(item.optInt("duration")),
                    description = item.optString("long_title"),
                    views = 0L,
                    likes = 0L,
                    danmaku = 0L,
                    publishTimeSeconds = item.optLong("pubdate").takeIf { it > 0L },
                ),
            )
        }
    }

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")

    private fun String.toHttps(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") -> replaceFirst("http://", "https://")
        else -> this
    }

    private fun formatDuration(seconds: Int): String {
        val safe = max(seconds, 0)
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private data class HttpResponse(val httpCode: Int, val body: String, val cookies: List<String>)

    companion object {
        private const val BASE_URL = "https://api.bilibili.com"
        private const val PASSPORT_BASE_URL = "https://passport.bilibili.com"
        private const val DASH_CAPABILITY_FLAGS = 4048
        private val DASH_QUALITY_IDS = setOf(74, 100, 112, 116, 120, 125, 126, 127, 129)
        private val HDR_QUALITY_IDS = setOf(125, 126, 129)
        private const val HOME_REFERER = "https://www.bilibili.com/"
        private const val RANKING_REFERER_PREFIX = "https://www.bilibili.com/v/popular/rank/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
