package net.cinehub.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MinoProtocol] 三段纯逻辑的验证：信封拆分、分集切分、票据读取。
 *
 * 这几处错的后果都不小（拿乱码继续解析、把用户点的集换成别的集、整条播放链断），
 * 而它们全都不依赖连接，所以能在 JVM 上钉死。期望串里的 `tk:` 是站点票证的前缀字面量。
 */
class MinoProtocolTest {

    /** 与 MinoSignTest 同一个向量：IV(12)‖密文‖tag(16) 的信封（12 个 0x07 打头）。 */
    private val bodyKey = ByteArray(32) { it.toByte() }
    private val payload =
        "BwcHBwcHBwcHBwcHdEjQMwtQvpDkpyK1s2TNrZFwrx3pmuQ7xJk/k3W3XHxAK58Z+DXHsC7c2d/4NbFD1yTcaTEZrO0Lb8Ofv0/uiaZRaTPLViSP7Epj0A=="

    @Test
    fun envelopeSpliceKeepsOuterFields() {
        val text = """{"code":1,"data":{"enc":1,"payload":"$payload"},"msg":"success"}"""
        val out = MinoProtocol.unwrapEnvelope(text, bodyKey)
        val root = out.jsonRootOrNull() ?: return assertTrue("拆完解不出 JSON：$out", false)
        assertEquals(1, root.intOf("code"))
        assertEquals("success", root.str("msg"))
        // 明文拼回了 data 的位置：data 现在就是原来那段明文对象
        val data = root["data"]?.asObjectOrNull() ?: return assertTrue("data 不是对象：$root", false)
        assertEquals(1535960L, data["vod_id"]?.asPrimitiveOrNull()?.content?.toLongOrNull())
    }

    @Test
    fun envelopeWithTrailingTextSplicesCorrectly() {
        // 信封后面还有别的字段时，end 必须落在配平的 } 上，不能吞掉 extra
        val text = """{"data":{"enc":1,"payload":"$payload"},"extra":"keep"}"""
        val out = MinoProtocol.unwrapEnvelope(text, bodyKey)
        val root = out.jsonRootOrNull() ?: return assertTrue("拆完解不出 JSON：$out", false)
        assertEquals("keep", root.str("extra"))
        assertNotNull(root["data"]?.asObjectOrNull())
    }

    @Test
    fun nestedObjectInsideEnvelopeDoesNotConfuseEnd() {
        // 信封内层多一层嵌套时，括号配对要越过嵌套的 } 找到真正的那个
        val text = """{"code":1,"data":{"enc":1,"nested":{"a":1},"payload":"$payload"}}"""
        val out = MinoProtocol.unwrapEnvelope(text, bodyKey)
        assertTrue("嵌套时把外层结构拆坏了：$out", out.startsWith("""{"code":1,"data":{"""))
        assertNotNull(out.jsonRootOrNull())
    }

    @Test
    fun noEnvelopePassesThroughUnchanged() {
        val plain = """{"code":1,"data":{"list":[]}}"""
        assertEquals(plain, MinoProtocol.unwrapEnvelope(plain, bodyKey))
        // 没钥时即便带着 enc 也不动（plain 分支的握手就是这个形态）
        val enc = """{"data":{"enc":1,"payload":"$payload"}}"""
        assertEquals(enc, MinoProtocol.unwrapEnvelope(enc, null))
    }

    @Test
    fun wrongKeyThrowsInsteadOfReturningCiphertext() {
        val text = """{"code":1,"data":{"enc":1,"payload":"$payload"},"msg":"ok"}"""
        val other = ByteArray(32) { (it + 1).toByte() }
        // **不**原样带回：原样带回的话上层会拿这段密文当明文去解析 —— 它是一份合法 JSON、
        // code 也是 1，只是找不到 data.videos，于是界面报「这个源里没有这部片」。
        // 真相是密钥不对/接口改了，那是句假话。所以这里必须响。
        assertThrows(MinoProtocol.EnvelopeFailure::class.java) {
            MinoProtocol.unwrapEnvelope(text, other)
        }
    }

