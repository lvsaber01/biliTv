package com.lvsaber.blblrebuild.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

/**
 * Thin API gateway. UI code only talks to typed methods, while endpoint
 * variants, WBI signing, cookies, headers and error classification stay here.
 */
class BiliApiClient(context: Context) {
    private val credentialStore = CredentialStore(context.applicationContext)
    @Volatile private var wbiKeys: Pair<String, String>? = null

    fun getPopularVideos(page: Int = 1, pageSize: Int = 20): List<VideoSummary> {
        val root = get("/x/web-interface/popular", mapOf("ps" to pageSize.toString(), "pn" to page.toString()))
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return list.toVideoSummaries()
    }

    /**
     * Loads the current leaderboard for one of Bilibili's top-level video
     * partitions. The v2 endpoint is the current web endpoint; the legacy
     * ranking path remains a compatibility fallback for older responses.
     */
    fun getRegionVideos(rid: Int): List<VideoSummary> {
        val root = runCatching {
            get(
                "/x/web-interface/ranking/v2",
                mapOf("rid" to rid.toString(), "type" to "all"),
            )
        }.recoverCatching {
            get(
                "/x/web-interface/ranking",
                mapOf("rid" to rid.toString(), "day" to "3"),
            )
        }.getOrThrow()
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return list.toVideoSummaries()
    }

    fun searchVideos(keyword: String, page: Int = 1): List<VideoSummary> {
        val params = linkedMapOf(
            "keyword" to keyword,
            "search_type" to "video",
            "page" to page.toString(),
            "order" to "totalrank",
        )
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
     * Requests either the legacy progressive MP4 URL or a DASH video/audio pair.
     * The MP4 path remains the compatibility fallback for older devices and
     * videos where Bilibili does not return a DASH manifest anonymously.
     */
    fun getPlaySource(bvid: String, cid: Long, settings: AppSettings = AppSettings()): PlaySource {
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "qn" to settings.defaultQuality.toString(),
            "fnver" to "0",
            "fnval" to if (settings.videoFormat == AppSettings.VideoFormat.DASH) "16" else "1",
            "fourk" to "1",
        )
        val root = runCatching { get("/x/player/playurl", params) }
            .recoverCatching { getSigned("/x/player/wbi/playurl", params) }
            .getOrThrow()
        val data = root.optJSONObject("data") ?: throw BiliApiException(-500, "播放数据为空")

        if (settings.videoFormat == AppSettings.VideoFormat.DASH) {
            val dash = data.optJSONObject("dash")
            val videoTracks = dash?.optJSONArray("video")?.toVideoTracks().orEmpty()
            val audioTracks = dash?.optJSONArray("audio")?.toAudioTracks().orEmpty()
            val selectedVideo = selectVideoTrack(videoTracks, settings)
            val selectedAudio = audioTracks.maxByOrNull { it.bandwidth }
            if (selectedVideo != null && selectedAudio != null) {
                return PlaySource(
                    url = "",
                    quality = qualityLabel(selectedVideo.id, selectedVideo.height),
                    mimeType = "video/mp4",
                    videoTrack = selectedVideo,
                    audioTrack = selectedAudio,
                    isDash = true,
                    availableAudio = audioTracks.sortedByDescending { it.bandwidth },
                )
            }
        }

        val durl = data.optJSONArray("durl")
        val first = durl?.optJSONObject(0)
        val url = first?.optString("url").orEmpty()
        if (url.isBlank()) throw BiliApiException(-404, "当前视频没有可用的 MP4 播放地址")
        return PlaySource(
            url = url,
            quality = qualityLabel(settings.defaultQuality, 0),
            mimeType = "video/mp4",
        )
    }

    private fun selectVideoTrack(tracks: List<VideoTrack>, settings: AppSettings): VideoTrack? {
        if (tracks.isEmpty()) return null
        val targetHeight = when (settings.defaultQuality) {
            16 -> 360
            32 -> 480
            64 -> 720
            80 -> 1080
            else -> 1080
        }
        val codecTracks = tracks.filter { track ->
            when (settings.videoCodec) {
                AppSettings.VideoCodec.AUTO -> true
                AppSettings.VideoCodec.AVC -> track.codecs.startsWith("avc", ignoreCase = true)
                AppSettings.VideoCodec.HEVC -> track.codecs.startsWith("hev", ignoreCase = true) || track.codecs.startsWith("hvc", ignoreCase = true)
                AppSettings.VideoCodec.AV1 -> track.codecs.startsWith("av01", ignoreCase = true)
            }
        }.ifEmpty { tracks }
        return codecTracks
            .sortedWith(
                compareBy<VideoTrack> {
                    if (it.height <= targetHeight) 0 else 1
                }.thenByDescending { if (it.height <= targetHeight) it.height else -it.height }
                    .thenByDescending { it.bandwidth },
            )
            .firstOrNull()
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

    private fun qualityLabel(id: Int, height: Int): String = when {
        height > 0 -> "${height}P"
        id >= 80 -> "1080P"
        id >= 64 -> "720P"
        id >= 32 -> "480P"
        else -> "360P"
    }

    fun getSubtitleInfo(bvid: String, cid: Long): JSONObject {
        val params = mapOf("bvid" to bvid, "cid" to cid.toString())
        return runCatching { getSigned("/x/player/wbi/v2", params) }
            .getOrElse { get("/x/player/v2", params) }
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

    fun getFavoriteFolders(): List<FavoriteFolder> {
        val navigation = getNavigation()
        val mid = navigation.optLong("mid")
        if (mid == 0L) throw BiliApiException(-101, "请先登录")
        val list = get(
            "/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to mid.toString()),
        ).optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                add(FavoriteFolder(item.optLong("id"), item.optString("title"), item.optInt("media_count")))
            }
        }
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
        return readResponse(connection).body
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

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val response = requestRoot(path, params)
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

    private fun requestRoot(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        return requestRoot(BASE_URL, path, params)
    }

    private fun requestRoot(baseUrl: String, path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val url = if (query.isBlank()) "$baseUrl$path" else "$baseUrl$path?$query"
        val connection = openConnection(url)
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

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", "https://www.bilibili.com/")
        connection.setRequestProperty("Origin", "https://www.bilibili.com")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        connection.setRequestProperty("Sec-Fetch-Site", "same-site")
        connection.setRequestProperty("Sec-Fetch-Mode", "cors")
        connection.setRequestProperty("Sec-Fetch-Dest", "empty")
        credentialStore.readCookie()?.let { connection.setRequestProperty("Cookie", it) }
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
                    coverUrl = item.optString("pic").ifBlank { item.optString("arcurl") }.toHttps(),
                    author = owner?.optString("name")
                        ?: item.optString("author").ifBlank { item.optString("uname") },
                    duration = item.optString("duration").ifBlank { formatDuration(item.optInt("duration")) },
                    description = cleanHtml(item.optString("description").ifBlank { item.optString("desc") }),
                    views = stat?.optLong("view") ?: item.optLong("play"),
                    likes = stat?.optLong("like") ?: item.optLong("favorites"),
                    danmaku = stat?.optLong("danmaku") ?: item.optLong("video_review"),
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

    private fun String.toHttps(): String = replaceFirst("http://", "https://")

    private fun formatDuration(seconds: Int): String {
        val safe = max(seconds, 0)
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private data class HttpResponse(val httpCode: Int, val body: String, val cookies: List<String>)

    companion object {
        private const val BASE_URL = "https://api.bilibili.com"
        private const val PASSPORT_BASE_URL = "https://passport.bilibili.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
