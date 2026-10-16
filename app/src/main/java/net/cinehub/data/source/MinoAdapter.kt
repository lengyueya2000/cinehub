package net.cinehub.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * MINO 型自建 Apple-CMS 后端的适配器。
 *
 * 站点形态与懂片帝AI 同属「静态页里没内容，得按它自己的接口协议取数」，
 * 但握手与取流是另一套：
 *
 * 1. 先做一次 ECDH 握手（临时 secp256r1 密钥对 + 服务端公钥 → HKDF 派生签名钥与
 *    响应体钥），之后每个请求带 HMAC-SHA256 签名头；
 * 2. 响应正文可能是 AES-256-GCM 加密的信封，用响应体钥拆回明文（[MinoProtocol]）；
 * 3. 接口里**没有**能直接播的地址 —— 每一集给的是一个加密票证 `tk:...`，
 *    播放时才换 play_ticket、再拿票据下发的解析接口现算成 m3u8。
 *
 * 与网页解析型源的区别就在第三步：换流是后端自己的解析器做的，这里照它前端
 * 那条路把它下发的地址拼出去，不跑它的脚本。
 *
 * 这是给**有权限的后端**用的接入（自建 / 自有授权站点）：会话凭据由服务端签发给
 * 本机、设备标识按安装生成，和它给自家客户端发的是同一套东西。
 */
class MinoAdapter(override val config: SourceConfig) : SourceAdapter {

    private val base: String = config.baseUrl.trim().trimEnd('/')

    /** 装机会话的设备 id，第一次真正发请求时（在 IO 线程上）才去读盘。 */
    private val deviceId: String by lazy { MinoDeviceId.get() }

    /**
     * 一次握手的产物：会话 id、派生的两把钥、以及本地记的到期时刻。
     *
     * `expires_in` 只有十分钟，而会话缓存在实例上 —— 用户慢悠悠挑片很容易跨过
     * 十分钟，所以走到过期的会话要重握手，而不是等着被后端 4604 打回来。
     */
    private class Session(
        val sessionId: String,
        val signKey: ByteArray,
        val bodyKey: ByteArray?,
        val deadlineMs: Long,
    )

    @Volatile
    private var session: Session? = null

    /** 同一份连接只握一次手：并发搜索时别让多个握手同时打过去。 */
    private val sessionMutex = Mutex()

    private suspend fun ensureSession(): Session {
        session?.let { if (System.currentTimeMillis() < it.deadlineMs) return it }
        return sessionMutex.withLock {
            // 排队期间别的手握完了，直接复用那一次的结果，不再重发握手
            val current = session
            val now = System.currentTimeMillis()
            if (current != null && now < current.deadlineMs) current else handshake()
        }
    }

    /**
     * 握手：`POST /api/v1/security/init`，带临时公钥，换回服务端公钥，ECDH + HKDF 派生双钥。
     *
     * 后端有两个分支：给了 `server_pub` 走 ECDH；只给 `sign_key` 走明文钥、正文不加密
     * （自建站关掉加密时的形态）。两条都接，认响应的样子而不是认配置。
     */
    private suspend fun handshake(): Session {
        val client = MinoSign.ClientEcdh()
        val body = "{\"platform\":\"$PLATFORM\",\"version\":\"$APP_VERSION\"," +
            "\"device_id\":\"$deviceId\",\"client_pub\":\"" +
            MinoSign.encodeBase64(client.publicPoint) + "\"}"
        val r = Http.post(
            url = base + INIT_PATH,
            json = body,
            headers = mapOf("X-Device-Risk" to DEVICE_RISK),
            timeoutSec = 20,
        )
        if (r.status !in 200..299) error(Http.failureReason(r.status, r.body))
        val root = r.body.jsonRootOrNull()
            ?: error("和这个后端握手时它答的不是预期那份 JSON（多半不是这一型源，或接口改了）")
        val data = root["data"]?.asObjectOrNull()
            ?: error(
                "握手没成：" + (root.str("msg") ?: "它没给回 data") +
                    "（码 " + (root["code"]?.asPrimitiveOrNull()?.content ?: "?") + "）",
            )
        val sessionId = data.str("session_id") ?: error("握手响应里没有 session_id")
        val expiresIn = data["expires_in"]?.asPrimitiveOrNull()?.content?.toLongOrNull() ?: 600L

        val serverPub = data.str("server_pub")?.let { MinoSign.decodeBase64(it) }
        val signKey: ByteArray
        val bodyKey: ByteArray?
        if (serverPub != null && MinoSign.looksLikePoint(serverPub)) {
            val shared = MinoSign.sharedSecret(client.privateKey, serverPub)
            val salt = sessionId.toByteArray(Charsets.UTF_8)
            signKey = MinoSign.hkdf(shared, salt, INFO_SIGN_KEY)
            bodyKey = MinoSign.hkdf(shared, salt, INFO_BODY_KEY)
        } else {
            // plain 分支：后端只发了一把签名钥，正文不加密
            signKey = data.str("sign_key")?.toByteArray(Charsets.UTF_8)
                ?: error("握手响应里既没有可用的 server_pub，也没有 sign_key —— 这一版的握手跟不上它")
            bodyKey = null
        }
        val s = Session(
            sessionId = sessionId,
            signKey = signKey,
            bodyKey = bodyKey,
            // 提前一分钟收，免得卡在过期那一瞬发出去
            deadlineMs = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(60L) * 1000L,
        )
        session = s
        return s
    }

