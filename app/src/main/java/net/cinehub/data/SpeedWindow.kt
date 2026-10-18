package net.cinehub.data

/**
 * 滑动窗口测速。全应用只有这一份实现，两处在用：
 *
 * - 播放器的「缓冲速度」（`PlayerBuffering.SpeedMeter`）：数据源回调给的是**增量**，
 *   100ms 一读、直接写在屏幕上，窗口 2 秒，还要能说出「已经几秒没动了」。
 * - 下载页的每任务速度（`DownloadRepository.reportBytes`）：worker 手里是**累计总数**，
 *   按片报告、几秒才动一次，窗口 3 秒。
 *
 * 为什么必须是一份：这两处量的都是「刚才这一小段真的下来了多快」，规则里每一条都是踩过坑
 * 才定下来的（见下面 [recordDelta] / [perSec] 的注释）。各写一套的话，改一处忘另一处，
 * 同一个网络在两个页面上就会给出两套结论。
 *
 * 纯 Kotlin，没有 Android 依赖：时刻一律当参数传进来，断言直接写死，不用 sleep 等真表。
 *
 * @param windowMs 只看最近多久的样本。再短就跟着网络抖动一跳一跳，再长就把快慢糊成一个平均数。
 * @param stallMs 最后一个样本超过这么久就认为「已经不下东西了」，速度返回 null。
 *   下载那一路传 [Long.MAX_VALUE]（它没有「停住」这个概念，按片报告本来就是稀疏的）。
 * @param minSpanMs 跨度小于这个值不报数：一次 100ms 的突发除出来的数字是天文数字。
 */
class SpeedWindow(
    private val windowMs: Long,
    private val stallMs: Long = Long.MAX_VALUE,
    private val minSpanMs: Long = 500L,
) {

    private val lock = Any()

    /** (时刻, 累计字节)。存累计值而不是每次的增量：算速度只需要两端点。 */
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var total = 0L

    /**
     * 累加一段字节 —— 给「回调里给的是增量」那一路（播放器的数据源）。
     *
     * 窗口里第一个样本要记成「这批字节来**之前**」的累计值，不能直接记进来之后的值。
     * 否则第一段字节落在窗口起点上、却没有起点可以减 —— 量出来只有后面那几段，
     * 速度会稳定偏低一档（1 MB/s 的线报成 512 KB/s），而这正是用户要看的那个数。
     *
     * 代价是**起播头一两秒偏高**：第一批字节（ExoPlayer 一灌就是几百 KB 到几 MB）被算进
     * 整个首段跨度，下一批才把跨度拉长、把这个数压回真实值。宁可短暂偏高，
     * 也不要报一个偏低一档的数。
     */
    fun recordDelta(byteCount: Int, now: Long) {
        if (byteCount <= 0) return
        synchronized(lock) {
            if (samples.isEmpty()) samples.addLast(now to total)
            total += byteCount
            push(now)
        }
    }

    /**
     * 报一次**累计总数** —— 给「worker 手里本来就是总数」那一路（下载）。
     *
     * 这一路不加基准点：第一次报告只落一个样本（算不出跨度），第二次报告起才有数，
     * 而它本来就是每秒才报一两次，不需要把第一段补进来。
     */
    fun recordTotal(totalBytes: Long, now: Long) {
        synchronized(lock) {
            total = totalBytes
            push(now)
        }
    }

    /** 同一个时刻的多路 worker 会挤在这里，锁在里面收口。 */
    private fun push(now: Long) {
        samples.addLast(now to total)
        // 只丢到窗口边缘，**至少留两个**样本：剪到只剩一个就算不出跨度了。
        // 于是队列里会长期留着一个**窗口之外**的基准点 —— 那不是 bug，是故意的：
        // 慢线上两次报告隔得比窗口还长时，没有它速度那一栏整个不会出现
        // （缓存页实测过：4kku 一条 0.4 MB/s 的线，进度从 163 MB 走到 263 MB，一个速度字都没有）。
        while (samples.size > 2 && now - samples[1].first > windowMs) samples.removeFirst()
    }

    /**
     * 现在这一段的速度（字节每秒）。
     *
     * 返回 null 表示**量不出来**，和「量到了 0」是两件事：
     * 前者（样本不够、跨度太短、已经停住）调用方只能闭嘴；后者是一句能说出口的结论。
     * 压成同一个 0 的话，界面就会在该闭嘴的时候报 0、该报 0 的时候闭嘴。
     */
    fun perSec(now: Long): Long? = synchronized(lock) {
        if (samples.size < 2) return@synchronized null
        val first = samples.first()
        val last = samples.last()
        val span = last.first - first.first
        if (span < minSpanMs) return@synchronized null
        if (now - last.first > stallMs) return@synchronized null
        ((last.second - first.second) * 1000 / span).coerceAtLeast(0L)
    }

    /**
     * 已经多久没有新字节了（毫秒）；没停住（或 [stallMs] 不设）返回 0。
     *
     * 这一格是「停住」和「没量到」的分界线。播放器那一路靠它把话说明白：
     * 追播中卡住时缓冲量已经大于一秒，「已缓冲 N 秒」自己不会动，
     * 没有这一项整行字就是静止的，用户分不清是在走还是死了。
     */
    fun stalledMs(now: Long): Long = synchronized(lock) {
        if (samples.size < 2 || stallMs == Long.MAX_VALUE) return@synchronized 0L
        (now - samples.last().first).coerceAtLeast(0L).takeIf { it > stallMs } ?: 0L
    }

    /** 换一条流 / 换一个任务就是另一段测量：上一条攒下的样本不作数。 */
    fun reset() {
        synchronized(lock) {
            samples.clear()
            total = 0L
        }
    }

    /** 当前窗口里的样本个数，给自测断言用。 */
    internal fun sampleCount(): Int = synchronized(lock) { samples.size }
}
