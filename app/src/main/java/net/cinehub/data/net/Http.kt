package net.cinehub.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.cinehub.data.quietly
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
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
 * 每个源站点都会校验 Referer 与 UA
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
                // 4_000_000 字节按十进制 MB 报，别写成整除出来的「3 MB」—— 那比闸门本身小，
                // 是一句会说谎的话（界面上这一句是要给用户看的）
                "这个源回的内容超过 ${MAX_BODY_BYTES / 1000 / 1000} MB，不像一份能解析的页面 —— " +
                    "多半是地址指到了视频本身",
            )
        }
        return String(out.toByteArray(), charsetOf(b.contentType()?.toString()))
    }

    /** 从 Content-Type 里认字符集；认不出来按 UTF-8 处理（这批站基本都声明了 gbk/utf-8）。 */
    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        // 参数名不分大小写，值也可能带引号：`CHARSET=GBK` 和 charset="gbk" 都是合法的写法。
        // 原来这两样都不认，于是整页按 UTF-8 解成乱码 —— 乱码解析出来是「零条结果」，
        // 界面就把它写成「这个源里没有这部片」。
        val lower = contentType?.lowercase() ?: return Charsets.UTF_8
        val at = lower.indexOf("charset=")
        if (at < 0) return Charsets.UTF_8
        val name = lower.substring(at + "charset=".length)
            .trim()
            .trim('"', '\'')
            .takeWhile { !it.isWhitespace() && it != ';' && it != ',' }
            .takeIf { it.isNotEmpty() } ?: return Charsets.UTF_8
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

    /**
     * 一条请求的头：UA 由这里统一给，调用方只补站点真正要看的那几个头。
     *
     * `[k, v]` 用 `header()` 而不是 `addHeader()`：用户在源配置里填的凭证要覆盖同名默认头，
     * 发两颗 Cookie 出去等于把凭证抄给下一个能读日志的东西。
     *
     * Accept-Language 没有放在这里：接口型源（懂片帝AI 那种）一直是「UA + 自己那几个头」的形态，
     * 一个站能用、另一个站不能用的差别往往就在头集合上，不顺手改已经跑通的那几路。
     */
    private fun builder(url: String, referer: String?, headers: Map<String, String>): Request.Builder {
        val provided = headers["Cookie"]
        val merged = mergeCookies(url, provided)
        return Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply { referer?.let { header("Referer", it) } }
            .apply { headers.forEach { (k, v) -> if (k != "Cookie") header(k, v) } }
            .apply { merged?.let { header("Cookie", it) } }
    }

    /**
     * 这个站攒下的会话 cookie，和调用方自己带的那份并在一起（同名以调用方填的凭证为准）。
     *
     * 按 host 取，绝不把 A 站的 cookie 带进 B 站 —— 站点下发的会话和用户填的凭证都是凭证。
     */
    private fun mergeCookies(url: String, provided: String?): String? {
        val saved = synchronized(jar) { hostOf(url)?.let { jar[it] }?.toMap().orEmpty() }
        if (saved.isEmpty()) return provided
        if (provided.isNullOrBlank()) return saved.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val have = provided.split(';').map { it.substringBefore('=').trim() }.toSet()
        val extra = saved.filterKeys { it !in have }.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return if (extra.isEmpty()) provided else "$provided; $extra"
    }

    /**
     * 按 host 记的会话 cookie。
     *
     * 只活在进程里、不落盘：这些站的下发 cookie 有效期就几个小时（实测高清点播那道题给的写的是
     * 2 小时），存到盘上既没用又留下一个能被人读走的东西。
     *
     * 两处上限，都是「别让一个站把这份表撑爆」：记住的 host 数、每个 host 记的名字数。
     * 超了就丢最早插进来的那一条（[LinkedHashMap] 是插入序，重写一个已有的名字不会把它挪到后面，
     * 所以「最早插入」是有定义的）。丢一条的下场只是那份会话重来一次，不设上限则是这个进程一直涨。
     *
     * 用 [LinkedHashMap] 而不是 ConcurrentHashMap，是为了留住插入序好做上面那件事；
     * 因此这一份只在 `synchronized(jar)` 里读写（见 [mergeCookies] / [remember]），
     * 而它的公开行为没有变。
     */
    private val jar = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /** [jar] 的容量：记住几个 host、每个 host 记几个名字。 */
    private const val JAR_MAX_HOSTS = 40
    private const val JAR_MAX_NAMES = 48

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    /**
     * 两个 host 是不是同一个可注册域。
     *
     * 只比最后两段点分标签：`a.example.com` 与 `example.com` 同为 `example.com`，
     * 这一条覆盖的正是 apex→www、http→https 那种**同站**重定向；`a.example.com` 与
     * `b.other.com` 不算，于是跨站重定向的 cookie 不会再被抄给发起方。
     *
     * 不看公共后缀表：`a.co.uk` 与 `b.co.uk` 会被判成同域，于是多记一份。这个方向是安全的 ——
     * 真正会伤人的那一步（把 B 站的 cookie 记到 A 站名下、下次发给 B）只在两颗域名最后两段
     * 相同时也发生，而那时它们几乎必定是同一个站。
     */
    private fun sameSite(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        fun tail(h: String): String = h.lowercase().trimEnd('.').split('.').takeLast(2).joinToString(".")
        return tail(a) == tail(b)
    }

    /**
     * 往 [jar] 里记一条（value 为 null 表示作废这个名字）；超过上限就丢最早插入的那条。
     *
     * 调用方必须持有 [jar] 的锁。
     */
    private fun putCookie(host: String, name: String, value: String?) {
        val m = jar.getOrPut(host) { LinkedHashMap() }
        if (value == null) {
            m.remove(name)
        } else {
            m[name] = value
            while (m.size > JAR_MAX_NAMES) m.remove(m.keys.first())
        }
        // 新 host 是被 getOrPut 追加到末尾的，所以这里丢掉的总是最旧的那个，不会是自己
        while (jar.size > JAR_MAX_HOSTS) jar.remove(jar.keys.first())
    }

    /** 收下这一条响应下发的 cookie。 */
    private fun remember(url: String, r: Response) {
        // 记在**真的下发它的那一台**名下。followRedirects 是开着的（见 client 那一段），
        // apex→www、http→https 之后那条 Set-Cookie 属于链上最后一跳；原来只按请求 URL 的
        // 第一跳记账，于是重定向型站点的会话存到了一个再也不会被问到的 host 上 ——
        // 表现是「会话永远建立不起来」，而这一类失败在界面上查不出原因。
        //
        // 最初请求的那个 host 只在**与最后一跳同域**时才一并记：apex→www 这类同站重定向
        // 两边共用一份会话，所以要都记上。跨站重定向不是这样 —— 把重定向**目标**下发的
        // cookie 记到发起方名下，下次就会把 B 站的凭据发给 A 站，正是本文件
        // 「绝不把 A 站的 cookie 带进 B 站」那条规矩要拦住的事，所以那种情况一个字都不记。
        val requested = hostOf(url)
        val finalHost = hostOf(r.request.url.toString())
        val hosts = if (finalHost == null) listOfNotNull(requested)
        else listOfNotNull(
            finalHost,
            requested?.takeIf { it != finalHost && sameSite(it, finalHost) },
        ).distinct()
        if (hosts.isEmpty()) return
        for (raw in r.headers("Set-Cookie")) {
            val kv = raw.substringBefore(';').trim()
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val name = kv.substring(0, i)
            val value = kv.substring(i + 1)
            // 一条删除指令（Max-Age=0 / Expires 已过期）被当成新值存下来，等于把这个名字
            // 永久钉在一份早该作废的会话上
            val dead = isDeadCookie(raw)
            synchronized(jar) {
                hosts.forEach { host -> putCookie(host, name, if (dead) null else value) }
            }
        }
    }

    /** 这条 Set-Cookie 是在「下发一个值」还是「作废一个名字」。 */
    private fun isDeadCookie(raw: String): Boolean {
        // 让 JDK 自己认这套属性（Max-Age / Expires 的格式与大小写都不用我们重抄一遍）。
        // 认不出来的（站点写得不规范很常见）一律当「还活着」：认错了方向会把一份能用的会话删掉，
        // 认不出来不过是这份 cookie 多活了一会儿 —— 反正它不落盘。
        val c = runCatching { java.net.HttpCookie.parse(raw).firstOrNull() }.getOrNull() ?: return false
        return c.maxAge == 0L || c.hasExpired()
    }

    /** 浏览器导航页面时会发的那组头里，站点真正会看的就是 Accept-Language 这一条。 */
    private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"

    /** 一次页面请求：发出去、收下它下发的 cookie，正文和状态码一起带回，不抛。 */
    private suspend fun fetchOnce(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        timeoutSec: Long,
        browserHeaders: Boolean,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val request = builder(url, referer, headers)
            .apply { if (browserHeaders) header("Accept-Language", ACCEPT_LANGUAGE) }
            .build()
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(request).await().use { r ->
                val body = r.bodyText()
                remember(url, r)
                ResponseBody(body = body, status = r.code, setCookie = setCookieOf(r))
            }
    }

    /**
     * 取一页。撞上「滑动验证」那道题时，照站点自己那条路答一次，答完立刻重发这一页。
     *
     * 只答一次：那道题的解锁活不了多久，而反复交卷正是把限流踩深的东西（实测连着打会改回 429）。
     * 答过了还是不通，原样把那张题页带回 —— 上层照旧会说「对方要求浏览器验证」。
     *
     * [viaBrowser] 是另一件事：这一源的题不在滑块那一类，而是**要执行脚本才答得出**的
     * （Cloudflare managed challenge）。那种题 OkHttp 答不了，所以整条请求换给
     * [BrowserEngine] —— 滑块那套重试对它是空转，直接跳过。
     */
    private suspend fun page(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        timeoutSec: Long,
        browserHeaders: Boolean,
        viaBrowser: Boolean = false,
    ): ResponseBody {
        if (viaBrowser) return BrowserEngine.get(url, timeoutSec)
        val first = fetchOnce(url, referer, headers, timeoutSec, browserHeaders)
        if (!SlideChallenge.isSlideGate(first.status, first.body)) return first
        if (!SlideChallenge.solve(url, first.body)) return first
        return fetchOnce(url, referer, headers, timeoutSec, browserHeaders)
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
        viaBrowser: Boolean = false,
    ): String {
        val r = page(url, referer, headers, timeoutSec, browserHeaders = true, viaBrowser = viaBrowser)
        if (r.status !in 200..299) error(failureReason(r.status, r.body))
        return r.body.ifBlank { error("空响应") }
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
    ): ResponseBody {
        val r = page(url, referer, headers, timeoutSec, browserHeaders = false)
        if (r.status !in 200..299) error(failureReason(r.status, r.body))
        return r.copy(body = r.body.ifBlank { error("空响应") })
    }

    /**
     * 取一页，状态码与 Set-Cookie 一起带回，**非 2xx 不抛**。
     *
     * [getWithHeaders] 一到非 2xx 就抛，等于把「这张错误页也带着 cookie」整件事丢掉。
     * 而有些站的会话恰恰是从一张错误页开始的：实测 高清点播 的播放页会先发回 403，
     * 正文只有 132 字节、一句 `window.location.href = 同一个地址`，同时 Set-Cookie 落下会话 ——
     * 浏览器是「重走一次并带上那颗 cookie」，第二次就一直是 200。
     * 要复现这个动作就得看得见状态码、还得读得到那张 403 页的 cookie，所以单独开这一条。
     */
    suspend fun getKeepStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutSec: Long = 20,
    ): ResponseBody = page(url, referer, headers, timeoutSec, browserHeaders = true)

    /** POST 一份表单；状态码与 Set-Cookie 一起带回（解析站那类接口收的是表单而不是 JSON）。 */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val b = builder(url, headers["Referer"], headers).post(body)
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(b.build()).await().use { r ->
                ResponseBody(body = r.bodyText(), status = r.code, setCookie = setCookieOf(r))
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
        val b = builder(url, headers["Referer"], headers).post(json.toRequestBody(mediaType))
        client.newBuilder().withBudget(timeoutSec).build()
            .newCall(b.build()).await().use { r ->
                ResponseBody(
                    body = r.bodyText(),
                    status = r.code,
                    setCookie = setCookieOf(r),
                )
            }
    }

    /** 服务端这次下发的 cookie（只要 name=value，属性它自己管不着我们）。 */
    private fun setCookieOf(r: Response): String? = r.headers("Set-Cookie")
        .joinToString("; ") { it.substringBefore(';') }
        .takeIf { it.isNotBlank() }

    /** 响应正文 + 状态码 + 服务端下发的会话 Cookie（没有则为 null）。 */
    data class ResponseBody(
        val body: String,
        val status: Int = 200,
        val setCookie: String? = null,
    )

    /** 探一次连通性：成功带回耗时，失败带回状态码和那张错误页的正文片段。 */
    private suspend fun probeOnce(url: String, referer: String?): Triple<Long?, Int, String> =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val b = builder(url, referer, emptyMap())
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                // 探测的语义就是「像浏览器一样把这个站打开一次」，所以这里补齐浏览器导航时会发的头。
                // 只加在这一处：各源真正取数据的请求仍按站点传头（见本文件顶部的约定）。
                // 少了 `Accept` 这一条，个别站在内容协商上直接回 500 —— 浏览器打得开、我们判死，
                // 而 500 会被读成「这个源不行」，那是假话（实测：hqvod 根路径）。
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
            referer?.let { b.header("Referer", it) }
            client.newBuilder()
                .readTimeout(8, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .callTimeout(24, TimeUnit.SECONDS)
                .build()
                .newCall(b.build()).await().use { r: Response ->
                    remember(url, r)
                    when {
                        r.isSuccessful -> Triple(System.currentTimeMillis() - t0, r.code, "")
                        else -> Triple(null, r.code, errorSnippet(r))
                    }
                }
        }

    /**
     * 探测连通性并返回延迟；失败抛出异常。
     *
     * 撞上那道滑块时先答一次再量：答得过去却报「这个源不可用」是假话，而这一栏的结论
     * 会决定它在源列表里排哪儿、要不要被自动换源跳过。
     *
     * [viaBrowser] 的源改由浏览器内核量：拿 OkHttp 去量一个挂了 Cloudflare 的站，
     * 得到的是「对方要求浏览器验证」，而这一栏要报的是**这个源现在到底通不通**。
     */
    suspend fun probe(url: String, referer: String? = null, viaBrowser: Boolean = false): Long {
        if (viaBrowser) return BrowserEngine.probe(url)
        val (ms, status, snippet) = probeOnce(url, referer)
        if (ms != null) return ms
        if (!SlideChallenge.isSlideGate(status, snippet)) return error(failureReason(status, snippet))
        // 撞上那道滑块题：探测这一路只截了**前 8 KB** 正文（见 errorSnippet），而拦访页
        // 实测十几 KB —— 要的正则（脚本 src、key/value）落在 8 KB 之后时这一路就答不了题，
        // 于是同一个站：取数那一路（读全份）答得出来，探测这一路报「对方要求浏览器验证、
        // 这个源接不进来」。两条路两种结论，而探测那一栏的结论决定它在源列表里排哪儿、
        // 要不要被自动换源跳过。所以这里换走 page() 那条读全份正文的路：它自己会答一次题、
        // 答完在新会话上重发，通了我才量得到延迟，不通就照实把那张页的原因带回去。
        val t1 = System.currentTimeMillis()
        val again = quietly { page(url, referer, emptyMap(), 20, browserHeaders = true) }.getOrNull()
        if (again != null && again.status in 200..299) return System.currentTimeMillis() - t1
        return error(failureReason(again?.status ?: status, again?.body.orEmpty()))
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
                // 这里原本还写着「用『站点页取链』打开它」—— 那是一条走不到的路，所以删掉：
                // 站点页取链要把接住的地址交给「正在播的那一集」（SiteBrowserScreen 的 canHandOff），
                // 而播放器里那个入口开的又是「正在播的那个源」自己的页面（PlayerScreen 用 playing.sitePageUrl）。
                // 偏偏这一类的源因为探不进来，根本存不进列表、也就永远成不了正在播的那个源 ——
                // 于是这句话只在它唯一不适用的场合被说出来。指引要等那条路真能走通再放回来说。
                //
                // 「滑块」那一类影枢现在会自己答一次（见 SlideChallenge），答过了就不会走到这句；
                // 走到这句的是答不了的（雷池、Cloudflare 那种要真浏览器指纹的），所以说「接不进来」。
                "对方要求浏览器验证（这类站在访问前摆了一道反爬的题）—— 影枢照它自己那条路答过一次还是没过，" +
                    "这个源接不进搜索和首页。换一个源"

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
        // 滑动验证那道题的字样。它们本来就是 [SlideChallenge.isSlideGate] 的判据，
        // 这里再列一遍是因为两者判的是两件事：那边判「要不要答这道题」，这边判
        // 「答完还是 403 时该说哪一句话」。少了这两个词，一张没答过去的滑块页会掉进
        // 下面那条通用 403 分支 —— 那句指引（站点页取链）恰恰是 [failureReason] 的
        // KDoc 里说明的死路，等于把人支使得更久。
        "滑动验证", "huadong_",
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
            // 这里不用 tryResumeWithException：抛异常那条路没有「资源没人接手」的问题，
            // 而协程已经取消时这个 resume 是空操作（挂起的续体不会醒）。
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // 取消与交付在同一条状态机上竞争，谁先谁赢 —— 先登记「取消就把这条响应关掉」：
            //  - **交付赢**：状态转成 Resumed，此后 cancel() 不会再成功，这个 handler 不会跑，
            //    响应交给调用方的 `use {}` 关；
            //  - **取消赢**：状态转成 Cancelled，handler 立刻被调用（`invokeOnCancellation` 的
            //    语义就是「已经取消时当场执行」），响应在这里关掉，随后那次 resume 是空操作。
            //
            // 两边都调 close()，而 `Response.close()` 是幂等的（OkHttp 内部就是 closeQuietly），
            // 所以不需要再判一次「究竟谁赢了」—— 原来那句 `if (cont.isCancelled) … else resume`
            // 的竞态恰恰就出在这一次判断上：取消落在判断和 resume 之间时，
            // resume 被丢进已取消的续体、调用方的 `use {}` 永不执行，这条连接没人关。
            cont.invokeOnCancellation { runCatching { response.close() } }
            cont.resume(response)
        }
    })
}
