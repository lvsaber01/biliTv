package com.lvsaber.blblrebuild.data

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
)

data class VideoPage(
    val page: Int,
    val cid: Long,
    val title: String,
    val durationSeconds: Int,
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
)

data class PlaySource(
    val url: String,
    val quality: String,
    val mimeType: String,
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val isDash: Boolean = false,
    val availableAudio: List<AudioTrack> = emptyList(),
)

data class VideoTrack(
    val id: Int,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Int,
    val url: String,
    val backupUrls: List<String> = emptyList(),
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
