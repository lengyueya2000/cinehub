package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质档（[qualityTagOf]）与归并键（[Media.key]）的验证。
 *
 * 这两件事一头一尾：「画质档」告诉用户这个源自己说它是几档，
 * 「归并键」决定两个源的同一条目会不会被认成同一部片。两者出错的表现都是
 * 「同一部片、同一屏、两句话」。
 */
class QualityAndKeyTest {

    /** 只给需要的那几个字段，其余保持默认 —— 用例正文才看得清测的是哪一场。 */
    private fun media(
        title: String = "暗影蜘蛛侠",
        year: String? = null,
        category: String? = null,
        remark: String? = null,
    ) = Media(
        id = "1",
        title = title,
        year = year,
        category = category,
        remark = remark,
        sourceId = "test",
    )

    // ---------- qualityTagOf ----------

    @Test
    fun bothFourKAndHdrCombine() {
        // 站点写「4K HDR」时界面要原样给出这一档。只报 4K 会把 HDR 这条信息丢掉，
        // 而对 HDR 片源来说 HDR 恰恰是用户挑源看的那一维。
        assertEquals("remark 里的 4K HDR", "4K HDR", qualityTagOf(media(remark = "4K HDR")))
        // 两个信号分散在不同字段里也要合并：remark 说 2160、title 挂 HDR10
        assertEquals("跨字段合并", "4K HDR", qualityTagOf(media(remark = "2160P", title = "暗影蜘蛛侠 HDR10")))
    }

    @Test
    fun fourKBeatsHdrWhichBeatsBluray() {
        // 顺序是 4K > HDR > 蓝光 > 1080 > 720：站点经常一次给好几个词
        // （「蓝光4K」），取错一档，同一部片在详情页和播放器面板上就会显示两种画质。
        assertEquals("只有 4K", "4K", qualityTagOf(media(remark = "4K")))
        assertEquals("2160 也算 4K", "4K", qualityTagOf(media(remark = "2160P")))
        assertEquals("大小写不敏感", "4K", qualityTagOf(media(remark = "4k")))
        assertEquals("蓝光4K 里 4K 赢", "4K", qualityTagOf(media(remark = "蓝光4K")))
        assertEquals("只有 HDR", "HDR", qualityTagOf(media(category = "HDR")))
        assertEquals("HDR 赢蓝光", "HDR", qualityTagOf(media(remark = "蓝光 HDR")))
        assertEquals("只有蓝光", "蓝光", qualityTagOf(media(remark = "蓝光")))
    }

    @Test
    fun blurayBeatsResolutionNumbers() {
        // 站点常常「蓝光 1080P」一起写。蓝光是发行规格、1080 只是分辨率，
        // 报 1080P 等于把更好的那个说法丢了。
        assertEquals("蓝光赢 1080", "蓝光", qualityTagOf(media(remark = "蓝光 1080P")))
        assertEquals("蓝光赢 720", "蓝光", qualityTagOf(media(remark = "蓝光 720P")))
        assertEquals("只有 1080", "1080P", qualityTagOf(media(remark = "1080P")))
        assertEquals("1080 赢 720", "1080P", qualityTagOf(media(remark = "1080P 720P")))
        assertEquals("只有 720", "720P", qualityTagOf(media(remark = "720P")))
    }

    @Test
    fun qualityIsReadFromRemarkCategoryAndTitleOnly() {
        // 只读站点给的这三个字段。别的字段里出现「4K」不算数 ——
        // 简介、演员表里出现「4K」纯属巧合，照它标画质等于凭空发明一个上限，
        // 而这正是 Models.kt 注释里点名要禁的那件事。
        assertNull(
            "简介/演员/年份里的 4K 不算信号",
            qualityTagOf(
                Media(
                    id = "1",
                    title = "暗影蜘蛛侠",
                    year = "2160",
                    actors = "4K",
                    intro = "本片有 4K 修复版",
                    sourceId = "test",
                ),
            ),
        )
        // 反过来：title 里带画质是允许的（有的源就把画质写在片名尾巴上）
        assertEquals("title 里的 4K 算信号", "4K", qualityTagOf(media(title = "暗影蜘蛛侠(2160P)")))
        assertEquals("category 里的蓝光算信号", "蓝光", qualityTagOf(media(category = "蓝光")))
    }

