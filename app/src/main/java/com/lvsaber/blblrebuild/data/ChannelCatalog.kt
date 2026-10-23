package com.lvsaber.blblrebuild.data

data class BrowseRail(
    val key: String,
    val title: String,
    val regionId: Int? = null,
    val keyword: String? = null,
    val searchTid: Int? = null,
)

data class ChannelDefinition(
    val key: String,
    val title: String,
    val symbol: String,
    val regionId: Int,
    val rails: List<BrowseRail>,
)

/** Only channels and topics verified against live responses are enabled. */
object ChannelCatalog {
    private fun channel(key: String, title: String, symbol: String, region: Int, tid: Int, vararg topics: String) =
        ChannelDefinition(key, title, symbol, region, listOf(BrowseRail("recommend", "${title}推荐", region)) +
            topics.mapIndexed { index, keyword -> BrowseRail("topic$index", "$keyword · 主题推荐", keyword = keyword, searchTid = tid) })

    val channels = listOf(
        channel("animation", "动画", "▶", 1005, 1, "同人动画", "动漫剪辑", "动漫评论"),
        channel("game", "游戏", "✚", 1008, 4, "单机游戏", "手机游戏", "电子竞技"),
        channel("knowledge", "知识", "◇", 1010, 36, "科学科普", "人文历史", "财经"),
        channel("digital", "科技数码", "▣", 1012, 188, "手机评测", "电脑", "摄影"),
        channel("music", "音乐", "♫", 1003, 3, "翻唱", "演奏", "音乐现场"),
        channel("food", "美食", "♨", 1020, 211, "家常菜", "探店", "烘焙"),
        channel("dance", "舞蹈", "♪", 1004, 129),
        channel("life", "生活兴趣", "☆", 1030, 160),
    )
    val keys = channels.map { it.key }
    val defaultPinned = keys.take(6)
    fun find(key: String?) = channels.firstOrNull { it.key == key }

    fun normalize(order: List<String>, known: List<String>): List<String> =
        order.filter { it in known }.distinct() + known.filterNot { it in order }

    fun homeRails(settings: AppSettings): List<BrowseRail> = HomeRailCatalog.normalizeOrder(settings.railOrder)
        .filterNot { it in settings.hiddenRailKeys }
        .mapNotNull { key ->
            if (key == HomeRailCatalog.FEATURED_KEY) BrowseRail(key, "热门精选")
            else find(key)?.let { BrowseRail(key, "${it.title}推荐", it.regionId) }
        }

    fun rails(channel: ChannelDefinition, settings: AppSettings): List<BrowseRail> {
        val order = normalize(settings.channelRailOrders[channel.key].orEmpty(), channel.rails.map { it.key })
        return order.mapNotNull { key -> channel.rails.find { it.key == key } }
            .filterNot { "${channel.key}/${it.key}" in settings.hiddenChannelRails }
    }
}
