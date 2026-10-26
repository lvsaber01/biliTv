package com.lvsaber.bilitv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HomeRailDefinition(
    val key: String,
    val title: String,
    val source: BrowseSource,
    val regionFeedId: Int? = null,
    val rankingRid: Int? = null,
) {
    fun toBrowseRail() = BrowseRail(key, title, source, regionFeedId, rankingRid)
}

object HomeRailCatalog {
    const val FEATURED_KEY = "featured"
    const val ALL_RANKING_KEY = "all_ranking"

    val legacyDefaultKeys = listOf(FEATURED_KEY, *ChannelCatalog.legacyHomeKeys.toTypedArray())

    val rails: List<HomeRailDefinition> = buildList {
        add(HomeRailDefinition(FEATURED_KEY, "热门精选", BrowseSource.POPULAR))
        add(HomeRailDefinition(ALL_RANKING_KEY, "全站排行榜", BrowseSource.ALL_RANKING, rankingRid = 0))
        ChannelCatalog.legacyHomeKeys.forEach { key -> add(channelRail(key)) }
        ChannelCatalog.channels.filterNot { it.key in ChannelCatalog.legacyHomeKeys }.forEach { add(channelRail(it.key)) }
    }

    val defaultKeys: List<String> = rails.map { it.key }
    val defaultHiddenKeys: Set<String> = defaultKeys.filterNot {
        it == FEATURED_KEY || it == ALL_RANKING_KEY || it in ChannelCatalog.legacyHomeKeys
    }.toSet()

    fun find(key: String): HomeRailDefinition? = rails.firstOrNull { it.key == key }

    fun normalizeOrder(order: List<String>): List<String> =
        order.filter { key -> rails.any { it.key == key } }.distinct() +
            defaultKeys.filterNot { key -> order.contains(key) }

    private fun channelRail(key: String): HomeRailDefinition {
        val channel = requireNotNull(ChannelCatalog.find(key))
        return HomeRailDefinition(
            key = channel.key,
            title = "${channel.title}推荐",
            source = BrowseSource.REGION_RECOMMEND,
            regionFeedId = channel.regionFeedId,
        )
    }
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
    val autoRouteSwitchEnabled: Boolean = true,
    val routePolicy: RoutePolicy = RoutePolicy.AUTO,
    val danmakuEnabled: Boolean = true,
    val danmaku: DanmakuSettings = DanmakuSettings(),
    val railOrder: List<String> = HomeRailCatalog.defaultKeys,
    val hiddenRailKeys: Set<String> = HomeRailCatalog.defaultHiddenKeys,
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

    enum class RoutePolicy(val storage: String, val label: String) {
        AUTO("auto", "自动选择"),
        PREFER_PRIMARY("prefer_primary", "优先主线路"),
        PRIMARY_ONLY("primary_only", "仅使用主线路"),
    }

    fun save(context: Context) {
        val display = danmaku.normalized()
        val normalizedChannelOrder = ChannelCatalog.normalize(channelOrder, ChannelCatalog.keys)
        val hiddenChannels = hiddenTopChannels.intersect(ChannelCatalog.keys.toSet())
        val overflowPinned = normalizedChannelOrder.filterNot { it in hiddenChannels }.drop(ChannelCatalog.MAX_PINNED).toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOME_STYLE, homeStyle.storage)
            .putInt(KEY_COLUMNS, gridColumns.coerceIn(1, 3))
            .putInt(KEY_QUALITY, defaultQuality)
            .putString(KEY_FORMAT, videoFormat.storage)
            .putString(KEY_CODEC, videoCodec.storage)
            .putString(KEY_RESIZE_MODE, videoResizeMode.storage)
            .putFloat(KEY_SUBTITLE_SCALE, subtitleTextScale.coerceIn(0.8f, 1.4f))
            .putBoolean(KEY_EXPERIMENTAL_QUALITY, experimentalQualityEnabled)
            .putBoolean(KEY_AUTO_ROUTE_SWITCH, autoRouteSwitchEnabled)
            .putString(KEY_ROUTE_POLICY, routePolicy.storage)
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
            .putString("channel_order", normalizedChannelOrder.joinToString(","))
            .putStringSet("hidden_top_channels", hiddenChannels + overflowPinned)
            .putString("channel_rail_orders", JSONObject().apply {
                channelRailOrders.forEach { (key, value) -> put(key, JSONArray(value)) }
            }.toString())
            .putStringSet("hidden_channel_rails", hiddenChannelRails)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
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
        private const val KEY_AUTO_ROUTE_SWITCH = "auto_route_switch_enabled"
        private const val KEY_ROUTE_POLICY = "route_policy"
        private const val KEY_DANMAKU = "danmaku_enabled"
        private const val KEY_RAIL_ORDER = "rail_order"
        private const val KEY_HIDDEN_RAILS = "hidden_rail_keys"
        private const val KEY_RAIL_PREFETCH = "rail_prefetch_enabled"
        private const val KEY_HERO_ENABLED = "hero_enabled"
        private const val KEY_SCHEMA_VERSION = "settings_schema_version"
        private const val CURRENT_SCHEMA_VERSION = 1

