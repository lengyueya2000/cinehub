package net.cinehub.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓冲提示那一句话的措辞。
 *
 * 这段文案被真机打回过两次，两次都是「看着有数、其实没信息」：
 *
 * 1. 最初报「整片下了百分之几」—— seek 到 20:44 那一刻它就定在 39% 不动，
 *    等缓冲够了整句又消失，用户看到的是「一直写缓冲中、百分比一闪而过」。
 * 2. 改成「已攒量 ÷ 起播阈值」之后仍然错：`MAX_BUFFER_MS` 是 120 秒，
 *    ExoPlayer 拿到数据就成批灌满几十秒，`bufferedPosition - currentPosition`
 *    是一步跳到位的 —— 百分比是个**阶跃函数**，0 → 100% 瞬间完成，
 *    而且会出现「缓冲中 100%」却还在等的画面（数据够了、解码器还没出帧）。
 *
 * 结论：这里没有可信的百分比可用。真正要报的是几件分开的事实 ——
 * 数据还没来时「已经等了多久」（单调、全程可见），数据到了之后
 * 「已经攒了多少秒」（这就是缓冲进度的真实值），再加上这一版新加的
 * 「正在多快往下攒」（回答「还得等多久」），以及「几秒没有新数据」
 * （量到了零，和「还没量到」是两件事）。每件各自都站得住，
 * 量不到的那一件就整项不写，不编数字。
 */
class BufferingLabelTest {

    @Test
    fun `数据还没来时报已经等了多久`() {
        // prepare / 建连阶段 bufferedPosition 恒为 0，而这一段往往是最长的等待。
        // 干写「缓冲中…」用户分不清是在走还是死了。
        assertEquals("缓冲中…已等 3 秒", PlayerBuffering.bufferingLabel(0L, 3))
    }

    @Test
    fun `等待不足一秒不报秒数`() {
        // 写「已等 0 秒」看着像卡住了
        assertEquals("缓冲中…", PlayerBuffering.bufferingLabel(0L, 0))
    }

    @Test
    fun `数据到了就报攒了多少秒`() {
        // 这才是「缓冲进度」的真实值：攒够 2.5 秒就能续播（见 BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS）
        assertEquals("缓冲中 · 已缓冲 12 秒", PlayerBuffering.bufferingLabel(12_400L, 5))
    }

    @Test
    fun `攒了不足一秒仍按等待秒数说`() {
        // 数据刚到几百毫秒时，报「已缓冲 0 秒」没有意义，仍按等待说
        assertEquals("缓冲中…已等 5 秒", PlayerBuffering.bufferingLabel(400L, 5))
    }

    @Test
    fun `有一秒就报一秒`() {
        assertEquals("缓冲中 · 已缓冲 1 秒", PlayerBuffering.bufferingLabel(1_000L, 0))
    }

    @Test
    fun `负数攒量当作没数据`() {
        // buf 与 pos 各自采样，两者之间会短暂错位成负数
        assertEquals("缓冲中…已等 2 秒", PlayerBuffering.bufferingLabel(-500L, 2))
    }

    @Test
    fun `数据到了就不再报等待秒数`() {
        // 两件事只报一件：并排写「已缓冲 12 秒 · 已等 5 秒」反而更乱
        assertEquals("缓冲中 · 已缓冲 30 秒", PlayerBuffering.bufferingLabel(30_000L, 9))
    }

    @Test
    fun `长等待报大数字不截断`() {
        assertEquals("缓冲中…已等 75 秒", PlayerBuffering.bufferingLabel(0L, 75))
    }

    // ---------------------------------------------------------------- 速度那一项

    @Test
    fun `量到速度时补在末尾`() {
        // 速度是回答「还得等多久」的那一问，和已经攒了多少是两件事，所以并排而不是替换
        assertEquals(
            "缓冲中 · 已缓冲 12 秒 · 1.5 MB/s",
            PlayerBuffering.bufferingLabel(12_400L, 5, 1_572_864L),
        )
    }

