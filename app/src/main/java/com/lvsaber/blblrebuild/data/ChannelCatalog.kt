package com.lvsaber.blblrebuild.data

/** The endpoint is selected by type; IDs for feed, ranking and legacy search never mix. */
enum class BrowseSource {
    POPULAR,
    ALL_RANKING,
    REGION_RECOMMEND,
    REGION_RANKING,
    KEYWORD_SEARCH,
}

data class BrowseRail(
    val key: String,
    val title: String,
    val source: BrowseSource = BrowseSource.POPULAR,
    val regionFeedId: Int? = null,
    val rankingRid: Int? = null,
    val keyword: String? = null,
    val searchTid: Int? = null,
    val rankingRefererSlug: String? = null,
)

data class ChannelDefinition(
    val key: String,
    val title: String,
    val symbol: String,
    val regionFeedId: Int,
    val rankingRid: Int?,
    val defaultLegacySearchTid: Int?,
    val rankingRefererSlug: String? = null,
    val rails: List<BrowseRail>,
)

private data class ChannelTopic(val key: String, val title: String, val legacyTid: Int? = null)

/** Only public v2 top-level channels are listed here; 1028 is unpublished and intentionally omitted. */
object ChannelCatalog {
    /** Frozen keys from the shipped eight-channel catalog; used only by one-time preference migration. */
    val legacyChannelKeys = listOf("animation", "game", "knowledge", "digital", "music", "food", "dance", "life")
    val legacyHomeKeys = listOf("animation", "music", "dance", "game", "knowledge", "life", "food", "digital")

    private fun channel(
        key: String,
        title: String,
        symbol: String,
        regionId: Int,
        legacyTid: Int?,
        topics: List<ChannelTopic>,
        rankingRid: Int? = regionId,
        rankingRefererSlug: String? = null,
    ): ChannelDefinition {
        val rails = buildList {
            add(BrowseRail("recommend", "${title}推荐", BrowseSource.REGION_RECOMMEND, regionFeedId = regionId))
            if (rankingRid != null) add(
                BrowseRail(
                    "ranking", "近期排行", BrowseSource.REGION_RANKING,
                    rankingRid = rankingRid,
                    rankingRefererSlug = rankingRefererSlug,
                ),
            )
            topics.forEach { topic ->
                add(
                    BrowseRail(
                        topic.key,
                        "${topic.title} · 主题推荐",
                        BrowseSource.KEYWORD_SEARCH,
                        keyword = topic.title,
                        searchTid = topic.legacyTid ?: legacyTid,
                    ),
                )
            }
        }
        return ChannelDefinition(key, title, symbol, regionId, rankingRid, legacyTid, rankingRefererSlug, rails)
    }

    private fun oldTopics(vararg titles: String) = titles.mapIndexed { index, title ->
        ChannelTopic("topic$index", title)
    }

