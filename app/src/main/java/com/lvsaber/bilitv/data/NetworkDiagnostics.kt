package com.lvsaber.bilitv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

data class ProbeTiming(
    val success: Boolean,
    val connectMs: Long = 0L,
    val firstByteMs: Long = 0L,
    val bytesRead: Int = 0,
    val httpCode: Int? = null,
    val error: String? = null,
) {
    val grade: String
        get() = when {
            !success -> "失败"
            firstByteMs < 300 -> "优秀"
            firstByteMs < 1_000 -> "正常"
            firstByteMs < 3_000 -> "较慢"
            else -> "很慢"
        }
}

data class RouteProbeResult(
    val routeId: String,
    val displayName: String,
    val host: String,
    val video: ProbeTiming,
    val audio: ProbeTiming? = null,
    val testedAt: Long = System.currentTimeMillis(),
) {
    val success: Boolean get() = video.success && (audio?.success != false)
}

data class PlaybackDiagnosticsSnapshot(
    val routeId: String? = null,
    val routeHost: String = "未选择",
    val firstFrameMs: Long? = null,
    val bufferingCount: Int = 0,
    val bufferingMs: Long = 0L,
    val switchCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Process-local diagnostic state. Signed URLs are intentionally not persisted. */
object PlaybackDiagnosticsStore {
    private val routeSetRef = AtomicReference(PlaybackRouteSet())
    private val probeRef = AtomicReference<Map<String, RouteProbeResult>>(emptyMap())
    private val snapshotRef = AtomicReference(PlaybackDiagnosticsSnapshot())

    fun setRouteSet(routeSet: PlaybackRouteSet) = routeSetRef.set(routeSet)
    fun routeSet(): PlaybackRouteSet = routeSetRef.get()
    fun probes(): Map<String, RouteProbeResult> = probeRef.get()
    fun snapshot(): PlaybackDiagnosticsSnapshot = snapshotRef.get()
    fun setProbes(results: List<RouteProbeResult>) = probeRef.set(results.associateBy { it.routeId })
    fun clearProbes() = probeRef.set(emptyMap())
    fun update(snapshot: PlaybackDiagnosticsSnapshot) = snapshotRef.set(snapshot.copy(updatedAt = System.currentTimeMillis()))

    fun recordRoute(routeId: String?, host: String, switchCount: Int) {
        update(snapshotRef.get().copy(routeId = routeId, routeHost = host, switchCount = switchCount))
    }
}

class RouteProbeClient {
    fun probe(route: PlaybackRoute): RouteProbeResult {
        val video = route.videoUrl?.let(::probeUrl) ?: ProbeTiming(false, error = "没有视频地址")
        val audio = route.audioUrl?.let(::probeUrl)
        return RouteProbeResult(route.id, route.displayName, route.displayHost, video, audio)
    }

    private fun probeUrl(url: String): ProbeTiming {
        var connection: HttpURLConnection? = null
        val started = System.nanoTime()
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Range", "bytes=0-${MAX_BYTES - 1}")
                setRequestProperty("Referer", "https://www.bilibili.com/")
                setRequestProperty("Origin", "https://www.bilibili.com")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val connectStart = System.nanoTime()
            val code = connection.responseCode
            val connectMs = elapsedMs(connectStart)
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                return ProbeTiming(false, connectMs, httpCode = code, error = "HTTP $code")
            }
            val buffer = ByteArray(MAX_BYTES)
            val firstByteStart = System.nanoTime()
            val input = connection.inputStream
            val first = input.read(buffer)
            val firstByteMs = elapsedMs(firstByteStart)
            var total = first.coerceAtLeast(0)
            while (total < MAX_BYTES) {
                val count = input.read(buffer, total, MAX_BYTES - total)
                if (count <= 0) break
                total += count
            }
            input.close()
            ProbeTiming(true, connectMs, firstByteMs, total, code)
        } catch (error: Exception) {
            ProbeTiming(false, elapsedMs(started), error = error.message?.take(96) ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val READ_TIMEOUT_MS = 4_000
        private const val MAX_BYTES = 64 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

        fun networkLabel(context: Context): String {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork ?: return "未连接"
            val capabilities = manager.getNetworkCapabilities(network) ?: return "未连接"
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                else -> "其他网络"
            } + if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " · 可访问互联网" else " · 未验证"
        }
    }
}
