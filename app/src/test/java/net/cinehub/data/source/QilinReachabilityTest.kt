package net.cinehub.data.source

import net.cinehub.data.model.StreamResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 麒麟解析器给出的地址，交给播放器之前先验一次「这台设备解不解得开那个域名」。
 *
 * 起因是真机反馈：西瓜源搜几部片全都播放失败。扫了十部片逐条线路走完整条取链，
 * 拿解析器回吐的流地址去查公网 DNS，发现它给的一部分地址挂在
 * `png.ruyijx.com` 上，而那个域名**在公网 DNS 上已经是 NXDOMAIN**（运营方弃用了）。
 * 播放器拿到这种地址必然 UnknownHostException —— 而它要等满读超时才报错，
 * 用户看到的就是「点了黑屏、卡半分钟、才换到别的源」。
 *
 * 这里提前一次 DNS 查询（和播放器用的是同一个系统解析器，所以判据是准的）：
 * 解不开就当场失败、说清是哪个域名，把换源的决策交回给上层，省掉那半分钟。
 *
 * 可解析性这一步做成了注入的（[resolvable]），因为 JVM 单测里不该真发 DNS 查询；
 * 真机上走的那一行见 QilinResolver.resolve。
 */
class QilinReachabilityTest {

    private fun check(url: String, ok: Boolean) =
        QilinResolver.gateOnHostResolvable(
            StreamResolution.Ready(url),
            resolvable = { ok },
        )

    @Test
    fun `域名解得开就原样交给播放器`() {
        val r = check("https://m3u8.gcncsm.cn/a/b.m3u8", ok = true)
        assertEquals(StreamResolution.Ready("https://m3u8.gcncsm.cn/a/b.m3u8"), r)
    }

    @Test
    fun `域名解不开时报失败并点出是哪个域名`() {
        val r = check("https://png.ruyijx.com/m3u8/x.m3u8?sig=1", ok = false)
        assertTrue("应当是 Failed，实际 $r", r is StreamResolution.Failed)
        val reason = (r as StreamResolution.Failed).reason
        // 域名要出现在原因里：这一句既给用户看，也是用户去「播放源管理」里判断的依据
        assertTrue("原因里要有域名: $reason", reason.contains("png.ruyijx.com"))
    }

    @Test
    fun `查询串与片段不参与域名提取`() {
        // 地址带 ? 和 # 时不能把整串当主机名去解
        val r = check("https://cdn.example.com/p.m3u8?x=1#frag", ok = false)
        val reason = (r as StreamResolution.Failed).reason
        assertTrue("只该报主机名: $reason", reason.contains("cdn.example.com"))
        assertTrue("不该带查询串: $reason", !reason.contains("x=1"))
    }

    @Test
    fun `不是已解析结果时原样带回`() {
        // Failed / ThirdPartyPage 那几类没有地址可验，别在它们身上做文章
        val f = StreamResolution.Failed("站点说解不出来")
        assertEquals(f, QilinResolver.gateOnHostResolvable(f, resolvable = { true }))
        val p = StreamResolution.ThirdPartyPage("https://v.qq.com/x.html", "腾讯视频")
        assertEquals(p, QilinResolver.gateOnHostResolvable(p, resolvable = { true }))
    }

    @Test
    fun `认不出主机名时不拦`() {
        // 地址畸形（没有 host）时，让播放器去报它自己的错，
        // 别在这里编一句「域名解不开」—— 那是假话，真实成因是地址本身不成形
        val r = QilinResolver.gateOnHostResolvable(
            StreamResolution.Ready("not-a-url"),
            resolvable = { throw AssertionError("不该被调用") },
        )
        assertTrue(r is StreamResolution.Ready)
    }
}
