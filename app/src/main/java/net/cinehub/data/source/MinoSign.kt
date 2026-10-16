package net.cinehub.data.source

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MINO 型自建 Apple-CMS 后端的接口安全层（纯 JDK，可在 JVM 上直接跑）。
 *
 * 参数全部按后端自己的协议实测复现（ECDH 握手 → HKDF 派生双钥 → 每请求
 * HMAC-SHA256 签名 → 响应体 AES-GCM 信封）。
 *
 * 刻意**不用** `android.util.Base64`（JVM 测试里是桩件）也不用 `java.util.Base64`
 * （API 26 才有，而本应用 minSdk 24）—— base64 在这里手写，一份代码两处都能跑。
 */
internal object MinoSign {

    /** P-256 一个坐标的定长字节数。 */
    private const val FIELD_BYTES = 32

    /** 临时客户端密钥对：一次会话用一把。 */
    class ClientEcdh {
        val privateKey: ECPrivateKey
        /** X962 非压缩点（65 字节，04 ‖ X ‖ Y）。 */
        val publicPoint: ByteArray

        init {
            val kp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            privateKey = kp.private as ECPrivateKey
            val w = (kp.public as ECPublicKey).w
            publicPoint = ByteArray(1 + 2 * FIELD_BYTES).also { out ->
                out[0] = 0x04
                copyPadded(w.affineX, out, 1)
                copyPadded(w.affineY, out, 1 + FIELD_BYTES)
            }
        }

        private fun copyPadded(v: BigInteger, to: ByteArray, at: Int) {
            val raw = v.toByteArray()
            // BigInteger 可能多带一个符号 0x00 头；只取低 FIELD_BYTES 字节、右对齐
            val src = if (raw.size > FIELD_BYTES) raw.copyOfRange(raw.size - FIELD_BYTES, raw.size) else raw
            System.arraycopy(src, 0, to, at + (FIELD_BYTES - src.size), src.size)
        }
    }

