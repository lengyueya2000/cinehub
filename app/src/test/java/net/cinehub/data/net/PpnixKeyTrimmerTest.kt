package net.cinehub.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥截断与播放清单改写。
 *
 * 这两件事是「PPnix 那一型到底放不放得出来」的全部机关：
 * - 截断错了 → 播放器解出乱码，界面是「解析直链失败」
 * - 改写错了 → 播放器还是去问站点要那把 32 字节的密钥，同上
 *
 * 所以每一条都对着实测到的那个站点形状写，不是泛泛的「健壮性」测试。
 */
class PpnixKeyTrimmerTest {

    /** 站点实测给的那一串：32 个 ASCII 字符。 */
    private val siteKey = "ba9bf05693b9fa202d922dd43a08f281"

    @Test
    fun `32 字节截成前 16 个字符`() {
        val trimmed = KeyTrimmer.trim(siteKey.toByteArray())
        assertEquals(16, trimmed.size)
        assertEquals("ba9bf05693b9fa20", String(trimmed))
    }

    @Test
    fun `正好 16 字节时原样返回`() {
        val raw = "0123456789abcdef".toByteArray()
        assertEquals(16, KeyTrimmer.trim(raw).size)
        assertTrue(raw.contentEquals(KeyTrimmer.trim(raw)))
    }

    @Test
    fun `24 字节截成 16`() {
        val trimmed = KeyTrimmer.trim(ByteArray(24) { 'x'.code.toByte() })
        assertEquals(16, trimmed.size)
    }

    /** 短于 16 字节的原样返回：截不出更多东西，交给播放器去报它那个错。 */
    @Test
    fun `短于 16 字节原样`() {
        val raw = "short".toByteArray()
        assertEquals(5, KeyTrimmer.trim(raw).size)
    }

    /**
     * 不能 hex 解码。
     *
     * 这一点值得单独钉：看着「32 个 hex 字符 → 解成 16 字节」是最自然的猜法，
     * 但实测解出来的东西放不出 TS 包头。站点给的就是「前 16 个字符当密钥」，
     * 照它的做，别替它「纠正」成规范的 hex 表达。
     */
    @Test
    fun `不是 hex 解码`() {
        val trimmed = KeyTrimmer.trim(siteKey.toByteArray())
        // hex 解码会得到 ba 9b f0 56 …，截断得到的是 ASCII 的 'b','a','9','b'…
        assertFalse(trimmed.contentEquals(hexDecode(siteKey)))
    }

