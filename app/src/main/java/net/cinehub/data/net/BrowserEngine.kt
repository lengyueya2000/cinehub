package net.cinehub.data.net

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台的一颗 WebView，替「只认真浏览器的那些源」取数据。
 *
 * 存在的理由：Cloudflare 那一类反爬在站点前面挂了一道**要执行它的脚本才答得出**的题。
 * 纯 HTTP 客户端拿不到正文 —— 实测 www.freeokk.lol 的每一个路径都回 403 `Just a moment...`，
 * 而同一台机器、同一个出口 IP，一颗 WebView 二十秒内自己把题做完，把苹果 CMS 的 JSON
 * 原样渲染了出来。差的不是 IP、不是请求头，是**有没有真的跑对方那段脚本**。
 * 伪造 cf_clearance 或者去凑 TLS 指纹都不叫过题（那颗 cookie 绑的就是答题那台浏览器的
 * UA + IP + 指纹，抄过来也活不过一次换网络），所以这里只有一条路：让引擎自己去答。
 *
 * 答完题之后，`cf_clearance` 存在 WebView 自己的 cookie 里 —— 和「站点页取链」共用
 * 同一份 CookieManager，落盘、跨启动有效。之后影枢只是拿浏览器已经挣到的那份会话，
 * 去问它本来就在问的那个接口，不新增任何权限，也不碰对方的验证逻辑。
 *
 * 四条由这颗引擎的形状决定的约束，改不掉：
 * - **只能问同源**：fetch 出不了当前文档那个源，跨源被 CORS 直接掐掉。所以要先把文档
 *   落在目标源上（[warm]），而且要**验它真的落在那儿**（[request] 读引擎自己报的 url）——
 *   站点把自己跳到第三方域名上是常事，那时候把这条 fetch 发出去，问的就不是这一源了。
 *   也因为这个，referer 不在这里传：同源 fetch 的 Referer 就是这一页自己的地址，
 *   浏览器给的那一份比手写的更真。
 * - **一次一条**：全应用共用一颗 WebView，请求排队（[gate]）。同时往一个文档里塞两条
 *   evaluateJavascript 不会更快，只会把题重做几遍。
 * - **慢，而且要有上限**：第一次过题按十几秒算，所以一趟给 [WALL_MS] 的总闸。
 *   没有这道闸，一个源能把整场跨源搜索拖住几分钟 —— 而其它源早就答完话了。
 * - **只服务标了 needsBrowser 的源**（见 [Http.get]）：其余源照旧走 OkHttp，一点不变。
 */
@SuppressLint("StaticFieldLeak")
object BrowserEngine {

    /** 一次导航最多等多久（含题页自己跳转的那几轮）。Cloudflare 的题是十几秒这一档。 */
    private const val WARM_TIMEOUT_MS = 30_000L

    /**
     * 页面「安静」多久才算暖好。
     *
     * 这里不能写成固定 sleep：Cloudflare 的题页 finishing 之后**还会自己再跳一次**，
     * 跳的那一下会把它身上正在跑的脚本连上下文一起拆掉。实测按固定 1.5 秒等，
     * 第一条 fetch 正好赶在跳转里发出去，20 秒后超时 —— 报回界面的是「这一页在 20s 内没回话」，
     * 而真相是「我们发早了」。所以改成等条件：加载状态连续 [WARM_QUIET_MS] 没有再翻动才算完。
     */
    private const val WARM_QUIET_MS = 1_500L

    /** 轮询加载状态的间隔。 */
    private const val WARM_POLL_MS = 250L

    /**
     * 一趟取数的总闸，暖机 + 重试 + 两次问话全在里面。
     *
     * 单看每一步都有超时，叠起来没有：暖机 30s + 一次问 20s + 再暖一次 + 再问一次
     * 就是接近一分半，而这一趟是**持着锁**跑的 —— 上限不给，一个源就能把整场搜索拖死。
     * 给到 70 秒是「冷启动第一次过题」和「这一趟该放弃了」之间的位置：正常一次就完，
     * 只有暖机早退的那一类才会用到第二趟。
     */
    private const val WALL_MS = 70_000L

