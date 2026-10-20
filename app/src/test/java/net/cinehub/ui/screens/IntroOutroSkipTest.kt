package net.cinehub.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 跳过片头 / 片尾的两个判据。
 *
 * 它们埋在 500ms 的轮询里，出错全是静默的 —— 用户不会知道「这一集为什么从 90 秒开始播」
 * 或「为什么刚点进去就播完了」。抽成纯函数钉住，就是钉住那几条最容易说错话的边界：
 * 时长没解出来不许跳（跳了就是把人扔到不存在的秒数上）、
 * 位置已过那一格不许跳（跳了就是往回倒带）、
 * 整集不足三倍不许跳（跳了等于把一集短片跳没）、
 * 拖进片尾区不算播进片尾区（那是用户自己拖的，播放器不该替他按结束）。
 */
class IntroOutroSkipTest {

    // ---------- 片头 ----------

    @Test
    fun introSkipsToTheConfiguredSecond() {
        assertEquals(90L, introSkipTarget(durationSec = 2700L, skipSec = 90, posSec = 5L))
    }

    @Test
    fun zeroMeansOff() {
        assertNull(introSkipTarget(durationSec = 2700L, skipSec = 0, posSec = 5L))
    }

    @Test
    fun unknownDurationNeverJumps() {
        // 时长没解出来时 player.duration 是 C.TIME_UNSET（负的哨兵）。
        // 拿它当 0 再判「0 <= 90*3」也恰好不跳，但那是在赌上游不改 —— 直接钉住负数这一条。
        assertNull(introSkipTarget(durationSec = -9223372036854775807L, skipSec = 90, posSec = 5L))
        assertNull(introSkipTarget(durationSec = 0L, skipSec = 90, posSec = 5L))
    }

    @Test
    fun pastTheMarkNeverJumps() {
        // 续播到第 18 分钟进来、或用户自己拖过了那一格：都不许再动。
        // 只看续播点不看当前位置的话，「时长晚一拍才解出来」那一刻会把人从第 2 分钟
        // 往回拖到 90 秒 —— 那不叫跳过片头，叫倒带。
        assertNull(introSkipTarget(durationSec = 2700L, skipSec = 90, posSec = 90L))
        assertNull(introSkipTarget(durationSec = 2700L, skipSec = 90, posSec = 1080L))
    }

    @Test
    fun oneSecondBeforeTheMarkStillJumps() {
        // 差一秒就不跳、下一拍才跳，等于让画面在 89 秒卡一下再弹到 90 —— 边界上宁可干脆
        assertEquals(90L, introSkipTarget(durationSec = 2700L, skipSec = 90, posSec = 89L))
    }

    @Test
    fun shortEpisodeIsNeverSkipped() {
        // 整集不足三倍就放过：45 秒的短片设着 90 秒的跳过，片头吃掉一份、
        // 片尾再吃掉一份，中间剩不了几秒 —— 那等于把这一集跳没了。
        assertNull(introSkipTarget(durationSec = 45L, skipSec = 90, posSec = 0L))
        assertNull(introSkipTarget(durationSec = 270L, skipSec = 90, posSec = 0L)) // 正好三倍：仍不跳
        assertEquals(90L, introSkipTarget(durationSec = 271L, skipSec = 90, posSec = 0L))
    }

    // ---------- 片尾 ----------

    @Test
    fun outroFiresOnlyWhenPlayedIntoTheTail() {
        // 上一拍还在区域外、这一拍进了区域、两拍之间只挪了一两秒 = 顺着播进来的
        assertEquals(2700L, outroSkipTarget(posSec = 2611L, prevPosSec = 2609L, durationSec = 2700L, skipSec = 90))
    }

    @Test
    fun draggedIntoTheTailDoesNotFire() {
        // 用户自己拖到片尾附近：他想在那儿看，播放器不该替他按结束。
        // 一拍之间挪了超过两秒就是拖过来的（轮询半秒一跳、最快 2 倍速也只挪一秒）。
        assertNull(outroSkipTarget(posSec = 2650L, prevPosSec = 300L, durationSec = 2700L, skipSec = 90))
    }

    @Test
    fun slowerCadenceDoesNotFoolTheDragHeuristic() {
        // 轮询那一跳被 GC / 切档拖长了：500ms 变 2.5 秒，2 倍速下「该挪 5 秒」。
        // 判据要是写死 2 秒，这一下就成人被拖过来的了 —— 这一集的片尾再也不跳。
        // 所以阈值由调用方按「隔了多久 × 倍速」给，这里钉住两种给法：
        // 写死的小阈值仍然拦得住真拖拽，放宽的阈值放正常播放过去。
        assertNull(outroSkipTarget(2650L, 300L, 2700L, 90, maxAdvanceSec = 2L))
        // 上一拍还在区域外（2605 < 2610）、这一拍刚进区域、挪了 6 秒 < 阈值 6
        assertEquals(2700L, outroSkipTarget(2611L, 2605L, 2700L, 90, maxAdvanceSec = 6L))
        // 同样的 6 秒，阈值收紧到 2 就仍然是「像被拖过来的」
        assertNull(outroSkipTarget(2611L, 2605L, 2700L, 90, maxAdvanceSec = 2L))
    }

