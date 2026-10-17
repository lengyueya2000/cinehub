package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 兜底检索词的验证：[withoutSeason] 与 [titleSearchVariants]。
 *
 * 站点多是 `LIKE '%词%'`：片名写法一变就空手而归，于是详情页「播放源」少一格，
 * 而用户明明在源站上看得见这部片。所以「原样 → 去空格 → 去季号」这几档要依次试。
 *
 * 但每档都是一趟**全源扇出**，所以档数越少越好、顺序越窄越好 ——
 * 下面每条边界都是这两件事折出来的。
 */
class TitleSearchVariantsTest {

    @Test
    fun titleWithoutSpaceOrSeasonHasExactlyOneVariant() {
        // 标题既没空格也没季号时只剩「原样」一档。多发一趟注定落空的查询，
        // 换来的就是用户在详情页多等一个源的超时。
        assertEquals("无空格无季号 → 只有原样一档", listOf("暗影蜘蛛侠"), titleSearchVariants("暗影蜘蛛侠"))
    }

    @Test
    fun titleWithBothSpaceAndSeasonYieldsOrderedForms() {
        // 豆瓣爱写成「绝望写手 第五季」，源站存的是连写。顺序有意义：
        // 原样（最窄）→ 去空格 → 去季号（最宽），调用方试到命中就停；
        // 先试最宽的那档会捞回整季的片子再逐条否掉，白烧一遍全源请求。
        assertEquals(
            "应依次给出 原样 → 去空格 → 去季号",
            listOf("绝望写手 第五季", "绝望写手第五季", "绝望写手"),
            titleSearchVariants("绝望写手 第五季"),
        )
    }

    @Test
    fun seasonOnlyTitleYieldsTwoVariants() {
        // 没有空格、只有季号：第二档与第一档是同一个串（被 distinct 去掉），
        // 只剩「原样」与「去季号」两档。
        assertEquals("应给出 原样 → 去季号", listOf("绝望写手第五季", "绝望写手"), titleSearchVariants("绝望写手第五季"))
    }

    @Test
    fun whitespaceOnlyTitleDropsTheRedundantThirdForm() {
        // 只有空格、没有季号：去季号那档与去空格那档是同一个串，只该留一份 ——
        // 否则同一趟全源请求会原样发两遍。
        assertEquals(
            "第三档与第二档相同应被去掉",
            listOf("暗影蜘蛛侠 黑白版", "暗影蜘蛛侠黑白版"),
            titleSearchVariants("暗影蜘蛛侠 黑白版"),
        )
    }

    @Test
    fun variantsAreDistinct() {
        // 每档都是一趟全源请求，重复的档就是白烧一遍流量与时间。
        val v = titleSearchVariants("绝望写手 第五季")
        assertEquals("同一写法不能重复出现", v.distinct(), v)
    }

    @Test
    fun seasonStrippingShorterThanTwoCharsIsDropped() {
        // 片名本身就是「第五季」时，去季号那档是空串。空查询词发出去等于拉全站，
        // 拿回来的东西又全要被 sameWork 逐条否掉 —— 纯白跑一趟。
        assertEquals("削完不足 2 个字就不发这一档", listOf("第五季"), titleSearchVariants("第五季"))
    }

    @Test
    fun withoutSeasonKeepsPunctuation() {
        // 这是 withoutSeason 刻意**不**顺手去标点的原因：这些站点的 LIKE 恰恰
        // 匹配不了被削过标点的写法（见 Models.kt 与 siblingsOf 的说明）——
        // 「暗影蜘蛛侠（黑白版）」削成「暗影蜘蛛侠黑白版」之后一条都搜不到。
        assertEquals("全角括号原样留着", "暗影蜘蛛侠（黑白版）", withoutSeason("暗影蜘蛛侠（黑白版）"))
        assertEquals("冒号原样留着", "暗影蜘蛛侠：黑白版", withoutSeason("暗影蜘蛛侠：黑白版"))
    }

    @Test
    fun withoutSeasonKeepsWhitespace() {
        // 同理，空白也照留：源里存的那串可能真带空格，削了就匹不上。
        // （尾随空格用拼接写出来，免得编辑器把字面量里的空格吃掉。）
        assertEquals("季号前那个空格要留下", "绝望写手" + " ", withoutSeason("绝望写手 第五季"))
        assertEquals("连写的片名原样返回", "绝望写手", withoutSeason("绝望写手第五季"))
    }

    @Test
    fun withoutSeasonRemovesEverySeasonMarkerNotJustTheTail() {
        // 【当前行为】函数名与注释说的是「去掉尾巴」，实现是 Regex.replace：
        // 出现几次就删几次，也不限于末尾。钉住它 —— 以后若改成只削后缀，
        // 这条会响，别处依赖「全删」的地方也能一起看见。
        assertEquals("出现多次会全部删掉", "剧名", withoutSeason("剧名第一季第二季"))
    }
}
