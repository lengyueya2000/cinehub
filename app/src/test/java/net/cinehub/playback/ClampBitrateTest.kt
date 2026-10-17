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

    // ---------- releaseBitrate：网络恢复后逐步放开 ----------

    @Test
    fun releaseRaisesTowardNinetyPercentOfEstimate() {
        // 实测 10 Mbps、上限 2 Mbps：目标 9 Mbps，但单步最多放宽 25% → 2.5 Mbps
        assertEquals(2_500_000L, PlayerBuffering.releaseBitrate(currentMax = 2_000_000L, estimate = 10_000_000L))
    }

    @Test
    fun releaseStopsAtNinetyPercentOfEstimate() {
        // 上限已经很接近实测：直接到 90% 为止，不越过去
        assertEquals(
            9_000_000L,
            PlayerBuffering.releaseBitrate(currentMax = 8_500_000L, estimate = 10_000_000L),
        )
    }

    @Test
    fun releaseNeverLowersTheCap() {
        // 实测忽然抖低：放宽路径不往下走（往下压是 clampBitrate 在重缓冲时干的事）
        assertEquals(
            8_000_000L,
            PlayerBuffering.releaseBitrate(currentMax = 8_000_000L, estimate = 1_000_000L),
        )
    }

    @Test
    fun releaseWithoutEstimateLeavesCapUnchanged() {
        assertEquals(4_000_000L, PlayerBuffering.releaseBitrate(currentMax = 4_000_000L, estimate = 0L))
        assertEquals(4_000_000L, PlayerBuffering.releaseBitrate(currentMax = 4_000_000L, estimate = -1L))
    }
}
