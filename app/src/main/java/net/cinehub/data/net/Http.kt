package net.cinehub.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 所有源共用的 HTTP 客户端。
 *
 * 每个源站点都会校验 Referer 与 UA，所以这里不做全局伪装，
 * 由调用方按站点传合适的头。
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 按源配置生成鉴权头：把用户在源配置里填的那份凭证带上。
     *
     * 能看什么取决于这份凭证在该站有什么权限。
     */
    fun authHeaders(cfg: net.cinehub.data.model.SourceConfig): Map<String, String> {
        val token = cfg.authToken?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return when (cfg.authMode) {
            net.cinehub.data.model.AuthMode.Cookie -> mapOf("Cookie" to token)
            net.cinehub.data.model.AuthMode.Bearer -> mapOf("Authorization" to "Bearer $token")
            net.cinehub.data.model.AuthMode.None -> emptyMap()
        }
    }

    /**
     * 一次响应最多读进内存多少字节。
     *
     * 原来四处 `body?.string()` 都是「有多少读多少」。这不是理论风险：把一个 m3u 源
     * 指向一个 `.mp4`，或者站点把某个列表接成了分页无限下钻的接口，这里就会照着
     * 几百 MB 往一个 String 里搬 —— Android 的堆上限是几百 MB，装不下就是 OOM，
     * 用户看到的现象是「搜一下就闪退」，而且下次进来还这样。
     */
    const val MAX_BODY_BYTES = 4_000_000L

    /**
     * 读正文，超过 [MAX_BODY_BYTES] 就报错，不截断、也不继续读。
     *
     * 为什么不截断着解析：半份 HTML 解析出来是「零条结果」，界面就会把它写成
     * 「这个源里没有这部片」—— 一句假话，而且方向还会把人支使得更远（换源、换关键词）。
     * 报错则落在「这个源没答上话」那一类，是真话。
     */
    private fun okhttp3.Response.bodyText(): String {
        val b = body ?: return ""
        val stream = b.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_BODY_BYTES) error(
                "这个源回的内容超过 ${MAX_BODY_BYTES / 1024 / 1024} MB，不像一份能解析的页面 —— " +
                    "多半是地址指到了视频本身",
            )
        }
        return String(out.toByteArray(), charsetOf(b.contentType()?.toString()))
    }

    /** 从 Content-Type 里认字符集；认不出来按 UTF-8 处理（这批站基本都声明了 gbk/utf-8）。 */
    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val name = contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() ?: Charsets.UTF_8
    }

    /**
     * 一次请求的总预算。
     *
     * readTimeout 只管「两次读之间最多等多久」，一个每次挤几百字节、永远不断流的慢站
     * 可以把一次请求拖成几分钟，而这期间界面一直挂着「正在查询…」。
     * 这里给每条页面类请求加一个总时限 —— 下载那条路不加：一集本来就要下十几分钟。
     */
    private fun OkHttpClient.Builder.withBudget(timeoutSec: Long): OkHttpClient.Builder =
        readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec * 3, TimeUnit.SECONDS)

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): String = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { b.header("Referer", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(b.build()).await().use { r ->
                val body = r.bodyText()
                if (!r.isSuccessful) error(failureReason(r.code, body))
                body.ifBlank { error("空响应") }
            }
    }

    /**
     * 带自定义头地取一次，并把响应里服务端下发的 Cookie 带回给调用方。
     *
     * 有些站（如带签名鉴权的前端应用）要先建匿名会话、之后的请求都得带上
     * 它 Set-Cookie 回来的东西 —— 只拿到正文就拿不到后续数据。
     */
    suspend fun getWithHeaders(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutSec: Long = 20,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        b.header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(b.build()).await().use { r ->
                val body = r.bodyText()
                if (!r.isSuccessful) error(failureReason(r.code, body))
                ResponseBody(
                    body = body.ifBlank { error("空响应") },
                    setCookie = r.headers("Set-Cookie").joinToString("; ") {
                        it.substringBefore(';')
                    }.takeIf { it.isNotBlank() },
                )
            }
    }

    /** POST 一段 JSON，同样把 Set-Cookie 带回来。 */
    suspend fun post(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val b = Request.Builder().url(url).header("User-Agent", UA).post(json.toRequestBody(mediaType))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(b.build()).await().use { r ->
                ResponseBody(
                    body = r.bodyText(),
                    status = r.code,
                    setCookie = r.headers("Set-Cookie").joinToString("; ") {
                        it.substringBefore(';')
                    }.takeIf { it.isNotBlank() },
                )
            }
    }

    /** 响应正文 + 服务端下发的会话 Cookie（没有则为 null）。 */
    data class ResponseBody(
        val body: String,
        val status: Int = 200,
        val setCookie: String? = null,
    )

    /** 探测连通性并返回延迟；失败抛出异常。 */
    suspend fun probe(url: String, referer: String? = null): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { b.header("Referer", it) }
        client.newBuilder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS)
            .build()
            .newCall(b.build()).await().use { r: Response ->
                // 「测试连接」这句结论要能自证 —— 拒答页正文里写着真正的原因，
                // 不读它就只能猜。成功路径不读整页，白省一次下行。
                if (!r.isSuccessful) error(failureReason(r.code, errorSnippet(r)))
                System.currentTimeMillis() - t0
            }
    }

    /**
     * 把状态码翻译成用户能理解的原因。
     *
     * 光看状态码翻译不准，同一个 403 后面可能是两件完全不同的事，而给错的下一步
     * 会把人支使得更久：实测 4kcz 拒的是雷池（SafeLine）的浏览器验证 —— 同一台机器、
     * 同一个中国出口 IP，模拟器里的 Chrome 打得开，带齐 Chrome 请求头、甚至把它下发的
     * sl-session 原样带回去重放，OkHttp 这边照样是 403。这时候说「换一条线路」是错的，
     * 换网络不会有任何变化，真正的答案是「这个源接不进来」。
     * 另一类 403 才是地区的：dytt 系直链的 CDN 正文里明写 The region has been denied，
     * 那种确实是换网络能解决的。
     *
     * 所以正文要传进来。没有正文时（比如探测延迟那种不读体的调用）退回按状态码说，
     * 并且不再断言原因。
     */
    fun failureReason(code: Int, body: String = ""): String = when {
        code == 403 || code == 451 || code == 567 -> when {
            body.hasMarker(browserMarkers) ->
                "对方要求浏览器验证（这类站在访问前摆了一道反爬的题，只有真的浏览器答得上来）—— " +
                    "这个源接不进搜索和首页，影枢也不去替程序答那道题。用「站点页取链」打开它，或者换一个源"

            body.hasMarker(regionMarkers) ->
                "拒绝来自访问地区 —— 换一条线路，或者换一个源"

            else -> "拒绝访问 —— 可能按来源、地区或客户端放行。换一条线路，或换一个源；" +
                "「站点页取链」是按浏览器的方式打开，有时能取到"
        }

        code == 404 -> "地址不存在"
        code == 429 -> "请求过于频繁"
        code in 500..599 -> "站点异常 $code"
        else -> "HTTP $code"
    }

    /** 拦下访问时用来判定的字样：浏览器验证那一类。 */
    private val browserMarkers = listOf(
        "safeline", "雷池", "chaitin", "access forbidden", "访问已被拦截",
        "just a moment", "attention required", "cf-chl", "cloudflare",
        "captcha", "验证码", "人机验证", "滑块",
    )

    /** 另一类：明写按地区拒绝的。 */
    private val regionMarkers = listOf(
        "region has been denied", "region denied", "not available in your region",
        "地区", "仅限中国大陆",
    )

    private fun String.hasMarker(markers: List<String>): Boolean {
        if (isEmpty()) return false
        val low = lowercase()
        return markers.any { low.contains(it) }
    }

    /**
     * 取错误响应的正文前若干字节，供 [failureReason] 判定。
     *
     * 只在失败这一条路上调，所以正文本来也没别用。拦下访问的页面都很小（实测十几 KB），
     * 截断到 8 KB 够把字样看到了 —— 而且是**只读这么多**：一个 500 页也可能有几 MB，
     * 全读进来等于让报错这一条路上也埋着 OOM。
     */
    fun errorSnippet(r: Response): String = runCatching {
        val b = r.body ?: return@runCatching ""
        val stream = b.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        var left = 8_000L
        while (left > 0L) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        String(out.toByteArray(), charsetOf(b.contentType()?.toString()))
    }.getOrNull().orEmpty()

    /** 解析站点 baseUrl 是否可用，返回最终生效的 baseUrl（可能被重定向）。 */
    suspend fun resolveBase(base: String): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base).header("User-Agent", UA)
        client.newCall(b.build()).await().use { r ->
            if (!r.isSuccessful) error(failureReason(r.code, errorSnippet(r)))
            r.request.url.toString().trimEnd('/')
        }
    }
}

/**
 * 把一次请求挂到协程上。
 *
 * 原来这几处都是 `withContext(Dispatchers.IO) { call.execute() }` —— 那个 `execute()`
 * 卡住的是线程，不是协程。取消只能等它自己回来，所以用户退出搜索页、换源、切集之后，
 * 那一串请求还在跑：慢站 20 秒超时，连着点几次就是几十个连接同时压着同一个站点，
 * 而它们的结果早就没人要了。走 enqueue + cancel 才是真的能掐断。
 *
 * 响应体交给调用方 `use`：这里不读，读是阻塞的，得留在 IO 线程上。
 * 缓存那边也用它 —— 暂停要的是「这一片立刻停」，不是「等这一片下完再说」。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) {
                // 协程已经走了，没人接手这个响应 —— 不 close 就是漏一个连接
                runCatching { response.close() }
            } else {
                cont.resume(response)
            }
        }
    })
}
