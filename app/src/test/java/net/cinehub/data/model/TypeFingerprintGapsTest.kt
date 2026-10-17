package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 类型归一的**新增**边界（[typeFingerprint]）。
 *
 * [TypeFingerprintTest] 已经钉了「漫剧 vs 连续剧」「同一档多种写法」「认不出来是 null」
 * 这几条主干，这里只补它没覆盖到的三块：
 * 1. 一个字段里同时出现两个可认档名时，**先出现的那个**赢（分段扫描），
 *    以及跨越别名表顺序的重叠字段由谁赢；
 * 2. 分隔符全集（`/`、`|`、`、`、全角逗号）以及连写字段；
 * 3. 单字别名永远不许命中 —— 别名表里只要溜进一个「剧」，整条链就崩了。
 */
class TypeFingerprintGapsTest {

    @Test
    fun firstRecognisablePartWinsByPositionNotByTableOrder() {
        // 分段是**按位置**扫的：先出现的可认档名先算，不是按别名表的先后。
        // 这很要紧 —— 「剧情,电影,短剧」这种字段里，位置在前的才是后端真正想标的那个，
        // 若实现改成「全表轮一遍」，同一份数据里的档名会随别名表顺序漂移。
        assertEquals("电影在前就先算电影", "电影", typeFingerprint("剧情,电影,短剧"))
        assertNotEquals("不能因为短剧排在别名表更前面就改判", "短剧", typeFingerprint("剧情,电影,短剧"))
        assertEquals("位置换过来就换成短剧", "短剧", typeFingerprint("短剧,电影"))
    }

    @Test
    fun overlappingFieldFollowsAliasTableOrderForTheWholeField() {
        // 「体育纪录片」同时含「体育」与「纪录片」两个档名。别名表里纪录片在前面，
        // 于是整段折成纪录片 —— 这是**更保守**的一档：真人拍的体育纪录片本来就是纪录片，
        // 折宽了最坏是「同一部片两档写法」，折窄了是把同一部片拆成两行。
        // 这条钉住别名表顺序 —— 顺序一调，重叠字段的判定就跟着变。
        assertEquals("纪录片在表里更靠前", "纪录片", typeFingerprint("体育纪录片"))
        assertEquals("不重叠的字段照旧各归各档", "体育", typeFingerprint("体育赛事"))
    }

    @Test
    fun slashAndPipeSeparateTagsToo() {
        // 【新增覆盖】现有用例只测了半角逗号与全角逗号。后端与站点实际还用 / 和 | 分隔
        // （「剧情/动作片」「悬疑|纪录」）。少认一种分隔符，整段就认不出任何档名 ——
        // 于是判「是不是同一部片」时这一边变成「没写」，该分家的两条被并成一条。
        assertEquals("斜杠分隔", "电影", typeFingerprint("剧情/动作片"))
        assertEquals("竖线分隔", "纪录片", typeFingerprint("剧情|悬疑|纪录"))
        assertEquals("顿号分隔", "连续剧", typeFingerprint("剧情、犯罪、国产剧"))
        assertEquals("全角逗号分隔", "短剧", typeFingerprint("剧情，犯罪，短剧"))
        assertEquals("多种分隔符混着用", "连续剧", typeFingerprint("剧情,犯罪|悬疑、国产剧"))
    }

    @Test
    fun concatenatedFieldIsTriedWhole() {
        // 有些源不写分隔符，「国产连续剧」这样连着写。分段只会走出一段，
        // 但整段里含「连续剧」，照样要认出来 —— 认不出就等于这个源没写类型。
        assertEquals("连写也要认", "连续剧", typeFingerprint("国产连续剧"))
        assertEquals("连写的漫剧也要认", "漫剧", typeFingerprint("国产动漫"))
    }

    @Test
    fun oneCharAliasCanNeverMatch() {
        // 别名一律两个汉字以上是硬约束：「剧」是单字，它一旦能命中，
        // 「漫剧」就会被折成连续剧 —— 而「漫剧 vs 连续剧」正是这个模块存在的理由。
        // 这几条把「单字不成词」写成断言，以后有人往别名表里加「剧」会当场响。
        assertNull("单字「剧」不能认成任何一档", typeFingerprint("剧"))
        assertNull("单字「画」不能认成任何一档", typeFingerprint("画"))
        assertNull("单字「视」不能认成任何一档", typeFingerprint("视"))
        // 只有分隔符、没有词：也不能凭空造出一档
        assertNull("只有分隔符要返回 null", typeFingerprint("///"))
    }

    @Test
    fun extraAliasSpellingsFoldToTheSameTier() {
        // 现有用例漏掉的几个别名写法。少一个写法，那个源的同名条目就会因为
        // 「一方没写类型」而走「不拦」，与另一档合成一行 —— 看到的就是线路少了。
        assertEquals("微短剧 → 短剧", "短剧", typeFingerprint("微短剧"))
        assertEquals("竖屏 → 短剧", "短剧", typeFingerprint("竖屏"))
        assertEquals("真人秀 → 综艺", "综艺", typeFingerprint("真人秀"))
        assertEquals("脱口秀 → 综艺", "综艺", typeFingerprint("脱口秀"))
        assertEquals("赛事 → 体育", "体育", typeFingerprint("赛事"))
        assertEquals("剧集 → 连续剧", "连续剧", typeFingerprint("剧集"))
    }

    @Test
    fun aliasMatchingIsSubstringSoLongerWordsContainingItAlsoHit() {
        // 【当前行为】matchAlias 用的是 text.contains(alias)，不是相等：
        // 「欧美剧」里含「美剧」，于是折成连续剧；「动画片」里含「动画」，于是折成漫剧
        // （不是电影 —— 电影那档里刻意只收「剧情片/动作片/喜剧片」这种具体写法，不收光板的「片」）。
        // 子串匹配是刻意的（要兼容「国产连续剧」这种连写），代价就是这两条边界。
        assertEquals("欧美剧命中别名「美剧」", "连续剧", typeFingerprint("欧美剧"))
        assertEquals("动画片命中别名「动画」", "漫剧", typeFingerprint("动画片"))
    }

    @Test
    fun tableOutSpellingsStillReturnNullNotTheInput() {
        // 认不出来是「没有信号」（null），**不是返回原文** —— 这一条上面那组已有用例讲过，
        // 这里补两个具体形状，防的是有人把「认不出就原样返回」当成顺手优化：
        // 那样每个源各发明一套档名，写法稍有差别就把本来对得上的片拆成两行。
        assertNull("表外的类型要给 null", typeFingerprint("科幻"))
        assertNull("表外的类型要给 null（含片字但不是档名）", typeFingerprint("文艺片"))
        assertNull("表外的英文类型要给 null", typeFingerprint("Action"))
        assertNotEquals("绝不能把原文当指纹返回", "文艺片", typeFingerprint("文艺片"))
    }
}
