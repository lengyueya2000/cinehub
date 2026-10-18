package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.net.Http
import java.util.concurrent.ConcurrentHashMap

/**
 * 西瓜型链路：苹果 CMS 的接口里存的**不是流、是各家平台的播放页**，
 * 站点自己的播放器靠一张「麒麟解析器」把平台页地址换成能播的 m3u8。
 *
 * 三步，都是照站点自己那套脚本的走法：
 *
 * 1. 站点的 `/static/js/playerconfig.js` 里 `MacPlayerConfig.player_list[线路].parse`
 *    写着解析器模板（实测 `https://hls.xiguadh.com/?url=`）。**不在这里写死那个域名** ——
 *    这类解析站换域名是常事，写死了换域名之后会一路报「打不开」，而站点自己会跟着换。
 * 2. 拿 `模板 + 平台页地址` 去要一张页面，页面里下发一个 `apiToken`。
 *    这颗 token 是**跟会话绑的**：下发它的那条请求与下一步的 resolve.php 必须共用
 *    同一颗 cookie。[Http] 按 host 记会话（见它的 jar），两步都在解析器那台域下，
 *    所以这里不用自己管 cookie。
 * 3. `解析器域/api/resolve.php?token=<token>` 回 `{code, type, url}`，
 *    `url` 才是能交给播放器的地址。
 *
 * 两处和 [MaccmsParseResolver] 不一样，是这条链路自己的脾气：
 * - **不用抓播放页**。那边 `player_aaaa.url` 是一串凭据；这一型 encrypt=0 时那格
 *   就等于接口 `vod_play_url` 里的平台页地址本身（实测一字不差），所以直接从接口
 *   拿那一串喂解析器即可，省掉一次页面请求。
 * - **不用签名、不用解密**。resolve.php 给的是明文地址。
 *
 * 也不是每条线路都解得出来：实测同一部片五条线路里 qq / bilibili / rrmj 出得来，
 * mgtv / youku 回 404「无法解析该视频，请尝试换一个资源或线路」。所以解不出来时
 * 把站点那句话原样带回，交给上层既有的「自动换线路」去走 —— 那是它本来就会处理的事。
 *
 * 文本抽取那四个函数是纯的（不碰网络），单测见 QilinResolverTest；
 * 正则认错的方向很隐蔽（拿上一条线路的模板去解 = 解出别的东西且不报错），所以钉在那边。
 */