    // ------------------------------------------------------------ 播放清单改写

    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:19
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-KEY:METHOD=AES-128,URI="../key",IV=0x00000000000000000000000000000000
        #EXTINF:13.000000,
        https://ipfs.ppnix.com/ipfs/bafybeibnhsqxbfgdtpakbwt7muwznofeh2j7r3udmsxqflz3qmcrtmppwa
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `key 的 URI 换成本机地址`() {
        val out = KeyTrimmer.rewritePlaylist(playlist, "http://127.0.0.1:51234/key?u=x")
        assertTrue(out.contains("""URI="http://127.0.0.1:51234/key?u=x""""))
    }

    /** METHOD 与 IV 一个字都不动 —— 那是站点说了算的事。 */
    @Test
    fun `METHOD 与 IV 保持原样`() {
        val out = KeyTrimmer.rewritePlaylist(playlist, "http://127.0.0.1:1/key?u=x")
        assertTrue(out.contains("METHOD=AES-128"))
        assertTrue(out.contains("IV=0x00000000000000000000000000000000"))
    }

    /** 分片地址一个字都不动 —— 它们不走代理，播放器直连 IPFS。 */
    @Test
    fun `分片地址不动`() {
        val out = KeyTrimmer.rewritePlaylist(playlist, "http://127.0.0.1:1/key?u=x")
        assertTrue(out.contains("https://ipfs.ppnix.com/ipfs/bafybeibnhsqxbfg"))
    }

    @Test
    fun `没有 EXT-X-KEY 的清单原样返回`() {
        val plain = "#EXTM3U\n#EXTINF:5,\nhttps://x/y.ts\n"
        assertEquals(plain, KeyTrimmer.rewritePlaylist(plain, "http://127.0.0.1:1/key"))
    }

    /** 不带引号的 URI 写法也要认。 */
    @Test
    fun `不带引号的 URI`() {
        val src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=../key,IV=0x00\n#EXT-X-ENDLIST\n"
        val out = KeyTrimmer.rewritePlaylist(src, "http://127.0.0.1:9/key")
        assertTrue(out.contains("""URI="http://127.0.0.1:9/key""""))
        assertFalse(out.contains("URI=../key"))
    }

    /** 多把 key（key rotation）时每一把都要换。 */
    @Test
    fun `多把 key 都换`() {
        val src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key\"\n#EXTINF:5,\nhttps://x/a.ts\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"../key\"\n#EXTINF:5,\nhttps://x/b.ts\n#EXT-X-ENDLIST\n"
        val out = KeyTrimmer.rewritePlaylist(src, "http://127.0.0.1:9/key")
        assertEquals(2, Regex("""URI="http://127\.0\.0\.1:9/key\"""").findAll(out).count())
    }

    // ------------------------------------------------------------ 密钥地址解析

    /**
     * 病灶回归：清单里的 key 地址是相对清单地址写的（`../key`）。
     *
     * 曾经把**清单自己的地址**编进本机 key 地址的查询串，取「密钥」时抓回来的
     * 是整份 m3u8，截 16 字节当密钥 —— 播放器必然解不开，PPnix 一次都没播出来过。
     * 真密钥地址是按清单地址解析出来的 `…/info/m3u8/key`（宿主机实测回 32 字节，
     * 截前 16 个字符能解出 TS 包头 0x47）。
     */
    @Test
    fun `相对 key 地址按清单地址解析`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        assertEquals(
            "https://www.ppnix.com/info/m3u8/key",
            KeyTrimmer.resolveKeyUrl(site, "../key"),
        )
    }

    @Test
    fun `已经是绝对地址的 key 原样保留`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val abs = "https://www.ppnix.com/key/today.bin"
        assertEquals(abs, KeyTrimmer.resolveKeyUrl(site, abs))
    }

    @Test
    fun `改写时每把 key 按各自地址解析`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val out = KeyTrimmer.rewritePlaylist(playlist) { raw ->
            KeyTrimmer.localKeyUriWithSite("http://127.0.0.1:51234", KeyTrimmer.resolveKeyUrl(site, raw))
        }
        val q = Regex("""URI="http://127\.0\.0\.1:51234/key\?u=([^"]*)"""").find(out)?.groupValues?.get(1)
            ?: error("改写后没有本机 key 地址")
        assertEquals(
            "https://www.ppnix.com/info/m3u8/key",
            java.net.URLDecoder.decode(q, "UTF-8"),
        )
    }

    @Test
    fun `不带引号的相对 key 地址也解析`() {
        val src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=../key,IV=0x00\n#EXT-X-ENDLIST\n"
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val out = KeyTrimmer.rewritePlaylist(src) { raw ->
            KeyTrimmer.localKeyUriWithSite("http://127.0.0.1:9", KeyTrimmer.resolveKeyUrl(site, raw))
        }
        assertTrue(out.contains(java.net.URLEncoder.encode("https://www.ppnix.com/info/m3u8/key", "UTF-8")))
    }

    /** 结尾换行别多加一个空行。 */
    @Test
    fun `结尾不多出空行`() {
        val src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key\"\n"
        val out = KeyTrimmer.rewritePlaylist(src, "http://127.0.0.1:9/key")
        assertFalse(out.endsWith("\n\n"))
    }

    // ------------------------------------------------------------ 地址编解码

    @Test
    fun `代理地址带上站点那份 m3u8 的地址`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val proxied = KeyTrimmer.proxiedPlaylistUrl("http://127.0.0.1:51234", site)
        assertTrue(proxied.startsWith("http://127.0.0.1:51234/playlist.m3u8?u="))
        // 查询串里的地址要能解回原样，否则代理拿不到站点那份
        val q = proxied.substringAfter("?u=")
        assertEquals(site, java.net.URLDecoder.decode(q, "UTF-8"))
    }

    /**
     * 病灶回归：代理清单地址必须被 [net.cinehub.data.model.StreamUrls.isHls] 认成 HLS。
     *
     * 播放器按这一条判定走 HLS 还是把地址当单个视频抽流。曾经代理路径叫
     * `/playlist`，后缀 / 末段 / 路径三条判据全不中 —— 整份 m3u8 文本被当作
     * 媒体探测，`UnrecognizedInputFormatException`，密钥请求一次都没发出来。
     * 路径上那枚 `.m3u8` 不是装饰，是让播放器认对道的路标。
     */
    @Test
    fun `代理清单地址被 isHls 认成 HLS`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val proxied = KeyTrimmer.proxiedPlaylistUrl("http://127.0.0.1:51234", site)
        assertTrue(
            "代理清单地址必须被 isHls 认成 HLS：$proxied",
            net.cinehub.data.model.StreamUrls.isHls(proxied),
        )
    }

    @Test
    fun `从请求目标里解回站点地址`() {
        val site = "https://www.ppnix.com/info/m3u8/8509/1.m3u8"
        val target = "/playlist.m3u8?u=" + java.net.URLEncoder.encode(site, "UTF-8")
        assertEquals(site, KeyTrimmer.siteUrlFrom(target))
    }

    @Test
    fun `没有 u 参数时解不出站点地址`() {
        assertEquals(null, KeyTrimmer.siteUrlFrom("/playlist"))
        assertEquals(null, KeyTrimmer.siteUrlFrom("/playlist?other=1"))
    }

    private fun hexDecode(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
