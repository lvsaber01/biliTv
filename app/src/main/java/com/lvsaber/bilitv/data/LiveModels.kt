package com.lvsaber.bilitv.data

import androidx.media3.common.PlaybackException
import org.json.JSONArray
import org.json.JSONObject

enum class LiveProtocolPreference(val label: String) {
    AUTO("自动 · HLS 优先"), HLS("仅 HLS"), FLV("仅 FLV"),
}

enum class LiveCodecPreference(val label: String) {
    AUTO("自动 · H.264 优先"), AVC("H.264 / AVC"), HEVC("H.265 / HEVC"),
}

enum class LiveStreamProtocol(val label: String) {
    HLS("HLS"), FLV("FLV"),
}

enum class LiveStreamCodec(val label: String) {
    AVC("H.264"), HEVC("H.265"), UNKNOWN("未知编码"),
}

data class LiveArea(
    val parentId: Int,
    val parentName: String,
    val areaId: Int,
    val areaName: String,
    val isHot: Boolean = false,
)

data class LiveRoomSummary(
    val roomId: Long,
    val title: String,
    val coverUrl: String,
    val anchorName: String,
    val anchorFaceUrl: String = "",
    val parentAreaId: Int = 0,
    val parentAreaName: String = "",
    val areaId: Int = 0,
    val areaName: String = "",
    val online: Long = 0L,
    val liveStatus: Int = 1,
    val isPortrait: Boolean = false,
)

data class LiveRoomDetails(
    val summary: LiveRoomSummary,
    val anchorMid: Long,
    val isHidden: Boolean,
    val isLocked: Boolean,
    val description: String,
)

data class LiveQualityOption(val id: Int, val label: String)

data class LiveStreamRoute(val label: String, val url: String)

data class LiveStreamProfile(
    val qualityId: Int,
    val qualityLabel: String,
    val protocol: LiveStreamProtocol,
    val format: String,
    val codec: LiveStreamCodec,
    val routes: List<LiveStreamRoute>,
)

data class LivePlaybackAttempt(
    val profile: LiveStreamProfile,
    val route: LiveStreamRoute,
)

data class LiveDanmakuHost(val host: String, val wssPort: Int)

data class LiveDanmakuConfig(
    val token: String,
    val hosts: List<LiveDanmakuHost>,
    val buvid3: String = "",
    val uid: Long = 0L,
)

/** Tolerant response parsing is isolated from network code so API shape drift is testable. */
object LiveApiParser {
    fun areas(root: JSONObject): List<LiveArea> {
        val data = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("list")
            ?: root.optJSONObject("data")?.optJSONArray("parent_list")
            ?: return emptyList()
        return buildList {
            for (parentIndex in 0 until data.length()) {
                val parent = data.optJSONObject(parentIndex) ?: continue
                val parentId = parent.optInt("id", parent.optInt("parent_area_id"))
                val parentName = parent.text("name", "parent_area_name").ifBlank { "其他" }
                val children = parent.optJSONArray("list") ?: parent.optJSONArray("areas") ?: continue
                for (areaIndex in 0 until children.length()) {
                    val child = children.optJSONObject(areaIndex) ?: continue
                    val areaId = child.int("id", "area_id")
                    if (areaId <= 0) continue
                    add(
                        LiveArea(
                            parentId = child.int("parent_id").takeIf { it > 0 } ?: parentId,
                            parentName = child.text("parent_name", "parent_area_name").ifBlank { parentName },
                            areaId = areaId,
                            areaName = child.text("name", "area_name").ifBlank { "其他" },
                            isHot = child.optInt("hot_status") == 1,
                        ),
                    )
                }
            }
        }.distinctBy { it.areaId }
    }

