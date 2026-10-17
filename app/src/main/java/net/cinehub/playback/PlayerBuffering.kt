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
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_500

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
        // 时间档优先于字节预算：慢源上「字节满了但时间不够」的假性就绪少一次是一次。
        // 字节预算本身仍交给 Media3 按 131 MB 那套自己算（见上面的注释，不要手动调小）。
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * 缓冲提示那一句话。
     *
     * **这里没有可信的百分比可用**，所以不报百分比。原因是真机上两次被打回：
     *
     * 1. 报「整片下了百分之几」：seek 到 20:44 那一刻它就定在 39% 不动，
     *    等缓冲够了整句又消失 —— 用户看到的是「一直写缓冲中、百分比一闪而过」。
     * 2. 改报「已攒量 ÷ 起播阈值」：仍然错。`MAX_BUFFER_MS` 是 120 秒，
     *    ExoPlayer 一旦拿到数据就成批灌满几十秒，`bufferedPosition - currentPosition`
     *    是一步跳到位的 —— 百分比成了阶跃函数，0 → 100% 瞬间完成，
     *    还会出现「缓冲中 100%」却仍在等的画面（数据够了、解码器还没出帧）。
     *
     * 于是这里只报两件分开的事实，各自都是单调且可信的：
     * - 数据还没来（[bufferedAheadMs] 不足一秒）→ 报**已经等了多久**。
     *   prepare / 建连阶段往往是最长的等待，而那时缓冲量恒为 0，
     *   任何比例都算不出来；干写「缓冲中…」用户分不清是在走还是死了。
     * - 数据到了 → 报**已经攒了多少秒**，这才是「缓冲进度」的真实值。
     *
     * [waitedSec] 为 0 时不报秒数：写「已等 0 秒」看着像卡住了。
     */
    fun bufferingLabel(bufferedAheadMs: Long, waitedSec: Int): String {
        val bufferedSec = bufferedAheadMs / 1_000L
        if (bufferedSec > 0L) return "缓冲中 · 已缓冲 $bufferedSec 秒"
        return if (waitedSec > 0) "缓冲中…已等 $waitedSec 秒" else "缓冲中…"
    }

    /**
     * 重缓冲时把 ABR 上限压到「实测带宽 × [ABR_HEADROOM]%」，返回新的上限（比特每秒）。
     *
     * 卡顿的典型机制：选中的档位码率 > 出口 sustained 带宽 —— 播一段、追不上、
     * 重缓冲、再播一段，桶（[MAX_BUFFER_MS]）再大也治不了消费速度超过生产速度。
     * 治法是重缓冲时把上限压到实测以下，让 ABR 自动降档，而不是硬扛着反复卡。
     *
     * 边界：
     * - 估计还没量出来（[estimate] <= 0）→ 原样返回 [currentMax]，什么都不动；
     * - 结果只降不升：比当前上限还高时按当前上限封顶（网络变好交给 ABR 自己慢慢爬）；
     * - 压到 [MIN_ABR_BITRATE] 为止 —— 再往下 ABR 会挑一条连音频都装不下的档。
     */
    fun clampBitrate(estimate: Long, currentMax: Long): Long {
        if (estimate <= 0L) return currentMax
        val capped = (estimate * ABR_HEADROOM / 100L).coerceAtMost(currentMax)
        return capped.coerceAtLeast(MIN_ABR_BITRATE)
    }

    /** 重缓冲时给实测带宽留的头余量（%）：压到实测的 80%，防抖。 */
    const val ABR_HEADROOM = 80L

    /**
     * 网络恢复后逐步放开 ABR 上限，返回新的上限（比特每秒）。
     *
     * 放开必须**单步有界**：上限从压扁状态一口气放回实测的 90%，一次网络抖动
     * 又会把它打回重缓冲 —— 用户看到的是「松一下、卡一下」的循环。
     * 这里每步最多放宽 [RELEASE_STEP_PERCENT]%，多轮才回到 [RELEASE_TARGET_PERCENT]%。
     *
     * 边界：
     * - 估计没量出来（<= 0）→ 原样返回；
     * - **只升不降**：往下压是 [clampBitrate] 在重缓冲时干的事，两条路径别混；
     * - 目标是实测的 [RELEASE_TARGET_PERCENT]%（比压下去的 80% 留更多头余量），到了就停。
     */
    fun releaseBitrate(currentMax: Long, estimate: Long): Long {
        if (estimate <= 0L) return currentMax
        val target = estimate * RELEASE_TARGET_PERCENT / 100L
        if (currentMax >= target) return currentMax
        val step = currentMax * RELEASE_STEP_PERCENT / 100L
        return (currentMax + step).coerceAtMost(target)
    }

    /** 放开路径的单步上限（%）：每步最多放宽 25%。 */
    const val RELEASE_STEP_PERCENT = 25L

    /** 放开路径的目标（实测的百分比）：90% —— 比 80% 那份多留一点，减少刚放开又卡的来回。 */
    const val RELEASE_TARGET_PERCENT = 90L

    /**
     * ABR 上限的最低档（比特每秒）。
     *
     * 弱网估计可能抖到几十 kbps，跟着压会让音轨都选不出来（播放器直接卡死在 BUFFERING）。
     * 320 kbps 够装一条音频 + 最低档视频。
     */
    const val MIN_ABR_BITRATE = 320_000L

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
