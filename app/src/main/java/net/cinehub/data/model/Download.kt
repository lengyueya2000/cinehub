package net.cinehub.data.model

import kotlinx.serialization.Serializable

enum class DownloadStatus { Queued, Running, Paused, Done, Failed }

/**
 * 一条离线缓存任务。
 *
 * 只存「怎么再取一次」所需的信息（源、条目、播放页地址），不存直链本身 ——
 * 直链大多带时效和 Referer 校验，存下来下次再访问基本已经作废，
 * 续传时重新解析才是对站点规则更诚实的做法。
 */
@Serializable
data class DownloadTask(
    val id: String,
    val mediaId: String,
    val mediaTitle: String,
    /** [Media.key] —— 本机回放时要把进度写回同一部片的观看记录，光靠标题对不上。 */
    val mediaKey: String = "",
    val mediaYear: String? = null,
    val mediaCover: String? = null,
    val sourceId: String,
    val sourceName: String,
    val episodeName: String,
    val episodeIndex: Int,
    /** 该集的播放页地址，用来重新解析直链。 */
    val episodeUrl: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val status: DownloadStatus = DownloadStatus.Queued,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /**
     * [bytesTotal] 是估的还是准的。
     *
     * HLS 的分片列表不给字节数，只能按已下的那些估一个平均，界面上要带「约」；
     * 单文件有 Content-Length，直接就是准的。下完之后一律转成实际落盘的字节。
     */
    val sizeEstimated: Boolean = false,
    /** HLS 按切片计数，单文件按字节计数。 */
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val error: String? = null,
    /** 落地后的入口文件名（HLS 是重写过的 index.m3u8，单文件就是视频本身）。 */
    val fileName: String? = null,
    /**
     * 已经落盘的那些分片属于**哪一条流**。
     *
     * 存的是列表地址的主机 + 路径（不含查询串）加分片数，不是直链本身 ——
     * 时效签名在查询串里，那部分每次解析都不一样，存下来既没用也会让每次续传都作废。
     *
     * 要它是因为续传会重新解析直链，而站点完全可能把你换到另一条流上：同一个 BANDWIDTH
     * 有两个变体、上一版还留着一份分辨率不同的旧列表。这时候盘上那半集的分片
     * 名字（seg_00001.ts 之类）和新列表严丝合缝，逐片「文件非空就跳过」会把两条流
     * 混成一份 index.m3u8 —— 进度 100%、标着可离线播放、点开是花的。
     * 对不上就把目录清掉从零开始：丢的是已经下的那些字节，坏的是整集，不能换。
     */
    val streamKey: String? = null,
) {
    /** 有字节数就按字节算（用户看的、也参与「约」那句的都是它），估不出来才退回按分片数。 */
    val progress: Float
        get() = when {
            status == DownloadStatus.Done -> 1f
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            segmentsTotal > 0 -> (segmentsDone.toFloat() / segmentsTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}
