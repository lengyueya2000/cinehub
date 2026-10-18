package net.cinehub.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓冲速度的窗口算法。
 *
 * 要验的是三件「报了就一定得是真的」的事：
 *
 * 1. **停就是 0** —— 连接挂了之后窗口里超过 [PlayerBuffering.SpeedMeter.STALL_MS]
 *    没有新样本就报 0，不许拿三秒前的速度替当下作保（这是不用 Media3 带宽估计的原因）。
 * 2. **慢也算得出来** —— 样本少到只剩一个时不能返回 null/0 了事：
 *    缓存页那边踩过，慢线上两次报告隔得比窗口还长，剪完只剩一个点，速度那一栏整个不出现。
 * 3. **本地缓存不数** —— file:// 那一路一秒能报几百 MB，写进「缓冲速度」就是在读内存。
 *
 * 全部走 [PlayerBuffering.SpeedMeter.record] 传入的时刻，不睡真表：
 * 睡着测一次要两秒，机器一慢还会飘。
 */
class BufferSpeedMeterTest {

    private val t0 = 1_000_000L

    private fun meter() = PlayerBuffering.SpeedMeter(PlayerBuffering.SpeedMeter.Noop)

    @Test
    fun `窗口内两个样本算出平均速度`() {
        val m = meter()
        // 2 秒里下了 2 MB → 1 MB/s
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 2_000)
        assertEquals(1_048_576L, m.speedPerSec(t0 + 2_000))
    }

    @Test
    fun `第一段字节不会被漏掉`() {
        // 窗口起点记的是「这批字节来之前」的累计值，所以第一段也算进跨度里。
        // 直接记「进来之后」的值会稳定偏低一档：1 MB/s 的线报成 512 KB/s。
        val m = meter()
        m.record(1_048_576, true, t0)
        assertEquals(2, m.sampleCount())
        // 只有一个时刻还构不成跨度，报 0 —— 但不许把第一段字节算丢
        assertEquals(0L, m.speedPerSec(t0))
        m.record(1_048_576, true, t0 + 1_000)
        assertEquals(2_097_152L, m.speedPerSec(t0 + 1_000))
    }

    @Test
    fun `一个样本算不出速度`() {
        // 只有一个点就没有跨度，报任何数都是编的
        val m = meter()
        m.record(500_000, true, t0)
        assertEquals(0L, m.speedPerSec(t0 + 100))
    }

    @Test
    fun `跨度不足半秒不报数`() {
        // 跨度太小会把一次突发除成天文数字（100ms 冲了 100KB → 1 MB/s）
        val m = meter()
        m.record(100_000, true, t0)
        m.record(100_000, true, t0 + 200)
        assertEquals(0L, m.speedPerSec(t0 + 200))
    }

    @Test
    fun `停超过三秒报零`() {
        val m = meter()
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 1_000)
        // 样本还在窗口里，但「现在」已经离它三秒多了 —— 说明这三秒一个字节都没来
        assertEquals(0L, m.speedPerSec(t0 + 1_000 + PlayerBuffering.SpeedMeter.STALL_MS + 1))
        // 一毫秒之内还是有的（两段各 1 MB，跨度 1 秒）
        assertEquals(
            2_097_152L,
            m.speedPerSec(t0 + 1_000 + PlayerBuffering.SpeedMeter.STALL_MS),
        )
    }

    @Test
    fun `只丢出窗口的样本而且至少留两个`() {
        val m = meter()
        // 持续喂，直到窗口外的第一个样本被丢掉
        var now = t0
        m.record(100_000, true, now)
        repeat(6) {
            now += PlayerBuffering.SpeedMeter.WINDOW_MS / 2
            m.record(100_000, true, now)
        }
        // 样本多了必须被剪掉，否则窗口就是一句空话；但剪到只剩一个，速度就再也算不出来
        assertTrue(m.sampleCount() >= 2)
        assertTrue(m.sampleCount() <= 4)
    }

    @Test
    fun `本地字节不计`() {
        val m = meter()
        m.record(900_000_000, false, t0) // 本地缓存那一路：一秒 900 MB
        m.record(900_000_000, false, t0 + 1_000)
        assertEquals(0L, m.speedPerSec(t0 + 1_000))
    }

    @Test
    fun `换流之后从零开始`() {
        val m = meter()
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 1_000)
        m.reset()
        assertEquals(0, m.sampleCount())
        assertEquals(0L, m.speedPerSec(t0 + 1_000))
    }

    @Test
    fun `零字节和负字节不算样本`() {
        val m = meter()
        m.record(0, true, t0)
        m.record(-1, true, t0)
        assertEquals(0, m.sampleCount())
    }

    @Test
    fun `两次报告隔得比窗口还长也算得出速度`() {
        // KDoc 第 2 条自己写的那个坑，必须有断言守着：慢线上两次报告能隔十几秒，
        // 若剪枝把窗口外的样本全剪掉，队里只剩刚进来那一个 —— 跨度恒为 0，
        // 速度那一栏整个不会出现（缓存页踩过一次，界面上一个速度字都没有）。
        // 留一个窗口外的基准点后，报出来的是这段平均：1 MB / 5 秒。
        val m = meter()
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 5_000)
        assertEquals(209_715L, m.speedPerSec(t0 + 5_000))
    }

    @Test
    fun `停住超过三秒就能说出停了多久`() {
        // 「速度为零」有两种意思：还没量到，和量到了零。后者必须能区分出来，
        // 否则追播中卡住时屏幕上那行字是静止的（见 bufferingLabel 的注释）。
        val m = meter()
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 1_000)
        // 还在窗口内、也还没停够久：不算停住
        assertEquals(0L, m.stalledMs(t0 + 2_000))
        // 停够久了：报出「离最后一个样本多久」
        assertEquals(3_500L, m.stalledMs(t0 + 1_000 + 3_500L))
        // 速度那一项同时归零 —— 两件事由同一个判据分开，不会互相矛盾
        assertEquals(0L, m.speedPerSec(t0 + 1_000 + 3_500L))
    }

    @Test
    fun `还没有样本时不算停住`() {
        // 还没开始收就报「已经 N 秒没有新数据」是纯吓人：那时候什么都还没发生。
        // （收过一次之后再没动静，那就是真的停了 —— 见上一条。）
        val m = meter()
        assertEquals(0L, m.stalledMs(t0 + 10_000))
    }

    @Test
    fun `换流之后停住状态也清零`() {
        val m = meter()
        m.record(1_048_576, true, t0)
        m.record(1_048_576, true, t0 + 1_000)
        m.reset()
        assertEquals(0L, m.stalledMs(t0 + 60_000))
    }
}
