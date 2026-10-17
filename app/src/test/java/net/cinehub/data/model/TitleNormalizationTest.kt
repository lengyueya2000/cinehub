package net.cinehub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 片名归一化（[normalizeTitle]）与季号指纹（[titleFingerprint]）的验证。
 *
 * 这两个函数决定「同一部片在两个源里算不算同一部」：归并键、收藏、观看记录、
 * 下载任务全都落在归一化后的片名上。归一不到位 = 同一部片被拆成两行、收藏存两份；
 * 归一过了头 = 两部不同的片被并成一条，后一条的线路被整个丢掉
 * （`AggregatedMedia.rowKey` 的注释里写的就是这个崩溃）。
 *
 * 下面每条注释写的是**它防的是哪一种真实坏法**，不是它做了什么。
 */
class TitleNormalizationTest {

    @Test
    fun crossSourceWritingsCollapseToTheSameTitle() {
        // 三个源对同一部片的三种写法（Models.kt 注释里点名的就是这一串）。
        // 只要有一种归一不到一起，跨源归并就会漏掉本该合并的条目 ——
        // 表现就是搜索卡片上挂着三个源徽标、点进去只剩两个。
        assertEquals("空格版应只削掉空白", "暗影蜘蛛侠黑白版", normalizeTitle("暗影蜘蛛侠 黑白版"))
        assertEquals("全角括号版应只削掉括号", "暗影蜘蛛侠黑白版", normalizeTitle("暗影蜘蛛侠（黑白版）"))
        assertEquals("冒号版应只削掉冒号", "暗影蜘蛛侠黑白版", normalizeTitle("暗影蜘蛛侠：黑白版"))
    }

    @Test
    fun everyKindOfWhitespaceIsRemoved() {
        // 源站返回的片名里混着首尾空格、制表、换行、全角空格是常态。
        // 少认一种，这个源的所有片名都会比别的源多一个字符，全都对不上。
        assertEquals("首尾半角空格、制表、换行都要削掉", "绝望写手第五季", normalizeTitle(" 绝望写手\t第五季\n"))
        assertEquals("全角空格 U+3000 也算空白", "绝望写手", normalizeTitle("\u3000绝望写手\u3000"))
    }

    @Test
    fun punctuationOutsideTheSetSurvives() {
        // 标点是白名单：只有 TITLE_PUNCT 里列出的那批被削。这里钉住边界 ——
        // 斜杠、加号、书名号都不在里面，原样保留。以后有人往 TITLE_PUNCT 里加字符，
        // 这条用例会先响。
        assertEquals("斜杠与加号不在标点集里", "钢之炼金术师/FA+剧场版", normalizeTitle("钢之炼金术师/FA+剧场版"))
        assertEquals("书名号《》不在标点集里（削的是全半角圆括号与方括号）", "《西游记》", normalizeTitle("《西游记》"))
        assertEquals("汉字当然原样保留", "暗影蜘蛛侠", normalizeTitle("暗影蜘蛛侠"))
    }

    @Test
    fun digitsAndLatinLettersAreNotPunctuation() {
        // 括号里的年份削掉括号后要留下数字：片名尾巴上的年份是各源写法差异最大的一维，
        // 归一化把数字一起吃了的话，年份就没法再参与比对。
        assertEquals("括号削掉、数字留着", "暗影蜘蛛侠2026", normalizeTitle("暗影蜘蛛侠（2026）"))
        // 反过来说：只剩标点的片名会归一成空串 —— 空串的语义是「没有可比的片名」，
        // 调用方不能拿它当有效检索词（那样等于拉全站）。
        assertEquals("纯标点归一成空串", "", normalizeTitle("：（）"))
    }