    val channels = listOf(
        // Keep the original eight keys and existing topic0..topic2 meanings stable.
        channel("animation", "动画", "▶", 1005, 1, oldTopics("同人动画", "动漫剪辑", "动漫评论") + listOf(
            ChannelTopic("mad_amv", "MAD·AMV"), ChannelTopic("anime_goods", "模玩周边"),
        )),
        channel("game", "游戏", "✚", 1008, 4, oldTopics("单机游戏", "手机游戏", "电子竞技") + listOf(
            ChannelTopic("online_games", "网络游戏"), ChannelTopic("game_guides", "攻略/试玩"),
        )),
        channel("knowledge", "知识", "◇", 1010, 36, oldTopics("科学科普", "人文历史", "财经") + listOf(
            ChannelTopic("social_science", "社科/法律/心理"), ChannelTopic("campus_learning", "校园学习"),
        )),
        channel("digital", "科技数码", "▣", 1012, 188, oldTopics("手机评测", "电脑", "摄影") + listOf(
            ChannelTopic("software_apps", "软件应用"), ChannelTopic("geek_diy", "极客 DIY"),
        )),
        channel("music", "音乐", "♫", 1003, 3, oldTopics("翻唱", "演奏", "音乐现场") + listOf(
            ChannelTopic("original_music", "原创音乐"), ChannelTopic("vocaloid", "VOCALOID"),
        )),
        channel("food", "美食", "♨", 1020, 211, oldTopics("家常菜", "探店", "烘焙") + listOf(
            ChannelTopic("food_vlog", "美食记录"), ChannelTopic("food_review", "美食测评"),
        )),
        channel("dance", "舞蹈", "♪", 1004, 129, listOf(
            ChannelTopic("otaku_dance", "宅舞"), ChannelTopic("street_dance", "街舞"),
            ChannelTopic("chinese_dance", "国风舞蹈"), ChannelTopic("dance_tutorial", "舞蹈教学/翻跳"),
        )),
        channel("life", "生活兴趣", "☆", 1030, 160, listOf(
            ChannelTopic("handmade", "手工"), ChannelTopic("life_skills", "生活技能/野生技能"),
            ChannelTopic("collectibles", "模玩潮玩"), ChannelTopic("leisure", "休闲玩乐"),
            ChannelTopic("offline_events", "线下演出"),
        )),

        channel("film", "影视", "▣", 1001, 181, listOf(
            ChannelTopic("film_editing", "影视剪辑"), ChannelTopic("film_chat", "影视杂谈"), ChannelTopic("short_films", "短剧/短片"),
        )),
        channel("entertainment", "娱乐", "✦", 1002, 5, listOf(
            ChannelTopic("entertainment_chat", "娱乐杂谈"), ChannelTopic("celebrities", "明星综合"),
            ChannelTopic("variety_shows", "综艺"), ChannelTopic("fan_stages", "粉丝创作/舞台"),
        )),
        channel("painting", "绘画", "✎", 1006, 162, listOf(
            ChannelTopic("anime_art", "二次元绘画"), ChannelTopic("general_art", "非二次元绘画"),
            ChannelTopic("art_tutorial", "绘画教程"), ChannelTopic("art_process", "绘画过程"),
        )),
        channel("kuso", "鬼畜", "♬", 1007, 119, listOf(
            ChannelTopic("kuso_theater", "鬼畜剧场"), ChannelTopic("kuso_remix", "鬼畜调教"),
            ChannelTopic("音mad", "音 MAD"), ChannelTopic("human_vocaloid", "人力 VOCALOID"),
        )),
        channel("news", "资讯", "▤", 1009, 202, listOf(
            ChannelTopic("social_news", "社会资讯"), ChannelTopic("current_affairs", "时政资讯"),
            ChannelTopic("world_news", "国际观察"),
        ), rankingRid = null),
        channel("artificial_intelligence", "人工智能", "✧", 1011, null, listOf(
            ChannelTopic("ai_tutorials", "AI 学习教程"), ChannelTopic("ai_tools", "AI 工具应用"),
            ChannelTopic("ai_news_creation", "AI 资讯/创作"),
        )),
        channel("automotive", "汽车", "◈", 1013, 223, listOf(
            ChannelTopic("car_life", "汽车生活"), ChannelTopic("new_energy", "新能源车"),
            ChannelTopic("car_buying", "购车攻略"), ChannelTopic("motorsport", "赛车"),
            ChannelTopic("car_knowledge", "汽车知识"),
        )),
        channel("fashion", "时尚美妆", "❋", 1014, 155, listOf(
            ChannelTopic("beauty_skincare", "美妆护肤"), ChannelTopic("outfits", "穿搭"),
            ChannelTopic("fashion_trends", "时尚潮流"), ChannelTopic("cosplay_makeup", "仿妆 COS"),
        )),
        channel("home_living", "家装房产", "⌂", 1015, 239, listOf(
            ChannelTopic("home_renovation", "家庭装修"), ChannelTopic("interiors", "家居展示"),
            ChannelTopic("home_appliances", "家电"), ChannelTopic("housing", "买房租房"),
        )),
        channel("outdoors", "户外潮流", "△", 1016, null, listOf(
            ChannelTopic("camping", "露营"), ChannelTopic("hiking", "徒步"),
            ChannelTopic("outdoor_exploration", "户外探秘"), ChannelTopic("outdoor_gear", "户外装备/路线"),
        )),
        channel("fitness", "健身", "✚", 1017, 164, listOf(
            ChannelTopic("fitness_follow", "健身跟练"), ChannelTopic("strength_training", "力量训练"),
            ChannelTopic("weight_loss", "减脂塑形"), ChannelTopic("fitness_science", "健身科普"),
            ChannelTopic("yoga_stretch", "瑜伽拉伸"),
        )),
        channel("sports", "体育运动", "⚽", 1018, 234, listOf(
            ChannelTopic("football", "足球"), ChannelTopic("basketball", "篮球"),
            ChannelTopic("sports_events", "竞技赛事"), ChannelTopic("running", "跑步"),
            ChannelTopic("sports_general", "运动综合"),
        )),
        channel("handcraft", "手工", "✿", 1019, 161, listOf(
            ChannelTopic("traditional_crafts", "传统手工艺"), ChannelTopic("light_crafts", "轻手作"),
            ChannelTopic("journaling", "文具手帐"), ChannelTopic("toy_diy", "DIY 玩具"),
            ChannelTopic("relaxing_crafts", "解压手工"),
        )),
        channel("skit", "小剧场", "▣", 1021, 85, listOf(
            ChannelTopic("comedy_skits", "搞笑剧情"), ChannelTopic("drama_skits", "剧情演绎"),
            ChannelTopic("short_movies", "短片"), ChannelTopic("street_interviews", "语言类小剧场/街采"),
        )),
        channel("travel", "旅游出行", "⌖", 1022, 250, listOf(
            ChannelTopic("travel_guides", "旅游攻略"), ChannelTopic("travel_diaries", "旅行记录"),
            ChannelTopic("city_travel", "城市出行"), ChannelTopic("public_transit", "公共交通"),
        )),
        channel("agriculture", "三农", "♧", 1023, 251, listOf(
            ChannelTopic("rural_life", "农村生活"), ChannelTopic("farming", "种植养殖"),
            ChannelTopic("fishing", "赶海捕鱼"), ChannelTopic("farm_food", "田园美食"),
            ChannelTopic("agriculture_tech", "农业技术"),
        )),
        channel("animals", "动物", "♧", 1024, 217, listOf(
            ChannelTopic("cats", "猫"), ChannelTopic("dogs", "狗"),
            ChannelTopic("small_pets", "小宠异宠"), ChannelTopic("wildlife", "野生动物科普"),
            ChannelTopic("animal_fanworks", "动物二创"),
        )),
        channel("parenting", "亲子", "♡", 1025, 254, listOf(
            ChannelTopic("family_play", "亲子互动"), ChannelTopic("kids", "萌娃"),
            ChannelTopic("parenting_education", "亲子教育"), ChannelTopic("pregnancy_baby", "孕婴护理"),
            ChannelTopic("kids_talent", "儿童才艺"),
        )),
        channel("health", "健康", "＋", 1026, null, listOf(
            ChannelTopic("health_science", "健康科普"), ChannelTopic("wellness", "养生"),
            ChannelTopic("mental_health", "心理健康"), ChannelTopic("sports_health", "运动健康"),
            ChannelTopic("sleep_asmr", "助眠/ASMR"),
        )),
        channel("emotion", "情感", "♡", 1027, null, listOf(
            ChannelTopic("family_relationships", "家庭关系"), ChannelTopic("romance", "恋爱关系"),
            ChannelTopic("interpersonal", "人际关系"), ChannelTopic("self_growth", "自我成长"),
            ChannelTopic("situational_drama", "情景短剧"),
        )),
        channel("vlog", "vlog", "◉", 1029, null, listOf(
            ChannelTopic("daily_vlog", "日常 vlog"), ChannelTopic("campus_vlog", "校园 vlog"),
            ChannelTopic("food_vlog_topic", "美食 vlog"), ChannelTopic("travel_vlog", "旅行 vlog"),
            ChannelTopic("career_vlog", "职业 vlog"),
        )),
        channel("life_tips", "生活经验", "☆", 1031, 160, listOf(
            ChannelTopic("life_skills_tips", "生活技能"), ChannelTopic("service_guides", "办事流程"),
            ChannelTopic("home_life", "家居生活"), ChannelTopic("life_experience", "生活经验"),
            ChannelTopic("wedding", "婚嫁"),
        )),
    )

