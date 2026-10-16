package net.cinehub.playback

import android.content.Context
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * 播放缓冲的几档参数，集中在这一处。
 *
 * 单独一个对象是因为这几件事互相牵连：起始码率决定第一档选多清，缓冲上下限决定
 * 攒多少才肯继续拉，起播 / 重缓冲阈值决定「多久出画」和「卡多久才恢复」。
 * 散在播放器构造代码里改一处就会和另一处对不上；写在这里至少看得见彼此。
 *
 * 调的方向按这类站点的实际体验来：直链普遍是慢 CDN，一边下边播边反复重缓冲，
 * 不如起播快一点、跑起来之后把桶撑大，让网络抖动落在缓冲里而不是变成卡顿。
 * 默认值一并注在旁边，想知道某一档是收紧了还是放开了不用去翻 Media3 的源码。
 */
object PlayerBuffering {

    /** 起播前要攒够多少毫秒。默认 2500 —— 慢站上等于白等一两秒才出画。 */
    const val BUFFER_FOR_PLAYBACK_MS = 1_200

    /** 卡住之后重新攒够多少毫秒才继续。默认 5000 —— 一次卡顿要停五秒才走。 */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

    /** 缓冲桶的下限。默认 15000。 */
    const val MIN_BUFFER_MS = 30_000

    /** 缓冲桶的上限。默认 50000 —— 拉到两分钟，慢 CDN 上抖动就不容易被看见成卡顿。 */
    const val MAX_BUFFER_MS = 120_000

    /**
     * 起始带宽估计（比特每秒）。
     *
     * Media3 在真正量出来之前按这个值挑档。**这一档定的是「第一次起播落在哪条」，
     * 不是「最高能到哪条」** —— 上限是另一件事（[clampToUserChoice]，默认不封顶），
     * 跑起来之后 ExoPlayer 自己会按实测带宽升降，这一值只影响头几秒。
     *
     * 从 1.5 Mbps 抬到 6 Mbps 是按「画质默认要最高」定的：估低了要往上爬好几轮才到顶，
     * 用户看到的就是「开头一段糊、过一会儿才清楚」。代价反过来也一样真：
     * 出口本来就慢的时候，开头那几秒会因为追一条下不动的档而多缓冲一会儿。
     * 两边不兼得，这里选了「起手就清楚」。
     */
    const val START_AT_BITRATE = 6_000_000

    /**
     * 单文件（mp4）那类地址一次预取多少字节。默认 500_000。
     *
     * 桶已经放到 [MAX_BUFFER_MS]，预取还按默认的小块走就填不满，
     * 白留一半缓冲上限在屏幕上看不见。
     */
    const val TARGET_BUFFER_BYTES = 2_000_000

    /** 按上面几档装一个 LoadControl。 */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .build()

    /**
     * 建带宽估计器。
     *
     * **拿回去的这个实例必须同时交给播放器和数据源**（`setBandwidthMeter` 一处、
     * `DefaultHttpDataSource.Factory.setTransferListener` 一处）：只给播放器而不挂监听，
     * 它就只能收到 [START_AT_BITRATE] 这一个初始值、此后永远不更新 ——
     * ABR 当场失效，比不调还糟。两处 wiring 都在 PlayerScreen，改其中一处必须改另一处。
     */
    fun bandwidthMeter(context: Context): DefaultBandwidthMeter =
        DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(START_AT_BITRATE.toLong())
            .build()

    /**
     * 把用户手选的画质上限压回选档参数里。
     *
     * 只有一个入口是有原因的：换集 / 换源之后也要重新设一遍参数，
     * 两处各写一套 `setMaxVideoSize`，早晚会有一处把用户刚点的「只看 720P」冲掉。
     */
    fun clampToUserChoice(
        base: TrackSelectionParameters,
        maxVideoHeight: Int,
    ): TrackSelectionParameters = base.buildUpon()
        .setMaxVideoSize(
            Int.MAX_VALUE,
            if (maxVideoHeight > 0) maxVideoHeight else Int.MAX_VALUE,
        )
        .build()

    /**
     * 这个地址该按 HLS 还是按单文件建媒体源。
     *
     * 判据和 [net.cinehub.data.DownloadRepository] 那一份保持同一套：只看 `.m3u8`
     * 后缀会漏掉 `/m3u8/xxxx` 这种不给后缀的列表地址 —— 播放那边漏了的后果是
     * 拿 ProgressiveMediaSource 去解一份 playlist，屏幕上只剩「播放失败：Source error」，
     * 而缓存那边同一集却报「这份列表里没有分片」。两边各判各的，用户就没法对齐。
     */
    fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/', path)
        return path.endsWith(".m3u8", ignoreCase = true) ||
            last.contains("m3u8", ignoreCase = true) ||
            path.contains("/m3u8/", ignoreCase = true)
    }
}
