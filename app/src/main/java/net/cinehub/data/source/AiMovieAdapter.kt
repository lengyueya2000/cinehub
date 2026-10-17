package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.cinehub.data.model.AuthMode
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.StreamUrls
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.net.Http
import net.cinehub.data.quietly
import net.cinehub.data.shortReason
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名。
 *
 * 站点前端把「方法 + 路径 + 时间戳 + 随机串」拼成消息算 HMAC-SHA256，
 * 密钥是前端自己带在包里的常量。影枢照同一套协议把请求算出来发过去 ——
 */
internal object AiMovieSign {

    /** 站前端公开分发的客户端密钥（不是任何人的账号凭据）。 */
    private const val KEY = "8b9a908a05eac640e1ee06f52acaa741bfe4ba9e004eeffdbeb635e532e06666"

    private const val CLIENT_NAME = "movie-search-frontend"
    private const val CLIENT_VERSION = "1.0.0"
    private const val BUILD_VERSION =
        "dongpiandi-v2026.10.10.2-download-source-entry-aa4983cf2052-web"
    private const val PROTOCOL_VERSION = "2026-07-05.library-v2.playback-v1"

    private val random = SecureRandom()

    /** 生成一次请求所需的 `x-ai-movie-*` 头。 */
    fun headers(method: String, pathAndQuery: String): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") {
            "%02x".format(it)
        }
        val msg = "$method\n$pathAndQuery\n$ts\n$nonce"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val sig = mac.doFinal(msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return mapOf(
            "x-ai-movie-timestamp" to ts,
            "x-ai-movie-nonce" to nonce,
            "x-ai-movie-signature" to sig,
            "x-ai-movie-client-name" to CLIENT_NAME,
            "x-ai-movie-client-version" to CLIENT_VERSION,
            "x-ai-movie-build-version" to BUILD_VERSION,
            "x-ai-movie-protocol-version" to PROTOCOL_VERSION,
        )
    }
}

/**
 * 懂片帝AI 适配器。
 *
 * 站点是带签名鉴权的前端应用 —— 静态页里没有内容，必须按它的接口协议取数据。
 * 与「网页解析」型源的区别是：这里解析的是它自己的 JSON，不是 HTML；
 * 与「苹果 CMS」的区别是：字段是它自定义的，不是 vod_*。
 */
