package net.cinehub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动窗口测速 [SpeedWindow] —— 播放器与下载页共用的那一份。
 *
 * 两处用法不一样，规则却必须是同一套：
 * - 播放器给的是**增量**（[SpeedWindow.recordDelta]），第一批字节不能被漏掉；
 * - 下载页给的是**累计总数**（[SpeedWindow.recordTotal]），第一次报告本来就凑不出跨度。
 *
 * 最要紧的一条是 **null 和 0 不是一回事**：null 是「量不出来」（样本不够、跨度太短、已经停住），
 * 0 是「量到了零」（这段时间真的一个字节都没动）。压成一个数，界面就会在该闭嘴的时候报 0、
 * 在该报 0 的时候闭嘴。
 */
class SpeedWindowTest {

    private val t0 = 1_000_000L

    private fun window(windowMs: Long = 2_000L, stallMs: Long = Long.MAX_VALUE) =
        SpeedWindow(windowMs = windowMs, stallMs = stallMs)

    @Test
    fun `增量那一路第一批字节不算丢`() {
        // 窗口起点记成「这批字节来之前」的累计值：直接记进来之后的值，第一段就没有起点可减，
        // 量出来会稳定偏低一档（1 MB/s 的线报成 512 KB/s）。
        val w = window()
        w.recordDelta(1_048_576, t0)
        assertEquals(2, w.sampleCount())
        assertNull("只有一个时刻凑不出跨度", w.perSec(t0))
        w.recordDelta(1_048_576, t0 + 1_000)
        assertEquals(2_097_152L, w.perSec(t0 + 1_000))
    }

    @Test
    fun `累计那一路第一次报告算不出速度`() {
        val w = window()
        w.recordTotal(1_048_576, t0)
        assertNull(w.perSec(t0))
        w.recordTotal(2_097_152, t0 + 1_000)
        assertEquals(1_048_576L, w.perSec(t0 + 1_000))
    }

    @Test
    fun `跨度不足半秒不报数`() {
        // 一次 100ms 的突发除出来是天文数字
        val w = window()
        w.recordTotal(0, t0)
        w.recordTotal(100_000, t0 + 200)
        assertNull(w.perSec(t0 + 200))
    }

    @Test
    fun `量到了零和量不出来是两件事`() {
        val w = window()
        w.recordTotal(1_048_576, t0)
        // 一秒之后还是同一个总数：跨度够了，速度**确实是 0**，不是「量不出来」
        w.recordTotal(1_048_576, t0 + 1_000)
        assertEquals(0L, w.perSec(t0 + 1_000))
    }

    @Test
    fun `两次报告隔得比窗口还长也算得出速度`() {
        // 「至少留两个样本」那条规矩：全剪掉就只剩刚进来那一个，跨度恒为 0，
        // 速度那一栏整个不出现（慢线上这是常态）。
        val w = window(windowMs = 2_000L)
        w.recordTotal(1_048_576, t0)
        w.recordTotal(2_097_152, t0 + 5_000)
        assertEquals(209_715L, w.perSec(t0 + 5_000))
        assertTrue(w.sampleCount() in 2..4)
    }

    @Test
    fun `设了停住判据就报不出速度并说出停了多久`() {
        val w = window(stallMs = 3_000L)
        w.recordDelta(1_048_576, t0)
        w.recordDelta(1_048_576, t0 + 1_000)
        // 还没停够久
        assertNotNull(w.perSec(t0 + 2_000))
        assertEquals(0L, w.stalledMs(t0 + 2_000))
        // 停够久了：速度没了，停住时长有了
        assertNull(w.perSec(t0 + 1_000 + 3_500L))
        assertEquals(3_500L, w.stalledMs(t0 + 1_000 + 3_500L))
    }

    @Test
    fun `下载那一路没有停住这个概念`() {
        // 按片报告本来就是稀疏的：隔很久没报不代表停了，所以不设判据、也不报停住
        val w = window()
        w.recordTotal(1_048_576, t0)
        w.recordTotal(2_097_152, t0 + 20_000)
        assertEquals(0L, w.stalledMs(t0 + 60_000))
        assertNotNull("隔了 20 秒也照样报得出这段平均", w.perSec(t0 + 20_000))
    }

    @Test
    fun `还没有样本时什么都不报`() {
        val w = window(stallMs = 3_000L)
        assertNull(w.perSec(t0))
        assertEquals(0L, w.stalledMs(t0 + 10_000))
    }

    @Test
    fun `零字节和负字节不算样本`() {
        val w = window()
        w.recordDelta(0, t0)
        w.recordDelta(-1, t0)
        assertEquals(0, w.sampleCount())
    }

    @Test
    fun `换一条流之后从零开始`() {
        val w = window(stallMs = 3_000L)
        w.recordDelta(1_048_576, t0)
        w.recordDelta(1_048_576, t0 + 1_000)
        w.reset()
        assertEquals(0, w.sampleCount())
        assertNull(w.perSec(t0 + 1_000))
        assertEquals(0L, w.stalledMs(t0 + 60_000))
    }
}
