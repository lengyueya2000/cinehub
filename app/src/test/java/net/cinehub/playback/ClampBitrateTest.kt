package net.cinehub.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重缓冲时把 ABR 上限压到「实测带宽 × 系数」的纯计算。
 *
 * 卡顿的典型机制是选中的档位码率 > 出口 sustained 带宽：播一段、追不上、
 * 重缓冲、再播一段。治法是重缓冲时把上限压到实测以下，让网络抖动落在
 * 档位选择里而不是变成卡顿。见 [PlayerBuffering.clampBitrate]。
 */
class ClampBitrateTest {

    @Test
    fun scalesEstimateDownByFactor() {
        // 10 Mbps 实测 × 0.8 = 8 Mbps 上限
        assertEquals(8_000_000L, PlayerBuffering.clampBitrate(10_000_000L, currentMax = 60_000_000L))
    }

    @Test
    fun neverRaisesAboveCurrentMax() {
        // 实测比当前上限还高（网络刚变好 / 估计抖高）：不抬上限，只封顶
        assertEquals(
            20_000_000L,
            PlayerBuffering.clampBitrate(30_000_000L, currentMax = 20_000_000L),
        )
    }

    @Test
    fun floorsAtMinimumUsableBitrate() {
        // 实测 100 kbps（估计还没量准 / Wi-Fi 瞬断）：压到下限为止，
        // 不然 ABR 会挑一条连音频都装不下的档
        val out = PlayerBuffering.clampBitrate(100_000L, currentMax = 60_000_000L)
        assertEquals(PlayerBuffering.MIN_ABR_BITRATE, out)
    }

    @Test
    fun negativeOrZeroEstimateLeavesCurrentMaxUnchanged() {
        // 带宽估计还没量出来（-1 / 0）时不做任何改动 —— 用旧上限继续播
        assertEquals(30_000_000L, PlayerBuffering.clampBitrate(-1L, currentMax = 30_000_000L))
        assertEquals(30_000_000L, PlayerBuffering.clampBitrate(0L, currentMax = 30_000_000L))
    }

    @Test
    fun resultIsMonotonicInEstimate() {
        // 实测越高上限越松（或不降）：同样的 currentMax 下，估计翻倍不应把上限压得更紧
        val low = PlayerBuffering.clampBitrate(2_000_000L, currentMax = 60_000_000L)
        val high = PlayerBuffering.clampBitrate(8_000_000L, currentMax = 60_000_000L)
        assertTrue(high >= low)
    }

    // ---------- releaseTarget：稳定够久之后放开到哪一档 ----------

    @Test
    fun `放开一步到实测的九成`() {
        // 原来这里是一条「每跳最多 +25%」的阶梯，绑在 500ms 轮询上，
        // 等于每秒放宽两次 —— 那就是画质鬼畜的来源。现在幅度一步到位，
        // 节奏交给 stableWindowMs / readyToRelease 管。
        assertEquals(9_000_000L, PlayerBuffering.releaseTarget(10_000_000L))
    }

    @Test
    fun `估计没量出来时放开值为零表示别动`() {
        // 这时候放开等于把上限抬到一个未知的位置
        assertEquals(0L, PlayerBuffering.releaseTarget(0L))
        assertEquals(0L, PlayerBuffering.releaseTarget(-1L))
    }

    @Test
    fun `估计极低也守住最低档`() {
        // 弱网估计可能抖到几十 kbps，跟着放开会让音轨都选不出来
        assertEquals(
            PlayerBuffering.MIN_ABR_BITRATE,
            PlayerBuffering.releaseTarget(100_000L),
        )
    }
}
