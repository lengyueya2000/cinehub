package net.cinehub.data.source

import net.cinehub.data.source.MinoSign.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MINO 协议安全层的字节级验证。
 *
 * 每个期望值都不是这个实现自己算出来的，而是从 `yuan` 那套已跑通的离线复现
 * （Python `cryptography` + 站点自己的签名串）导出的参考向量 —— 两边必须逐字节一致。
 * 换域名、换后端时这一组用例是「我们还在照协议说话」的那道闸。
 */
class MinoSignTest {

    private fun hexToBytes(s: String) =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdfSignAndBodyKeysMatchReference() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = "sid_0123456789abcdef".toByteArray()
        val sign = MinoSign.hkdf(ikm, salt, "bwv/apisec/ecdh-signkey/v1".toByteArray())
        val body = MinoSign.hkdf(ikm, salt, "bwv/apisec/ecdh-bodykey/v1".toByteArray())
        assertEquals("04b944c4c0f26dbd284be1a0b0282d94bd852af9f7b6bf55a3e54ca137759f55", sign.toHex())
        assertEquals("e7e5e73273b2a263b471d41b18f658d614e84abd704f75b4eeb3eaa56e91a0d1", body.toHex())
    }

    @Test
    fun hkdfEmptySaltUsesZeroKey() {
        val ikm = ByteArray(32) { it.toByte() }
        val sign = MinoSign.hkdf(ikm, ByteArray(0), "bwv/apisec/ecdh-signkey/v1".toByteArray())
        assertEquals("52e87e710384ef218be08965a5024700a6cc805a22c9ffca2d1dfc693108c5a7", sign.toHex())
    }

    @Test
    fun signGetPlainMatchesReferenceCanonical() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        val sig = MinoSign.sign(
            signKey = key, method = "GET", path = "/api/v1/video/detail", query = "id=1535960",
            body = null, timestampSec = "1700000000", nonceHex = "a".repeat(32),
            deviceId = "deadbeefdeadbeef", sessionId = "sid_test", appVersion = "1.0.1",
        )
        assertEquals("3ee9fc5d576930d1028839d0fc188090bb99efff38bc0c8d35fbf2b19825c5bc", sig)
    }

    @Test
    fun signGetSortsQueryByKey() {
        // 参考的 canonicalQuery 把 page_size/page/wd 重排成 page=1&page_size=24&wd=…
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        val q = "page_size=24&page=1&wd=%E5%BA%86%E4%BD%99%E5%B9%B4"
        assertEquals("page=1&page_size=24&wd=%E5%BA%86%E4%BD%99%E5%B9%B4", MinoSign.canonicalQuery(q))
        val sig = MinoSign.sign(
            signKey = key, method = "GET", path = "/api/v1/search", query = q,
            body = null, timestampSec = "1700000000", nonceHex = "a".repeat(32),
            deviceId = "deadbeefdeadbeef", sessionId = "sid_test", appVersion = "1.0.1",
        )
        assertEquals("5b6aa29ccab82ae9350f5213cb81cdbf63506a94b54454066a42d35d193c64a2", sig)
    }

    @Test
    fun signPostTicketHashesBodyBytes() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        val body = """{"token":"tk:AbC_123"}""".toByteArray()
        val sig = MinoSign.sign(
            signKey = key, method = "POST", path = "/api/v1/vod/ticket", query = "", body = body,
            timestampSec = "1700000000", nonceHex = "a".repeat(32),
            deviceId = "deadbeefdeadbeef", sessionId = "sid_test", appVersion = "1.0.1",
        )
        assertEquals("2cf68a3ae73cbc18cb6d5320acb37fcc415af30d46b0bbe14b17eddea7e5ad2f", sig)
        // 空正文的 body 位是那个常量 SHA256
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MinoSign.EMPTY_SHA256,
        )
        assertEquals(MinoSign.EMPTY_SHA256, MinoSign.sha256Hex(ByteArray(0)))
    }

    @Test
    fun base64StandardVectors() {
        val cases = listOf(
            hexToBytes("0400010203040506070809") to "BAABAgMEBQYHCAk=",
            byteArrayOf(-1, -2, 0) to "//4A",
            ByteArray(0) to "",
            "a".toByteArray() to "YQ==",
            "ab".toByteArray() to "YWI=",
            "abc".toByteArray() to "YWJj",
        )
        for ((bytes, want) in cases) assertEquals(want, MinoSign.encodeBase64(bytes))
    }

    @Test
    fun base64UrlSafeAndDecodeRoundTrip() {
        assertEquals("__4A", MinoSign.encodeBase64(byteArrayOf(-1, -2, 0), urlSafe = true))
        // 无填充的 JWT payload 段能解回来
        val decoded = MinoSign.decodeBase64("eyJhIjoxLCJiIjoieD9-KyJ9", urlSafe = true)
        assertEquals("""{"a":1,"b":"x?~+"}""", decoded?.let { String(it) })
    }

    @Test
    fun base64DecodeRejectsGarbage() {
        assertNull(MinoSign.decodeBase64("not*base64!", urlSafe = false))
    }

    @Test
    fun percentEncodeMatchesPythonQuoteSafeEmpty() {
        val cases = listOf(
            "https://v.qq.com/x/cover/abc/y00.html?t=1&x=2" to
                "https%3A%2F%2Fv.qq.com%2Fx%2Fcover%2Fabc%2Fy00.html%3Ft%3D1%26x%3D2",
            "http://a/b c/d?e=f&g=%7Bx%7D#frag" to
                "http%3A%2F%2Fa%2Fb%20c%2Fd%3Fe%3Df%26g%3D%257Bx%257D%23frag",
            "中文/路径?q=值" to
                "%E4%B8%AD%E6%96%87%2F%E8%B7%AF%E5%BE%84%3Fq%3D%E5%80%BC",
        )
        for ((s, want) in cases) assertEquals(want, MinoSign.percentEncode(s))
    }

    @Test
    fun aesGcmEnvelopeDecryptsReferencePayload() {
        // 站点格式是 IV(12) ‖ 密文 ‖ tag(16)；这段以 12 个 0x07（base64 的 BwcH…）打头
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val payload = MinoSign.decodeBase64(
            "BwcHBwcHBwcHBwcHdEjQMwtQvpDkpyK1s2TNrZFwrx3pmuQ7xJk/k3W3XHxAK58Z+DXHsC7c2d/4NbFD1yTcaTEZrO0Lb8Ofv0/uiaZRaTPLViSP7Epj0A==",
        ) ?: return assertTrue("参考 payload 解不出", false)
        val plain = MinoSign.decryptGcm(payload, key) ?: return assertTrue("GCM 解密失败", false)
        assertEquals(
            "7b22766f645f6964223a313533353936302c226e616d65223a22616971696e67676f6e67797533222c2278223a2268656c6c6f20e4b8ade69687227d",
            plain.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun aesGcmWrongKeyReturnsNull() {
        val payload = MinoSign.decodeBase64(
            "BwcHBwcHBwcHBwcHdEjQMwtQvpDkpyK1s2TNrZFwrx3pmuQ7xJk/k3W3XHxAK58Z+DXHsC7c2d/4NbFD1yTcaTEZrO0Lb8Ofv0/uiaZRaTPLViSP7Epj0A==",
        )!!
        assertNull(MinoSign.decryptGcm(payload, ByteArray(32) { (it + 1).toByte() }))
    }

    @Test
    fun pointValidationAcceptsP256Uncompressed() {
        val pub = MinoSign.ClientEcdh().publicPoint
        assertTrue(MinoSign.looksLikePoint(pub))
        assertTrue(pub.size == 65 && pub[0] == 0x04.toByte())
    }

    @Test
    fun ecdhIsSymmetricAcrossTwoKeys() {
        // 本端私钥 × 对端公钥 == 对端私钥 × 本端公钥 —— 两把派生钥对得上的前提
        val a = MinoSign.ClientEcdh()
        val b = MinoSign.ClientEcdh()
        val sharedA = MinoSign.sharedSecret(a.privateKey, b.publicPoint)
        val sharedB = MinoSign.sharedSecret(b.privateKey, a.publicPoint)
        assertEquals(sharedA.toHex(), sharedB.toHex())
        val salt = "sid_x".toByteArray()
        assertEquals(
            MinoSign.hkdf(sharedA, salt, INFO).toHex(),
            MinoSign.hkdf(sharedB, salt, INFO).toHex(),
        )
    }

    private companion object {
        val INFO = "bwv/apisec/ecdh-signkey/v1".toByteArray()
    }
}
