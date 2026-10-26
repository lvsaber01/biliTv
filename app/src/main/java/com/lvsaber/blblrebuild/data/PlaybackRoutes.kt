package com.lvsaber.blblrebuild.data

import java.net.URI

/**
 * A playable video/audio URL pair returned for one CDN route.
 * Signed URLs stay in memory only; the UI uses [videoHost] and [audioHost].
 */
data class PlaybackRoute(
    val id: String,
    val displayName: String,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val primary: Boolean = false,
    val priority: Int = 0,
) {
    val videoHost: String get() = hostOf(videoUrl)
    val audioHost: String get() = hostOf(audioUrl)

    val displayHost: String
        get() = when {
            videoHost.isNotBlank() && audioHost.isNotBlank() && videoHost != audioHost -> "$videoHost / $audioHost"
            videoHost.isNotBlank() -> videoHost
            audioHost.isNotBlank() -> audioHost
            else -> "地址不可用"
        }

    companion object {
        private fun hostOf(url: String?): String = runCatching { URI(url.orEmpty()).host }.getOrNull().orEmpty()
    }
}

/**
 * Candidate routes for the currently selected quality and codec.
 * The selected route is only a session preference and is never persisted as a signed URL.
 */
data class PlaybackRouteSet(
    val routes: List<PlaybackRoute> = emptyList(),
    val selectedRouteId: String? = null,
) {
    val selectedRoute: PlaybackRoute? get() = routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()
    val hasAlternates: Boolean get() = routes.size > 1

    fun select(routeId: String): PlaybackRouteSet =
        if (routes.any { it.id == routeId }) copy(selectedRouteId = routeId) else this

    fun routeForUrl(url: String?): PlaybackRoute? =
        url?.takeIf { it.isNotBlank() }?.let { candidate ->
            routes.firstOrNull { it.videoUrl == candidate || it.audioUrl == candidate }
        }

    /**
     * Returns the selected URL first, then the other candidates for the same media type.
     * Media3 asks for a new DataSource for every DASH segment, so this also covers segment-level failures.
     */
    fun urlsFor(initialUrl: String): List<String> {
        val mediaIsVideo = routes.any { it.videoUrl == initialUrl }
        val selected = selectedRoute
        val ordered = buildList {
            selected?.let { add(it) }
            routes.forEach { if (it.id != selected?.id) add(it) }
        }
        return ordered.mapNotNull { route ->
            if (mediaIsVideo) route.videoUrl else route.audioUrl
        }.filter(String::isNotBlank).distinct()
    }

    fun routeIndex(url: String): Int = urlsFor(url).indexOf(url).coerceAtLeast(0)

    companion object {
        fun fromUrls(videoUrls: List<String>, audioUrls: List<String> = emptyList()): PlaybackRouteSet {
            val count = maxOf(videoUrls.size, audioUrls.size)
            if (count == 0) return PlaybackRouteSet()
            val routes = (0 until count).mapNotNull { index ->
                val video = videoUrls.getOrNull(index) ?: videoUrls.firstOrNull()
                val audio = audioUrls.getOrNull(index) ?: audioUrls.firstOrNull()
                if (video == null && audio == null) return@mapNotNull null
                PlaybackRoute(
                    id = "route-$index",
                    displayName = if (index == 0) "主线路" else "备用线路 $index",
                    videoUrl = video,
                    audioUrl = audio,
                    primary = index == 0,
                    priority = index,
                )
            }
            return PlaybackRouteSet(routes, routes.firstOrNull()?.id)
        }
    }
}
