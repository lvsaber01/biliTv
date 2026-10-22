package com.lvsaber.blblrebuild.data

import android.content.Context

/** User preferences shared by the home screen, settings screen and player. */
data class AppSettings(
    val homeStyle: HomeStyle = HomeStyle.RAILS,
    val gridColumns: Int = 2,
    val defaultQuality: Int = 80,
    val videoFormat: VideoFormat = VideoFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.AUTO,
    val danmakuEnabled: Boolean = true,
) {
    enum class HomeStyle(val storage: String, val label: String) {
        RAILS("rails", "TV 横向轨道"),
        GRID("grid", "网格卡片"),
    }

    enum class VideoFormat(val storage: String, val label: String) {
        MP4("mp4", "MP4 兼容模式"),
        DASH("dash", "DASH 分离音视频"),
    }

    enum class VideoCodec(val storage: String, val label: String) {
        AUTO("auto", "自动选择"),
        AVC("avc", "AVC / H.264"),
        HEVC("hevc", "HEVC / H.265"),
        AV1("av1", "AV1"),
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOME_STYLE, homeStyle.storage)
            .putInt(KEY_COLUMNS, gridColumns.coerceIn(1, 3))
            .putInt(KEY_QUALITY, defaultQuality)
            .putString(KEY_FORMAT, videoFormat.storage)
            .putString(KEY_CODEC, videoCodec.storage)
            .putBoolean(KEY_DANMAKU, danmakuEnabled)
            .apply()
    }

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_HOME_STYLE = "home_style"
        private const val KEY_COLUMNS = "grid_columns"
        private const val KEY_QUALITY = "default_quality"
        private const val KEY_FORMAT = "video_format"
        private const val KEY_CODEC = "video_codec"
        private const val KEY_DANMAKU = "danmaku_enabled"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                homeStyle = HomeStyle.entries.firstOrNull { it.storage == prefs.getString(KEY_HOME_STYLE, HomeStyle.RAILS.storage) } ?: HomeStyle.RAILS,
                gridColumns = prefs.getInt(KEY_COLUMNS, 2).coerceIn(1, 3),
                defaultQuality = prefs.getInt(KEY_QUALITY, 80),
                videoFormat = VideoFormat.entries.firstOrNull { it.storage == prefs.getString(KEY_FORMAT, VideoFormat.MP4.storage) } ?: VideoFormat.MP4,
                videoCodec = VideoCodec.entries.firstOrNull { it.storage == prefs.getString(KEY_CODEC, VideoCodec.AUTO.storage) } ?: VideoCodec.AUTO,
                danmakuEnabled = prefs.getBoolean(KEY_DANMAKU, true),
            )
        }
    }
}