    @Test
    fun undecodablePayloadThrowsInsteadOfReturningCiphertext() {
        // 信封认出来了（enc == 1 且有 payload），但 payload 不是 base64：
        // 同样是「认出来了却打不开」，要响，不能静默退回密文
        val text = """{"code":1,"data":{"enc":1,"payload":"!!!not-base64!!!"}}"""
        assertThrows(MinoProtocol.EnvelopeFailure::class.java) {
            MinoProtocol.unwrapEnvelope(text, bodyKey)
        }
    }

    @Test
    fun envelopeWithWhitespaceBeforeColonSplicesCorrectly() {
        // `"data" :` 是合法 JSON。原来锚点用字面量 indexOf("\"data\":") 找，
        // 找不到就静默退回密文 —— 后端只要在冒号前多打一个空格，整条链路就成了
        // 「没有这部片」。两处 splice（unwrapEnvelope / envelopeEnd）都得认这个写法。
        val text = """{"code":1,"data" :{"enc":1,"payload":"$payload"},"msg":"success"}"""
        val out = MinoProtocol.unwrapEnvelope(text, bodyKey)
        val root = out.jsonRootOrNull() ?: return assertTrue("拆完解不出 JSON：$out", false)
        assertEquals(1, root.intOf("code"))
        assertEquals("success", root.str("msg"))
        val data = root["data"]?.asObjectOrNull() ?: return assertTrue("data 不是对象：$root", false)
        assertEquals(1535960L, data["vod_id"]?.asPrimitiveOrNull()?.content?.toLongOrNull())
    }

    @Test
    fun episodesSplitNameAndTicket() {
        val raw = "第1集${'$'}tk:AAA#第2集${'$'}tk:BBB#第3集${'$'}tk:CCC"
        val eps = MinoProtocol.episodesFromUrl(raw, "mino:", "tk:")
        assertEquals(3, eps.size)
        assertEquals("第1集", eps[0].name)
        assertEquals(1, eps[0].index)
        assertEquals("mino:tk:AAA", eps[0].coordinate)
        assertEquals("第3集", eps[2].name)
        // 1080P 那一型用裸数字当集名，也要照原样留着
        assertEquals(
            "2",
            MinoProtocol.episodesFromUrl("1${'$'}tk:A#2${'$'}tk:B", "mino:", "tk:")[1].name,
        )
    }

    @Test
    fun episodesDropSegmentsWithoutTicket() {
        // 没有票证的段（空串、光杆名字）不收：它们点开了必然换不出地址
        val raw = "第1集${'$'}tk:AAA#第2集#只有名字#x"
        assertEquals(1, MinoProtocol.episodesFromUrl(raw, "mino:", "tk:").size)
    }

    @Test
    fun episodeNameFallsBackWhenSegmentHasNoTitle() {
        val eps = MinoProtocol.episodesFromUrl("${'$'}tk:AAA#${'$'}tk:BBB", "mino:", "tk:")
        assertEquals(listOf("第 1 集", "第 2 集"), eps.map { it.name })
    }

    @Test
    fun ticketPayloadReadsUnpaddedBase64Url() {
        val seg = "eyJ0b2tlbiI6InRrOkFiQyIsImRhdGEiOnsidXJsIjoiaHR0cHM6" +
            "Ly92LnFxLmNvbS94L2NvdmVyL2EvYi5odG1sIiwicGFyc2VfYXBpIjoiaHR0cHM6Ly9qeC9hcGkv" +
            "P2tleT14JnVybD0iLCJwYXJzZV9tb2RlIjoyfX0"
        val p = MinoProtocol.ticketPayload("$seg.c2lnLXBhcnQ")
            ?: return assertTrue("解不出 payload", false)
        val d = p["data"]?.asObjectOrNull()
        assertEquals("https://v.qq.com/x/cover/a/b.html", d?.str("url"))
        assertEquals("https://jx/api/?key=x&url=", d?.str("parse_api"))
        assertEquals(2, d?.intOf("parse_mode"))
    }

    @Test
    fun ticketPayloadRejectsUnknownShapes() {
        assertNull(MinoProtocol.ticketPayload("not-a-jwt"))
        assertNull(MinoProtocol.ticketPayload(""))
        assertNull(MinoProtocol.ticketPayload("###notbase64###"))
        // payload 段是 base64url 但解出来不是 JSON 对象
        assertNull(MinoProtocol.ticketPayload("aGVsbG8gd29ybGQ.sig"))
    }
}