    /**
     * ECDH 共享密钥（X 坐标），与协议里的 exchange 一致。
     *
     * 服务端点位用**本端私钥自带的曲线参数**重构成公钥 —— 而不是手搓一份 P-256 参数：
     * JCE 在 doPhase 时比对两把钥的参数是否同一个对象/等价命名曲线，手搓的裸
     * ECParameterSpec 会被私钥那份命名曲线判成「EC parameters error」（单测实测）。
     * 本端密钥对就是 secp256r1 生成的，等价于协议要的曲线；点位不合法时
     * generatePublic / doPhase 自己抛，由握手如实报「握手没成」。
     */
    fun sharedSecret(priv: ECPrivateKey, serverPoint: ByteArray): ByteArray {
        require(looksLikePoint(serverPoint)) { "服务端公钥不是一个 P-256 非压缩点" }
        val x = BigInteger(1, serverPoint.copyOfRange(1, 1 + FIELD_BYTES))
        val y = BigInteger(1, serverPoint.copyOfRange(1 + FIELD_BYTES, serverPoint.size))
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), priv.params),
        ) as ECPublicKey
        return KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(pub, true)
            // 只取 X 坐标 —— 协议侧的 exchange 就是这一份 raw secret
            generateSecret()
        }
    }

    /** 是不是一个合法的 P-256 非压缩点（04 开头、65 字节）。 */
    fun looksLikePoint(raw: ByteArray): Boolean =
        raw.size == 1 + 2 * FIELD_BYTES && raw[0] == 0x04.toByte()

    /**
     * HKDF-SHA256（extract 再 expand，取 32 字节）。
     *
     * 后端派生 signKey / bodyKey 用的是同一式子：salt 是 session_id，
     * info 分别是 bwv/apisec/ecdh-signkey/v1 与 bwv/apisec/ecdh-bodykey/v1。
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        // 空 salt 按 HKDF 规定补 32 个零字节（ByteArray 没有 ifEmpty，这里显式判）
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        // 32 字节输出一块就够，但仍按 expand 循环写，以后 info 变长不至于截错
        var prev = ByteArray(0)
        val out = ArrayList<ByteArray>()
        var total = 0
        var i = 1
        while (total < 32) {
            prev = hmac(prk, prev + info + byteArrayOf(i.toByte()))
            out += prev
            total += prev.size
            i++
        }
        return out.fold(ByteArray(0)) { a, b -> a + b }.copyOf(32)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** 空正文的 SHA256 —— GET 请求在 canonical 的 body 位上用的就是这个常量。 */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 查询串规范化：按 (key, value) 排序重拼。
     *
     * 值保持 URL 编码原样 —— 签的就是发的那串字节，这里只做重排不做重编码。
     */
    fun canonicalQuery(query: String): String {
        if (query.isBlank()) return ""
        return query.split("&")
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    /**
     * 九行 canonical 求 X-Sign。
     *
     * METHOD / 编码路径 / 规范查询 / body SHA256 / unix 秒 / 16 随机字节 hex /
     * device_id / session_id / app_version，用 \n 连接；HMAC-SHA256(signKey)，小写 hex。
     */
    fun sign(
        signKey: ByteArray,
        method: String,
        path: String,
        query: String,
        body: ByteArray?,
        timestampSec: String,
        nonceHex: String,
        deviceId: String,
        sessionId: String,
        appVersion: String,
    ): String {
        val bodySha = body?.let { sha256Hex(it) } ?: EMPTY_SHA256
        val canonical = listOf(
            method.uppercase(), path, canonicalQuery(query), bodySha,
            timestampSec, nonceHex, deviceId, sessionId, appVersion,
        ).joinToString("\n")
        return hmac(signKey, canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** 一次新随机串（16 字节 hex）。 */
    fun newNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    /**
     * AES-256-GCM 解密：payload 的字节布局是 IV(12) ‖ 密文 ‖ tag(16)，无 AAD。
     *
     * 认不出来（长度不对、密钥不对、tag 验不过）返回 null，由调用方按
     * 「接口答的样子不认识」去说，不拿一份乱码继续往下解析。
     */
    fun decryptGcm(blob: ByteArray, bodyKey: ByteArray): ByteArray? = runCatching {
        require(blob.size >= 28)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(bodyKey, "AES"),
                GCMParameterSpec(128, blob, 0, 12),
            )
            doFinal(blob, 12, blob.size - 12)
        }
    }.getOrNull()

    // ---------- base64 / 百分号编码：协议用到的这两样都在这里，纯 JVM 实现 ----------

    private const val B64STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encodeBase64(bytes: ByteArray, urlSafe: Boolean = false): String {
        val table = if (urlSafe) B64URL else B64STD
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(table[n shr 18 and 63]).append(table[n shr 12 and 63])
                .append(table[n shr 6 and 63]).append(table[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                out.append(table[n shr 18 and 63]).append(table[n shr 12 and 63]).append("==")
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                out.append(table[n shr 18 and 63]).append(table[n shr 12 and 63])
                    .append(table[n shr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /** 解 base64；urlSafe=true 认 `-`/`_` 字母表。填充可缺省（JWT 的 payload 段就没有填充）。 */
    fun decodeBase64(text: String, urlSafe: Boolean = false): ByteArray? {
        val table = if (urlSafe) B64URL else B64STD
        val clean = text.trim().filterNot { it == '\n' || it == '\r' }
        if (clean.any { it != '=' && table.indexOf(it) < 0 }) return null
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        for (c in clean) {
            if (c == '=') break
            val v = table.indexOf(c)
            if (v < 0) return null
            acc = acc shl 6 or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((acc shr bits and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /**
     * 百分号编码，`safe=""` 语义：除 RFC 3986 不保留字符（字母数字 `-._~`）外全编。
     *
     * 解析协议那条路要把平台页地址整串塞进 `?url=` 后面，站点那侧（Python `quote(safe="")`
     * / JS encodeURIComponent）就是这么编的，这里对齐，不然 `://` 会提前把查询串断开。
     * 刻意不用 `URLEncoder.encode`：那是表单的 `+` 号语义（空格编成 `+`），和这里要的 `%20` 不一致。
     */
    fun percentEncode(s: String): String {
        val out = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val keep = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (keep) out.append(c.toChar()) else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }
}
