package net.cinehub.data.model

import kotlinx.serialization.Serializable

/**
 * 一部片的收藏。
 *
 * 按 [Media.key]（标题 + 年份）去重，所以同一部片在三个源上被收藏也只有一条。
 * 记着当时的 sourceId / mediaId 是因为详情页就是靠这两个值打开的；
 * 那个源后来被删了，点进去会如实报「源不在了」，不做跨源回退 —— 那是另一套解析逻辑。
 */
@Serializable
data class Favorite(
    val key: String,
    val sourceId: String,
    val mediaId: String,
    val title: String,
    val year: String? = null,
    val category: String? = null,
    val cover: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * 一条观看记录：一部片只留最近看到的那一集。
 *
 * 「继续观看」要的是「上次看到哪了」，不是每集一条流水账 ——
 * 所以这里按 [Media.key] upsert，而不是按 (key, 集数) 存一堆。
 */
@Serializable
data class HistoryEntry(
    val key: String,
    val sourceId: String,
    val mediaId: String,
    val title: String,
    val year: String? = null,
    val cover: String? = null,
    val episodeIndex: Int,
    val episodeName: String,
    val positionSec: Long,
    val durationSec: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * 离结尾多近算看完。
     *
     * 固定 30 秒对短片是错的：一条 30 秒的预览，看到第 5 秒就被判成「看完了」，
     * 续播提示永远出不来。所以取两者里更小的那个 —— 长片仍是 30 秒，
     * 短片按 10% 算，并且不低于 5 秒（免得几秒的片段一开播就算看完）。
     */
    private val tail: Long get() = minOf(30L, (durationSec / 10).coerceAtLeast(5L))

    val finished: Boolean get() = durationSec > 0 && positionSec >= durationSec - tail

    /** 只有「看了点东西、又没看完」才值得提示续播。 */
    val resumable: Boolean get() = positionSec > 0 && !finished

    /** 「第3集 · 12:34」，给首页横排和详情页按钮用。 */
    val where: String
        get() = "第$episodeIndex 集 · ${clock(positionSec)}"
}

/** 秒 → 12:34 / 1:02:03。 */
fun clock(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