    @Test
    fun `还没攒到量也照样能报速度`() {
        // 速度刚起来、缓冲还没到 1 秒：用户正等着的恰恰是这个数
        assertEquals(
            "缓冲中…已等 3 秒 · 800 KB/s",
            PlayerBuffering.bufferingLabel(400L, 3, 819_200L),
        )
    }

    @Test
    fun `速度是零就一个字都不写`() {
        // 0 = 这一段窗口里没数到字节（连接挂了 / 本地缓存那趟不走网络）。
        // 写成「缓冲中 · 已缓冲 2 秒 · 0 B/s」是把「没量到」说成「量到了 0」，是两种话。
        assertEquals("缓冲中 · 已缓冲 2 秒", PlayerBuffering.bufferingLabel(2_400L, 7, 0L))
        assertEquals("缓冲中…已等 7 秒", PlayerBuffering.bufferingLabel(0L, 7, 0L))
    }

    @Test
    fun `速度是负数同样不写`() {
        // 采样错位那一瞬可能算出负数，负速度没有语义
        assertEquals("缓冲中 · 已缓冲 4 秒", PlayerBuffering.bufferingLabel(4_400L, 8, -1L))
    }

    @Test
    fun `速度格式按进制切档`() {
        assertEquals("1.5 MB/s", PlayerBuffering.formatSpeed(1_572_864L))
        // KB 档是整数：这一档的小数位一次采样就变一次，写出来只是噪音（和缓存页同一口径）
        assertEquals("800 KB/s", PlayerBuffering.formatSpeed(819_200L))
        assertEquals("10 KB/s", PlayerBuffering.formatSpeed(10_240L))
        // 一 KB 都不到就别写小数，那一位没意义
        assertEquals("512 B/s", PlayerBuffering.formatSpeed(512L))
    }

    @Test
    fun `速度量不到时格式化返回空而不是零`() {
        assertNull(PlayerBuffering.formatSpeed(0L))
        assertNull(PlayerBuffering.formatSpeed(-9_000L))
    }

    @Test
    fun `停住之后说出「没有新数据」`() {
        // 追播中卡住时，「已缓冲 3 秒」自己不会动；不写这一项，整行字就是静止的，
        // 用户分不清是在走还是死了 —— 而这正是这个提示存在的理由。
        assertEquals(
            "缓冲中 · 已缓冲 3 秒 · 已 4 秒没有新数据",
            PlayerBuffering.bufferingLabel(3_000L, 9, speedPerSec = 0L, stalledMs = 4_200L),
        )
    }

    @Test
    fun `停住时不挂三秒前的旧速度`() {
        // 那一刻速度那一项已经不代表「现在」了：照旧值写就是拿三秒前替当下作保。
        val s = PlayerBuffering.bufferingLabel(3_000L, 9, speedPerSec = 1_500_000L, stalledMs = 4_200L)
        assertEquals("缓冲中 · 已缓冲 3 秒 · 已 4 秒没有新数据", s)
        assertFalse(s.contains("MB/s"))
    }

    @Test
    fun `没停住就不写没有新数据`() {
        val s = PlayerBuffering.bufferingLabel(3_000L, 9, speedPerSec = 1_500_000L, stalledMs = 0L)
        assertEquals("缓冲中 · 已缓冲 3 秒 · 1.4 MB/s", s)
        assertFalse(s.contains("没有新数据"))
    }

    @Test
    fun `一兆那一格是换档的边界`() {
        // 差一个字节就该落回 KB 档：换档点写错会让 1.0 MB/s 显示成 1024 KB/s
        assertEquals("1.0 MB/s", PlayerBuffering.formatSpeed(1_048_576L))
        assertEquals("1023 KB/s", PlayerBuffering.formatSpeed(1_048_575L))
        assertEquals("1 KB/s", PlayerBuffering.formatSpeed(1_024L))
        assertEquals("1023 B/s", PlayerBuffering.formatSpeed(1_023L))
    }
}
