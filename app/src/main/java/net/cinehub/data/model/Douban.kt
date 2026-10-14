package net.cinehub.data.model

import kotlinx.serialization.Serializable

/**
 * 豆瓣榜单的一条。
 *
 * 豆瓣给的就这些：片名、评分、封面、它自己的条目页 —— **不给播放地址**。
 * 所以这一条点下去之后必须由影枢拿片名去自己接的源里搜，搜得到才有得播。
 */
@Serializable
data class DoubanItem(
    val id: String,
    val title: String,
    val url: String,
    val cover: String? = null,
    val rate: String? = null,
    val kinds: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    /** 豆瓣标的年份。同名不同片（翻拍、续集、同名综艺）就靠它分辨。 */
    val year: String? = null,
    /** Top250 才有名次；标签榜是 0。 */
    val rank: Int = 0,
) {
    /** 类型 · 地区，够短才显示得下。 */
    val whereFrom: String
        get() = listOf(
            kinds.take(2).joinToString("/"),
            regions.take(2).joinToString("/"),
        ).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * 一个豆瓣榜单。
 *
 * 这张表是手写的，因为豆瓣那个能列标签的接口（`j/tag_names`）已经 404。
 * 每一行都在 2026-10-14 实测过能回数据；实测只有 1~6 条的（科幻/悬疑/喜剧/
 * 爱情/恐怖）和直接回空的（剧集的新剧/高分/经典/港台剧/海外剧/英美剧）都没收进来 ——
 * 一屏两部的榜单只会让人以为影枢坏了。豆瓣哪天把这些榜收了，这里就会如实显示拉不到。
 */
data class DoubanChart(
    val kind: String,
    val name: String,
    val tag: String? = null,
    val sort: String? = null,
    val topList: Boolean = false,
) {
    /** 缓存的键。 */
    val id: String get() = if (topList) "top-$kind" else "$kind/$tag/$sort"

    companion object {
        val MOVIE = listOf(
            DoubanChart("movie", "热门", "热门", "recommend"),
            DoubanChart("movie", "最新", "最新", "time"),
            DoubanChart("movie", "高分", "高分", "rank"),
            DoubanChart("movie", "经典", "经典", "rank"),
            DoubanChart("movie", "Top250", topList = true),
            DoubanChart("movie", "华语", "华语", "recommend"),
            DoubanChart("movie", "欧美", "欧美", "recommend"),
            DoubanChart("movie", "日韩", "日韩", "recommend"),
            DoubanChart("movie", "动画", "动画", "recommend"),
            DoubanChart("movie", "纪录片", "纪录片", "recommend"),
            DoubanChart("movie", "犯罪", "犯罪", "recommend"),
        )
        val TV = listOf(
            DoubanChart("tv", "热门", "热门", "recommend"),
            DoubanChart("tv", "Top250", topList = true),
            DoubanChart("tv", "国产剧", "国产剧", "recommend"),
            DoubanChart("tv", "美剧", "美剧", "recommend"),
            DoubanChart("tv", "日剧", "日剧", "recommend"),
            DoubanChart("tv", "韩剧", "韩剧", "recommend"),
            DoubanChart("tv", "综艺", "综艺", "recommend"),
        )
    }
}