    /**
     * JS 侧一次最多交回多少**字符**。
     *
     * 不照抄 OkHttp 那条 4 MB：那边是边读边限字节，这边是整个 String 从 JS 递过来，
     * 而中文一个字在 JVM 里就是两个字节、过桥时还要再拷一份。200 万字符约等于 4 MB 内存，
     * 再大就不是「一份接口正文」而是有人把地址指到了一坨别的东西上。
     */
    private const val CHAR_CAP = 2_000_000

    private val main = Handler(Looper.getMainLooper())

    /** 整颗引擎的排队闸。 */
    private val gate = Mutex()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
    private val tokens = SecureRandom()

    private var appCtx: Context? = null
    private var engine: WebView? = null

    /**
     * 文档**现在真的**落在哪个源上（scheme://host）。
     *
     * 只从引擎自己报的 url 里写（[WebViewClient.onPageFinished]、[docOrigin]），
     * 绝不在「我们让它去哪儿」的时候写 —— 加载超时、被拒、中途被站点跳走，
     * 都发生在「想去」和「真到了」之间。写错了的后果是后面每次都跳过暖机、
     * 对着一个不在这一源上的文档发 fetch，然后这个源到进程结束都救不回来。
     */
    @Volatile private var liveOrigin: String? = null

    /**
     * 这颗引擎现在是不是在加载东西。
     *
     * 由 [WebViewClient] 的 onPageStarted / onPageFinished 翻，暖机就等它连续安静
     * [WARM_QUIET_MS] 不动 —— 这是「题答完了、页面停住了」唯一诚实的判据。
     */
    @Volatile private var loading = false

    private data class Reply(val status: Int, val body: String, val length: Int)

    /** 由 [net.cinehub.CineHubApplication] 在启动时接上，之后一直用 Application context。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * JS 侧唯一的一扇门：把 fetch 的结果交回来。
     *
     * 只写不读，而且只认我们发出去的随机号（[newToken]）—— 页面里任何脚本都能调它，
     * 所以「猜得到号」就等于「能伪造这一条结果」。伪造出来的是一条影枢会照着去播、
     * 去缓存的播放地址，所以这个号必须随机、必须一次一换。
     */
    private val bridge = object : Any() {
        @JavascriptInterface
        fun reply(token: String, status: Int, body: String, length: Int) {
            pending.remove(token)?.complete(Reply(status, body, length))
        }
    }

    /**
     * 用浏览器内核取一页。非 2xx 原样带回状态码；压根没问到（暖机失败 / 超时 / 跨源）才抛。
     *
     * 抛出去的话只有一种：这一趟没问到。那和「对方回了 403」是两件事，
     * 前者不能报成「这个源拒绝访问」。
     */
    suspend fun get(url: String, timeoutSec: Long): Http.ResponseBody = fetch(url, timeoutSec).first

    /** 探一次连通性，只带回耗时。走的是和取数一模一样的那条路，所以过题重试也一模一样。 */
    suspend fun probe(url: String): Long {
        val (r, ms) = fetch(url, 30)
        if (r.status !in 200..299) error(Http.failureReason(r.status, r.body))
        return ms
    }

    /** 带回正文，和**它真正花了多久**（排队等锁的那段不算，那个数字要拿去显示在界面上）。 */
    private suspend fun fetch(url: String, timeoutSec: Long): Pair<Http.ResponseBody, Long> {
        val origin = originOf(url) ?: error("这个地址没有主机名，浏览器内核不知道该打开谁")
        val out = withTimeoutOrNull(WALL_MS) {
            gate.withLock {
                val wv = ensure()
                val t0 = System.currentTimeMillis()
                val r = if (!warm(wv, url)) {
                    Http.ResponseBody("浏览器内核没能把这一源打开（过题那一次加载没回来）", 0)
                } else {
                    var one = request(wv, origin, url, timeoutSec)
                    if (isChallenge(one) || one.status == 0) {
                        // 两种「还没到」：题页还挂着（403 那张），或者这一趟压根没回话 ——
                        // 后者就是文档在我们发出 fetch 之后自己跳走了，脚本连上下文一起没。
                        // 两种都重新加载一次、让引擎再答一遍，然后只问第二次。
                        // 不循环 —— 反复交卷正是把限流踩深的东西（高清点播那边实测连着打会改回 429）。
                        one = if (!warm(wv, url, force = true)) {
                            Http.ResponseBody("重答一次也没能把这一源打开", 0)
                        } else {
                            request(wv, origin, url, timeoutSec)
                        }
                    }
                    one
                }
                r to (System.currentTimeMillis() - t0)
            }
        } ?: error("浏览器内核这一趟超过 ${WALL_MS / 1000} 秒 —— 放弃")
        if (out.first.status == 0) error(out.first.body)
        return out
    }

