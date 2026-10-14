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

/**
 * 「第几季/第几部」的中文数字折成阿拉伯数字之后的片名 —— 只用于比对。
 *
 * 同一部剧，懂片帝写「绝望写手 第5季」、4kku 写「绝望写手第五季」，[normalizeTitle]
 * 之后仍然一个 5 一个五，谁也不认谁 —— 搜索页挂着两个源、详情「播放源」只剩一个。
 *
 * 刻意**不**并进 [Media.key]：键是收藏 / 观看记录 / 下载任务的落盘凭据，改了写法
 * 老记录就对不上号。不同季折出来仍是不同数字（第五→5、第六→6），不会把续集认成前作。
 */
fun titleFingerprint(s: String): String =
    SEASON_CN.replace(normalizeTitle(s)) { m ->
        "第${cnNumber(m.groupValues[1]) ?: m.groupValues[1]}${m.groupValues[2]}"
    }

/**
 * 去掉「第几季/第几部」尾巴，其余原样留着 —— 只当兜底搜索词用（见 [titleSearchVariants]）。
 *
 * 刻意不顺手去标点：这些站点的 LIKE 恰恰匹配不了被削过标点的写法（见 siblingsOf 的说明），
 * 一并去掉只会多发一趟注定落空的查询。
 */
fun withoutSeason(s: String): String = SEASON_CN.replace(s, "")

/**
 * 拿一个片名去源里搜时该依次试的几种写法：原样 → 去掉空白 → 去掉季号。
 *
 * 站点多是 `LIKE '%词%'`，片名写法一变就空手而归：豆瓣爱在季号前搁一个空格，
 * 懂片帝写「第5季」而 4kku 写「第五季」。第三档只留公共的那一截，
 * 认领哪一条交给 [titleFingerprint] —— 放宽检索词不等于放宽判定，不会把第二季认成第五季。
 *
 * 每档都是全源扇出，所以调用方应当**试到命中就停**，别三档都发。
 * 标题既没空格也没季号时，这里就只剩「原样」一档。
 */
fun titleSearchVariants(title: String): List<String> {
    val flat = title.filterNot { it.isWhitespace() }
    val base = withoutSeason(flat)
    return listOfNotNull(
        title,
        flat.takeIf { it != title },
        base.takeIf { it.length >= 2 && it != flat },
    ).distinct()
}

private val SEASON_CN = Regex("第([零一二三四五六七八九十百两0-9]+)([季部])")

/** 一…九十九的中文数字；认不出来返回 null，交回原文。 */
private fun cnNumber(s: String): Int? {
    val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    return when {
        s.length == 1 -> d[s[0]] ?: if (s[0] == '十') 10 else null
        s.length == 2 && s[0] == '十' -> 10 + (d[s[1]] ?: return null)
        s.length == 2 && s[1] == '十' -> (d[s[0]] ?: return null) * 10
        s.length == 3 && s[1] == '十' -> (d[s[0]] ?: return null) * 10 + (d[s[2]] ?: return null)
        else -> null
    }
}

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
