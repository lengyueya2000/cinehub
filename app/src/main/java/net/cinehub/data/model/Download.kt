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
    /** HLS 按切片计数，单文件按字节计数。 */
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val error: String? = null,
    /** 落地后的入口文件名（HLS 是重写过的 index.m3u8，单文件就是视频本身）。 */
    val fileName: String? = null,
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.Done -> 1f
            segmentsTotal > 0 -> (segmentsDone.toFloat() / segmentsTotal).coerceIn(0f, 1f)
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}