    private suspend fun ensure(): WebView {
        engine?.let { return it }
        val ctx = appCtx ?: error("浏览器内核没接上 —— 应用启动时没调 BrowserEngine.init")
        // 造不出来就让它抛出去（有的设备没有可用的 WebView 提供程序）。
        // 把异常吞在这里等于让 gate 那把锁永远没人还 —— 之后每一个走这条路的源都会卡死。
        val wv = onMain {
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 默认 UA 里那段 `; wv` 明摆着告诉对方「这不是浏览器，是别人 App 里嵌的一颗 WebView」，
                // 而 Cloudflare 那一类正按这一条打分。和「站点页取链」那页同一个处理：
                // 只抹掉这个自报家门的标记，其余交给引擎自己 —— 引擎是 Chrome/146，
                // 写死一个 Chrome/120 的串反而成了「UA 和指纹不一致」的那种请求。
                settings.userAgentString = settings.userAgentString?.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                addJavascriptInterface(bridge, "__cinehub")
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        loading = true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // 认引擎报的那一份，不认我们传进去的那一条：中途被站点跳走时这两个不一样
                        liveOrigin = originOf(view.url ?: url)
                        loading = false
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // 主框架直接失败时也要把「还在加载」这面旗放下来，否则暖机白等满 30 秒
                        if (request?.isForMainFrame == true) {
                            liveOrigin = originOf(view.url)
                            loading = false
                        }
                    }
                }
            }
        }
        engine = wv
        return wv
    }

    /**
     * 把文档落在 [url] 上，并确认真的落住了。返回「到了没有」。
     *
     * 这一步就是「让引擎自己去答题」。加载的是**要去问的那一条地址本身**，不是站点首页 ——
     * 首页是一份会自己动来动去的文档：它带着站点的脚本、公告、播放器，随时可能再跳一次，
     * 而我们那条 fetch 就挂在它身上。实测正是这样死的：探根路径量到 978ms 一切正常，
     * 换成从首页那份文档里去 fetch 搜索接口，20 秒没回话（页面跳走，promise 跟着旧文档一起没了）。
     * 接口地址这份文档是死的，没有脚本会替我们导航。
     *
     * 等的是条件，不是固定时长，而且条件不能只看「加载停了没有」：Cloudflare 那张题页是
     * **带着 403 直接占住你要的那个地址**的，它的 `onPageFinished` 会照常回调、URL 也不会变
     * （答题脚本在原地址上跑完再自己刷回来）。只看加载状态会在这里被骗 ——
     * 暖机报成功，紧接着的 fetch 拿回 403 那道题，界面上就成了「影枢答过一次还是没过」，
     * 而真相是「我们抢在它答完之前问了」。
     */
    private suspend fun warm(wv: WebView, url: String, force: Boolean = false): Boolean {
        val origin = originOf(url) ?: return false
        if (!force && liveOrigin == origin && !loading) return true
        try {
            onMain {
                // 上一趟超时后被放弃的那次加载可能还在跑。不停掉它，它会和这一趟叠在一起，
                // 最后「安静下来」判的就不是这一源了。
                runCatching { wv.stopLoading() }
                liveOrigin = null
                loading = true
                wv.loadUrl(url)
            }
        } catch (e: Throwable) {
            loading = false
            liveOrigin = null
            throw e
        }
        val deadline = System.currentTimeMillis() + WARM_TIMEOUT_MS
        var quietSince = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(WARM_POLL_MS)
            val settled = !loading && !onMain { stillOnChallenge(wv) }
            if (!settled) {
                quietSince = 0L
            } else {
                if (quietSince == 0L) quietSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - quietSince >= WARM_QUIET_MS) break
            }
        }
        if (quietSince == 0L) {
            liveOrigin = null
            return false
        }
        return docOrigin(wv) == origin
    }

    /** 引擎此刻还挂在题页上吗。题页的标题就是 `Just a moment...`，而它占的是你要的那个地址。 */
    private fun stillOnChallenge(wv: WebView): Boolean {
        val t = wv.title.orEmpty()
        return t.contains("just a moment", ignoreCase = true) ||
            t.contains("attention required", ignoreCase = true)
    }

    private suspend fun request(
        wv: WebView,
        origin: String,
        url: String,
        timeoutSec: Long,
    ): Http.ResponseBody {
        if (docOrigin(wv) != origin) {
            // 文档不在这源上就别发这条 fetch：跨源的 fetch 只会拿到一条 CORS 错，
            // 而更糟的是它会把「我们其实没暖到这一源」这件事伪装成「这个源回话不正常」。
            liveOrigin = null
            return Http.ResponseBody("这一源的页面没停在它自己身上（引擎现在在 ${docOrigin(wv) ?: "别的地方"}）", 0)
        }
        val token = newToken()
        val d = CompletableDeferred<Reply>()
        pending[token] = d
        val q = JSONObject.quote(token)
        val script = "fetch(" + JSONObject.quote(url) + ",{credentials:\"include\"})" +
            ".then(function(r){return r.text().then(function(t){" +
            "__cinehub.reply($q,r.status,t.slice(0,$CHAR_CAP),t.length)})})" +
            ".catch(function(e){__cinehub.reply($q,0,String(e),0)})"
        try {
            onMain { wv.evaluateJavascript(script, null) }
            val r = withTimeoutOrNull(timeoutSec * 1000) { d.await() }
                ?: return Http.ResponseBody("这一页在 ${timeoutSec}s 内没回话", 0)
            if (r.length > CHAR_CAP) error("这个源回的内容超过 $CHAR_CAP 字符，不像一份能解析的接口")
            return Http.ResponseBody(body = r.body, status = r.status)
        } finally {
            // 被取消时也要摘掉：留在表里就是一条永远没人认领的 Deferred
            pending.remove(token)
        }
    }

    /** 引擎此刻的文档在哪个源上。读的是 WebView 自己报的 url，不是我们想去的那个。 */
    private suspend fun docOrigin(wv: WebView): String? = onMain { originOf(wv.url) }

    /** 这一源现在挂的是浏览器验证那道题。 */
    private fun isChallenge(r: Http.ResponseBody): Boolean {
        if (r.status != 403 && r.status != 503) return false
        val low = r.body.lowercase()
        return listOf("just a moment", "cf-chl", "cloudflare", "attention required")
            .any { low.contains(it) }
    }

    private fun newToken(): String = ByteArray(12).also { tokens.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun originOf(url: String?): String? = runCatching {
        val u = url?.let { java.net.URI(it) } ?: return@runCatching null
        if (u.scheme == null || u.host == null) null else "${u.scheme}://${u.authority}"
    }.getOrNull()

    /**
     * 丢到主线程上跑完，把结果（或异常）原样带回来。
     *
     * 异常必须传出去：吞掉它，调用方就只能等到一个超时，而真正的原因（造不出 WebView、
     * 页面加载抛了）永远看不见 —— 更糟的是那条路上锁还没还。
     */
    private suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine { cont ->
        main.post {
            val v = runCatching(block)
            if (!cont.isActive) return@post
            cont.resumeWith(
                if (v.isSuccess) Result.success(v.getOrThrow())
                else Result.failure(v.exceptionOrNull() ?: IllegalStateException("主线程执行失败")),
            )
        }
    }
}