    @Test
    fun noSignalReturnsNullInsteadOfInventingACap() {
        // 没有信号就返回 null（界面不显示这一枚 pill），绝不写死一个上限。
        // 站点没说的话，影枢替它说「只有 720P」是造谣。
        assertNull("三个字段都没有", qualityTagOf(media()))
        assertNull("都是与画质无关的字", qualityTagOf(media(remark = "小艺", category = "连续剧")))
        assertNull("高清不等于 4K/蓝光", qualityTagOf(media(remark = "高清")))
        assertNull("HD 不是 HDR", qualityTagOf(media(remark = "HD")))
        assertNull("更新至 34 集里没有画质", qualityTagOf(media(remark = "更新至34集")))
    }

    // ---------- Media.key ----------

    @Test
    fun noYearMeansNoPipe() {
        // 没有年份时键就是归一化片名，尾巴上不该多一个空的分隔符 ——
        // 多了它就与「有年份」的写法永远不会相等，跨源归并直接失效。
        val k = media(title = "暗影蜘蛛侠 黑白版").key
        assertEquals("无年份 → 只有归一化片名", "暗影蜘蛛侠黑白版", k)
        assertFalse("无年份不该出现分隔符", k.contains('|'))
    }

    @Test
    fun yearIsAppendedAfterAPipe() {
        assertEquals("有年份 → 片名|年份", "暗影蜘蛛侠黑白版|2026", media(title = "暗影蜘蛛侠（黑白版）", year = "2026").key)
    }

    @Test
    fun fullDateYearIsTruncatedToFourChars() {
        // 有的源给的是完整上映日期（「2026-05-01」），有的只给年份。
        // 不截断的话这两种写法永远对不上，同一部片会被拆成两行、收藏存两份。
        assertEquals("完整日期截成 4 位", "暗影蜘蛛侠|2026", media(year = "2026-05-01").key)
        assertEquals("与只写年份的写法相同", media(year = "2026").key, media(year = "2026-05-01").key)
        assertEquals("八位数字也截成 4 位", "暗影蜘蛛侠|2026", media(year = "20260501").key)
    }

    @Test
    fun punctuationOnlyDifferenceStillGivesTheSameKey() {
        // 这就是 Models.kt 注释里那三个跨源写法：归并键必须相同，
        // 否则「暗影蜘蛛侠 黑白版」在 A 源、B 源各成一行。
        val a = media(title = "暗影蜘蛛侠 黑白版", year = "2026").key
        val b = media(title = "暗影蜘蛛侠（黑白版）", year = "2026").key
        val c = media(title = "暗影蜘蛛侠：黑白版", year = "2026").key
        assertEquals("空格版与括号版同键", a, b)
        assertEquals("括号版与冒号版同键", b, c)
    }

    @Test
    fun differentYearGivesDifferentKey() {
        // 年份不同就是另一条记录；同键会把两季/两版写进同一份观看进度。
        assertNotEquals("年份不同键就不同", media(year = "2026").key, media(year = "2025").key)
    }

    @Test
    fun pipeInsideTitleMakesTheKeyAmbiguous() {
        // 【已知局限，不是本次要改的】标题里带 '|' 时，归并键会与「片名 + 年份」撞车：
        // 下面两条一条是「片名尾巴带 |2026、没年份」，一条是「片名不带、年份 2026」，
        // 键却完全相同。下游 MediaRepository.yearOf 用 substringAfter('|') 取年份，
        // 于是前一条会被读成「有年份 2026」—— 年份比对因此可能给出一条错误的结论。
        // 这里按**当前行为**钉住它；将来把分隔符换成不会出现在片名里的字符，这条用例要一起改。
        val titleCarriesPipe = media(title = "暗影蜘蛛侠|2026", year = null).key
        val yearIsReal = media(title = "暗影蜘蛛侠", year = "2026").key
        assertEquals("标题里的 | 与真正的年份分隔符撞成同一个键（已知局限）", "暗影蜘蛛侠|2026", titleCarriesPipe)
        assertEquals("两条本不该是同一部，键却一样", yearIsReal, titleCarriesPipe)
        // 附带记录：下游按 substringAfter('|') 取到的「年份」是标题的一部分
        assertEquals("下游会把标题尾巴读成年份", "2026", titleCarriesPipe.substringAfter('|', ""))
        assertTrue("键里确实出现了分隔符", titleCarriesPipe.contains('|'))
    }
}
