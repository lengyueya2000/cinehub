package net.cinehub.playback

import org.junit.Assert.assertEquals
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
 * 结论：这里没有可信的百分比可用。真正要报的是两件分开的事实 ——
 * 数据还没来时「已经等了多久」（单调、全程可见），数据到了之后
 * 「已经攒了多少秒」（这就是缓冲进度的真实值）。
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
}
