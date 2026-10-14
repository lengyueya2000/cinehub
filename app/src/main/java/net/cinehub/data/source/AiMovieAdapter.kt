package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 密钥是前端自己带在包里的常量。影枢照同一套协议发请求 ——
 * 不猜算法、不绕过校验，能拿到什么取决于站点愿意给匿名会话什么。
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

    /** 站点给本机签发的匿名会话 Cookie。只保存站点自己发回来的东西。 */
    @Volatile
    private var sessionCookie: String? = config.authToken?.takeIf { it.isNotBlank() }

    /**
     * 带签名地取一次 JSON。
     *
     * 第一次调用会先建匿名会话（站点自己的流程，等于浏览器里的未登录访客），
     * 之后带上它发回来的 Cookie。
     */
    private suspend fun api(pathAndQuery: String, method: String = "GET"): String {
        val headers = AiMovieSign.headers(method, pathAndQuery).toMutableMap()
        sessionCookie?.let { headers["Cookie"] = it }
        val body = Http.getWithHeaders(
            url = base + pathAndQuery,
            headers = headers,
            referer = base,
            timeoutSec = 25,
        )
        return body.body.also {
            body.setCookie?.takeIf { it.isNotBlank() }?.let { sessionCookie = it }
        }
    }

    /** 建匿名会话：站点自己的未登录访客流程，影枢不代为登录任何人的账号。 */
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
            ).setCookie?.takeIf { it.isNotBlank() }?.let { sessionCookie = it }
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
     * 详情：只取分集列表，不预取每集的线路。
     *
     * 站点的结构是「variant（某一季/版本）→ 若干 episode → 每集各有自己的一套线路」，
     * 而**取一集的线路要花解析额度**（实测每集 3 万 token，匿名每日 50 万 ——
     * 一部 22 集的剧光开详情页就吃掉小一半）。所以这里只把分集列出来，
     * 每集带上能取线路的 `aiep:` 标记，等用户真的点开那一集时再解析。
     */
    override suspend fun detail(id: String): MediaDetail {
        ensureSession()
        val variant = id.substringAfter("variant:").ifBlank { id }
        val card = cardOf(variant)
        val episodes = episodeIdsOf(variant)

        val lines = if (episodes.isEmpty()) {
            listOf(PlayLine("正片", listOf(Episode("正片", 1, mark(variant, null)))))
        } else {
            listOf(
                PlayLine(
                    card?.str("title") ?: "正片",
                    episodes.map { (index, name, token) ->
                        Episode(name, index, mark(variant, token))
                    },
                )
            )
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
     * 分集的「播放地址」—— 不是直链，而是取线路所需的坐标。
     *
     * 真正播放时由 [resolveStream] 认出这个前缀再去取线路，
     * 这样额度只在用户真的点开某一集时花，而不是开一次详情页就把整部剧刷一遍。
     */
    private fun mark(variant: String, episodeToken: String?): String =
        "$MARK$variant|${episodeToken ?: ""}"

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
     * 分三种输入：
     * - `aiep:` 标记 → 向站点取这一集的线路，挑一条能直接播的 m3u8（**这一步要花额度**）；
     * - `resolve://` 票证 → 还要站点服务端再算一次，影枢不触发，如实说拿不到；
     * - 已经是媒体直链 → 直接给播放器。
     */
    override suspend fun resolveStream(episodeUrl: String): StreamResolution {
        if (episodeUrl.startsWith(MARK)) {
            ensureSession()
            val rest = episodeUrl.removePrefix(MARK)
            val variant = rest.substringBefore('|')
            // `!= "null"` 是给老任务兜底的：以前 mark() 把 null 直接拼进了坐标，
            // 那样存下来的下载任务解一次字符串就是字面量 "null"，会去请求 ?episode=null。
            val token = rest.substringAfter('|').takeIf { it.isNotBlank() && it != "null" }
            return StreamResolution.Ready(bestLine(variant, token))
        }
        if (episodeUrl.startsWith("resolve://")) return StreamResolution.NeedsClientScript
        if (StreamUrls.isMediaUrl(episodeUrl)) return StreamResolution.Ready(episodeUrl)
        return VideoPlatforms.platformOf(episodeUrl)
            ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
            ?: StreamResolution.Failed("这个地址不是可直接播放的媒体文件")
    }

    /**
     * 取某一集的线路，返回其中**最可能播得动**的一条直链。
     *
     * 站点给的是一堆同质备份（新浪资源 / 红牛资源 / …），用户在界面上分不出好坏，
     * 所以按站点自己的标记挑：已解析过的优先、其次按它的偏好权重。
     * 只留 `url_kind == m3u8` 且本身就是 http 直链的条目 —— `resolve://` 票证不碰。
     *
     * 拿不到就抛，而且要说清是哪一种拿不到：接口没答话、答的样子不认识、
     * 和这一集真的只给了需要二次解析的地址，是三件不同的事。
     * 以前统一写成最后一种，用户对着「站点只给了需要二次解析的地址」根本没法判断，
     * 而真相可能是匿名额度用完了。
     */
    private suspend fun bestLine(variant: String, episodeToken: String?): String {
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
        val candidates = lines.mapNotNull { el ->
            val line = el.jsonObjectOrNull() ?: return@mapNotNull null
            val url = line.str("url") ?: return@mapNotNull null
            if (line.str("url_kind") != "m3u8" || !url.startsWith("http")) return@mapNotNull null
            val resolved = line["resolved"]?.primitiveOrNull()?.content == "true"
            val weight = line["preference_weight"]?.primitiveOrNull()?.content?.toLongOrNull() ?: 0L
            Triple(url, resolved, weight)
        }.sortedWith(
            compareByDescending<Triple<String, Boolean, Long>> { it.second }
                .thenByDescending { it.third }
        )
        if (candidates.isEmpty()) {
            error(
                "这一集站点只给了需要二次解析的地址（resolve:// 那一类），影枢不触发对方的解析。" +
                    "换一条线路或换一个源。"
            )
        }

        // 站点把一堆同质备份都标成 resolved，但其中相当一部分已经 404 了 ——
        // 按它的标记挑一条就交给播放器，用户看到的就是「播放失败：Source error」。
        // 所以这里对前几条真的探一下，只把能取到 m3u8 正文的第一条交给播放器。
        for ((url, _, _) in candidates.take(MAX_PROBE)) {
            if (looksPlayable(url)) return url
        }
        // 全都探不动时退回站点的首选 —— 至少失败原因是它自己的，不是影枢挑的
        return candidates.first().first
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
        val root = runCatching { json.parseToJsonElement(body).jsonObjectOrNull() }
            .getOrNull() ?: return emptyList()
        val cards = root["cards"]?.jsonArrayOrNull() ?: return emptyList()
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

        /** 分集坐标前缀：不是直链，是「variant|token」，播放时才去换线路。 */
        const val MARK = "aiep:"

        /** 最多探几条备选线路 —— 站点往往给二十多条，每条都探太慢。 */
        const val MAX_PROBE = 6

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