internal object QilinResolver {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 站点那份 playerconfig.js 里的解析器模板。 */
    private val PARSE_FIELD = Regex(""""parse"\s*:\s*"([^"]*)"""")

    /** 解析器页面下发的会话 token。 */
    private val API_TOKEN = Regex("""apiToken\s*:\s*"([^"]+)"""")

    /**
     * 一次取链：读模板（按站缓存）→ 要 token → 换地址。
     *
     * 全程不抛：拿不到就回 [StreamResolution.Failed]，原因说人话。
     * 网络本身不通（超时、DNS 解不出）仍由 [Http] 抛出，交给 MediaRepository 兜底 ——
     * 那是「这个源现在答不上话」，和「这一条线路解不出来」是两件事。
     */
    suspend fun resolve(platformUrl: String, cfg: SourceConfig): StreamResolution {
        val template = parseTemplate(cfg)
            ?: return StreamResolution.Failed(
                "站点的 playerconfig.js 里读不到解析器地址 —— 它换了取链的写法",
            )
        val endpoint = resolveEndpoint(template)
            ?: return StreamResolution.Failed("解析器地址不成形，没法接着往下问")
        // 站点自己的播放器就是这么把平台页地址拼上去的，Referer 也照它给
        val page = runCatching {
            Http.get(
                template + encodeComponent(platformUrl),
                referer = cfg.baseUrl.trimEnd('/') + "/",
                timeoutSec = 30,
            )
        }.getOrElse { return StreamResolution.Failed("解析器没有答话：${it.message ?: "请求失败"}") }
        if (page.contains("isUnauthorized")) {
            return StreamResolution.Failed("解析器说这台机器没被授权 —— 换一条线路，或换一个源")
        }
        val token = apiTokenFromPage(page)
            ?: return StreamResolution.Failed("解析器页面里没有下发 token —— 它换了写法")
        val reply = runCatching {
            Http.get(
                "$endpoint?token=${encodeComponent(token)}",
                referer = template.substringBefore('?'),
                // 上游平台取源慢的时候这一问要等几十秒（实测 rrmj 那条 5 秒、qq 那条 10 秒，
                // 但慢的时候确实见过更久的），给足；界面上有「缓冲中」那句兜着
                timeoutSec = 90,
            )
        }.getOrElse { return StreamResolution.Failed("解析器没有答话：${it.message ?: "请求失败"}") }
        return gateOnHostResolvable(streamFromResolve(reply))
    }

    /**
     * 交给播放器之前先验一次「这台设备解不解得开那个域名」。
     *
     * 解析器回吐的地址挂在几个不同的 CDN 域下，而其中有的**在公网 DNS 上已经是
     * NXDOMAIN**（实测 `png.ruyijx.com`，运营方弃用了）。播放器拿到这种地址必然
     * UnknownHostException，但它要等满读超时才报错 —— 用户看到的是「点了黑屏、
     * 卡半分钟才换到别的源」。这里提前一次 DNS 查询把这种地址当场判掉，换源的决策
     * 交回上层，那半分钟就省下来了。
     *
     * 用的是系统解析器（和播放器同一个），所以判据跟播放器实际会不会失败是一致的。
     * 主机上验的时候会被本地代理的 fake-ip 骗过去（任何域名都回个假 IP），
     * 这一句在设备上才是它本来的意思。
     *
     * 认不出主机名（地址本身不成形）时**不拦**：让播放器去报它自己的错，
     * 这里编一句「域名解不开」是假话，真实成因是地址不成形。
     *
     * [resolvable] 做成注入的，是为了单测里不真发 DNS 查询。
     */
    fun gateOnHostResolvable(
        res: StreamResolution,
        resolvable: (String) -> Boolean = defaultResolvable,
    ): StreamResolution {
        val url = (res as? StreamResolution.Ready)?.url ?: return res
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return res
        if (resolvable(host)) return res
        return StreamResolution.Failed(
            "解析器给的地址解不开那个域名（$host）—— 它自己挂的 CDN 已经失效了，换一条线路或换一个源",
        )
    }

    private val defaultResolvable: (String) -> Boolean = { host ->
        // 解不开时 getAllByName 是抛 UnknownHostException，不是回空数组：两种都算「解不开」
        runCatching { java.net.InetAddress.getAllByName(host).isNotEmpty() }.getOrDefault(false)
    }

    /**
     * 解析器模板：读站点的 `/static/js/playerconfig.js`，取第一个非空的 `parse`。
     *
     * 读一次记下来（同 [MaccmsParseResolver.parserBases] 的理由）：它是张静态文件，
     * 而自动重试会把一个源的十几条线路连着试完，每次都重新要一遍就是每次都多打一个请求。
     */
    private suspend fun parseTemplate(cfg: SourceConfig): String? {
        val site = cfg.baseUrl.trimEnd('/')
        parserTemplates[site]?.let { return it }
        val js = runCatching {
            Http.get("$site/static/js/playerconfig.js", referer = "$site/", timeoutSec = 20)
        }.getOrNull() ?: return null
        val template = parseTemplateFromConfig(js) ?: return null
        parserTemplates[site] = template
        return template
    }

    private val parserTemplates = ConcurrentHashMap<String, String>()

    // ---------- 纯文本抽取（单测覆盖这一组） ----------

    /**
     * 从 playerconfig.js 里认解析器模板；一个非空的都没有时返回 null。
     *
     * 取**第一个非空**而不是按线路名查：同一站所有要解析的线路共用同一个解析器
     * （实测七条线路的 parse 一字不差），而调用方手里未必有线路名。
     * 站点把 JSON 塞进 JS 字面量时斜杠常写成 `\/`，还原一次。
     */
    fun parseTemplateFromConfig(js: String): String? =
        PARSE_FIELD.findAll(js)
            .asSequence()
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { it.isNotBlank() }

    /** 从解析器页面里认那颗 apiToken。 */
    fun apiTokenFromPage(html: String): String? = API_TOKEN.find(html)?.groupValues?.get(1)

    /**
     * resolve 接口挂在解析器**自己的域**下，路径固定 `/api/resolve.php`。
     *
     * 只取模板的 scheme://host[:port]：模板后面那串 `?url=` 是喂 token 用的，
     * 拼进接口地址会多带一个空参数。认不出 http/https 的返回 null，不硬拼。
     */
    fun resolveEndpoint(template: String): String? {
        val uri = runCatching { java.net.URI(template.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port/api/resolve.php"
    }

    /**
     * resolve.php 的响应翻成取链结果。
     *
     * `code` 有时是数字有时是字符串（同一站不同线路都见过），按内容取整数。
     * 非 200 时把站点自己那句 `msg` 带回去 —— 那句是「换一个资源或线路」这类人话，
     * 比我们自己编的「解析失败」更能指路。
     *
     * 200 但 url 空 / url 不是 http 开头的都算失败：出错时这一格偶尔回一句提示文字，
     * 直接交给播放器就是「黑屏且没有任何解释」。
     */
    fun streamFromResolve(body: String): StreamResolution {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return StreamResolution.Failed("解析器回的不是能读的那份数据")
        val code = (obj["code"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
        val url = (obj["url"] as? JsonPrimitive)?.content?.trim()
        val msg = (obj["msg"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        if (code != 200) {
            return StreamResolution.Failed(
                msg ?: "解析器没有给出这一集的地址（回的是 code=${(obj["code"] as? JsonPrimitive)?.content ?: "?"}）",
            )
        }
        if (url.isNullOrBlank()) return StreamResolution.Failed("解析器说解出来了，却没给地址")
        if (!url.startsWith("http")) return StreamResolution.Failed("解析器给的不像一个地址")
        return StreamResolution.Ready(url)
    }

    /**
     * 按 JS 的 encodeURIComponent 编一个值（等价于站点脚本里那一步）。
     *
     * 不用 [java.net.URLEncoder]：它按 form 规则把空格编成 `+`，而路径里 `+` 是字面量，
     * 平台页地址带 `+` 时（腾讯那类 id 里真出现过）解出来的就是另一部片。
     */
    private fun encodeComponent(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '_'.code || c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append("%%%02X".format(c))
            }
        }
    }
}
