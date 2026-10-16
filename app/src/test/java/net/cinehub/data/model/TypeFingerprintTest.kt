package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 类型归一的验证。
 *
 * 起因是一条实测出来的真事：后端里「余红旧事」2026 有两条同名条目 ——
 * 一条 `漫剧`（3 条 1080P 线路）、一条 `连续剧`（蓝光4K / 4K / 小艺）。
 * 只按「同名同年」归并会把两条合成一条，用户看到的就是
 * 「官方源明明有 3 条 4K，影枢里只有 3 条 1080P」。
 *
 * 这一组用例钉的是两件事：**该分的分得开**（漫剧 vs 连续剧），
 * 以及**不该分的不许分**（同一档的各种写法、认不出来的原样比）。
 */
class TypeFingerprintTest {

    @Test
    fun manhuaAndSeriesAreDifferent() {
        // 这是这次要修的那一条
        assertNotEquals(typeFingerprint("漫剧"), typeFingerprint("连续剧"))
    }

    @Test
    fun manhuaIsNotSwallowedBySeries() {
        // 「漫剧」含「剧」字：别名表里要是有单字「剧」，它就会被折成连续剧，
        // 而这一项正是要拦的那个 —— 这条用例专门钉住它
        assertEquals("漫剧", typeFingerprint("漫剧"))
        assertNotEquals("连续剧", typeFingerprint("漫剧"))
    }

    @Test
    fun sameTierDifferentWritingsAgree() {
        assertEquals(typeFingerprint("连续剧"), typeFingerprint("电视剧"))
        assertEquals(typeFingerprint("连续剧"), typeFingerprint("国产剧"))
        assertEquals(typeFingerprint("连续剧"), typeFingerprint("美剧"))
        assertEquals(typeFingerprint("漫剧"), typeFingerprint("动漫"))
        assertEquals(typeFingerprint("漫剧"), typeFingerprint("番剧"))
        assertEquals(typeFingerprint("电影"), typeFingerprint("院线"))
        assertEquals(typeFingerprint("纪录片"), typeFingerprint("纪录"))
    }

    @Test
    fun multiTagFieldPicksTheRecognisableOne() {
        // 后端一个字段里塞好几个标签是常态（「剧情,犯罪,短剧」）
        assertEquals("短剧", typeFingerprint("剧情,犯罪,短剧"))
        assertEquals("连续剧", typeFingerprint("悬疑,犯罪,剧情,国产剧"))
        assertEquals("漫剧", typeFingerprint("漫剧,奇幻"))
    }

    @Test
    fun unknownTypeReturnsNullSoItNeverCausesASplit() {
        // 认不出来 = 没有信号（null），不是返回原文。与年份那条规矩一致：
        // 语义是「这一边没说」，调用方按「不拦」处理 —— 少拦一次，
        // 好过拿两个源各写一套的档名去把本来对得上的片子拆成两行。
        assertNull(typeFingerprint("某个没见过的档"))
        assertNull(typeFingerprint("科幻"))
        assertNotEquals("连续剧", typeFingerprint("某个没见过的档"))
    }

    @Test
    fun blankIsNullAndNullOnlyMatchesNull() {
        assertNull(typeFingerprint(null))
        assertNull(typeFingerprint(""))
        assertNull(typeFingerprint("   "))
        // 两边都没有 → 由调用方按「不拦」处理（见 MediaRepository.sameWork）
    }

    @Test
    fun whitespaceAndCaseAreTolerated() {
        assertEquals(typeFingerprint("连续剧"), typeFingerprint("  连续剧  "))
        // 返回的是**归一后的档名**，不是别名原文
        assertEquals("漫剧", typeFingerprint("动画"))
    }
}
