package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 用**真实后端数据**钉住同名条目的归并行为。
 *
 * 这一组不是我自己编的样本：`漫剧/剧情,犯罪,短剧` 与 `连续剧/悬疑,犯罪,剧情,国产`
 * 是 MINO 后端「余红旧事」2026 那两条同名条目的原样字段（vod 1840029 与 1592969）。
 * 影枢原来只按「标题指纹 + 年份」归并，两条落进同一个桶，后一条被整个丢掉 ——
 * 用户看到的就是「官方源明明有 3 条 4K（蓝光4K/4K/小艺），影枢里只有 3 条 1080P」。
 */
class SameTitleTypeSplitTest {

    /** 1840029：漫剧，3 条 1080P 线路。 */
    private fun manhua() = Media(
        id = "1840029",
        title = "余红旧事",
        year = "2026",
        category = "漫剧",
        sourceId = "minoself",
    )

    /** 1592969：连续剧，蓝光4K / 4K / 小艺。 */
    private fun series() = Media(
        id = "1592969",
        title = "余红旧事",
        year = "2026",
        category = "连续剧",
        sourceId = "minoself",
    )

    @Test
    fun twoSameTitleEntriesDifferOnlyByType() {
        // 标题与年份完全一样：只按 Media.key 判，这两条是同一个东西
        assertEquals(manhua().key, series().key)
        // 类型是唯一能把它们分开的那一维
        assertNotEquals(typeFingerprint(manhua().category), typeFingerprint(series().category))
    }

    @Test
    fun manifestTypesNormalizeToDifferentTiers() {
        // 两条的原样字段。适配器取的是 type_name（认得出），vod_class 只是备选：
        //   1840029 → type_name=漫剧      vod_class=剧情,犯罪,短剧
        //   1592969 → type_name=连续剧    vod_class=悬疑,犯罪,剧情,国产
        assertEquals("漫剧", typeFingerprint(manhua().category))
        assertEquals("连续剧", typeFingerprint(series().category))
        assertNotEquals(typeFingerprint(manhua().category), typeFingerprint(series().category))
        // 备选字段也认一次：没有 type_name 的源会落到这里
        assertEquals("短剧", typeFingerprint("剧情,犯罪,短剧"))
        // 「悬疑,犯罪,剧情,国产」里没有能认的档名（「国产」是地区不是档名）——
        // 认不出来就是**没有信号**（null），由调用方按「不拦」处理。
        // 这里刻意不硬猜成连续剧：猜错了会把「剧情,国产」的电影和「电影」拆成两行。
        assertNull(typeFingerprint("悬疑,犯罪,剧情,国产"))
    }

    @Test
    fun mediaKeyAloneCannotSeparateThemWhichIsTheBug() {
        // 把 bug 的形状写下来：旧判据（标题+年份）对两条都返回 true，
        // 所以同一个源的两条结果里只会留下先回来的那一条。
        fun oldJudge(a: Media, b: Media): Boolean =
            titleFingerprint(a.title) == titleFingerprint(b.title) &&
                a.key.substringAfter('|', "") == b.key.substringAfter('|', "")

        assertEquals(true, oldJudge(manhua(), series()))
    }

    @Test
    fun newJudgeSeparatesThemByType() {
        // 新判据：加上类型归一比一次，两条就分开了 —— 这正是「官方 4K 线路能出现」的前提
        fun newJudge(a: Media, b: Media): Boolean {
            val ta = typeFingerprint(a.category)
            val tb = typeFingerprint(b.category)
            // 与 MediaRepository.sameWork 一致：有一方没信号就不拦
            if (ta == null || tb == null) return true
            return ta == tb
        }

        assertEquals(false, newJudge(manhua(), series()))
        // 同一条自己和自己比仍然算同一部
        assertEquals(true, newJudge(manhua(), manhua()))
        assertEquals(true, newJudge(series(), series()))
    }
}