    /**
     * 生成一次请求的签名头。
     *
     * ts 与 nonce 在**这里**一次生成、既进 canonical 又进头 —— 分两处生成会让签的那份
     * 和发的那份对不上（后端正是逐字节比这两样：签错回 4600，没签回 4604）。
     */
    private suspend fun authHeaders(
        s: Session,
        method: String,
        path: String,
        query: String,
        body: ByteArray?,
    ): MutableMap<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = MinoSign.newNonce()
        val bodySha = body?.let { MinoSign.sha256Hex(it) } ?: MinoSign.EMPTY_SHA256
        val sign = MinoSign.sign(
            signKey = s.signKey, method = method, path = path, query = query, body = body,
            timestampSec = ts, nonceHex = nonce, deviceId = deviceId,
            sessionId = s.sessionId, appVersion = APP_VERSION,
        )
        return mutableMapOf(
            "X-Session-Id" to s.sessionId,
            "X-Timestamp" to ts,
            "X-Nonce" to nonce,
            "X-Sign" to sign,
            "X-Body-Sha256" to bodySha,
            "X-App-Version" to APP_VERSION,
            "X-Device-Id" to deviceId,
        )
    }

    /** 一次签名 GET（pathAndQuery 是相对 base 的，含查询串）。 */
    private suspend fun apiGet(pathAndQuery: String): String {
        val s = ensureSession()
        val headers = authHeaders(
            s, "GET", pathAndQuery.substringBefore('?'), pathAndQuery.substringAfter('?', ""), null,
        ).apply { this["X-Device-Risk"] = DEVICE_RISK }
        val r = Http.getWithHeaders(base + pathAndQuery, headers = headers, timeoutSec = 25)
        return MinoProtocol.unwrapEnvelope(r.body, s.bodyKey)
    }

    /** 一次签名 POST（JSON 正文 —— 签的字节必须等于发的字节，后端逐字节比 X-Body-Sha256）。 */
    private suspend fun apiPost(path: String, jsonBody: String, act: String? = null): String {
        val s = ensureSession()
        val headers = authHeaders(s, "POST", path, "", jsonBody.toByteArray(Charsets.UTF_8))
            .apply {
                this["X-Device-Risk"] = DEVICE_RISK
                act?.let { this["X-Act"] = it }
            }
        val r = Http.post(url = base + path, json = jsonBody, headers = headers, timeoutSec = 25)
        if (r.status !in 200..299) error(Http.failureReason(r.status, r.body))
        return MinoProtocol.unwrapEnvelope(r.body, s.bodyKey)
    }

    // ---------- SourceAdapter 的四个读接口 ----------

    /** 首页：浏览全库（type_id=0 即不限分类，实测给出整个片库的分页）。 */
    override suspend fun browse(page: Int): List<Media> {
        val root = apiJson("/api/v1/category/videos?type_id=0&page=$page&page_size=$PAGE_SIZE")
        return cardsOf(root["data"]?.asObjectOrNull()?.get("videos"))
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val root = apiJson("/api/v1/search?wd=${enc(keyword)}&page=$page&page_size=$PAGE_SIZE")
        return cardsOf(root["data"]?.asObjectOrNull()?.get("hits"))
    }

    /**
     * 详情：线路名单与分集。
     *
     * 接口不给 `vod_play_url`（实测恒为空串）；线路在 `vod_url_with_player[]` 里，
     * 每一条自带站点给的名字（「小腾4K」「1080P」「小艺」），`url` 是 `第N集$tk:票证`
     * 用 `#` 连接的整串。电影同样是一条「第 1 集」，界面上就是单集。
     */
    override suspend fun detail(id: String): MediaDetail {
        val root = apiJson("/api/v1/video/detail?id=${enc(id)}")
        val data = root["data"]?.asObjectOrNull()
            ?: error("这个后端对这部片没给出详情（返回里少了 data）")
        val info = data["vod_info"]?.asObjectOrNull()
            ?: error("这个后端没给出这部片的 vod_info（接口可能改了）")

        val media = Media(
            id = id,
            title = info.str("vod_name") ?: config.name,
            cover = info.str("vod_pic") ?: info.str("vod_pic_slide"),
            year = info.str("vod_year") ?: info.str("vod_pubdate")?.take(4),
            area = info.str("vod_area"),
            category = info.str("vod_class")?.substringBefore(','),
            remark = info.str("vod_remarks"),
            score = info["vod_score"]?.asPrimitiveOrNull()?.content?.takeIf { it.isNotBlank() && it != "0" },
            actors = info.str("vod_actor"),
            director = info.str("vod_director"),
            intro = info.str("vod_blurb") ?: info.str("vod_content"),
            sourceId = config.id,
        )

        val srcs = info["vod_url_with_player"]?.asArrayOrNull()
        val lines = srcs?.mapIndexedNotNull { i, el ->
            val o = el.asObjectOrNull() ?: return@mapIndexedNotNull null
            val raw = o.str("url") ?: return@mapIndexedNotNull null
            val eps = MinoProtocol.episodesFromUrl(raw, MARK, TOKEN_PREFIX)
                .map { Episode(name = it.name, index = it.index, playUrl = it.coordinate) }
            if (eps.isEmpty()) return@mapIndexedNotNull null
            PlayLine(o.str("name")?.takeIf { it.isNotBlank() } ?: "线路 ${i + 1}", eps)
        }.orEmpty()

        return MediaDetail(
            media = media,
            // 一条线路都没有时给一条「默认」空坐标 —— 播放器会如实报「这一集没给出票证」，
            // 比整个详情页抛错更接近真相：这部片确实在库上，只是没带出票证
            lines = lines.ifEmpty { listOf(PlayLine("默认", listOf(Episode("正片", 1, MARK)))) },
            intro = media.intro,
        )
    }

    /**
     * 解析直链：`mino:tk:` 坐标 → play_ticket → 票据下发的解析接口现算 → 最终 m3u8。
     *
     * 票据验签不在客户端做，见 [MinoProtocol.ticketPayload] 的说明。
     */
    override suspend fun resolveStream(episodeUrl: String): StreamResolution {
        if (!episodeUrl.startsWith(MARK)) {
            if (StreamUrls.isMediaUrl(episodeUrl)) return StreamResolution.Ready(episodeUrl)
            return VideoPlatforms.platformOf(episodeUrl)
                ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
                ?: StreamResolution.Failed("这个地址不是这个源给的票证，也不是可直接播放的媒体文件")
        }
        val token = episodeUrl.removePrefix(MARK)
        if (token.isBlank()) return StreamResolution.Failed("这一集这个后端没给出票证，换不出地址 —— 换一条线路试试")
        return try {
            val ticketJson = apiPost(
                "/api/v1/vod/ticket",
                // JsonPrimitive.toString() 产出的就是带引号并转义好的 JSON 字符串
                """{"token":${JsonPrimitive(token)}}""",
                act = "play:${System.currentTimeMillis() % 10000}",
            )
            val root = ticketJson.jsonRootOrNull() ?: error("换票时它答的不是预期那份 JSON")
            if (root.intOf("code") != 1) {
                error("这个后端不给这一集换票：" + (root.str("msg") ?: "code != 1"))
            }
            val jwt = root["data"]?.asObjectOrNull()?.str("ticket")
                ?: error("返回里没有 ticket —— 这一集可能要权限，或票证过期了")
            val payload = MinoProtocol.ticketPayload(jwt)
                ?: error("票据的格式不认识（不是 payload.signature 那型）")
            val d = payload["data"]?.asObjectOrNull() ?: error("票据里没有 data 段")
            StreamResolution.Ready(finalUrlOf(d))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            StreamResolution.Failed(e.message ?: "没给出原因")
        }
    }

    /**
     * 按票据里的下发把最终可播地址拼出来。三种形态都实测过：
     *
     * - `parse_api` 带 `{md5}` 占位（1080P 那型）：`url` 段本身就是 32hex 的 vid，
     *   代入后直接 GET 即出 `{code:200,url}`；
     * - `parse_api` 是前缀、`url` 是被解析的平台页（小腾4K / 小艺那型）：拼成
     *   `api + 百分号编码(page)` 去问，标准「解析 API」协议；
     * - 自建源相对下发（`/api/v1/vod/parse?...`）：像别的 GET 一样补签名（实测路径）。
     *
     * 解析接口回 `code != 200`（那条线今天取不到流）时如实报原因，不硬拼。
     */
    private suspend fun finalUrlOf(d: JsonObject): String {
        val api = d.str("parse_api").orEmpty()
        val url = d.str("url") ?: error("票据没给 url")
        if (api.isBlank() && StreamUrls.isMediaUrl(url)) return url

        val request = when {
            api.contains("{md5}") -> api.replace("{md5}", url.substringAfter('-', url))
            api.startsWith("/") -> base + api + MinoSign.percentEncode(url)
            api.isNotBlank() -> api + MinoSign.percentEncode(url)
            else -> error("票据给了下发，但 parse_api 和 url 拼不出能播的地址")
        }
        val text = apiGetAbsolute(request)
        val root = text.jsonRootOrNull()
            ?: return if (text.startsWith("#EXTM3U")) request
            else error("解析这条线时它答的不是 JSON，也不是 m3u8 —— 换一条线路试试")
        val out = root.str("url") ?: root["data"]?.asObjectOrNull()?.str("url")
        return when {
            out != null && out.startsWith("http") -> out
            else -> error(
                "解析这条线时它没给回地址（code=" + (root["code"]?.asPrimitiveOrNull()?.content ?: "?") +
                    (root.str("msg")?.let { "，$it" } ?: "") + "）—— 换一条线路试试",
            )
        }
    }

    /** 问一个绝对地址：同域（自建源相对下发）时补签名头；外部解析接口裸 GET。 */
    private suspend fun apiGetAbsolute(absolute: String): String {
        val sameHost = runCatching {
            java.net.URI(absolute).host == java.net.URI(base).host
        }.getOrDefault(false)
        return if (sameHost) apiGet(absolute.substring(base.length))
        else Http.getWithHeaders(absolute, headers = emptyMap(), timeoutSec = 25).body
    }

    /** 一次签名 GET 并解成 JSON 根；跟不上接口 / 后端说不是 —— 都如实抛出。 */
    private suspend fun apiJson(pathAndQuery: String): JsonObject {
        val body = quietly { apiGet(pathAndQuery) }
            .fold(onSuccess = { it }, onFailure = { e ->
                error("向这个后端取数据时没取回来：${e.shortReason()}")
            })
        val root = body.jsonRootOrNull()
            ?: error("这个后端给回来的不是预期的 JSON，接口多半改了")
        val code = root.intOf("code")
        if (code != null && code != 1) {
            error("这个后端说：" + (root.str("msg") ?: "code $code"))
        }
        return root
    }

    /**
     * 列表卡片：`vod_id / vod_name / vod_pic / …`，和 maccms 一套字段同源。
     *
     * 实测搜索回 `data.hits[]`、分类回 `data.videos[]`，字段一致，共用这一段。
     */
    private fun cardsOf(arr: kotlinx.serialization.json.JsonElement?): List<Media> =
        arr?.asArrayOrNull()?.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val id = o.str("vod_id") ?: o.str("id") ?: return@mapNotNull null
            Media(
                id = id,
                title = o.str("vod_name") ?: o.str("title") ?: return@mapNotNull null,
                cover = o.str("vod_pic") ?: o.str("coverUrl"),
                year = o.str("vod_year") ?: o.str("year"),
                area = o.str("vod_area") ?: o.str("area"),
                category = o.str("type_name") ?: o.str("vod_class")?.substringBefore(','),
                remark = o.str("vod_remarks"),
                score = o["vod_score"]?.asPrimitiveOrNull()?.content?.takeIf { it.isNotBlank() && it != "0" },
                actors = o.str("vod_actor"),
                intro = o.str("vod_blurb") ?: o.str("previewDescription"),
                sourceId = config.id,
            )
        } ?: emptyList()

    /** 查询串里的值：URLEncoder 的表单语义把空格编成 `+`，这一型后端要的是 %20。 */
    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val PLATFORM = "android"

        /**
         * 协议版本号 —— 后端按它放行，不是随便填的字符串。
         *
         * 实测拿别的值去签直接 4604「请更新到最新版本后重试」；这是这套接口公开给
         * 自家客户端的那一版，改它等于改协议。
         */
        const val APP_VERSION = "1.0.1"
        const val DEVICE_RISK = "0"
        const val PAGE_SIZE = 24
        const val INIT_PATH = "/api/v1/security/init"
        val INFO_SIGN_KEY = "bwv/apisec/ecdh-signkey/v1".toByteArray(Charsets.UTF_8)
        val INFO_BODY_KEY = "bwv/apisec/ecdh-bodykey/v1".toByteArray(Charsets.UTF_8)

        /** 接口给的每集票证前缀（`第N集$tk:xxxx`）。 */
        const val TOKEN_PREFIX = "tk:"

        /** 分集播放地址的坐标前缀：不是直链，是「这一集的票证」，播放时才去换票现算。 */
        const val MARK = "mino:"
    }
}
