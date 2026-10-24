package net.cinehub.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * 按超时预算缓存的客户端：同一个预算复用同一颗。
     *
     * 原来每一处请求都写 `client.newBuilder().withBudget(timeoutSec).build()` ——
     * 每请求一颗新客户端。
     *
     * **核实结论（重要，别当成已知的前提去推断）**：
     * `OkHttpClient.newBuilder()` 走的是 `Builder(okHttpClient)`，而那份构造器
     * **复制的是引用** —— `dispatcher` 与 `connectionPool` 两个字段都是从旧客户端
     * **搬过来**的同一个对象（对着 okhttp 4.12.0 的字节码 `javap -c` 能看到
     * `getDispatcher$okhttp()` / `getConnectionPool$okhttp()` 紧接着
     * `putfield dispatcher` / `putfield connectionPool`），**不是**各造一份新的。
     *
     * 所以：**每请求建一颗新客户端，并不是实机抓包里「58 条新建 TCP 连接、
     * 无一条被复用」的直接原因** —— 连接池与派发器本来就是共享的。
     * 那 58 条连接的成因在别处（同一 URL 被反复取、站点回 `Connection: close`、
     * 以及 `Dispatcher.maxRequestsPerHost` 默认 5 给整轮扇出踩的刹车），
     * 见 `.scratch/review/01-network-rate-limit.md` 的 F4 与 F16。
     *
     * 即便如此，这处仍要改 —— 代价是实的：
     *
     *  - 每请求一颗客户端，等于每请求**重新分配**一整套 config、任务队列、
     *    `RouteDatabase`、SSL 配置 —— 纯粹的浪费。
     *  - 每颗新客户端都带**自己的** `callTimeout`，而 `callTimeout` 是在
     *    **这一颗**上生效的 —— 想让不同调用点各有各的总预算，就只能每请求换一颗
     *    客户端。这就把「预算」和「客户端身份」绑在了一起。
     *  - 所有这些客户端共用**同一个** `Dispatcher` 实例，而
     *    `maxRequestsPerHost`（默认 5）是**按 dispatcher 算**的 ——
     *    同一个 dispatcher 被几百颗不同客户端轮流喂，队列里的 call 属于不同客户端，
     *    那个限制的语义被搅得更乱。
     *
     * 现在的做法：**按预算缓存客户端**，同一个预算复用同一颗；
     * 单请求的总预算改用 OkHttp 的 **per-call timeout**
     * （`call.timeout().timeout(...)`）—— 它挂在**这一次调用**上，
     * 不影响共享的客户端，也不影响别的调用点各自的预算。
     */
    private val clientsByBudget = ConcurrentHashMap<Long, OkHttpClient>()

    /**
     * 基础客户端：超时、跟随重定向、失败重连都在这里定死。
     *
     * 对外也留着它：`DownloadRepository` 的下载客户端就是从它派生的
     * （`Http.client.newBuilder()…build()`），那条路要自己的 dispatcher
     * （分段并发 8、整体并发 3），不能走这里的出站闸。
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** 拿一颗配好预算的客户端；同一个预算复用同一颗。 */
    private fun clientFor(timeoutSec: Long): OkHttpClient = clientsByBudget.getOrPut(timeoutSec) {
        client.newBuilder()
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec * 3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 探测那一路专用的客户端：它的时间预算与页面请求不同（8s 连 / 8s 读 / 24s 总）。
     *
     * 单独一颗、也单独缓存 —— 它同样不能每请求新建。
     */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 出站请求的总闸。
     *
     * 原来**完全没有**这道闸：`healthAll` / `searchOnIo` / `siblingsOnIo` 全是无上限
     * `async` 扇出，一次全源操作就是几颗站同时被打、每颗站还可能连着打三四次
     * （同一片名的几种写法，见 `MediaRepository.findInSource`）。站点那边看到的
     * 是一片机器人 —— 而这几句话正是「连着打就回 429」那批站最忌讳的。
     *
     * 6 是这么定的：够放下「一次搜索扇出」而不至于把界面拖死，又能把瞬时并发压到
     * 个位数。超出的请求**排队等**，不丢 —— 所以功能不缺，只是慢一点。
     */
    private val egressGate = Semaphore(6)

    /**
     * 出站时占住闸门的包装。
     *
     * 放在真正发请求的那一层（[fetchOnce] / [postForm] / [post] / [getBytes] /
     * [probeOnce]），这样**所有**路径自动受约束 —— 包括以后新加的调用点，
     * 不用记得再手工过一次。
     */
    private suspend fun <T> gated(block: suspend () -> T): T =
        egressGate.withPermit { block() }

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
     * 二进制读法的字节上限，只对 [getBytes] 生效。
     *
     * 密钥就该是 16/24/32 字节。给到几十 KB 说明这个地址指的已经不是密钥了 ——
     * 那时候该报错，而不是照截：截一份不是密钥的东西，播放器只会解出一个更说不清的错。
     * 读取本身是「到流结束为止」，这一个数只是读完后的闸门。
     */
    const val MAX_KEY_BYTES = 64

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

    // ------------------------------------------------------------------
    // 并发去重（SingleFlight）与响应缓存
    //
    // 两者针对的是同一件事的两个时间尺度：**同一个请求被发出多次**。
    //  - 去重管的是「**同时**在飞的那几趟」：合并成一趟网络往返。
    //  - 缓存管的是「刚刚已经拿过的那一份」：TTL 内直接复用，不走网络。
    //
    // 为什么这两样都不能省：实机抓包（bb 模拟器，冷启动→搜索→详情）看到
    // **58 条新建 TCP 连接、1 秒内 17 条、同一 host 11 条、无一条连接被复用**。
    // 那些连接里有一大把是**同一个 URL 被重复取**的：详情页的 `siblingsOf`
    // 与搜索页的 `search` 各自扇出一遍，`findInSource` 还会为同一片名换三种写法
    // 再各打一遍。站点那边看到的就是一个机器人反复问同一个问题 ——
    // 于是它回 429，而 429 又让下一轮扇出打得更凶。
    // ------------------------------------------------------------------

    /**
     * 同一请求的「飞行中」表：key -> 这一趟共享的结果。
     *
     * 用 [CompletableDeferred] 而不是 `Future`：等待方要能**各自**被取消，
     * 而共享的那一趟不能因为一个等待方走了而被取消（那会连累还在等的人）。
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ResponseBody>>()

    /**
     * 共享那一趟跑的地方：一个**独立**的作用域，不挂在任何调用方的协程下面。
     *
     * 为什么要有它：发起方（第一个到的人）随时可能被取消 —— 用户划走页面、
     * 协程被结构化并发取消。若那一趟挂在发起方的作用域里，发起方一被取消，
     * 这一趟就被掐了：它已经占着出站闸、已经在网络上了，白跑一趟，
     * 而**还在等着的别的人**拿到的还是一个失败。
     *
     * 所以那一趟走自己的作用域：[SupervisorJob] 让它不被别人的失败带走，
     * [Dispatchers.IO] 是网络等待该待的线程池。
     */
    private val flightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 这里**没有**响应缓存 —— 只并「同一时刻的重复请求」（[singleFlight]），
     * 不缓存「隔一会儿再要一次」的结果。
     *
     * 审查时一度加过一份 60 秒的正文缓存，随后被实机代码走查撤掉，理由是
     * **这一批站里有好几个把「重试」写在了对同一 URL 的连续请求上**：
     *
     * - `PpnixAdapter.playlistOrRetry`（`data\source\PpnixAdapter.kt:257-266`）：
     *   站点对同一个取链地址有三种回答 —— 标准 m3u8、301 到首页、以及 **200 +
     *   一份首页 HTML**。最后那种必须重发才可能拿到真清单。缓存会把第一次的
     *   坏正文原样喂给第二、第三次重试，PPnix 直接播不了 —— 而这正是 1.4.9
     *   那一版修过的病（`4027d0e`）。
     * - `MaccmsParseResolver`（`data\source\MaccmsParseResolver.kt:127-130`）：
     *   先吃一张 403 拿 cookie、再带 cookie 重走一遍。重走那一步要是命中了
     *   403 那次的缓存，会话永远建立不起来。
     * - `SlideChallenge` 的交卷接口：那张题页缓存 60 秒等于把「答完题重发」
     *   变成「再拿一张题页」。
     *
     * 跨时间的重复请求在**语义层**收掉更准（那里知道「同一部片」是什么）：
     * 见 `MediaRepository` 的 `searchCache`（120 秒、按片名指纹+年份+类型）
     * 与 `detailShared`（同 key 并发合并）。实机抓包：修复后同一条链路的
     * 新建连接从 58 降到 9。
     */

    /**
     * 去重 / 缓存用的 key。
     *
     * **必须包含头**：不同源可能给同一个 URL 配上不同的 `Referer`、不同的
     * `x-ai-movie-signature` —— 站点按这些头决定给什么。把它们并成同一把
     * key 就会「拿 A 源的响应回给 B 源」。
     *
     * 头的顺序也不能影响 key，所以这里排序后再拼。
     */
    private fun requestKey(url: String, referer: String?, headers: Map<String, String>): String {
        val hs = headers.entries
            .sortedBy { it.key.lowercase() }
            .joinToString("&") { "${it.key.lowercase()}=${it.value}" }
        return "$url\n${referer.orEmpty()}\n$hs"
    }

    /**
     * 并发的同一趟请求合并成一次网络往返。
     *
     * 谁先到谁发；后来的等它那一趟的结果。
     *
     * **等待方被取消时不能取消共享的那一趟** —— 那是发起方的责任，
     * 而等待方只是恰好也想知道同一个答案。取消它会连累还在等的人。
     *
     * 所以那一趟网络往返跑在一个**独立的作用域**里（[flightScope]），不挂在
     * 任何等待方的协程下面：发起方自己被取消（用户划走页面）时，
     * 那一趟照跑到底，别的还在等的人照样拿到答案 ——
     * 而这一趟本来就已经占着出站闸、已经在网络上了，让它白跑才是浪费。
     *
     * 注意这里**只并同一时刻的重复请求**，不缓存跨时间的结果 ——
     * 为什么不能缓存，见 [flightScope] 下面那段「这里没有响应缓存」的说明。重试型调用点
     * （`PpnixAdapter.playlistOrRetry`、`MaccmsParseResolver` 先吃 403 再带 cookie 重走）
     * 的两次请求是**先后**发的、不重叠，所以不会被这一层并掉，重试语义完好。
     */
    private suspend fun singleFlight(
        key: String,
        url: String,
        referer: String?,
        headers: Map<String, String>,
        timeoutSec: Long,
        browserHeaders: Boolean,
    ): ResponseBody {
        val mine = CompletableDeferred<ResponseBody>()
        // putIfAbsent：只有一个赢家会去发那一趟，其余的人拿到**赢家那颗** Deferred 去等。
        // （注意：等的是**别人**那颗，不是自己这颗 —— 等错一颗就成永远等下去。）
        val winner = inFlight.putIfAbsent(key, mine)
        if (winner != null) {
            // 等待方：只等，不发。它被取消时不影响这一趟（见上）。
            return winner.await()
        }
        // 发起方：把这一趟丢进独立作用域去跑。
        flightScope.launch {
            try {
                val r = fetchOnce(url, referer, headers, timeoutSec, browserHeaders)
                mine.complete(r)
            } catch (e: Throwable) {
                // 共享那一趟失败了：把同样的失败原样交给所有等待方。
                // 不在这里重发 —— 那正是「同一时刻几趟一起打」的来源。
                mine.completeExceptionally(e)
            } finally {
                // 这一趟结束了（或失败了），把它从「飞行中」表里摘掉。
                // 摘的是**自己那颗** —— 别把别人的（新一轮已经占上同一个 key）摘掉。
                inFlight.remove(key, mine)
            }
        }
        return mine.await()
    }

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
     * 两个 host 是不是同一个站。
     *
     * **比完整主机名**，不再只比最后两段标签。
     *
     * 原来那版（末两段）是错的，而且错的方向正好是要命的方向：`evil.co.uk` 与
     * `bank.co.uk` 的末两段都是 `co.uk` → 判成同域 → `remember()` 把最后一跳
     * 下发的 cookie 记到发起方名下 → 下一次就把 **B 站的凭据发给 A 站**。
     * 「只看末两段大概就是同一个站」这个假设在公共后缀是 `co.uk` / `com.cn` /
     * `com.hk` 这一档时完全不成立 —— 而这批源里带 `co.uk` 的站就有几个。
     *
     * 现在比完整主机名（大小写不敏感、去尾部 `.`），另开一个口子：**去掉开头的
     * `www.` 之后相同**也算同站。这一条覆盖的正是 apex→www、http→https 那种
     * **同站**重定向 —— 那种重定向两边共用一份会话，所以要都记上。
     *
     * 代价：原来 `a.example.com` 与 `b.example.com` 也算同域，现在不算。
     * 这是对的 —— 那是**两个不同的站**，把一边的 cookie 记到另一边名下正是
     * 本文件「绝不把 A 站的 cookie 带进 B 站」那条规矩要拦住的事。
     */
    private fun sameSite(a: String, b: String): Boolean {
        val x = a.lowercase().trimEnd('.')
        val y = b.lowercase().trimEnd('.')
        if (x == y) return true
        return x.removePrefix("www.") == y.removePrefix("www.")
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

    /**
     * 一次页面请求：发出去、收下它下发的 cookie，正文和状态码一起带回，不抛。
     *
     * 这一层管三件事：限流冷却、429 退避重试、出站闸（[gated]）。
     */
    private suspend fun fetchOnce(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        timeoutSec: Long,
        browserHeaders: Boolean,
    ): ResponseBody {
        // 这个站刚因限流被拒过、还在冷却期里 → **不去打扰它**。
        //
        // 这一步是整轮扇出的止血阀：站点回了 429 之后，同一轮还在跑的
        // 其它请求、以及下一轮的整轮扇出，都会在这里被挡下，
        // 于是这个站这一次扇出里只被打「已经打过的那些次」，不再加码。
        //
        // 放在闸门**外面**：被挡下的请求根本不该占着出站的额度。
        //
        // 回一份状态码 429 的空响应，与真被拒时上层看到的**完全一样**
        // （[failureReason] 里那句「请求过于频繁」）—— 不骗人。
        if (SlideChallenge.isRateLimited(url)) {
            return ResponseBody(body = "", status = 429, setCookie = null)
        }
        return gated {
            withContext(Dispatchers.IO) {
                val request = builder(url, referer, headers)
                    .apply { if (browserHeaders) header("Accept-Language", ACCEPT_LANGUAGE) }
                    .build()
                var attempt = 0
                var result: ResponseBody? = null
                while (result == null) {
                    val call = clientFor(timeoutSec).newCall(request)
                    // 单请求的总预算挂在**这一次调用**上（见 [clientFor]）：客户端是共享的，
                    // 不能为了每次不同的预算去新建一颗 —— 那正是连接复用失效的帮凶。
                    call.timeout().timeout(timeoutSec * 3, TimeUnit.SECONDS)
                    val outcome = try {
                        call.await().use { r ->
                            val status = r.code
                            val body = r.bodyText()
                            // cookie 先记下来 —— 哪怕这一次是 429：要重试的话，
                            // 站点这一趟顺手下发的会话（限流前发的）也得留着。
                            remember(url, r)
                            if (status == 429 && attempt < RATE_LIMIT_RETRIES) {
                                // 429：对方在限流。退避 + 重试，别把这当一次普通失败就交出去 ——
                                // 一次普通的 429 会让整轮扇出少一个源，而站点那边看到的是
                                // 「这个客户端被限了」，于是下一轮它更狠。详见 [retryAfter429]。
                                Outcome.Retry(retryAfter429(r, attempt + 1))
                            } else {
                                if (status == 429) {
                                    // 重试用完了还是 429：给这个站记一笔**冷却**，
                                    // 后面那一整轮扇出就都别去打扰它了。
                                    // 见 [SlideChallenge.noteRateLimited]。
                                    SlideChallenge.noteRateLimited(url)
                                }
                                Outcome.Done(
                                    ResponseBody(body = body, status = status, setCookie = setCookieOf(r)),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // 同一种情况：连接被掐（对方限流时常常直接断连，而不是回 429）。
                        // 这种情况退避重试比直接失败好 —— 但同样有上限，不做成死循环。
                        if (attempt < RATE_LIMIT_RETRIES && e is IOException) {
                            Outcome.Retry(backoffMs(attempt + 1))
                        } else {
                            throw e
                        }
                    }
                    when (outcome) {
                        is Outcome.Done -> result = outcome.body
                        is Outcome.Retry -> {
                            attempt++
                            delay(outcome.waitMs)
                        }
                    }
                }
                result ?: ResponseBody(body = "", status = 500, setCookie = null)
            }
        }
    }

    /** 一次请求的两种结局：拿到了，或者该退避再打一次。 */
    private sealed interface Outcome {
        class Done(val body: ResponseBody) : Outcome
        class Retry(val waitMs: Long) : Outcome
    }

    /**
     * 429 之后该等多久。
     *
     * 对方给了 `Retry-After` 就用它（那是它自己说的「我什么时候有空」），
     * 但**封顶 5 秒** —— 有些站会写一个很大的值，照着等下去界面像卡死了。
     * 没给就按指数退避 + 抖动（第 1 次约 800ms、第 2 次约 2000ms）。
     */
    private fun retryAfter429(r: Response, attempt: Int): Long {
        val hdr = r.headers("Retry-After").firstOrNull()?.trim()
        val secs = hdr?.toLongOrNull()
        if (secs != null && secs >= 0) return minOf(secs * 1000L, RATE_LIMIT_MAX_WAIT_MS)
        return backoffMs(attempt)
    }

    /**
     * 指数退避 + 随机抖动。
     *
     * 抖动是**必须**的：几路并发同时撞上 429，若都等一模一样的时长，它们会在
     * 同一毫秒一起再打一次 —— 那是把限流再踩深一次（重试风暴）。
     * ±30% 的抖动足以把它们错开，又不至于散得太开。
     */
    private fun backoffMs(attempt: Int): Long {
        val base = RATE_LIMIT_BACKOFF_MS.getOrElse(attempt - 1) { RATE_LIMIT_BACKOFF_MS.last() }
        val jitter = 0.7 + Math.random() * 0.6   // 0.7 ~ 1.3
        return (base * jitter).toLong().coerceAtMost(RATE_LIMIT_MAX_WAIT_MS)
    }

    /** 429 最多重试几次。 */
    private const val RATE_LIMIT_RETRIES = 2

    /** 退避的基准值：第 1 次等 800ms，第 2 次等 2000ms。 */
    private val RATE_LIMIT_BACKOFF_MS = longArrayOf(800L, 2_000L)

    /** 单次等待的上限（毫秒）。超过就照发，别把界面拖成「看着像卡了」。 */
    private const val RATE_LIMIT_MAX_WAIT_MS = 5_000L

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
        // 先过一次去重：同一页在同一时刻被两处要（详情页与搜索页各问一遍、
        // 播放器里「查一次」与详情页那栏撞上）时，只发一次网络往返。
        val first = singleFlight(requestKey(url, referer, headers), url, referer, headers, timeoutSec, browserHeaders)
        if (!SlideChallenge.isSlideGate(first.status, first.body)) return first
        // 撞上滑块题：答一次题再重发。
        // **重发走 fetchOnce、不回去过单飞** —— 那一趟刚把题页记在单飞的牌上，
        // 重发要是命中它，拿回来的还是同一张题页，这道题就永远答不过去。
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
    ): ResponseBody = gated {
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val b = builder(url, headers["Referer"], headers).post(body)
            val call = clientFor(timeoutSec).newCall(b.build())
            call.timeout().timeout(timeoutSec * 3, TimeUnit.SECONDS)
            call.await().use { r ->
                ResponseBody(body = r.bodyText(), status = r.code, setCookie = setCookieOf(r))
            }
        }
    }

    /** POST 一段 JSON，同样把 Set-Cookie 带回来。 */
    suspend fun post(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): ResponseBody = gated {
        withContext(Dispatchers.IO) {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val b = builder(url, headers["Referer"], headers).post(json.toRequestBody(mediaType))
            val call = clientFor(timeoutSec).newCall(b.build())
            call.timeout().timeout(timeoutSec * 3, TimeUnit.SECONDS)
            call.await().use { r ->
                ResponseBody(
                    body = r.bodyText(),
                    status = r.code,
                    setCookie = setCookieOf(r),
                )
            }
        }
    }

    /** 服务端这次下发的 cookie（只要 name=value，属性它自己管不着我们）。 */
    private fun setCookieOf(r: Response): String? = r.headers("Set-Cookie")
        .joinToString("; ") { it.substringBefore(';') }
        .takeIf { it.isNotBlank() }

    /**
     * 二进制读一次：密钥、证书这类不是文本的响应走这条。
     *
     * [get] 那条会把正文按 charset 解成 `String` —— 非 ASCII 字节会被换成 U+FFFD，
     * 字节数对不上，拿回来没法用。HLS 的 `#EXT-X-KEY` 指向的就是原始密钥字节，
     * 所以单开这一条。
     *
     * 大小有上限（[MAX_KEY_BYTES]）：给到几十 KB 说明这个地址指的已经不是密钥了。
     */
    suspend fun getBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
        maxBytes: Int = MAX_KEY_BYTES,
    ): ByteArray = gated {
        withContext(Dispatchers.IO) {
            val b = builder(url, referer, headers)
            val call = clientFor(timeoutSec).newCall(b.build())
            call.timeout().timeout(timeoutSec * 3, TimeUnit.SECONDS)
            call.await().use { r ->
                if (!r.isSuccessful) error(failureReason(r.code, ""))
                val src = r.body?.source() ?: error("响应是空的")
                // 读到流结束为止（密钥本来就是几十字节），再用上限卡「指到别的文件」。
                // 不能用 readByteArray(maxBytes+1)：那是「恰好读这么多」，
                // 32 字节的密钥会在第 33 字节处等来 EOF —— 实测 PPnix 的密钥
                // 就是这样一封 全军覆没 的（EOFException）。
                val buf = src.readByteArray()
                if (buf.size > maxBytes) error("响应有 ${buf.size} 字节，超过 $maxBytes 上限")
                buf
            }
        }
    }

    /** 响应正文 + 状态码 + 服务端下发的会话 Cookie（没有则为 null）。 */
    data class ResponseBody(
        val body: String,
        val status: Int = 200,
        val setCookie: String? = null,
    )

    /** 探一次连通性：成功带回耗时，失败带回状态码和那张错误页的正文片段。 */
    private suspend fun probeOnce(url: String, referer: String?): Triple<Long?, Int, String> = gated {
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
            val call = probeClient.newCall(b.build())
            call.timeout().timeout(24, TimeUnit.SECONDS)
            call.await().use { r: Response ->
                remember(url, r)
                when {
                    r.isSuccessful -> Triple(System.currentTimeMillis() - t0, r.code, "")
                    else -> Triple(null, r.code, errorSnippet(r))
                }
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
 *
 * **v1.4.4 在这里崩过，两处教训都钉在这段代码里：**
 * 1. `suspendCancellableCoroutine` 的协程还挂着时**只许注册一颗**取消 handler，
 *    第二次注册直接抛 `IllegalStateException: It's prohibited to register multiple
 *    handlers`。上一版为了「响应也得在取消时关掉」在 onResponse 里又注册了一颗 ——
 *    等于每一次请求响应到达时都炸，开机首轮健康扇出就把应用掀了（真机闪退）。
 * 2. 想避免 `isCancelled` 查与 `resume` 之间的 TOCTOU，用**公开**的
 *    `resume(value, onCancellation)`：转不出去（已被取消）时 onCancellation 被调用、
 *    value 被丢弃。不需要 `tryResume/completeResume`，更不需要第二颗 handler。
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // 抛异常那条路没有「资源没人接手」的问题：协程已经取消时这个 resume 是空操作。
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // 转手与取消在 resume 内部一次定胜负：转手成功，响应归调用方的 `use {}`；
            // 转不出去（取消先到），这个回调里当场关掉，连接不漏。close 幂等，重复调无害。
            cont.resume(response) { runCatching { response.close() } }
        }
    })
}