        private fun parseOrder(value: String?, fallback: List<String>): List<String> = value
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: fallback

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val migrating = prefs.getInt(KEY_SCHEMA_VERSION, 0) < CURRENT_SCHEMA_VERSION
            val hasOldChannelPreferences = prefs.contains("channel_order") || prefs.contains(KEY_RAIL_ORDER)
            val legacyKeys = ChannelCatalog.legacyChannelKeys
            val legacyKeySet = legacyKeys.toSet()

            val savedChannelOrder = parseOrder(prefs.getString("channel_order", null), legacyKeys)
            val savedTopHidden = prefs.getStringSet(
                "hidden_top_channels",
                (legacyKeys - ChannelCatalog.defaultPinned.toSet()).toSet(),
            ).orEmpty().toSet()
            val channelOrder: List<String>
            val hiddenTopChannels: Set<String>
            if (migrating && hasOldChannelPreferences) {
                val oldOrder = ChannelCatalog.normalize(savedChannelOrder.filter { it in legacyKeySet }, legacyKeys)
                val oldHidden = savedTopHidden.intersect(legacyKeySet)
                val retainedPinned = oldOrder.filterNot { it in oldHidden }.take(ChannelCatalog.MAX_PINNED).toSet()
                channelOrder = ChannelCatalog.normalize(
                    oldOrder + ChannelCatalog.keys.filterNot { it in legacyKeySet },
                    ChannelCatalog.keys,
                )
                hiddenTopChannels = ChannelCatalog.keys.toSet() - retainedPinned
            } else {
                channelOrder = ChannelCatalog.normalize(
                    parseOrder(prefs.getString("channel_order", null), ChannelCatalog.keys),
                    ChannelCatalog.keys,
                )
                val savedHidden = prefs.getStringSet(
                    "hidden_top_channels",
                    (ChannelCatalog.keys - ChannelCatalog.defaultPinned.toSet()).toSet(),
                ).orEmpty().toSet()
                hiddenTopChannels = clampHiddenTop(channelOrder, savedHidden)
            }

            val savedHomeOrder = parseOrder(
                prefs.getString(KEY_RAIL_ORDER, null),
                if (migrating && hasOldChannelPreferences) HomeRailCatalog.legacyDefaultKeys else HomeRailCatalog.defaultKeys,
            )
            val homeOrder = if (migrating && hasOldChannelPreferences) {
                val oldHomeKeys = HomeRailCatalog.legacyDefaultKeys.toSet()
                val preserved = savedHomeOrder.filter { it in oldHomeKeys }.distinct().toMutableList()
                if (HomeRailCatalog.FEATURED_KEY !in preserved) preserved.add(0, HomeRailCatalog.FEATURED_KEY)
                if (HomeRailCatalog.ALL_RANKING_KEY !in preserved) {
                    preserved.add((preserved.indexOf(HomeRailCatalog.FEATURED_KEY) + 1).coerceAtLeast(0), HomeRailCatalog.ALL_RANKING_KEY)
                }
                HomeRailCatalog.normalizeOrder(preserved)
            } else HomeRailCatalog.normalizeOrder(savedHomeOrder)