class AiMovieAdapter(override val config: SourceConfig) : SourceAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val base: String = config.baseUrl.trim().trimEnd('/')

    /**
     * 站点给本机签发的匿名会话 Cookie。只保存站点自己发回来的东西。
     *
     * 用户填的凭证只有选了 **[AuthMode.Cookie]** 时才落进这一份（那样它确实是以 Cookie
     * 的形式发出去的）；选了 [AuthMode.Bearer] 的那份走 [userAuth] 的 `Authorization`
     * 头，**不在这里** —— 原来不管用户选什么，token 都从这个字段当成 Cookie 发出去，
     * 于是一个只认 Bearer 的站点永远收不到它，而界面上只是「填了凭证还是没权限」，
     * 查不出原因。这一份还兼着站点下发的匿名会话，两者是两件事。
     */
    @Volatile
    private var sessionCookie: String? =
        if (config.authMode == AuthMode.Bearer) null
        else config.authToken?.takeIf { it.isNotBlank() }

    /**
     * 用户填的凭证里，需要以**独立请求头**发出去的那一份。
     *
     * 只有 Bearer 走这里（[Http.authHeaders] 给的是 `Authorization: Bearer …`）；
     * Cookie 那一份已经并进 [sessionCookie]，再带上一次就是同一个凭据发两颗。
     * 站点下发的匿名会话仍按原来的方式跟在 Cookie 头上，两者互不顶替。
     */
    private val userAuth: Map<String, String>
        get() = if (config.authMode == AuthMode.Bearer) Http.authHeaders(config) else emptyMap()

    /**
     * 把站点新发回来的 Cookie 并进已有那一份，而不是整条替换。
     *
     * 原来每一次 `Set-Cookie` 都直接 `sessionCookie = 新的`：用户在源配置里填的那份凭证
     * 是这里的初始值，站点一回话就把它冲没了 —— 他登录后填的 token 悄悄失效，
     * 之后每一句「这个源没有这部片」都可能其实是「没带着我的凭证去问」。
     * 按名字合并：站点刷新哪个就换哪个，用户那份它没提过的原样留着。
     */
    private fun mergeCookie(incoming: String) {
        val pairs = LinkedHashMap<String, String>()
        fun absorb(raw: String) = raw.split(';').forEach { piece ->
            val kv = piece.trim()
            val i = kv.indexOf('=')
            if (i > 0) pairs[kv.substring(0, i)] = kv.substring(i + 1)
        }
        sessionCookie?.let(::absorb)
        absorb(incoming)
        sessionCookie = pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 两份线路缓存：一部片的线路名单、一集片的整包线路地址。
     *
     * 挂在**实例**上，不是 companion object。原来它们是进程级的：接两个同型源
     * （两个都走这套接口的站）时，A 站那部片的线路名单会被当成 B 站同一 id 的答案直接返回 ——
     * 站点不同、token 不同，拿回来的地址要么 404、要么放出另一家站的内容。
     * 实例本来就按连接配置缓存在 [SourceAdapters] 里，跨调用照样复用，
     * 但复用范围不再跨过源边界。
     */
    private val variantLines = LinkedHashMap<String, List<LineOpt>>()
    private val episodeLines = LinkedHashMap<String, List<LineOpt>>()

    @Synchronized
    private fun <V> cached(map: MutableMap<String, V>, key: String): V? = map[key]

    @Synchronized
    private fun <V> remember(map: MutableMap<String, V>, key: String, value: V) {
        map[key] = value
        while (map.size > CACHE_MAX) map.remove(map.keys.first())
    }

    /**
     * 带签名地取一次 JSON。
     *
     * 第一次调用会先建匿名会话（站点自己的流程，等于浏览器里的未登录访客），
     * 之后带上它发回来的 Cookie。用户选了 Bearer 的那份凭证另走 `Authorization`
     * 头（见 [userAuth]），与站点的匿名会话并存、互不顶替。
     */
    private suspend fun api(pathAndQuery: String, method: String = "GET"): String {
        val headers = AiMovieSign.headers(method, pathAndQuery).toMutableMap()
        sessionCookie?.let { headers["Cookie"] = it }
        headers.putAll(userAuth)
        val body = Http.getWithHeaders(
            url = base + pathAndQuery,
            headers = headers,
            referer = base,
            timeoutSec = 25,
        )
        return body.body.also {
            body.setCookie?.takeIf { it.isNotBlank() }?.let { mergeCookie(it) }
        }
    }

    /**
     * 带签名地 POST 一次 JSON。
     *
     * 签名算的是「方法 + 路径」，和 [api] 同一套；站点那两个要签名的写口
     * （建匿名会话、换官方线路的地址）都走这里。
     */
    private suspend fun apiPost(pathAndQuery: String, payload: String): String {
        val headers = AiMovieSign.headers("POST", pathAndQuery).toMutableMap()
        headers["Content-Type"] = "application/json"
        sessionCookie?.let { headers["Cookie"] = it }
        headers.putAll(userAuth)
        val r = Http.post(
            url = base + pathAndQuery,
            json = payload,
            headers = headers,
            timeoutSec = 25,
        )
        r.setCookie?.takeIf { it.isNotBlank() }?.let { mergeCookie(it) }
        if (r.status !in 200..299) error(Http.failureReason(r.status, r.body))
        return r.body
    }

    /** 建匿名会话：站点自己的未登录访客流程，用的是站点发给本机的这一份会话。 */
    private suspend fun ensureSession() {
        if (sessionCookie != null) return
        quietly {
            val headers = AiMovieSign.headers("POST", ANON_PATH).toMutableMap()
            headers["Content-Type"] = "application/json"
            Http.post(
                url = base + ANON_PATH,
                json = """{"anonymous_id":"cinehub_${System.currentTimeMillis()}"}""",
                headers = headers,
                timeoutSec = 15,
            ).setCookie?.takeIf { it.isNotBlank() }?.let { mergeCookie(it) }
        }
        // 建不成会话不在这儿报错：后面的 api() 会撞上同一个原因，那时报一次就够了，
        // 而且报的是用户真正做的那一步（搜索/取线路），不是后台的准备工作。
    }

    override suspend fun browse(page: Int): List<Media> {
        ensureSession()
        return parseCards(api("/v1/browse/catalog?page=$page&limit=48"))
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        ensureSession()
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        return parseCards(api("/v1/browse/catalog?q=$q&page=$page&limit=48"))
    }

    /**
     * 详情：分集列表 + 这一部片的官方线路。
     *
     * 站点的结构是「variant（某一季/版本）→ 若干 episode → 每集各有自己的一套线路」。
     * **线路名单不在任何免费接口里**（实测：`/v1/catalog/{variant}` 的
     * `playback_groups` 恒为空数组，每集只白送一个 `playback_count` 条数；
     * 想要名字只能问某一集的线路），而问一次要花 3 万 token（匿名每日 50 万）。
     * 但这一问给的是**那一集的全部线路**，不是单条 —— 所以一部片只花一次，
     * 也不至于像早期版本那样预取每一集（22 集的剧开一次详情页就吃掉小半天额度）。
     *
     * 名单只留官方那几条（见 [lineNamesOf]）：其余是同一个视频挂在各资源站上的同质备份。
     * 名单按 variant 记在进程里，同一部片再开不重复花；
     * 问不出来（额度用完、网络断、站点改版、这部片没有官方线路）时退回「智能选路」那一条，
     * 而不是让整个详情页报错 —— 用户只是想看一眼这部片。
     */
    override suspend fun detail(id: String): MediaDetail {
        ensureSession()
        val variant = id.substringAfter("variant:").ifBlank { id }
        val card = cardOf(variant)
        // 电影没有分集：正文就是「这一集」，坐标里的 token 留空
        val episodes = episodeIdsOf(variant).ifEmpty { listOf(Triple(1, "正片", "")) }
        val listed = lineNamesOf(variant, episodes.first().third.takeIf { it.isNotBlank() })

        /** 一条线路下的分集：每集带自己的坐标，点开来才去换那一集的地址。 */
        fun epsOf(lineId: String?) = episodes.map { (index, name, token) ->
            Episode(name, index, mark(variant, token.ifBlank { null }, lineId))
        }

        val lines = when {
            // 只有一条时不必摆「线路 1/1」，直接沿用站点给的名字
            listed == null -> listOf(PlayLine("智能选路", epsOf(null)))
            listed.size == 1 -> listOf(PlayLine(listed.first().label, epsOf(listed.first().id)))
            else -> listed.map { PlayLine(it.label, epsOf(it.id)) }
        }

        val media = Media(
            id = variant,
            title = card?.str("title") ?: config.name,
            cover = card?.str("poster_url"),
            year = card?.str("year"),
            area = card?.str("area"),
            category = CONTENT_KIND[card?.str("content_kind")] ?: card?.str("content_kind"),
            remark = card?.str("remarks"),
            actors = card?.get("actors")?.jsonArrayOrNull()?.joinToString(" ") {
                it.primitiveOrNull()?.content ?: ""
            }?.takeIf { it.isNotBlank() },
            intro = card?.str("overview") ?: card?.str("remarks"),
            sourceId = config.id,
        )
        return MediaDetail(media = media, lines = lines, intro = media.intro)
    }

    /**
     * 分集的「播放地址」—— 不是直链，而是取线路所需的坐标：`variant|这一集的 token|点名的线路`。
     *
     * 真正播放时由 [resolveStream] 认出这个前缀再去取线路，
     * 这样额度只在用户真的点开某一集时花，而不是开一次详情页就把整部剧每集刷一遍。
     * 第三段为空（老下载任务、以及退回「智能选路」时建的坐标）就让 [bestLine] 自己挑一条。
     */
    private fun mark(variant: String, episodeToken: String?, lineId: String? = null): String =
        MARK + listOf(variant, episodeToken ?: "", lineId ?: "").joinToString("|") { escField(it) }

    /**
     * 坐标里每一段都要转义。
     *
     * 这三段是拿 `|` 分的，而站点给的 variant / token 是它自己的字符串，没人保证里面没有 `|`：
     * 有的话读回来就切成四段，第三段被当成「点名的线路」，于是这一集拿到的是**另一条线路**
     * 的地址 —— 播得开，但放的不是用户点的那一条。`%` 也要转义，不然读的那一侧会解错。
     */
    private fun escField(s: String) = s.replace("%", "%25").replace("|", "%7C")

    private fun unescField(s: String) = s.replace("%7C", "|").replace("%25", "%")

    /** 分集列表：`/v1/catalog/{variant}/episodes`。这一条不花额度。 */
    private suspend fun episodeIdsOf(variant: String): List<Triple<Int, String, String>> {
        val enc = java.net.URLEncoder.encode(variant, "UTF-8")
        val root = quietly {
            json.parseToJsonElement(api("/v1/catalog/$enc/episodes")).jsonObjectOrNull()
        }.getOrNull() ?: return emptyList()
        return root["episodes"]?.jsonArrayOrNull()?.mapIndexedNotNull { i, el ->
            val o = el.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val token = o.str("token") ?: return@mapIndexedNotNull null
            Triple(i + 1, o.str("title") ?: "第 ${i + 1} 集", token)
        } ?: emptyList()
    }

    /** 按 variant id 取目录卡片（标题 / 封面 / 演职员）。这一条也不花额度。 */
    private suspend fun cardOf(variant: String): JsonObject? {
        val enc = java.net.URLEncoder.encode(variant, "UTF-8")
        return quietly {
            json.parseToJsonElement(api("/v1/catalog/$enc")).jsonObjectOrNull()
        }.getOrNull()
    }

    /**
     * 解析直链。
     *
     * 分几种输入：
     * - `aiep:` 坐标且点了名 → 取这一集全部线路里用户要的那一条（**一次调用，额度按次不算按条**）；
     * - `aiep:` 坐标但没点名（老任务 / 名单没取到时的「智能选路」）→ 自己挑一条最播得动的；
     * - `resolve://` 票证 → 拿这张票再问站点一次，换成能播的地址（见 [resolveTicket]）；
     * - 已经是媒体直链 → 直接给播放器。
     */
    override suspend fun resolveStream(episodeUrl: String): StreamResolution {
        if (episodeUrl.startsWith(MARK)) {
            ensureSession()
            val parts = episodeUrl.removePrefix(MARK).split('|')
            val variant = unescField(parts.firstOrNull().orEmpty())
            // `!= "null"` 是给老任务兜底的：以前 mark() 把 null 直接拼进了坐标，
            // 那样存下来的下载任务解一次字符串就是字面量 "null"，会去请求 ?episode=null。
            val token = parts.getOrNull(1)?.let { unescField(it) }.takeIf {
                it != null && it.isNotBlank() && it != "null"
            }
            val lineId = parts.getOrNull(2)?.let { unescField(it) }.takeIf {
                it != null && it.isNotBlank() && it != "null"
            }
            return if (lineId == null) StreamResolution.Ready(bestLine(variant, token))
            else pickLine(variant, token, lineId)
        }
        if (episodeUrl.startsWith("resolve://")) {
            ensureSession()
            return StreamResolution.Ready(resolveTicket(episodeUrl.removePrefix("resolve://")))
        }
        if (StreamUrls.isMediaUrl(episodeUrl)) return StreamResolution.Ready(episodeUrl)
        return VideoPlatforms.platformOf(episodeUrl)
            ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
            ?: StreamResolution.Failed("这个地址不是可直接播放的媒体文件")
    }

    /**
     * 站点的一条线路。
     *
     * `id` 用的是 `provider_id`（`hongniu` / `bytevod-lv2` 这种）：它跨集稳定，
     * 同一部片的每一集都会带着同名同 id 的那几条，所以能当线路坐标写进 [mark] 里。
     */
    private data class LineOpt(
        val id: String,
        val label: String,
        val url: String,
        val kind: String,
        val weight: Long,
        /**
         * 官方线路的票证。
         *
         * 站点把「1080P-官方Z」「高清-官方V」那几条标成 `resolve_mode == parse`，
         * 直接 url 给的是 `resolve://<票>`，要再拿这张票去问一次才换得成真的地址
         * （见 [resolveTicket]）。
         */
        val ticket: String?,
    ) {
        /** 已经是一个能直接交给播放器的 m3u8 直链。 */
        val direct: Boolean get() = kind == "m3u8" && url.startsWith("http")

        /**
         * 站点的官方线路（1080P-官方Z / 高清-官方B·普通话 / 海外-官方C…）。
         *
         * 懂片帝AI 只列这些：其余二十几条是同一个视频挂在各资源站上的同质备份，
         * 站点自己也把它们排在官方那几条后面、权重给得更低。
         */
        val official: Boolean get() = ticket != null
    }

    /**
     * 线路名单：`display_label` 实测就是站点自己那排格子上写的字
     * （「新浪资源」「1080P-官方Z」「优酷」），不用自己编名字。
     */
    private fun labelOf(o: JsonObject): String? =
        o.str("display_label") ?: o.str("label") ?: o.str("provider_name")

    /**
     * 一部片有哪些线路。**花一次额度**（站点只在你问某一集时才把名单给你），
     * 按 variant 记在进程里，同一部片再开详情不再花。
     *
     * 只列**官方线路**：站点那二十几条里，除了官方那几条，剩下的都是同一个视频
     * 挂在各个资源站上的同质备份（实测「无水印 / 索尼 / 无尽 / 最大 / 牛牛」给的是同一个
     * `76EpC1bCxk1`），画质和时效都不如官方，摆在格子里只是噪声。
     * 这部片一条官方线路都没有时返回 null，让详情退回「智能选路」。
     *
     * 额度用完、网络断、站点改版也返回 null —— 都不该让用户连这部片长什么样都看不到。
     */
    private suspend fun lineNamesOf(variant: String, probeToken: String?): List<LineOpt>? {
        cached(variantLines, variant)?.let { return it }
        val opts = quietly { optionsOf(variant, probeToken) }.getOrNull() ?: return null
        val listed = opts.filter { it.official }.distinctBy { it.url }.distinctBy { it.id }
        if (listed.isEmpty()) return null
        remember(variantLines, variant, listed)
        return listed
    }

    /**
     * 用户点名要的那一条线路。
     *
     * 这里不偷偷换成别的：这一条取不到就说取不到，界面上旁边还有别的格子可以点 ——
     * 悄悄换一条等于把用户挑的东西丢掉，还会让他以为站点只有这一条能播。
     * 这一集的整包地址在 [optionsOf] 里已经存过一份，所以同一集之间换线路不再花额度。
     */
    private suspend fun pickLine(variant: String, token: String?, lineId: String): StreamResolution {
        val opts = optionsOf(variant, token)
        val o = opts.firstOrNull { it.id == lineId }
            ?: return StreamResolution.Failed("站点这一集没给出这条线路的地址 —— 换一条线路试试")
        val platform = VideoPlatforms.platformOf(o.url)
        return when {
            o.direct && looksPlayable(o.url) -> StreamResolution.Ready(o.url)
            o.direct -> StreamResolution.Failed(
                "「${o.label}」这一集现在取不到正文（地址在，打不开）—— 换一条线路试试",
            )
            // 官方那几条（1080P-官方Z / 高清-官方V …）不给直链，只给一张票：拿票再问一次
            o.ticket != null -> StreamResolution.Ready(resolveTicket(o.ticket))
            o.url.startsWith("resolve://") -> StreamResolution.NeedsClientScript
            platform != null -> StreamResolution.ThirdPartyPage(o.url, platform)
            else -> StreamResolution.Failed("「${o.label}」给的地址不是能播的媒体文件")
        }
    }

    /**
     * 拿官方线路那张票换真地址。
     *
     * 站点自己的前端也是这么做的：`resolve_mode == parse` 的那几条不给直链，
     * 而是给一张 `resolve://<票>`，再 POST 一次 `/v1/playback/resolve-line` 才换成能播的地址。
     * 这一步用的还是同一套签名头（站点公开分发给每个访客的那份常量），不跑它的脚本。
     *
     * 实测这一次**不另计额度**：响应里 `quota_delta` 是 null，而问线路名单那次是 30000。
     * 换回来的地址带 `x-expires`（约一个半小时），所以每次播放现换，不留存。
     */
    private suspend fun resolveTicket(ticket: String): String {
        val body = apiPost(
            "/v1/playback/resolve-line",
            // JsonPrimitive.toString() 产出的就是带引号并转义好的 JSON 字符串
            """{"ticket":${JsonPrimitive(ticket)}}""",
        )
        val root = quietly { json.parseToJsonElement(body).jsonObjectOrNull() }.getOrNull()
            ?: error("站点换地址时答的不是预期那份 JSON（可能改版了）")
        val line = root["line"]?.jsonObjectOrNull()
        val url = line?.str("url")
        if (url.isNullOrBlank()) {
            val why = root.str("message") ?: root.str("error") ?: "站点没给回地址"
            error("这条官方线路的地址没换出来：$why")
        }
        return url
    }

    /**
     * 取某一集的**全部**线路。
     *
     * 站点按「一次调用给出这一集的所有线路」计价（实测一次 3 万 token），
     * 所以这一包的产物都留在进程内：问名单要它，点线路也要它，切来切去不再多花。
     *
     * 拿不到就抛，而且要说清是哪一种拿不到：接口没答话、答的样子不认识、
     * 和这一集真的只给了需要二次解析的地址，是三件不同的事。
     * 以前统一写成最后一种，用户对着「站点只给了需要二次解析的地址」根本没法判断，
     * 而真相可能是匿名额度用完了。
     */
    private suspend fun optionsOf(variant: String, episodeToken: String?): List<LineOpt> {
        val key = "$variant|${episodeToken ?: ""}"
        cached(episodeLines, key)?.let { return it }
        val enc = java.net.URLEncoder.encode(variant, "UTF-8")
        val path = if (episodeToken == null) "/v1/playback/entry/$enc"
        else "/v1/playback/entry/$enc?episode=" + java.net.URLEncoder.encode(episodeToken, "UTF-8")
        val body = quietly { api(path) }
            .fold(
                onSuccess = { it },
                onFailure = { e ->
                    error(
                        "向站点问这一集的线路时没问出来：${e.shortReason()}" +
                            "（懂片帝AI 的匿名额度按天算，用完了也会这样）"
                    )
                },
            )
        val root = quietly { json.parseToJsonElement(body).jsonObjectOrNull() }.getOrNull()
            ?: error("站点这次的回答不是预期那份 JSON（可能改版了）")
        val lines = root["line_options"]?.jsonArrayOrNull()
            ?: error("站点没给出这一集的线路列表（返回里少了 line_options）")
        val parsed = lines.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            val url = o.str("url") ?: return@mapNotNull null
            LineOpt(
                id = o.str("provider_id") ?: o.str("play_from") ?: return@mapNotNull null,
                label = labelOf(o) ?: "线路",
                url = url,
                kind = o.str("url_kind") ?: "",
                weight = o["preference_weight"]?.primitiveOrNull()?.content?.toLongOrNull() ?: 0L,
                // 站点自己的取票顺序（抄它前端那套判断）：显式的 resolve_ticket 字段，
                // 或者 url 就是 resolve://<票>，或者它自己标了 url_kind == resolve_ticket
                ticket = when {
                    o.str("resolve_mode") != "parse" && o["resolve_required"]
                        ?.primitiveOrNull()?.content != "true" -> null
                    else -> o.str("resolve_ticket")
                        ?: url.removePrefix("resolve://").takeIf { it.isNotBlank() && it != url }
                        ?: url.takeIf { o.str("url_kind") == "resolve_ticket" }
                },
            )
        }.sortedWith(
            // 按站点自己的偏好权重排：它把官方线路标成 999 并默认选中，所以官方排在第一格。
            // 第一格不是摆设 —— 切源续播、从记录里续播都走 lines.first()，
            // 排在最前的就是站点自己认为该给用户的那条。
            compareByDescending<LineOpt> { it.weight }
                .thenByDescending { it.official }
                .thenByDescending { it.direct }
        )
        remember(episodeLines, key, parsed)
        return parsed
    }

    /**
     * 没点名时挑一条（老下载任务留下的坐标、以及这部片压根没有官方线路时的退路）。
     *
     * 先试站点的官方线路 —— 那是它自己默认选中的那一条，权重 999。
     * 换不出地址（票过期、这条今天不给）才退回那些资源站的 m3u8 同质备份：
     * 画质差一档，但总比让用户看到「播不了」强。
     */
    private suspend fun bestLine(variant: String, episodeToken: String?): String {
        val opts = optionsOf(variant, episodeToken)
        val official = opts.firstOrNull { it.official }
        val ticket = official?.ticket
        if (ticket != null) {
            quietly { resolveTicket(ticket) }.getOrNull()?.let { return it }
        }

        val candidates = opts.filter { it.direct }
        if (candidates.isEmpty()) {
            error(
                if (ticket != null)
                    "官方线路「${official?.label}」这会儿换不出地址（票可能过期了）" +
                        "—— 退出去重开一次这部片，或换一个源。"
                else
                    "这一集站点没给出一条能直接播的地址（既没有官方线路，也没有能用的 m3u8）" +
                        "—— 换一条线路或换一个源。"
            )
        }

        // 站点把一堆同质备份都标成 resolved，但其中相当一部分已经 404 了 ——
        // 按它的标记挑一条就交给播放器，用户看到的就是「播放失败：Source error」。
        // 所以这里对前几条真的探一下，只把能取到 m3u8 正文的第一条交给播放器。
        for (c in candidates.take(MAX_PROBE)) {
            if (looksPlayable(c.url)) return c.url
        }
        // 全都探不动时退回站点的首选 —— 至少失败原因是它自己的，不是影枢挑的
        return candidates.first().url
    }

    /** 探一下这个地址现在是否真的给得出 m3u8 正文。 */
    private suspend fun looksPlayable(url: String): Boolean = quietly {
        val body = Http.getWithHeaders(url, headers = emptyMap(), timeoutSec = 10).body
        body.startsWith("#EXTM3U") || body.contains("#EXT-X-STREAM-INF")
    }.getOrDefault(false)

    /**
     * 搜索结果的卡片字段。
     *
     * `id` 就是 variant（同一部片的某一季/版本），详情与播放地址都按它取，
     * 所以这里直接把它当 Media.id —— 详情页不需要再猜一遍 id。
     */
    private fun parseCards(body: String): List<Media> {
        val root = runCatching { json.parseToJsonElement(body).jsonObjectOrNull() }.getOrNull()
            ?: error("这个源给回来的不是预期的 JSON，接口多半改了")
        // 「搜不到」和「读不懂」是两句话：`cards: []` 是站点给的答案，
        // 整个字段不存在是这一版代码跟不上它。原来两者都返回空列表，
        // 于是接口一改，界面上就成了一句「这个源里没有这部片」——
        // 用户会去换关键词、换源，而真正坏掉的是解析。
        val cards = root["cards"]?.jsonArrayOrNull()
            ?: error("这个源的返回里没有「cards」这一项，这一版解析跟不上它的接口")
        return cards.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            // 站点标记成不可播的条目不进列表 —— 否则搜索结果里会混进点开就是「拿不到」的坑
            if (o["availability"]?.jsonObjectOrNull()?.get("playable")?.primitiveOrNull()
                    ?.content == "false"
            ) return@mapNotNull null

            Media(
                id = id,
                title = o.str("title") ?: return@mapNotNull null,
                cover = o.str("poster_url"),
                year = o.str("year"),
                area = o.str("area"),
                category = CONTENT_KIND[o.str("content_kind")] ?: o.str("content_kind"),
                remark = o.str("remarks"),
                actors = o["actors"]?.jsonArrayOrNull()?.joinToString(" ") {
                    it.primitiveOrNull()?.content ?: ""
                }?.takeIf { it.isNotBlank() },
                intro = o.str("overview"),
                sourceId = config.id,
            )
        }.distinctBy { it.id }
    }

    private fun JsonObject.str(field: String): String? {
        val p = this[field] as? JsonPrimitive ?: return null
        // 该接口把数字、空值都以字符串形式下发（year 是数字、某些字段是 "null"）
        if (!p.isString && p.content == "null") return null
        return p.content.takeIf { it.isNotBlank() }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
        this as? kotlinx.serialization.json.JsonArray

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? =
        this as? JsonPrimitive

    private companion object {
        const val ANON_PATH = "/v1/users/anonymous"

        /** 分集坐标前缀：不是直链，是「variant|集 token|线路 id」，播放时才去换线路。 */
        const val MARK = "aiep:"

        /** 最多探几条备选线路 —— 站点往往给二十多条，每条都探太慢。 */
        const val MAX_PROBE = 6

        /**
         * 线路缓存的条数上限。缓存本身见实例上的 [variantLines] / [episodeLines] ——
         * 那两份必须按源各留一份，不能是进程级的。
         */
        const val CACHE_MAX = 48

        /** 站点的内容类型枚举 → 中文分类名。 */
        val CONTENT_KIND = mapOf(
            "movie" to "电影",
            "series" to "剧集",
            "short_drama" to "短剧",
            "anime" to "动漫",
            "variety" to "综艺",
            "documentary" to "纪录片",
            "sports" to "体育",
        )
    }
}
