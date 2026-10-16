package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MINO 协议里与网络无关的三段纯逻辑：响应信封拆分、分集票证切分、票据 payload 读取。
 *
 * 单独拎出来是因为这三处最容易错、又最不该错：信封拆错会拿乱码继续解析、
 * 分集切错会把用户点的第 3 集换成别的集、票据读错整条播放链就断。
 * 它们不依赖任何一端的连接，所以能在 JVM 上直接钉死。
 */
internal object MinoProtocol {

    /**
     * 拆响应信封：`{"code":1,"data":{"enc":1,"payload":"base64"},"msg":"ok"}`。
     *
     * 后端只加密 `data` 那一段，外层 code/msg 是明文 —— 解出来的明文要**拼回 data 的
     * 位置**，调用方看到的才仍是一份完整 JSON（站点自己的前端也是这么做的）。
     *
     * 四种「不像信封」都原样带回，让上层按「接口答的样子不认识」去说，不拿半份东西
     * 往下解析：没带钥、外层解不出 JSON、`enc` 不是 1、payload 解不开或 tag 验不过。
     */
    fun unwrapEnvelope(text: String, bodyKey: ByteArray?): String {
        if (bodyKey == null || !text.contains("\"enc\"")) return text
        val root = text.jsonRootOrNull() ?: return text
        val data = root["data"] as? JsonObject ?: return text
        if (data.intOf("enc") != 1) return text
        val payload = data.str("payload") ?: return text
        val blob = MinoSign.decodeBase64(payload) ?: return text
        val plain = MinoSign.decryptGcm(blob, bodyKey) ?: return text
        val at = text.indexOf("\"data\":")
        if (at < 0) return text
        // 拼回到 data: 之后 —— 那七个字符（"data":）保留，后面的整个对象换成明文
        return text.substring(0, at + "\"data\":".length) + String(plain, Charsets.UTF_8) + text.substring(envelopeEnd(text))
    }

    /** 信封那个对象在原文里结束的位置（配平的 `}` 之后）；配不上时退回文本末尾。 */
    internal fun envelopeEnd(text: String): Int {
        val i = text.indexOf("\"data\":")
        if (i < 0) return text.length
        val brace = text.indexOf('{', i)
        if (brace < 0) return text.length
        var depth = 0
        var inStr = false
        var esc = false
        for (j in brace until text.length) {
            val c = text[j]
            if (esc) { esc = false; continue }
            when {
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
            }
        }
        return text.length
    }

    /**
     * 把一条线路的 `第N集$tk:票证#第N+1集$tk:票证…` 切成分集坐标。
     *
     * 只收带票证的段：站点偶尔给出没有票证的段（空串、光杆一个名字），那种点开了
     * 也换不出地址 —— 收进来就是列表里一个必然失败的格子，不如不给。
     */
    fun episodesFromUrl(rawUrl: String, mark: String, tokenPrefix: String): List<MinoEpisode> =
        rawUrl.split("#").mapIndexedNotNull { n, seg ->
            val name = seg.substringBefore('$').trim().ifBlank { "第 ${n + 1} 集" }
            val token = seg.substringAfter('$', "").trim()
            if (!token.startsWith(tokenPrefix)) null else MinoEpisode(name, n + 1, mark + token)
        }

    /** 一集（切分结果；与模型层的 Episode 分开，这一段不依赖界面类型）。 */
    data class MinoEpisode(val name: String, val index: Int, val coordinate: String)

    /**
     * 读 play_ticket（`base64url(payload).base64(sig)`）的 payload 对象。
     *
     * **不做客户端验签**：会话本身就是服务端签发给本机的，握手过了才换得到票，
     * 验签是给「收到别人转述的票」那种场景准备的；直接读 payload 与站点前端
     * 解密后的结果等效。格式不认识时返回 null，由调用方说清是哪一种不认识。
     */
    fun ticketPayload(jwt: String): JsonObject? {
        val seg = jwt.substringBeforeLast('.', "")
        if (seg.isEmpty() || seg == jwt) return null
        val raw = MinoSign.decodeBase64(seg, urlSafe = true) ?: return null
        return String(raw, Charsets.UTF_8).jsonRootOrNull()
    }
}

private val protocolJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 能解成 JSON 对象就返回，否则 null（不抛 —— 调用方要说的是「答的样子不认识」）。 */
internal fun String.jsonRootOrNull(): JsonObject? =
    runCatching { protocolJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** 元素是不是对象（不是就当没有，不抛）。 */
internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

/** JSON 取数组，不是数组就当没有，不抛。 */
internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

/** JSON 取原始值，不是原始值就当没有。 */
internal fun JsonElement.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/** JSON 取字符串字段：非字符串的 `null` 当没有；空白当没有。 */
internal fun JsonObject.str(field: String): String? {
    val p = this[field] as? JsonPrimitive ?: return null
    if (!p.isString && p.content == "null") return null
    return p.content.takeIf { it.isNotBlank() }
}

/** JSON 取整数字段（后端的 code / enc 发成数字，也认字符串形式）。 */
internal fun JsonObject.intOf(field: String): Int? =
    (this[field] as? JsonPrimitive)?.content?.toIntOrNull()

