package net.cinehub.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
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
     * 于是这里报几件分开的事实，各自都站得住：
     * - 数据还没来（[bufferedAheadMs] 不足一秒）→ 报**已经等了多久**。
     *   prepare / 建连阶段往往是最长的等待，而那时缓冲量恒为 0，
     *   任何比例都算不出来；干写「缓冲中…」用户分不清是在走还是死了。
     * - 数据到了 → 报**已经攒了多少秒**，这才是「缓冲进度」的真实值。
     * - 量到了速度 → 再补一句**缓冲速度**（[speedPerSec]）。它是另一件事：
     *   「已缓冲多少秒」是已经攒下的，「正在多快往下攒」才回答「还得等多久」。
     *   没量到（0 或负）就一个字都不写 —— 编一个数出来比没有更糟。
     *
     * **[stalledMs] 单独算一项，不能和「没量到速度」混为一谈**：追播中掉进缓冲时
     * 缓冲量通常已经大于一秒，于是这一行停在「已缓冲 3 秒」上不动；三秒没有新字节之后
     * 速度那一项又整项消失 —— 屏幕上是**一行完全静止的字**，而那正是这个提示存在的理由。
     * 「这三秒一个字节都没进来」是量出来的结论，不是「没有数据可报」，所以要说出来。
     * 措辞只说事实、不猜原因（不写「网络慢」也不写「解码卡」）：光看字节流分不清这两者。
     *
     * [waitedSec] 为 0 时不报秒数：写「已等 0 秒」看着像卡住了。
     */
    fun bufferingLabel(
        bufferedAheadMs: Long,
        waitedSec: Int,
        speedPerSec: Long = 0L,
        stalledMs: Long = 0L,
    ): String {
        val bufferedSec = bufferedAheadMs / 1_000L
        val head = when {
            bufferedSec > 0L -> "缓冲中 · 已缓冲 $bufferedSec 秒"
            waitedSec > 0 -> "缓冲中…已等 $waitedSec 秒"
            else -> "缓冲中…"
        }
        // 停住时只说停住：那一刻再挂一个三秒前的速度，等于替「现在」作保。
        val stall = stalledMs / 1_000L
        if (stall > 0L) return "$head · 已 $stall 秒没有新数据"
        val speed = formatSpeed(speedPerSec)
        return if (speed == null) head else "$head · $speed"
    }

    /**
     * 字节每秒 → 人话。量不出来（<= 0）返回 null，由调用方整项不写。
     *
     * 进制与档位全部交给 [net.cinehub.data.ByteFormat]：缓存页那一份也用它。
     * 两处各写一套的话，同一条 4kku 的线在播放器里写 1.0 MB/s、在缓存页写 0.9 MB/s，
     * 看着就是两个数 —— 而这个文件以前正是各写一套。
     */
    fun formatSpeed(bytesPerSec: Long): String? = net.cinehub.data.ByteFormat.perSec(bytesPerSec)

    /**
     * 缓冲速度的测量。
     *
     * 为什么不直接报 Media3 的带宽估计（`bandwidth.bitrateEstimate`）：那个数是
     * 「一次加载的字节 ÷ 那一次耗时」的平滑样本，**只在真的发生加载时更新**。
     * 而缓冲区最需要看清楚的那一刻恰恰是「一个字都没来」—— 连接挂了、CDN 不回话、
     * 对方限流。那时候估计仍稳稳停在最后一次成功加载的那一档上，界面就写出
     * 「缓冲中 · 1.2 MB/s」而其实一秒都没动。那比不写速度更糟。
     *
     * 这里自己数字节：按最近 [WINDOW_MS] 内的样本算，另留一个**窗口之外**的基准点
     * （慢线上两次报告能隔十几秒，只留窗口内的样本就永远算不出跨度，速度那一栏会整个不出现）。
     * 窗口里真的没样本就是 0。数据一停速度归零，数据一来立刻有数。屏幕上那个数的语义就一句话：
     * 「刚才这一小段真的下来了多快」。
     *
     * **必须把 [delegate] 带着走**：一个数据源只接一个 [TransferListener]，而那个位置
     * 原来是 [DefaultBandwidthMeter] 独占的。换上这颗而不转发给它，等于把测量从带宽估计器上
     * 摘下来 —— ABR 当场失效，比不显示速度还糟（见 [bandwidthMeter] 那段「两处 wiring」）。
     *
     * 线程：回调在 ExoPlayer 的加载线程上，UI 读它则在重组线程，所以状态一律 synchronized 护着。
     */
    class SpeedMeter(private val delegate: TransferListener) : TransferListener by delegate {

        /** 窗口规则本身只有一份实现，见 [net.cinehub.data.SpeedWindow]。 */
        private val win = net.cinehub.data.SpeedWindow(WINDOW_MS, STALL_MS)

        /**
         * 只覆写这一个回调，其余三路靠接口委托原样转给 [delegate]。
         *
         * 用 `by delegate` 而不是手写四个 `override` 是有原因的：少转发一路，ABR 就静默失效
         * （带宽估计器收不到样本，自适应码率退化成常数），而单元测试一个字都不会红 ——
         * 那是最难查的一类坏。委托之后「少写一路」在语法上不可能发生，
         * 这里只剩「转发 + 记一笔」这一件事要做对。
         *
         * 转发放在 [record] **之前**：拿锁期间不回调进 Media3，也就不存在锁序问题。
         */
        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            byteCount: Int,
        ) {
            delegate.onBytesTransferred(source, dataSpec, isNetwork, byteCount)
            record(byteCount, isNetwork, now())
        }

        /**
         * [onBytesTransferred] 的全部计算，单独拆出来是为了把「时刻」当成参数传。
         *
         * 不拆的话这份窗口逻辑只有一种测法：喂真时钟、挂线程 sleep 2 秒 ——
         * 那样一次测试要等两秒，还会在慢机器上飘。时刻是入参之后就是纯算术，断言直接写死。
         *
         * 只数 [isNetwork] 的字节。本地缓存（`file://` 或缓存目录）那一路一秒能报出几百 MB，
         * 「缓冲速度」写成 300 MB/s 看着像在读内存，那不是用户想问的事。
         * 实测里这条路目前根本不会回调（数据源工厂没挂监听器），所以这一格是纯防御 ——
         * 留着是因为它一旦真回调，漏掉守卫就是一个荒诞的数。
         */
        internal fun record(byteCount: Int, isNetwork: Boolean, now: Long) {
            if (!isNetwork) return
            win.recordDelta(byteCount, now)
        }

        /**
         * 现在这一段的速度（字节每秒）。量不出来返回 0。生产代码用这个。
         *
         * 注意：这个无参重载走 [now]（`SystemClock.elapsedRealtime`），纯 JVM 单测里会抛
         * `Method elapsedRealtime not mocked`。自测一律走带 `now` 的那个重载。
         */
        fun speedPerSec(): Long = speedPerSec(now())

        /**
         * 同上，但把「现在」当参数收。
         *
         * 距离最后一个样本超过 [STALL_MS] 也报 0：窗口里那几个样本已经不代表「现在」，
         * 照旧值报就是拿三秒前的速度替当下作保。
         */
        internal fun speedPerSec(now: Long): Long = win.perSec(now) ?: 0L

        /**
         * 已经多久没有新字节了（毫秒）。没停住、或者压根还没开始收，都返回 0。
         *
         * 这一格是「停住」和「没量到」的分界线。原来两者都被压成 `speedPerSec() == 0`，
         * 于是追播中掉进缓冲的那一刻，屏幕上是**一行完全静止的字**：
         * 「已缓冲 3 秒」不会再动，速度那一项又在三秒后整项消失 ——
         * 而那正是这个提示存在的理由（分清是在走还是死了）。
         *
         * 停住是**量到的结论**（这三秒确实一个字节都没进来），不是「没有数据可报」，
         * 所以它该说出来，只是措辞只说事实、不猜原因：不写「网络慢」也不写「解码卡」——
         * 光凭字节流分不清这两者。
         */
        fun stalledMs(now: Long = this.now()): Long = win.stalledMs(now)

        /**
         * 区间测量一律用**单调**时钟，不用 `System.currentTimeMillis()`。
         *
         * 墙上时钟会被 NTP 校时和用户改时间往回拨：往回拨的那一刻，跨度算出来是负的，
         * 「离最后一个样本多久了」也是负的 —— 后者会让一段其实已经停住的数据继续按旧值报速度。
         * `elapsedRealtime()` 从开机起单调递增，系统时间怎么调都影响不到它。
         *
         * 下载页那一路仍在用墙上时钟（它每几秒整体重算一次，一次跳变会在下一个报告点自己纠正），
         * 而缓冲提示这边是每 100ms 读一次、直接写在屏幕上，不能有那种抖动。
         * 窗口规则本身两边共用 [net.cinehub.data.SpeedWindow]，**时钟由各自给**。
         */
        private fun now(): Long = android.os.SystemClock.elapsedRealtime()

        /** 换集 / 换源 / 换线路都是另一条流：上一条攒下的样本不作数，从零数这一条。 */
        fun reset() {
            win.reset()
        }

        /** 当前窗口里的样本个数，给自测断言用。 */
        internal fun sampleCount(): Int = win.sampleCount()

        companion object {
            /** 取最近多久内的样本：再短就跟着网络抖动一跳一跳，再长就把快慢糊成一个平均数。 */
            const val WINDOW_MS = 2_000L

            /** 最后一个样本超过这么久，认为已经不下东西了 —— 报 0。 */
            const val STALL_MS = 3_000L

            /**
             * 什么都不转发的下一环 —— 给单元测试用。
             *
             * 测试里造不出 Media3 那颗估计器（它要 Context），也不该让一个纯 JVM 测试去碰它；
             * 而这里要验的正是「只数字节」那半件事。生产代码一律带真 delegate。
             */
            internal val Noop: TransferListener = object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
                ) = Unit

                override fun onTransferStart(
                    source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
                ) = Unit

                override fun onBytesTransferred(
                    source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, byteCount: Int,
                ) = Unit

                override fun onTransferEnd(
                    source: DataSource, dataSpec: DataSpec, isNetwork: Boolean,
                ) = Unit
            }
        }
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
     * 网络恢复后放开到的目标上限（比特每秒）：实测的 [RELEASE_TARGET_PERCENT]%。
     *
     * **这里原来是「每跳最多放宽 25%」的阶梯，那是画质鬼畜的直接来源**：阶梯绑在
     * 500ms 的轮询上，等于每秒放宽两次 —— 压下去之后一两秒就冲回高位，ABR 又挑高档，
     * 又占满出口，又卡。用户看到的是清楚与模糊来回跳。
     *
     * 放开的两件事因此拆开：**幅度**在这里（一步到实测的 90%，比压下去时的 80%
     * 多留余量），**时机**在 [readyToRelease]（要连续稳定播放够久，且每卡一次窗口翻倍）。
     *
     * 降档之后估计值为什么仍可信：DefaultBandwidthMeter 按「一次加载的字节 ÷ 那一次
     * 耗时」取样，而分片是尽量快地下完的 —— 量到的是出口能跑多快，不是当前这一档有多高。
     * 所以降档不会把估计一起拖低到放不开手。
     *
     * 边界：估计没量出来（<= 0）→ 返回 0，由调用方保持现状（此时放开等于抬到未知）。
     */
    fun releaseTarget(estimate: Long): Long {
        if (estimate <= 0L) return 0L
        return (estimate * RELEASE_TARGET_PERCENT / 100L).coerceAtLeast(MIN_ABR_BITRATE)
    }

    /** 放开路径的目标（实测的百分比）：90% —— 比压下去的 80% 多留一点，减少刚放开又卡的来回。 */
    const val RELEASE_TARGET_PERCENT = 90L

    /**
     * 压住上限之后要**连续稳定播放**多久才允许放开一次（毫秒）。
     *
     * [rebuffers] 是这一条流上第几次重缓冲（1 起）。每多一次窗口翻倍：出口带宽正好
     * 卡在某一档上时第一次放开多半还会卡，退避让抖动越来越稀，而不是持续抽搐。
     * 到 [MAX_STABLE_MS] 封顶 —— 再往后翻就是十几分钟才试一次，等于这一场不打算给高画质了。
     *
     * 0 次（没卡过）返回 0：那时候根本没有「放开」这回事。
     */
    fun stableWindowMs(rebuffers: Int): Long = when {
        rebuffers <= 0 -> 0L
        else -> (FIRST_STABLE_MS shl (rebuffers - 1).coerceAtMost(6)).coerceAtMost(MAX_STABLE_MS)
    }

    /** 第一次重缓冲之后要求的稳定时长。 */
    const val FIRST_STABLE_MS = 20_000L

    /** 稳定窗口翻倍的封顶值。 */
    const val MAX_STABLE_MS = 300_000L

    /**
     * 现在能不能放开一次。
     *
     * [clamped] 是「上限正被压着、在等放开」这一位，**不能从 abrMaxBitrate 的数值推**：
     * 放开之后上限仍是一个有限的数（实测的 90%），照数值判会一直以为还压着、
     * 反复重建选档参数 —— 而每次重建都会让 ABR 重新选一次档，那本身就是一种抖动。
     */
    fun readyToRelease(clamped: Boolean, stableMs: Long, rebuffers: Int): Boolean {
        if (!clamped || rebuffers <= 0) return false
        return stableMs >= stableWindowMs(rebuffers)
    }

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
     *
     * 第二处现在挂的是包着它的 [SpeedMeter]（回调一律转发回这颗），不是这颗本身 ——
     * 换上时必须保持转发，否则这一段说的「两处 wiring」当场断一半。
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
