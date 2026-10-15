package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 苹果 CMS 播放页里那段 `player_xxx = { … }`。
 *
 * 模板站的这段是页面自己的公开数据，不藏在登录态后面；两个适配器都要读它
 * （一种直接取 `url` 当地址，另一种把 `url` 当凭据交给解析站），所以单列一份。
 */
internal object MacPlayerJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 页面上那段对象；读不到返回 null。 */
    fun read(html: String): JsonObject? {
        val raw = extract(html) ?: return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    /**
     * 从 `player_<名字> =` 这个锚点起做一次配对括号扫描，切出那段对象。
     *
     * 原来用的是一条 `(\{.*?\})\s*[;<]` 加 DOT_MATCHES_ALL —— 那是会把一个页面变成
     * 一场等待的写法：`\s*[;<]` 只要往后出现一次「不像结尾」的片段，引擎就要把花括号里的
     * 每一个位置重数一遍，模板页几十个 script 很常见，界面表现为「详情页转圈转到天荒地老」。
     * 扫描只看自己那一段，一遍走完。
     */
    private fun extract(html: String): String? {
        val anchor = Regex("""player_[A-Za-z0-9_]+\s*=\s*\{""").find(html) ?: return null
        val open = html.indexOf('{', anchor.range.first)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until html.length) {
            val c = html[i]
            when {
                inString && escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(open, i + 1)
                }
            }
            // 一坨超过 256 KB 的「脚本」不是站点给播放地址的那份数据，别陪着它耗内存
            if (i - open > 256_000) return null
        }
        return null
    }

    /** 页面标题 —— 有的站把它当作签名原文的一部分（见 [MaccmsParseResolver]）。 */
    fun title(html: String): String =
        TITLE.find(html)?.groupValues?.get(1).orEmpty()

    private val TITLE = Regex("""(?i)<title[^>]*>(.*?)</title>""")
}

/** 这段 JSON 全是 `{"key":"value"}`，取值按字符串拿就够了（`code` 那种数字也是字符串形式）。 */
internal fun JsonObject.field(name: String): String? = (this[name] as? JsonPrimitive)?.content
