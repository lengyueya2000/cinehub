package net.cinehub.data.source

import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拿一份**真实**的搜索结果页跑 [PpnixAdapter] 的列表解析。
 *
 * 这一份不是手写的样例：它是 2026-10-18 从 `www.ppnix.com/cn/search/功夫女足--.html`
 * 抓下来原样存进来的（见 [HTML]）。为什么要用真的页面 ——
 * 模拟器上实测「6 个源检索、4 个有结果」，PPnix 是没答上话的那两个之一，
 * 而宿主机用同一份 HTML + 同一个选择器能取出 15 条。差别只能在解析这一步，
 * 所以把它钉在测试里，改坏了立刻看得见。
 */
class PpnixParseListTest {

    private val cfg = SourceConfig(
        id = "ppnix",
        name = "PPnix",
        baseUrl = "https://www.ppnix.com/cn",
        kind = SourceKind.PpnixApi,
    )

    private fun parse(html: String) = PpnixAdapter(cfg).parseListPublic(Jsoup.parse(html, "https://www.ppnix.com/cn/"))

    @Test
    fun `真实搜索页能取出条目`() {
        val list = parse(HTML)
        // 站点这一页给 15 个数字 id 的条目
        assertEquals(15, list.size)
    }

    @Test
    fun `第一条是功夫女足 2026`() {
        val first = parse(HTML).first()
        assertEquals("8582", first.id)
        assertEquals("功夫女足", first.title)
        assertEquals("2026", first.year)
    }

    @Test
    fun `封面取的是绝对地址`() {
        val first = parse(HTML).first()
        assertTrue(first.cover?.startsWith("https://") == true)
    }

    @Test
    fun `条目都带源 id`() {
        assertTrue(parse(HTML).all { it.sourceId == "ppnix" })
    }

    /** 分类 / 演员 / 导演页的锚点不该混进来。 */
    @Test
    fun `不把分类页当条目`() {
        val ids = parse(HTML).map { it.id }
        assertTrue(ids.none { !it.all { c -> c.isDigit() } })
    }

    companion object {
        /**
         * 2026-10-18 抓的 `www.ppnix.com/cn/search/功夫女足--.html` 原文。
         *
         * 放在测试资源里而不是写成 Kotlin 字符串：那份 HTML 一万字节、带一堆转义，
         * 抄进源码只会让这个文件没法读。
         */
        private val HTML: String by lazy {
            val stream = PpnixParseListTest::class.java.getResourceAsStream("/ppnix_search.html")
                ?: error("缺测试资源 /ppnix_search.html")
            stream.bufferedReader().use { it.readText() }
        }
    }
}
