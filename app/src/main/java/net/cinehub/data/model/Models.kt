package net.cinehub.data.model

/** 媒体条目。同一部片在不同源里 id 不同，聚合后靠 [key] 归并。 */
data class Media(
    val id: String,
    val title: String,
    val cover: String? = null,
    val year: String? = null,
    val category: String? = null,
    val remark: String? = null,
    val score: String? = null,
    val area: String? = null,
    val actors: String? = null,
    val director: String? = null,
    val intro: String? = null,
    val sourceId: String,
) {
    /**
     * 归并键：标题 + 年份。
     *
     * 同一个片名在各源的写法差别很大（「暗影蜘蛛侠 黑白版」/「暗影蜘蛛侠（黑白版）」/
     * 「暗影蜘蛛侠：黑白版」），所以这里去掉空白与标点再比对，
     * 否则跨源归并会漏掉本该合并的条目。
     */
    val key: String
        get() = buildString {
            append(normalizeTitle(title))
            year?.take(4)?.let { append('|').append(it) }
        }
}

/**
 * 标题归一化：去掉空白与标点。
 *
 * 同一个片名在各源的写法差别很大（「暗影蜘蛛侠 黑白版」/「暗影蜘蛛侠（黑白版）」/
 * 「暗影蜘蛛侠：黑白版」），跨源比对、以及拿豆瓣的片名去源里搜，都得先走这一步。
 */
fun normalizeTitle(s: String): String = s.filterNot { it.isWhitespace() || it in TITLE_PUNCT }

/** 归并时忽略的标点：全半角括号、冒号、破折号、顿号等。 */
private val TITLE_PUNCT = setOf(
    '(', ')', '（', '）', '[', ']', '【', '】', ':', '：', '·', '.', '。',
    '-', '_', '—', '～', '~', '、', '，', ',', '!', '！', '?', '？', '"', '\'',
)

/** 一个可播放的剧集 / 分集。 */
data class Episode(
    val name: String,
    val index: Int,
    val playUrl: String? = null,
)

/** 一个源内的一条线路（同一部片的多套地址）。 */
data class PlayLine(
    val name: String,
    val episodes: List<Episode>,
)

/** 详情：媒体 + 各线路。 */
data class MediaDetail(
    val media: Media,
    val lines: List<PlayLine>,
    val intro: String? = null,
) {
    val episodeCount: Int get() = lines.maxOfOrNull { it.episodes.size } ?: 0
}

/** 源健康状态。 */
enum class Health { Ok, Slow, Down, Unknown }

data class HealthReport(
    val health: Health,
    val latencyMs: Long? = null,
    val detail: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** 跨源聚合后的一条搜索结果：一部片 + 它在各源的可播情况。 */
data class AggregatedMedia(
    val media: Media,
    val availability: List<SourceAvailability>,
) {
    val playableCount: Int get() = availability.count { it.health == Health.Ok || it.health == Health.Slow }
}

data class SourceAvailability(
    val sourceId: String,
    val sourceName: String,
    val item: Media,
    val health: Health,
    val quality: String? = null,
    val latencyMs: Long? = null,
    val note: String? = null,
)

/**
 * 一次跨源搜索的成果：归并好的条目，加上**没查动的源和它的原因**。
 *
 * 只返回 List 的话，「所有源都超时了」和「搜到了但一片都没有」在界面上长一样，
 * 用户只能猜。失败原因留到界面这一层才说得出「少了哪几个源」。
 */
data class SearchOutcome(
    val items: List<AggregatedMedia>,
    val failures: List<Failure> = emptyList(),
) {
    data class Failure(val sourceName: String, val reason: String)

    val isEmpty: Boolean get() = items.isEmpty()
}