    val keys = channels.map { it.key }
    val defaultPinned = listOf("animation", "game", "knowledge", "digital", "music", "food")
    const val MAX_PINNED = 8

    fun find(key: String?) = channels.firstOrNull { it.key == key }

    fun normalize(order: List<String>, known: List<String>): List<String> =
        order.filter { it in known }.distinct() + known.filterNot { it in order }

    /** Add new channel rails without changing any existing user's explicit order. */
    fun migratedRailOrder(channel: ChannelDefinition, oldOrder: List<String>): List<String> {
        val normalized = normalize(oldOrder, channel.rails.map { it.key }).toMutableList()
        val rankingIndex = normalized.indexOf("ranking")
        val recommendationIndex = normalized.indexOf("recommend")
        if (rankingIndex >= 0 && recommendationIndex >= 0 && rankingIndex != recommendationIndex + 1) {
            normalized.removeAt(rankingIndex)
            normalized.add((recommendationIndex + 1).coerceAtMost(normalized.size), "ranking")
        }
        return normalized
    }

    fun homeRails(settings: AppSettings): List<BrowseRail> = HomeRailCatalog.normalizeOrder(settings.railOrder)
        .filterNot { it in settings.hiddenRailKeys }
        .mapNotNull { key -> HomeRailCatalog.find(key)?.toBrowseRail() }

    fun rails(channel: ChannelDefinition, settings: AppSettings): List<BrowseRail> {
        val order = normalize(settings.channelRailOrders[channel.key].orEmpty(), channel.rails.map { it.key })
        return order.mapNotNull { key -> channel.rails.find { it.key == key } }
            .filterNot { "${channel.key}/${it.key}" in settings.hiddenChannelRails }
    }
}