    fun rooms(root: JSONObject): List<LiveRoomSummary> {
        val data = root.optJSONObject("data") ?: root
        val sources = listOfNotNull(
            data.optJSONArray("recommend_room_list"),
            data.optJSONArray("list"),
            data.optJSONArray("rooms"),
            data.optJSONArray("room_list"),
            data.optJSONArray("live_room"),
            data.optJSONArray("result"),
        )
        if (sources.isEmpty()) return emptyList()
        val flattened = buildList {
            for (source in sources) {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val nested = item.optJSONArray("list") ?: item.optJSONArray("rooms")
                    if (nested != null) {
                        for (childIndex in 0 until nested.length()) nested.optJSONObject(childIndex)?.let(::add)
                    } else add(item)
                }
            }
        }
        return flattened.mapNotNull(::room).distinctBy { it.roomId }
    }

    fun roomDetails(initRoot: JSONObject, infoRoot: JSONObject): LiveRoomDetails {
        val init = initRoot.optJSONObject("data") ?: JSONObject()
        val info = infoRoot.optJSONObject("data") ?: JSONObject()
        val summary = room(info, init) ?: throw BiliApiException(-500, "直播间信息缺少房间号")
        return LiveRoomDetails(
            summary = summary,
            anchorMid = info.optLong("uid", init.optLong("uid")),
            isHidden = init.optBoolean("is_hidden"),
            isLocked = init.optBoolean("is_locked"),
            description = info.optString("description").takeUnless { it == "null" }.orEmpty(),
        )
    }

    fun playInfo(root: JSONObject, requestedQuality: Int): Pair<List<LiveQualityOption>, List<LiveStreamProfile>> {
        val data = root.optJSONObject("data") ?: return emptyList<LiveQualityOption>() to emptyList()
        val playurl = data.optJSONObject("playurl_info")?.optJSONObject("playurl")
            ?: data.optJSONObject("playurl")
            ?: return emptyList<LiveQualityOption>() to emptyList()
        val descriptions = linkedMapOf<Int, String>()
        (playurl.optJSONArray("g_qn_desc") ?: playurl.optJSONArray("quality_description"))?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = item.optInt("qn", item.optInt("quality"))
                val label = item.text("desc", "name", "description")
                if (id > 0 && label.isNotBlank()) descriptions[id] = label
            }
        }

        val qualityIds = linkedSetOf<Int>()
        val profiles = mutableListOf<LiveStreamProfile>()
        val streams = playurl.optJSONArray("stream") ?: JSONArray()
        for (streamIndex in 0 until streams.length()) {
            val stream = streams.optJSONObject(streamIndex) ?: continue
            val protocol = when (stream.text("protocol_name").lowercase()) {
                "http_hls", "hls" -> LiveStreamProtocol.HLS
                "http_stream", "flv" -> LiveStreamProtocol.FLV
                else -> continue
            }
            val formats = stream.optJSONArray("format") ?: continue
            for (formatIndex in 0 until formats.length()) {
                val format = formats.optJSONObject(formatIndex) ?: continue
                val formatName = format.optString("format_name").lowercase()
                if (protocol == LiveStreamProtocol.FLV && formatName != "flv") continue
                if (protocol == LiveStreamProtocol.HLS && formatName !in setOf("ts", "fmp4", "mp4")) continue
                val codecs = format.optJSONArray("codec") ?: continue
                for (codecIndex in 0 until codecs.length()) {
                    val codec = codecs.optJSONObject(codecIndex) ?: continue
                    val codecName = codec.text("codec_name", "codec").lowercase()
                    val streamCodec = when {
                        "avc" in codecName || "h264" in codecName -> LiveStreamCodec.AVC
                        "hevc" in codecName || "h265" in codecName -> LiveStreamCodec.HEVC
                        else -> LiveStreamCodec.UNKNOWN
                    }
                    val qualityId = codec.optInt("current_qn", requestedQuality)
                        .takeIf { it > 0 } ?: requestedQuality
                    val accepted = codec.optJSONArray("accept_qn")
                    if (accepted != null) for (index in 0 until accepted.length()) {
                        accepted.optInt(index).takeIf { it > 0 }?.let(qualityIds::add)
                    }
                    if (qualityId > 0) qualityIds += qualityId
                    val base = codec.optString("base_url").takeUnless { it == "null" }.orEmpty()
                    val urlInfo = codec.optJSONArray("url_info") ?: JSONArray()
                    val routes = buildList {
                        for (routeIndex in 0 until urlInfo.length()) {
                            val route = urlInfo.optJSONObject(routeIndex) ?: continue
                            val host = route.optString("host").trimEnd('/')
                            val extra = route.optString("extra").takeUnless { it == "null" }.orEmpty()
                            val url = normalizeUrl(host + base + extra)
                            if (host.isNotBlank() && base.isNotBlank() && url.startsWith("https://")) {
                                add(LiveStreamRoute(if (routeIndex == 0) "主线" else "备线 $routeIndex", url))
                            }
                        }
                    }.distinctBy { it.url }
                    if (routes.isEmpty() || qualityId <= 0) continue
                    profiles += LiveStreamProfile(
                        qualityId = qualityId,
                        qualityLabel = descriptions[qualityId] ?: qualityLabel(qualityId),
                        protocol = protocol,
                        format = formatName,
                        codec = streamCodec,
                        routes = routes,
                    )
                }
            }
        }
        val qualities = qualityIds.sortedDescending().map { LiveQualityOption(it, descriptions[it] ?: qualityLabel(it)) }
        return qualities to profiles.distinctBy { Triple(it.qualityId, it.protocol, it.codec) to it.format }
    }

    fun danmakuConfig(root: JSONObject, buvid3: String = "", uid: Long = 0L): LiveDanmakuConfig {
        val data = root.optJSONObject("data") ?: throw BiliApiException(-500, "弹幕连接配置为空")
        val token = data.optString("token").takeUnless { it == "null" }.orEmpty()
        val hosts = data.optJSONArray("host_list") ?: JSONArray()
        val parsed = buildList {
            for (index in 0 until hosts.length()) {
                val item = hosts.optJSONObject(index) ?: continue
                val host = item.optString("host").trim()
                val port = item.optInt("wss_port", 443).takeIf { it in 1..65535 } ?: 443
                if (host.isNotBlank()) add(LiveDanmakuHost(host, port))
            }
        }.distinctBy { it.host to it.wssPort }
        if (token.isBlank() || parsed.isEmpty()) throw BiliApiException(-500, "直播间未返回有效弹幕连接信息")
        return LiveDanmakuConfig(token, parsed, buvid3, uid)
    }

    private fun room(primary: JSONObject, fallback: JSONObject = JSONObject()): LiveRoomSummary? {
        fun long(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
            primary.opt(key)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
                ?: fallback.opt(key)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        } ?: 0L
        fun text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            primary.optString(key).takeUnless { it.isBlank() || it == "null" }
                ?: fallback.optString(key).takeUnless { it.isBlank() || it == "null" }
        }.orEmpty()
        val roomId = long("room_id", "roomid", "real_roomid", "id")
        if (roomId <= 0L) return null
        val areaId = long("area_v2_id", "area_id").toInt()
        val parentAreaId = long("area_v2_parent_id", "parent_area_id").toInt()
        return LiveRoomSummary(
            roomId = roomId,
            title = text("title", "roomname", "room_name").ifBlank { "直播间 $roomId" },
            coverUrl = normalizeUrl(text("user_cover", "cover_from_user", "cover", "keyframe", "cover_url")),
            anchorName = text("uname", "anchor_name", "nickname", "name").ifBlank { "主播" },
            anchorFaceUrl = normalizeUrl(text("face", "uface", "avatar")),
            parentAreaId = parentAreaId,
            parentAreaName = text("area_v2_parent_name", "parent_area_name"),
            areaId = areaId,
            areaName = text("area_v2_name", "area_name", "cate_name"),
            online = long("online", "watched_show_num"),
            liveStatus = primary.optInt("live_status", fallback.optInt("live_status", 1)),
            isPortrait = primary.optBoolean("is_portrait", fallback.optBoolean("is_portrait")),
        )
    }

    private fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        optString(key).takeUnless { it.isBlank() || it == "null" }
    }.orEmpty()

    private fun JSONObject.int(vararg keys: String): Int = keys.firstNotNullOfOrNull { key ->
        opt(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 }
    } ?: 0

    private fun qualityLabel(id: Int): String = when (id) {
        30000, 401 -> "杜比"
        20000 -> "4K"
        10000 -> "原画"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        80 -> "流畅"
        else -> "画质 $id"
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}" 
        value.startsWith("https://", ignoreCase = true) -> value
        else -> value
    }
}

