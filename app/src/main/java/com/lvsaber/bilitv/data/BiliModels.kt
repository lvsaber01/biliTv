package com.lvsaber.bilitv.data

data class VideoSummary(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val coverUrl: String,
    val author: String,
    val duration: String,
    val description: String,
    val views: Long,
    val likes: Long,
    val danmaku: Long,
    val authorMid: Long = 0L,
    val publishTimeSeconds: Long? = null,
)

data class VideoPage(
    val page: Int,
    val cid: Long,
    val title: String,
    val durationSeconds: Int,
)

data class BrowseFetchResult(
    val videos: List<VideoSummary>,
    /** True means this source has no more pages in the current result set. */
    val exhausted: Boolean,
)

data class VideoDetail(
    val summary: VideoSummary,
    val pages: List<VideoPage>,
    val description: String,
)

data class FavoriteFolder(
    val id: Long,
    val title: String,
    val count: Int,
    val selected: Boolean = false,
)

data class UserProfile(
    val mid: Long,
    val name: String,
    val avatarUrl: String,
    val signature: String,
    val verification: String = "",
    val level: Int = 0,
    val followerCount: Long = 0L,
    val followingCount: Long = 0L,
    val videoCount: Long = 0L,
    val isFollowing: Boolean = false,
    val liveStatus: Boolean = false,
)

data class UserVideoPage(
    val videos: List<VideoSummary>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
) {
    val hasMore: Boolean get() = videos.isNotEmpty() && page * pageSize < total
}

data class SubtitleTrack(
    val id: Long,
    val language: String,
    val label: String,
    val url: String,
)

data class SubtitleCue(
    val fromMs: Long,
    val toMs: Long,
    val content: String,
)

data class VideoChapter(
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

data class PlayerInfo(
    val subtitles: List<SubtitleTrack> = emptyList(),
    val chapters: List<VideoChapter> = emptyList(),
)

data class PlaySource(
    val url: String,
    val quality: String,
    val mimeType: String,
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val isDash: Boolean = false,
    val availableAudio: List<AudioTrack> = emptyList(),
    val availableQualities: List<PlaybackQuality> = emptyList(),
    val routeSet: PlaybackRouteSet = PlaybackRouteSet(),
)

/**
 * A quality returned by Bilibili for this video and account.
 * `deviceSupported` is calculated locally and never overrides server-side
 * login/VIP restrictions represented by [needsLogin] and [needsVip].
 */
data class PlaybackQuality(
    val id: Int,
    val label: String,
    val width: Int = 0,
    val height: Int = 0,
    val codecs: List<String> = emptyList(),
    val needsLogin: Boolean = false,
    val needsVip: Boolean = false,
    val isHdr: Boolean = false,
    val isDolbyVision: Boolean = false,
    val deviceSupported: Boolean = true,
    val deviceReason: String? = null,
)

data class VideoTrack(
    val id: Int,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Int,
    val url: String,
    val backupUrls: List<String> = emptyList(),
    val frameRate: Float = 0f,
    val mimeType: String = "video/mp4",
    val isHdr: Boolean = false,
    val isDolbyVision: Boolean = false,
    val deviceSupported: Boolean = true,
    val deviceReason: String? = null,
)

data class AudioTrack(
    val id: Int,
    val codecs: String,
    val bandwidth: Int,
    val url: String,
    val backupUrls: List<String> = emptyList(),
)

data class QrLoginTicket(
    val url: String,
    val key: String,
)

data class QrLoginStatus(
    val code: Int,
    val message: String,
    val loggedIn: Boolean,
)

class BiliApiException(
    val apiCode: Int,
    override val message: String,
    val httpCode: Int? = null,
) : Exception(message)
