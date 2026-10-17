package net.cinehub.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重缓冲之后什么时候允许把码率上限放开。
 *
 * 这里换掉了一版会自己造出「画质鬼畜」的写法：原来放开绑在 500ms 的轮询上，
 * 每跳抬 25%。后果是一条自激回路 —— 压下去 → ABR 降档（糊）→ 出口不再被占满 →
 * 带宽估计上涨 → 放开循环几秒内把上限抬回高位 → ABR 挑高档（清楚）→ 又占满 → 又卡。
 * 每秒抖两轮画质，用户看到的是清楚与模糊来回跳。
 *
 * 现在的规矩：压下去之后要**连续稳定播放**够久才放开一次，而且每多卡一次，
 * 这个窗口翻倍 —— 网络本来就在临界点上时，抖动越来越稀，而不是持续抽搐。
 */
class StableReleaseTest {

    @Test
    fun `第一次卡住要稳定二十秒才放开`() {
        assertEquals(20_000L, PlayerBuffering.stableWindowMs(1))
    }

    @Test
    fun `每多卡一次窗口翻倍`() {
        assertEquals(40_000L, PlayerBuffering.stableWindowMs(2))
        assertEquals(80_000L, PlayerBuffering.stableWindowMs(3))
        assertEquals(160_000L, PlayerBuffering.stableWindowMs(4))
    }

    @Test
    fun `窗口有上限不会无限翻倍`() {
        // 翻到十几分钟才放开等于这一场就不打算再给高画质了
        assertEquals(PlayerBuffering.MAX_STABLE_MS, PlayerBuffering.stableWindowMs(5))
        assertEquals(PlayerBuffering.MAX_STABLE_MS, PlayerBuffering.stableWindowMs(30))
    }

    @Test
    fun `没卡过就没有窗口`() {
        // 0 次重缓冲时不该有「放开」这回事（上限本来就没被压过）
        assertEquals(0L, PlayerBuffering.stableWindowMs(0))
    }

    @Test
    fun `负数按没卡过处理`() {
        assertEquals(0L, PlayerBuffering.stableWindowMs(-3))
    }

    @Test
    fun `够久才放开`() {
        val window = PlayerBuffering.stableWindowMs(1)
        assertEquals(
            false,
            PlayerBuffering.readyToRelease(clamped = true, stableMs = window - 1, rebuffers = 1),
        )
        assertEquals(
            true,
            PlayerBuffering.readyToRelease(clamped = true, stableMs = window, rebuffers = 1),
        )
    }

    @Test
    fun `卡过两次就要稳定更久`() {
        // 同一段稳定时长，第一次够、第二次不够 —— 退避是真的在起作用
        val first = PlayerBuffering.stableWindowMs(1)
        assertEquals(true, PlayerBuffering.readyToRelease(clamped = true, stableMs = first, rebuffers = 1))
        assertEquals(false, PlayerBuffering.readyToRelease(clamped = true, stableMs = first, rebuffers = 2))
    }

    @Test
    fun `上限没被压过时不需要放开`() {
        // 这一条挡住「每 500ms 白写一次 trackSelectionParameters」：
        // 不封顶时反复重建参数会让 ABR 重新选档，本身就是一种抖动
        assertEquals(
            false,
            PlayerBuffering.readyToRelease(clamped = false, stableMs = Long.MAX_VALUE, rebuffers = 3),
        )
    }

    @Test
    fun `没卡过时即使稳定很久也不放开`() {
        assertEquals(
            false,
            PlayerBuffering.readyToRelease(clamped = true, stableMs = 999_999L, rebuffers = 0),
        )
    }
}