    @Test
    fun seasonNumeralWritingsAgree() {
        // 实测倒逼出来的那一条：懂片帝写「绝望写手 第5季」、4kku 写「绝望写手第五季」。
        // normalizeTitle 之后一个 5 一个五，两边谁也不认谁 —— 搜索页挂着两个源、
        // 详情页「播放源」只剩一个。
        assertEquals("阿拉伯数字的季号削完空白后就是归一形式", "绝望写手第5季", titleFingerprint("绝望写手 第5季"))
        assertEquals("中文数字的季号要折成阿拉伯数字", "绝望写手第5季", titleFingerprint("绝望写手第五季"))
        assertEquals("两种写法必须折成同一个指纹", titleFingerprint("绝望写手 第5季"), titleFingerprint("绝望写手第五季"))
    }

    @Test
    fun differentSeasonsStayDifferent() {
        // 第五→5、第六→6 必须折成不同数字：指纹是「认领哪一条」的判据。
        // 一旦把续集折成前作，第二季的观看进度会写进第五季的观看记录里。
        assertEquals("第五季折成 5", "绝望写手第5季", titleFingerprint("绝望写手第五季"))
        assertEquals("第六季折成 6", "绝望写手第6季", titleFingerprint("绝望写手第六季"))
        assertNotEquals("不同季不能折成同一个指纹", titleFingerprint("绝望写手第五季"), titleFingerprint("绝望写手第六季"))
    }

    @Test
    fun tensFoldCorrectly() {
        // 十位数的季号在长剧里很常见（第十季、第二十季）。折错一个数字，
        // 豆瓣片名与源站片名就永远对不上，跨源归并静默失效。
        assertEquals("第十季 → 第10季", "绝望写手第10季", titleFingerprint("绝望写手第十季"))
        assertEquals("第十二季 → 第12季", "绝望写手第12季", titleFingerprint("绝望写手第十二季"))
        assertEquals("第二十季 → 第20季", "绝望写手第20季", titleFingerprint("绝望写手第二十季"))
        assertEquals("与阿拉伯写法折成同一个", titleFingerprint("绝望写手第十季"), titleFingerprint("绝望写手第10季"))
    }

    @Test
    fun partMarkerFoldsLikeSeasonMarker() {
        // 正则同时认「季」和「部」：电影续集大多写「第二部」。
        // 只折季不折部，电影续集在跨源比对里照样对不上，而且不比剧集少见。
        assertEquals("第二部 → 第2部", "暗影蜘蛛侠第2部", titleFingerprint("暗影蜘蛛侠第二部"))
        assertEquals("两种写法同一个指纹", titleFingerprint("暗影蜘蛛侠第二部"), titleFingerprint("暗影蜘蛛侠第2部"))
    }

    @Test
    fun titleWithoutSeasonIsOnlyNormalized() {
        // 没有季号的片名，指纹只应该等于归一化结果 —— 不能顺手改动别的字符，
        // 否则「豆瓣抓来的片名」与「源站片名」之间会凭空多出一处差异。
        assertEquals("没有季号就只剩归一化", normalizeTitle("暗影蜘蛛侠：黑白版"), titleFingerprint("暗影蜘蛛侠（黑白版）"))
        assertEquals("原样（除标点空白外）不动", "暗影蜘蛛侠黑白版", titleFingerprint(" 暗影蜘蛛侠 黑白版 "))
    }

    @Test
    fun seasonMarkerBeyondNinetyNineFallsBackToTheOriginalText() {
        // 【当前行为的局限，不是本次要修的】正则的字符类里收了「百」，
        // 但 cnNumber 的注释写明只认到九十九，认不出来就把原文写回去。
        // 于是「第一百季」原样保留，跟「第100季」折不到一起 ——
        // 没有百位以上的季号时这条不影响线上，它钉住的是那个兜底分支。
        assertEquals("认不出的数字原样写回", "剧名第一百季", titleFingerprint("剧名第一百季"))
        assertNotEquals("所以与阿拉伯写法折不到一起", titleFingerprint("剧名第一百季"), titleFingerprint("剧名第100季"))
    }
}
