package net.cinehub.data.source

import net.cinehub.data.model.StreamResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 西瓜型链路（苹果 CMS 把平台播放页交给麒麟解析器）里那几步纯文本抽取。
 *
 * 这几处都是「从站点发回来的一大坨里认出一个值」，正则写错的方向很隐蔽：
 * 认不出来是「解析失败」（会报给用户、看得见），认错一个（比如把上一条线路的
 * parse 模板接过来）则是拿错的地址去解、解出别的东西，反而没有报错。
 * 所以钉在测试里，样例都取自 2026-10-18 真机抓下来的原文。
 */
class QilinResolverTest {

    // ---------- 1. 从 playerconfig.js 里认解析器模板 ----------

    @Test
    fun `playerconfig 里第一个非空 parse 就是解析器模板`() {
        val js = """
            MacPlayerConfig.player_list={
              "dplayer":{"show":"DPlayer","ps":"0","parse":""},
              "qq":{"show":"TX","ps":"1","parse":"https://hls.xiguadh.com/?url="},
              "youku":{"show":"YK","ps":"1","parse":"https://hls.xiguadh.com/?url="}
            };
        """.trimIndent()
        assertEquals("https://hls.xiguadh.com/?url=", QilinResolver.parseTemplateFromConfig(js))
    }

    @Test
    fun `转义斜杠的写法也认`() {
        // 站点那份原文里斜杠是 \/ 转义的（JSON 塞进 JS 字面量的常见写法）。
        // raw string 不处理转义，所以这里就是一个反斜杠加一个斜杠，照抄站点。
        val js = """"qq":{"show":"TX","ps":"1","parse":"https:\/\/hls.xiguadh.com\/?url="}"""
        assertEquals(
            "https://hls.xiguadh.com/?url=",
            QilinResolver.parseTemplateFromConfig(js),
        )
    }

    @Test
    fun `全是空 parse 时认不出模板`() {
        val js = """"dplayer":{"ps":"0","parse":""},"link":{"ps":"0","parse":""}"""
        assertNull(QilinResolver.parseTemplateFromConfig(js))
    }

    // ---------- 2. 从解析器页面认 apiToken ----------

    @Test
    fun `apiToken 从脚本里取出来`() {
        val html = """
            <script>
              var cfg = { apiToken: "aB3_xK9-2", expire: 600 };
            </script>
        """.trimIndent()
        assertEquals("aB3_xK9-2", QilinResolver.apiTokenFromPage(html))
    }

    @Test
    fun `没有 apiToken 时返回 null 而不是崩`() {
        assertNull(QilinResolver.apiTokenFromPage("<html>授权失败</html>"))
    }

    // ---------- 3. 从 parse 模板推 resolve.php 的地址 ----------

    @Test
    fun `resolve 接口挂在解析器自己的域下`() {
        assertEquals(
            "https://hls.xiguadh.com/api/resolve.php",
            QilinResolver.resolveEndpoint("https://hls.xiguadh.com/?url="),
        )
    }

    @Test
    fun `模板不成形时不硬拼`() {
        assertNull(QilinResolver.resolveEndpoint("不是个地址"))
        assertNull(QilinResolver.resolveEndpoint(""))
    }

    // ---------- 4. resolve.php 的响应翻成取链结果 ----------

    private fun ok(body: String): StreamResolution = QilinResolver.streamFromResolve(body)

    @Test
    fun `code 200 带 url 就是能播`() {
        val r = ok("""{"code":200,"type":"m3u8","url":"https://cdn/x/y.m3u8"}""")
        assertEquals(StreamResolution.Ready("https://cdn/x/y.m3u8"), r)
    }

    @Test
    fun `code 是字符串也认`() {
        val r = ok("""{"code":"200","type":"mp4","url":"https://cdn/x.mp4"}""")
        assertTrue(r is StreamResolution.Ready)
    }

    @Test
    fun `非 200 把站点那句话带回去`() {
        // 实测 mgtv / youku 线路回的就是这一类：解不出来，但给了人话
        val r = ok("""{"code":404,"msg":"无法解析该视频，请尝试换一个资源或线路"}""")
        assertTrue(r is StreamResolution.Failed)
        assertTrue(
            "站点自己那句话要留在原因里: ${(r as StreamResolution.Failed).reason}",
            (r as StreamResolution.Failed).reason.contains("无法解析"),
        )
    }

    @Test
    fun `code 200 但 url 是空的算失败`() {
        val r = ok("""{"code":200,"type":"m3u8","url":""}""")
        assertTrue(r is StreamResolution.Failed)
    }

    @Test
    fun `回的不是 JSON 时如实说而不是抛`() {
        val r = ok("<html>502 Bad Gateway</html>")
        assertTrue(r is StreamResolution.Failed)
    }

    @Test
    fun `url 不是 http 开头的算失败`() {
        // 有的解析器出错时 url 字段回一句提示文字，不是地址
        val r = ok("""{"code":200,"url":"播放失败"}""")
        assertTrue(r is StreamResolution.Failed)
    }
}