/** Resolves advertised/requested qualities against profiles that actually contain stream URLs. */
object LiveQualityPolicy {
    fun targetQuality(
        requestedQualityId: Int,
        availableQualities: List<LiveQualityOption>,
        profiles: List<LiveStreamProfile>,
    ): Int? {
        if (profiles.isEmpty()) return null
        if (requestedQualityId > 0 && (
                availableQualities.any { it.id == requestedQualityId } ||
                    profiles.any { it.qualityId == requestedQualityId }
            )
        ) return requestedQualityId
        return profiles.maxOfOrNull { it.qualityId }
    }

    fun actualQuality(requestedQualityId: Int, profiles: List<LiveStreamProfile>): Int? {
        if (profiles.isEmpty()) return null
        return requestedQualityId.takeIf { requested -> profiles.any { it.qualityId == requested } }
            ?: profiles.maxOfOrNull { it.qualityId }
    }
}

/** Applies the product rule: HLS first, AVC before HEVC, and never switch codec/quality silently. */
object LiveRecoveryPolicy {
    fun isRecoverablePlaybackCode(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || errorCode in 2000..2999

    fun attempts(
        profiles: List<LiveStreamProfile>,
        qualityId: Int,
        protocolPreference: LiveProtocolPreference,
        codecPreference: LiveCodecPreference,
        supportedCodecs: Set<PlaybackCodec>,
    ): List<LivePlaybackAttempt> {
        val qualityProfiles = profiles.filter { qualityId <= 0 || it.qualityId == qualityId }
        if (qualityProfiles.isEmpty()) return emptyList()
        val requestedCodec = when (codecPreference) {
            LiveCodecPreference.AVC -> LiveStreamCodec.AVC
            LiveCodecPreference.HEVC -> LiveStreamCodec.HEVC
            LiveCodecPreference.AUTO -> null
        }
        val codecOrder = if (requestedCodec != null) listOf(requestedCodec) else {
            val hasAvc = qualityProfiles.any { it.codec == LiveStreamCodec.AVC } &&
                (supportedCodecs.isEmpty() || PlaybackCodec.AVC in supportedCodecs)
            val hasHevc = qualityProfiles.any { it.codec == LiveStreamCodec.HEVC } &&
                (supportedCodecs.isEmpty() || PlaybackCodec.HEVC in supportedCodecs)
            when {
                hasAvc -> listOf(LiveStreamCodec.AVC)
                hasHevc -> listOf(LiveStreamCodec.HEVC)
                else -> emptyList()
            }
        }
        val protocolOrder = when (protocolPreference) {
            LiveProtocolPreference.AUTO -> listOf(LiveStreamProtocol.HLS, LiveStreamProtocol.FLV)
            LiveProtocolPreference.HLS -> listOf(LiveStreamProtocol.HLS)
            LiveProtocolPreference.FLV -> listOf(LiveStreamProtocol.FLV)
        }
        val chosenCodec = codecOrder.firstOrNull() ?: return emptyList()
        val candidatesByProtocol = protocolOrder.associateWith { protocol ->
            qualityProfiles.asSequence()
                .filter { it.protocol == protocol && it.codec == chosenCodec }
                .sortedBy { if (it.format == "ts") 0 else if (it.format == "fmp4" || it.format == "mp4") 1 else 2 }
                .flatMap { profile -> profile.routes.asSequence().map { route -> LivePlaybackAttempt(profile, route) } }
                .toList()
        }
        val ordered = if (protocolPreference != LiveProtocolPreference.AUTO) {
            protocolOrder.flatMap { candidatesByProtocol[it].orEmpty() }
        } else {
            val hls = candidatesByProtocol[LiveStreamProtocol.HLS].orEmpty()
            val flv = candidatesByProtocol[LiveStreamProtocol.FLV].orEmpty()
            val primary = hls.firstOrNull()
            if (primary == null) flv else {
                val sameProfile = hls.filter { it.profile == primary.profile }
                val afterFirstBackup = hls.filterNot { it.profile == primary.profile }
                buildList {
                    add(primary)
                    sameProfile.getOrNull(1)?.let(::add)
                    flv.firstOrNull()?.let(::add)
                    addAll(sameProfile.drop(2))
                    addAll(afterFirstBackup)
                    addAll(flv.drop(1))
                }
            }
        }
        return ordered.distinctBy { it.profile.protocol to it.route.url }
    }

    fun nextAttempt(attempts: List<LivePlaybackAttempt>, currentIndex: Int, retriesUsed: Int, enabled: Boolean): Int? {
        if (!enabled || retriesUsed >= 2) return null
        val next = currentIndex + 1
        return next.takeIf { it in attempts.indices }
    }
}
