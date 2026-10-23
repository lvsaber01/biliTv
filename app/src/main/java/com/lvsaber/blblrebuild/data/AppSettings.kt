package com.lvsaber.blblrebuild.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    /** 0 means automatic highest compatible quality. */
    val defaultQuality: Int = 0,
    val videoFormat: VideoFormat = VideoFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.AUTO,
    val videoResizeMode: VideoResizeMode = VideoResizeMode.FIT,
    val subtitleTextScale: Float = 1f,
    val experimentalQualityEnabled: Boolean = false,
    val danmakuEnabled: Boolean = true,
    val danmaku: DanmakuSettings = DanmakuSettings(),
    val railOrder: List<String> = HomeRailCatalog.defaultKeys,
    val hiddenRailKeys: Set<String> = emptySet(),
    /** Prefetch near the end of a rail; pressing right on the last card always remains explicit. */
    val railPrefetchEnabled: Boolean = true,
    val heroEnabled: Boolean = true,
    val channelOrder: List<String> = ChannelCatalog.keys,
    val hiddenTopChannels: Set<String> = (ChannelCatalog.keys - ChannelCatalog.defaultPinned.toSet()).toSet(),
    val channelRailOrders: Map<String, List<String>> = emptyMap(),
    val hiddenChannelRails: Set<String> = emptySet(),
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

    enum class VideoResizeMode(val storage: String, val label: String) {
        FIT("fit", "适应画面"),
        FILL("fill", "铺满画面"),
        ZOOM("zoom", "裁剪铺满"),
    }

    fun save(context: Context) {
        val display = danmaku.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOME_STYLE, homeStyle.storage)
            .putInt(KEY_COLUMNS, gridColumns.coerceIn(1, 3))
            .putInt(KEY_QUALITY, defaultQuality)
            .putString(KEY_FORMAT, videoFormat.storage)
            .putString(KEY_CODEC, videoCodec.storage)
            .putString(KEY_RESIZE_MODE, videoResizeMode.storage)
            .putFloat(KEY_SUBTITLE_SCALE, subtitleTextScale.coerceIn(0.8f, 1.4f))
            .putBoolean(KEY_EXPERIMENTAL_QUALITY, experimentalQualityEnabled)
            .putBoolean(KEY_DANMAKU, danmakuEnabled)
            .putInt("danmaku_area", display.areaPercent)
            .putFloat("danmaku_speed", display.speed)
            .putFloat("danmaku_text_scale", display.textScale)
            .putInt("danmaku_opacity", display.opacityPercent)
            .putInt("danmaku_density", display.maxVisible)
            .putString(KEY_RAIL_ORDER, HomeRailCatalog.normalizeOrder(railOrder).joinToString(","))
            .putStringSet(KEY_HIDDEN_RAILS, hiddenRailKeys.intersect(HomeRailCatalog.defaultKeys.toSet()))
            .putBoolean(KEY_RAIL_PREFETCH, railPrefetchEnabled)
            .putBoolean(KEY_HERO_ENABLED, heroEnabled)
            .putString("channel_order", ChannelCatalog.normalize(channelOrder, ChannelCatalog.keys).joinToString(","))
            .putStringSet("hidden_top_channels", hiddenTopChannels.intersect(ChannelCatalog.keys.toSet()))
            .putString("channel_rail_orders", JSONObject().apply {
                channelRailOrders.forEach { (key, value) -> put(key, JSONArray(value)) }
            }.toString())
            .putStringSet("hidden_channel_rails", hiddenChannelRails)
            .apply()
    }

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_HOME_STYLE = "home_style"
        private const val KEY_COLUMNS = "grid_columns"
        private const val KEY_QUALITY = "default_quality"
        private const val KEY_FORMAT = "video_format"
        private const val KEY_CODEC = "video_codec"
        private const val KEY_RESIZE_MODE = "video_resize_mode"
        private const val KEY_SUBTITLE_SCALE = "subtitle_text_scale"
        private const val KEY_EXPERIMENTAL_QUALITY = "experimental_quality_enabled"
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
                defaultQuality = prefs.getInt(KEY_QUALITY, 0),
                videoFormat = VideoFormat.entries.firstOrNull { it.storage == prefs.getString(KEY_FORMAT, VideoFormat.MP4.storage) } ?: VideoFormat.MP4,
                videoCodec = VideoCodec.entries.firstOrNull { it.storage == prefs.getString(KEY_CODEC, VideoCodec.AUTO.storage) } ?: VideoCodec.AUTO,
                videoResizeMode = VideoResizeMode.entries.firstOrNull { it.storage == prefs.getString(KEY_RESIZE_MODE, VideoResizeMode.FIT.storage) } ?: VideoResizeMode.FIT,
                subtitleTextScale = prefs.getFloat(KEY_SUBTITLE_SCALE, 1f).coerceIn(0.8f, 1.4f),
                experimentalQualityEnabled = prefs.getBoolean(KEY_EXPERIMENTAL_QUALITY, false),
                danmakuEnabled = prefs.getBoolean(KEY_DANMAKU, true),
                danmaku = DanmakuSettings(
                    prefs.getInt("danmaku_area", 50), prefs.getFloat("danmaku_speed", 1f),
                    prefs.getFloat("danmaku_text_scale", 1f), prefs.getInt("danmaku_opacity", 75),
                    prefs.getInt("danmaku_density", 24),
                ).normalized(),
                railOrder = HomeRailCatalog.normalizeOrder(savedOrder),
                hiddenRailKeys = hiddenRails,
                railPrefetchEnabled = prefs.getBoolean(KEY_RAIL_PREFETCH, true),
                heroEnabled = prefs.getBoolean(KEY_HERO_ENABLED, true),
                channelOrder = ChannelCatalog.normalize(prefs.getString("channel_order", "").orEmpty().split(","), ChannelCatalog.keys),
                hiddenTopChannels = prefs.getStringSet("hidden_top_channels", (ChannelCatalog.keys - ChannelCatalog.defaultPinned.toSet()).toSet()).orEmpty().toSet(),
                channelRailOrders = runCatching {
                    val json = JSONObject(prefs.getString("channel_rail_orders", "{}") ?: "{}")
                    ChannelCatalog.channels.associate { channel ->
                        val array = json.optJSONArray(channel.key) ?: JSONArray()
                        channel.key to (0 until array.length()).map { array.optString(it) }
                    }
                }.getOrDefault(emptyMap()),
                hiddenChannelRails = prefs.getStringSet("hidden_channel_rails", emptySet()).orEmpty().toSet(),
            )
        }
    }
}
