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

    /**
     * 页面标题 —— 有的站把它当作签名原文的一部分（见 [MaccmsParseResolver]）。
     *
     * 标题参与解析站的签名，所以读不到不能悄悄给一个空串：空标题算出来的签名是错的，
     * 用户看到的是解析站一句笼统的失败，而真正的原因（这一版正则跟不上这页的写法）查不出来。
     * 读不到时抛出去，由调用方按「这一页读不出标题」说清楚。
     *
     * `DOT_MATCHES_ALL` 是必需的：模板把 `<title>` 折行写、或者标题里有换行很常见，
     * 少了它 `.` 不匹配换行，整段就匹配不上 —— 结果是一个空标题（上面说的那条错链）。
     * 实体也要还原：页面上写的是 `&amp;`、`&#39;`、`&quot;`，而签名原文用的是字符本身。
     */
    fun title(html: String): String {
        val raw = TITLE.find(html)?.groupValues?.get(1).orEmpty()
        val text = unescape(raw).trim()
        if (text.isEmpty()) error("播放页里读不出标题（它参与签名，空着算出来的签名是错的）—— 这个模板多半改版了")
        return text
    }

    /** 页面标题里最常见的几个 HTML 实体；认不出的原样留着（宁可多留一个字符，也别猜错）。 */
    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private val TITLE = Regex("""(?i)<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
}

/** 这段 JSON 全是 `{"key":"value"}`，取值按字符串拿就够了（`code` 那种数字也是字符串形式）。 */
internal fun JsonObject.field(name: String): String? = (this[name] as? JsonPrimitive)?.content
