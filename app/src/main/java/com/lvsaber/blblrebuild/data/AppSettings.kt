package com.lvsaber.blblrebuild.data

import android.content.Context

data class HomeRailDefinition(
    val key: String,
    val title: String,
    val rid: Int?,
)

object HomeRailCatalog {
    const val FEATURED_KEY = "featured"

    val rails = listOf(
        HomeRailDefinition(FEATURED_KEY, "热门精选", null),
        HomeRailDefinition("animation", "动画", 1),
        HomeRailDefinition("music", "音乐", 3),
        HomeRailDefinition("dance", "舞蹈", 129),
        HomeRailDefinition("game", "游戏", 4),
        HomeRailDefinition("knowledge", "知识", 36),
        HomeRailDefinition("life", "生活", 160),
        HomeRailDefinition("food", "美食", 211),
        HomeRailDefinition("digital", "科技数码", 188),
    )

    val defaultKeys: List<String> = rails.map { it.key }

    fun normalizeOrder(order: List<String>): List<String> =
        order.filter { key -> rails.any { it.key == key } }.distinct() +
            defaultKeys.filterNot { key -> order.contains(key) }
}

/** User preferences shared by the home screen, settings screen and player. */
data class AppSettings(
    val homeStyle: HomeStyle = HomeStyle.RAILS,
    val gridColumns: Int = 2,
    val defaultQuality: Int = 80,
    val videoFormat: VideoFormat = VideoFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.AUTO,
    val danmakuEnabled: Boolean = true,
    val railOrder: List<String> = HomeRailCatalog.defaultKeys,
    val hiddenRailKeys: Set<String> = emptySet(),
    /** Prefetch near the end of a rail; pressing right on the last card always remains explicit. */
    val railPrefetchEnabled: Boolean = true,
    val heroEnabled: Boolean = true,
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
            .putString(KEY_RAIL_ORDER, HomeRailCatalog.normalizeOrder(railOrder).joinToString(","))
            .putStringSet(KEY_HIDDEN_RAILS, hiddenRailKeys.intersect(HomeRailCatalog.defaultKeys.toSet()))
            .putBoolean(KEY_RAIL_PREFETCH, railPrefetchEnabled)
            .putBoolean(KEY_HERO_ENABLED, heroEnabled)
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
        private const val KEY_RAIL_ORDER = "rail_order"
        private const val KEY_HIDDEN_RAILS = "hidden_rail_keys"
        private const val KEY_RAIL_PREFETCH = "rail_prefetch_enabled"
        private const val KEY_HERO_ENABLED = "hero_enabled"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedOrder = prefs.getString(KEY_RAIL_ORDER, null)
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: HomeRailCatalog.defaultKeys
            val knownKeys = HomeRailCatalog.defaultKeys.toSet()
            val hiddenRails = prefs.getStringSet(KEY_HIDDEN_RAILS, emptySet())
                ?.filter { it in knownKeys }
                ?.toSet()
                ?: emptySet()
            return AppSettings(
                homeStyle = HomeStyle.entries.firstOrNull { it.storage == prefs.getString(KEY_HOME_STYLE, HomeStyle.RAILS.storage) } ?: HomeStyle.RAILS,
                gridColumns = prefs.getInt(KEY_COLUMNS, 2).coerceIn(1, 3),
                defaultQuality = prefs.getInt(KEY_QUALITY, 80),
                videoFormat = VideoFormat.entries.firstOrNull { it.storage == prefs.getString(KEY_FORMAT, VideoFormat.MP4.storage) } ?: VideoFormat.MP4,
                videoCodec = VideoCodec.entries.firstOrNull { it.storage == prefs.getString(KEY_CODEC, VideoCodec.AUTO.storage) } ?: VideoCodec.AUTO,
                danmakuEnabled = prefs.getBoolean(KEY_DANMAKU, true),
                railOrder = HomeRailCatalog.normalizeOrder(savedOrder),
                hiddenRailKeys = hiddenRails,
                railPrefetchEnabled = prefs.getBoolean(KEY_RAIL_PREFETCH, true),
                heroEnabled = prefs.getBoolean(KEY_HERO_ENABLED, true),
            )
        }
    }
}