            val knownHomeKeys = HomeRailCatalog.defaultKeys.toSet()
            val loadedHiddenRails = prefs.getStringSet(KEY_HIDDEN_RAILS, emptySet()).orEmpty()
                .filter { it in knownHomeKeys }.toSet()
            val hiddenRails = if (migrating && hasOldChannelPreferences) {
                (loadedHiddenRails + (ChannelCatalog.keys - ChannelCatalog.legacyHomeKeys.toSet())) - HomeRailCatalog.ALL_RANKING_KEY
            } else if (!hasOldChannelPreferences && migrating) {
                HomeRailCatalog.defaultHiddenKeys
            } else loadedHiddenRails

            val parsedChannelRailOrders = runCatching {
                val json = JSONObject(prefs.getString("channel_rail_orders", "{}") ?: "{}")
                ChannelCatalog.channels.associate { channel ->
                    val array = json.optJSONArray(channel.key) ?: JSONArray()
                    val saved = (0 until array.length()).map { array.optString(it) }
                    channel.key to if (migrating && channel.key in legacyKeySet && saved.isNotEmpty()) {
                        ChannelCatalog.migratedRailOrder(channel, saved)
                    } else saved
                }
            }.getOrDefault(emptyMap())

            val settings = AppSettings(
                homeStyle = HomeStyle.entries.firstOrNull { it.storage == prefs.getString(KEY_HOME_STYLE, HomeStyle.RAILS.storage) } ?: HomeStyle.RAILS,
                gridColumns = prefs.getInt(KEY_COLUMNS, 2).coerceIn(1, 3),
                defaultQuality = prefs.getInt(KEY_QUALITY, 0),
                videoFormat = VideoFormat.entries.firstOrNull { it.storage == prefs.getString(KEY_FORMAT, VideoFormat.MP4.storage) } ?: VideoFormat.MP4,
                videoCodec = VideoCodec.entries.firstOrNull { it.storage == prefs.getString(KEY_CODEC, VideoCodec.AUTO.storage) } ?: VideoCodec.AUTO,
                videoResizeMode = VideoResizeMode.entries.firstOrNull { it.storage == prefs.getString(KEY_RESIZE_MODE, VideoResizeMode.FIT.storage) } ?: VideoResizeMode.FIT,
                subtitleTextScale = prefs.getFloat(KEY_SUBTITLE_SCALE, 1f).coerceIn(0.8f, 1.4f),
                experimentalQualityEnabled = prefs.getBoolean(KEY_EXPERIMENTAL_QUALITY, false),
                autoRouteSwitchEnabled = prefs.getBoolean(KEY_AUTO_ROUTE_SWITCH, true),
                routePolicy = RoutePolicy.entries.firstOrNull { it.storage == prefs.getString(KEY_ROUTE_POLICY, RoutePolicy.AUTO.storage) } ?: RoutePolicy.AUTO,
                danmakuEnabled = prefs.getBoolean(KEY_DANMAKU, true),
                danmaku = DanmakuSettings(
                    prefs.getInt("danmaku_area", 50), prefs.getFloat("danmaku_speed", 1f),
                    prefs.getFloat("danmaku_text_scale", 1f), prefs.getInt("danmaku_opacity", 75),
                    prefs.getInt("danmaku_density", 24),
                ).normalized(),
                railOrder = homeOrder,
                hiddenRailKeys = hiddenRails,
                railPrefetchEnabled = prefs.getBoolean(KEY_RAIL_PREFETCH, true),
                heroEnabled = prefs.getBoolean(KEY_HERO_ENABLED, true),
                channelOrder = channelOrder,
                hiddenTopChannels = hiddenTopChannels,
                channelRailOrders = parsedChannelRailOrders,
                hiddenChannelRails = prefs.getStringSet("hidden_channel_rails", emptySet()).orEmpty().toSet(),
            )
            if (migrating) settings.save(context)
            return settings
        }

        private fun clampHiddenTop(order: List<String>, hidden: Set<String>): Set<String> {
            val pinned = order.filterNot { it in hidden }
            return hidden + pinned.drop(ChannelCatalog.MAX_PINNED)
        }
    }
}
