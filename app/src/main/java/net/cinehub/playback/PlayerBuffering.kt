package net.cinehub.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import net.cinehub.data.model.StreamUrls

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
@OptIn(UnstableApi::class)
object PlayerBuffering {

    /** 起播前要攒够多少毫秒。默认 2500 —— 慢站上等于白等一两秒才出画。 */
    const val BUFFER_FOR_PLAYBACK_MS = 1_200

    /** 卡住之后重新攒够多少毫秒才继续。默认 5000 —— 一次卡顿要停五秒才走。 */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

    /**
     * 缓冲桶的下限。Media3 默认 50000。
     *
     * **这里是有意调低**（50s → 30s），不是抬：弱网下攒够 50 秒才肯继续拉，
     * 用户看到的就是「卡住之后要等很久才开始走」。上限才负责把桶撑大。
     */
    const val MIN_BUFFER_MS = 30_000

    /** 缓冲桶的上限。Media3 默认 50000 —— 拉到两分钟，慢 CDN 上抖动就不容易被看见成卡顿。 */
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
     * 按上面几档装一个 LoadControl。
     *
     * **不要调 `setTargetBufferBytes`**。Media3 的默认是 `DEFAULT_TARGET_BUFFER_BYTES = -1`，
     * 意思是「按渲染器自己算」—— 视频那一路算出来是 131072000 字节（约 131 MB，音频另有 13 MB）。
     * 而字节闸门在 `prioritizeTimeOverSizeThresholds = false`（也是默认）下是**硬停**：
     * 分配量一到，无论离 [MIN_BUFFER_MS] 还有多远都立刻停止加载。
     * 早先这里显式写了个 2 MB，等于把预算缩小约 65 倍 —— 5 Mbps 的 1080p 大约三秒就填满，
     * [MIN_BUFFER_MS] 和 [MAX_BUFFER_MS] 两个时间档都变得不可达，还会让 Media3 自己打一句
     * `Target buffer size reached with less than 500ms of buffered media data.`
     * 那与这个文件想做的事（把网络抖动落在缓冲里）正好相反，所以让 Media3 自己算。
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
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
     * 判法只有一份（[StreamUrls.isHls]）—— 缓存那边用的是同一个函数。
     * 两处各判各的时候，同一集在播放器里按 HLS 解、在缓存里按单文件存，
     * 用户就没法对齐两边给出的结论。
     */
    fun isHlsUrl(url: String): Boolean = StreamUrls.isHls(url)
}