    @Test
    fun firstSampleNeverFiresOutro() {
        // 没有上一拍（-1）分不清是播进来的还是拖进来的，也不该拿「刚进来就在片尾区」
        // 当证据 —— 那可能正是用户从上一集的进度拖过来的
        assertNull(outroSkipTarget(posSec = 2650L, prevPosSec = -1L, durationSec = 2700L, skipSec = 90))
    }

    @Test
    fun outroRespectsOffAndShortEpisodes() {
        assertNull(outroSkipTarget(posSec = 2650L, prevPosSec = 2649L, durationSec = 2700L, skipSec = 0))
        // 短片同样放过：不然一集 100 秒的片子一进来就「播完了」
        assertNull(outroSkipTarget(posSec = 95L, prevPosSec = 94L, durationSec = 100L, skipSec = 90))
    }

    @Test
    fun unknownDurationNeverFiresOutro() {
        assertNull(outroSkipTarget(posSec = 5L, prevPosSec = 4L, durationSec = 0L, skipSec = 90))
        assertNull(outroSkipTarget(posSec = 5L, prevPosSec = 4L, durationSec = -1L, skipSec = 90))
    }

    @Test
    fun boundaryAtTheEdgeCountsAsInside() {
        // 正好落在边界上算进了那片区域（差一秒就不跳，然后下一拍才跳，纯属抖一下）
        assertEquals(2700L, outroSkipTarget(posSec = 2610L, prevPosSec = 2609L, durationSec = 2700L, skipSec = 90))
    }

    @Test
    fun scrubbingIntoTheTailKeepsItSuppressedAfterwards() {
        // 拖进片尾区之后：那一拍被「挪得太多」挡住，而 prevPos 落在区域里，
        // 于是后面每一拍都因 prevPosSec >= edge 继续被挡 —— 用户停在那儿看，就不该被打断。
        assertNull(outroSkipTarget(2650L, 300L, 2700L, 90))
        assertNull(outroSkipTarget(2651L, 2650L, 2700L, 90))
        // 但往回拖出去再顺着播进来，是正常播放：该跳
        assertEquals(2700L, outroSkipTarget(2611L, 2609L, 2700L, 90))
    }

    @Test
    fun resumeFromInsideTheTailNeverEndsTheEpisode() {
        // 从上次看到的地方接着播，而那个地方就在片尾区：第一拍 prevPos = -1 被挡，
        // 之后就一直是「上一拍也在区域里」—— 不会刚进去就「播完了」。
        assertNull(outroSkipTarget(2650L, -1L, 2700L, 90))
        assertNull(outroSkipTarget(2651L, 2650L, 2700L, 90))
    }

    // ---------- 倍速读数与滑杆步进 ----------

    @Test
    fun speedLabelKeepsTheFifthOfAStep() {
        // 1.05f 存成 float 是 1.04999…：拿它直接 trimEnd('0') 会印成「1」，
        // 而 0.05 一格正是自定义滑杆的步进 —— 拖到那一格读数必须说 1.05。
        assertEquals("1.05", fmtSpeed(1.05f))
        // 百分位是 0 的走一位小数：1.1 不印成 1.10（那一位 0 是噪声）
        assertEquals("1.1", fmtSpeed(1.1f))
        assertEquals("1.75", fmtSpeed(1.75f))
        assertEquals("2.35", fmtSpeed(2.35f))
    }

    @Test
    fun speedLabelDropsTheUselessZeroOnWholeTenths() {
        assertEquals("0.5", fmtSpeed(0.5f))
        assertEquals("1.0", fmtSpeed(1f))
        assertEquals("1.3", fmtSpeed(1.3f))
        assertEquals("3.0", fmtSpeed(3f))
    }

    @Test
    fun speedLabelSurvivesFloatNoiseAroundATick() {
        // 滑杆 quantise 到 1/20 之后落点可能带一点点浮点毛刺，读数不能跟着抖
        assertEquals("1.05", fmtSpeed(1.05f * 0.9999999f + 0.0000001f))
        assertEquals("1.5", fmtSpeed(1.5f - 1e-7f))
    }

    @Test
    fun sliderQuantisesToAFifth() {
        // 自定义滑杆的 onValueChange：拖到任意像素都归到 0.05 的整数倍，
        // 不然手指停在一个没读数的地方，用户说不清自己设了几倍速。
        // （归整只在「要归」的时候动手：0.4、0.75 本来就在格上，不许被推走。）
        fun q(v: Float) = ((v * 20f).roundToInt() / 20f)
        assertEquals(1.05f, q(1.049f), 1e-6f)
        assertEquals(1.15f, q(1.141f), 1e-6f)
        assertEquals(1.5f, q(1.4999f), 1e-6f)
        assertEquals(0.75f, q(0.75f), 1e-6f)
        assertEquals(0.4f, q(0.4f), 1e-6f)
        // 3.2 本来就在格上（64/20），不许被推走；真正带毛刺的才归
        assertEquals(3.2f, q(3.2f), 1e-6f)
        assertEquals(3.2f, q(3.21f), 1e-6f)
    }
}
