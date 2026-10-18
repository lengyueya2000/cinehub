package net.cinehub.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import net.cinehub.data.model.StreamUrls
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 站点自己的页面里取直链。
 *
 * 存在的理由：有些站（4kvm.net 是典型）把直链放在**它自己的脚本现算出来的签名请求**后面 ——
 * 不执行它的脚本就构造不出那条请求。影枢不去逆这个签名，那等于绕过对方的取链规则。
 *
 * 这里走的是另一条路：页面由站点自己渲染、登录由用户自己做、
 * 地址由站点的脚本自己算出来发给它自己的播放器，影枢只是在中间
 * 把站点已经决定给这个用户的那条地址接住，交给本机播放器。
 * 所以这个界面必须让用户看得见整个过程，而不是一个黑盒。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteBrowserScreen(
    vm: AppViewModel,
    startUrl: String,
    label: String,
    referer: String? = null,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf(label) }
    var progress by remember { mutableIntStateOf(0) }
    /** 主框架这一轮导航失败的原因；每次开始加载都清空，成功出画面就不该再留着。 */
    var pageError by remember { mutableStateOf<String?>(null) }
    /**
     * 站点页面请求过的媒体地址，留着让用户自己挑。
     *
     * 连它被请求时带的 Referer / Cookie 一起留着：不少解析页给的地址只认
     * 「就是我这页发出去的那次请求」，只把 URL 交给播放器会拿到 404（实测西瓜影院就是这样）。
     *
     * 这里只保留最近接住的 [MaxCapturedLinks] 条，超出丢最老的。理由：一个长页面加载时
     * 往往先抖出一串预览 / 广告 / 埋点地址，用户真正要看的那条常常是第 4 条以后 ——
     * 无界留着既白白涨内存、又在界面最关键的「挑一条」这一步骗人。丢就丢最老的那头，
     * 因为用户此刻更需要看到最新接到的那条（那多半就是他刚点播放的这一集），旧的预览早该让位。
     */
    var links by remember { mutableStateOf<List<Captured>>(emptyList()) }
    // 累计接住过多少条 —— 列表被截到 20 条后，界面仍要如实说「一共接到过多少」
    var totalSeen by remember { mutableIntStateOf(0) }
    val queue = remember { ConcurrentLinkedQueue<Captured>() }
    var web by remember { mutableStateOf<WebView?>(null) }
    /** 这台 WebView 当前加载的是哪个目标；[startUrl] 或 [referer] 变了才重新加载。 */
    var loadedFrom by remember { mutableStateOf("") }
    // 这一页只在真的有一集正在播时才有处可交：接住的地址要交给「正在播的这一集」。
    // （以前「播放源管理 → 站点」那条纯登录的入口也会落到这里，那一趟接住的地址无处可交。
    //  现在源管理不再有开站点页的动作，这一页只剩取链这一个来路。）
    val playing by vm.playing.collectAsState()

    // 浏览器自己的历史优先：页面里点进了一层解析页，返回键该先退那一层。
    // 原来这里无条件 onClose —— 用户按返回是想回到刚才那页，结果整扇门被关上、
    // 会话页也没了，得从播放器那边重新进（源管理里已经不再有开站点页的动作）。
    BackHandler(enabled = true) {
        val wv = web
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    // shouldInterceptRequest 在 WebView 自己的线程上回调，跨到主线程只能靠轮询这份队列
    LaunchedEffect(queue) {
        var seq = 0
        while (true) {
            val drained = generateSequence { queue.poll() }.toList()
            if (drained.isNotEmpty()) {
                // 按到达顺序盖序号，越新越大 —— 界面靠它认「最新接到的那条」
                val stamped = drained.map { it.copy(seq = seq++) }
                totalSeen += stamped.size
                // 同一条地址再看一次时，留**后面**那一份的 Referer / Cookie。
                // 原来 distinctBy 认第一份：签名页给的地址常常同一毫秒问两次，第一次的
                // Referer 是旧解析页的 —— 拿那份去交给播放器就是 404，而屏幕上看起来一切正常。
                links = (links.filterNot { n -> stamped.any { it.url == n.url } } + stamped)
                    .distinctBy { it.url }
                    .takeLast(MaxCapturedLinks)
            }
            delay(400)
        }
    }

    // 收尾挂在生命周期上而不是按钮上：系统返回键退出这一页时也必须走到
    DisposableEffect(web) {
        val wv = web
        onDispose {
            if (wv != null) {
                // 停loading只停了页面自己的加载，页面里那个正在播的 <video> 会继续出声继续耗流量 ——
                // 先让页面自己把媒体停了，再 stopLoading 掐掉还在跑的请求。
                //
                // 这里不能再叠 onPause()：平台文档写明 onPause 作用于**本进程所有 WebView**，
                // 它把页面整体 background 化、推迟 timer 与周期任务。而影枢里另一台 WebView 是
                // BrowserEngine 那台取数引擎 —— 用户关掉站点页那一瞬它可能正卡在 Cloudflare
                // 那道题上，题的计时器被冻住就再也不会回话，30 秒暖机白等，最后报
                // 「这个源接不进来了」，还把那个源在健康表里判死。全项目没有任何一处 onResume
                // 能把它解开来，这份假死一直留到进程重启。
                //
                // 收尾必须等脚本跑完再拆。`evaluateJavascript` 是**异步**的：它把这段脚本
                // 丢进渲染器就返回，结果（这里不关心）稍后才回调回主线程。紧接着就 destroy，
                // 等于在脚本执行之前把渲染器拆了 —— 那一句「让页面自己把媒体停了」从来没执行过，
                // 只剩 stopLoading()，而它管不住一个**已经在播**的 <video>。
                // 所以收尾放进它的回调里，并留一条有界的兜底：WebView 已经死了就不会回调，
                // 那种情况也不能把这次销毁漏掉。
                var disposed = false
                fun detachAndDestroy() {
                    if (disposed) return
                    disposed = true
                    // 必须先从父布局摘出来再 destroy，否则 WebView 被父 view 引用着，销毁不掉、还泄漏
                    runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
                    runCatching { wv.destroy() }
                }
                runCatching { wv.stopLoading() }
                val posted = runCatching {
                    wv.evaluateJavascript(PauseMediaJs) { detachAndDestroy() }
                }.isSuccess
                if (!posted) {
                    detachAndDestroy()
                } else {
                    // handler 挂在 wv 自己身上，它要是已经被回收了这颗就永远不会跑 ——
                    // 所以上面那条回调仍然是主路，这一颗只是兜底。
                    runCatching { wv.postDelayed({ detachAndDestroy() }, 500L) }
                }
            }
            // 会话要落盘，不能清。这一页有两个来路：播放失败后从播放器进来接直链，
            // 以及从「播放源管理 → 某条内置源的『凭证』」进来先登好自己的账号 ——
            // README 写的边界就是「凭证只留在本机 WebView 的 cookie 里」。
            // 清掉等于每次进来都得重登一遍；而 removeAllCookies 清的还是整个 App 的 cookie 域，
            // 会把用户在别的源刚登好的会话一起抹了。
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Sp.s2,
                    start = Sp.s4,
                    end = Sp.s4,
                    bottom = Sp.s2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                // 这颗是这一页唯一的出口，32dp 太小 —— 而且它旁边就是状态栏的边
                Modifier.size(44.dp).clip(CircleShape).background(T.Surface2)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, null, tint = T.Text, modifier = Modifier.size(16.dp))
            }
            Spacer8()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = T.Text,
                    fontSize = Fs.sm,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "站点页面 · 登录与解析都在对方自己的页面里完成 · 关闭会停掉页面里正在播的",
                    color = T.TextFaint,
                    fontSize = Fs.tiny,
                )
            }
            if (progress in 1..99) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    color = T.Src500,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // 页面没出来时得有一句话。这颗 WebView 以前只会黑着：站点拒了、题没过、网络断了，
        // 屏上都是同一个黑屏，而「黑着」在用户眼里和「卡住了」分不出来 —— 他会一直等。
        if (pageError != null && links.isEmpty()) {
            Text(
                "${pageError} —— 这一页在影枢里没打开。" +
                    "页面里要是让你登录或点一下播放，做完这一步它自己会重来。",
                color = T.TextDim,
                fontSize = Fs.xs,
                modifier = Modifier.padding(start = Sp.s4, end = Sp.s4, bottom = Sp.s2),
            )
        }

        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 站点的播放器要点一下才开始请求地址，这里不替用户设卡
                    settings.mediaPlaybackRequiresUserGesture = false
                    // UA 用 WebView 自己那一份，只把「我是一颗嵌入式 WebView」这个标记抹掉。
                    //
                    // 原来这里写死成 [Http.UA]（给 OkHttp 那种纯 HTTP 客户端用的 Chrome/120 串），
                    // 而引擎其实是 Chrome/146 —— UA 与引擎指纹不一致，正是按指纹放行那道题
                    // 最爱判死的一种请求。改成不覆盖之后仍然打不开（同一台模拟器、同一个网络出口，
                    // 系统浏览器把 高清点播 的页面正常渲染出来了，这颗 WebView 却连首页都是黑的），
                    // 剩下的差别就是默认 UA 里那段 `; wv`：它明摆着告诉对方「这不是浏览器，
                    // 是别人 App 里嵌的一颗 WebView」，而 Cloudflare 那一类正是按这一条给分数的。
                    // 这一页的语义本来就是「按浏览器的方式打开它」，所以这里只去掉这个自报家门的标记，
                    // 其余原样交给引擎自己。
                    settings.userAgentString = settings.userAgentString?.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = run {
                            val u = request.url.toString()
                            if (StreamUrls.isMediaUrl(u)) queue.add(capturedOf(u, request))
                            null
                        }

                        // 每一次重新导航都先把上一轮的错擦掉：站点那道题就是靠「先拒一次、
                        // 再放行一次」过的（高清点播 的播放页实测如此），
                        // 留着第一条拒绝信息报「打不开」同样是假话。
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageError = null
                        }

                        // 主框架被拒时把原因留在屏幕上。原来这一页只会黑着：
                        // 页面没出来、没有一条地址、也没有一句话，用户只能猜是网断了还是站点不给。
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageError = "这一页回了 HTTP ${errorResponse?.statusCode ?: "?"}"
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageError = error?.description?.toString() ?: "这一页没打开"
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, t: String) {
                            if (t.isNotBlank() && !t.startsWith("http")) title = t
                        }
                    }
                    web = this
                    loadedFrom = "$startUrl|" + (referer ?: "")
                    // 源站的解析端按来源域名放行，不带 Referer 会被当成陌生域名拒掉
                    if (referer.isNullOrBlank()) loadUrl(startUrl)
                    else loadUrl(startUrl, mapOf("Referer" to referer))
                }
            },
            // 换了目标却不重新加载：这一页挂着同一套 composable，只是 startUrl 变了
            // （从另一部片的「站点页取链」进来），factory 不会再跑一次。
            // 于是屏上显示的是上一部片的站点页面，接住的地址也是它的 —— 挑一条交出去，
            // 播的是完全不相干的一部。
            update = { wv ->
                val now = "$startUrl|" + (referer ?: "")
                if (loadedFrom != now) {
                    loadedFrom = now
                    links = emptyList()
                    totalSeen = 0
                    title = label
                    if (referer.isNullOrBlank()) wv.loadUrl(startUrl)
                    else wv.loadUrl(startUrl, mapOf("Referer" to referer))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        if (links.isNotEmpty()) {
            // 挑「最可能有用」的几条，不是插入顺序的前几条：最新接到的排最前，其余按像不像正片排。
            val ordered = pickUsefulFirst(links)
            val shown = ordered.take(LinksShown)
            Column(
                Modifier.fillMaxWidth().background(T.Surface)
                    .padding(horizontal = Sp.s4, vertical = Sp.s3),
                verticalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                Text(
                    when {
                        playing == null ->
                            "接到 $totalSeen 个地址，保留最近 ${links.size} 条 —— 先在影枢里点播一部影片，再回来挑一个"
                        else ->
                            "接到 $totalSeen 个地址，保留最近 ${links.size} 条 —— 挑一个交给影枢播放"
                    },
                    color = T.Text,
                    fontSize = Fs.xs,
                    fontWeight = FontWeight.SemiBold,
                )
                shown.forEach { c ->
                    val canHandOff = playing != null
                    Row(
                        Modifier.fillMaxWidth().clip(R.sm).background(T.Surface2)
                            .border(1.dp, T.Border, R.sm)
                            .clickable(enabled = canHandOff) { vm.useSiteLink(c.url, c.headers); onClose() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.url.substringBefore('?').substringAfterLast('/').take(28)
                                    .ifBlank { c.url.take(28) },
                                color = T.Src400,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.Bold,
                            )
                            // 让人看得出这条地址是哪个站给的 —— 挑的就是这个
                            Text(
                                runCatching { java.net.URI(c.url).host }.getOrNull() ?: c.url,
                                color = T.TextFaint,
                                fontSize = Fs.tiny,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer8()
                        Text(
                            if (canHandOff) "播放" else "待播放",
                            color = if (canHandOff) T.Text else T.TextFaint,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                // 说清看到的是全部还是截断后的几条，别让人以为只有这几条
                if (shown.size < links.size) {
                    Text(
                        "以上按「最新接到 / 最像正片」列出 ${shown.size} 条，" +
                            "还有 ${links.size - shown.size} 条没列出（最多只留最近 $MaxCapturedLinks 条）。",
                        color = T.TextDim,
                        fontSize = Fs.xs,
                    )
                }
            }
        }
    }
}

/**
 * 接住的一条地址，连同它被请求时携带的条件。
 *
 * [seq] 是接住时的到达序号，越新越大 —— 只用来在界面上认「最新接到的那条」。
 */
private data class Captured(val url: String, val headers: Map<String, String>, val seq: Int = 0)

// 列表只留最近这么多条：再多就是页面加载抖出来的噪声，涨内存还骗界面
private const val MaxCapturedLinks = 20

// 界面一次列几条 —— 多了划不到、少了怕漏掉真正那条，4 条够挑
private const val LinksShown = 4

// 让页面自己把在放的媒体停了：影枢不能替站点「拆」它的播放器，
// 但用户要走了，暂停它自己正在出声的 video/audio 是本分
private const val PauseMediaJs =
    "document.querySelectorAll('video,audio').forEach(function(e){try{e.pause();}catch(x){}});"

/**
 * 明显是预览 / 广告 / 埋点的地址特征 —— 只用来把这类压到最后，
 * 绝不拿它去猜地址或伪造来源。命中的都是 URL 里写得很直白的标记词。
 */
private val JunkMarkers = listOf("preview", "trailer", "advert", "advertisement", "banner", "beacon", "splash")

/**
 * 这条地址有多像「正片」，分越高越该靠前。
 *
 * 判断只取自 URL —— 在 shouldInterceptRequest 这一层只有请求本身，拿不到响应的
 * content-type 和体积，所以不假装知道，只按扩展名和明显的预览标记粗排：
 * 整集的清单（.m3u8 / .mpd）最像正片，完整文件次之，单切片（.ts 等）多半不是要挑的那条。
 */
private fun looksLikeMainFeature(url: String): Int {
    val path = url.substringBefore('?').lowercase()
    if (JunkMarkers.any { path.contains(it) }) return 0
    return when {
        path.endsWith(".m3u8") || path.endsWith(".mpd") -> 3
        path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".mov") -> 2
        else -> 1
    }
}

/**
 * 界面显示顺序：最新接到的那条排最前（用户刚点的多半就是它），
 * 其余按「有多像正片」降序，分数相同再看谁更新。
 */
private fun pickUsefulFirst(links: List<Captured>): List<Captured> {
    if (links.isEmpty()) return emptyList()
    val newest = links.maxOf { it.seq }
    return links.sortedWith(
        compareByDescending<Captured> { it.seq == newest }
            .thenByDescending { looksLikeMainFeature(it.url) }
            .thenByDescending { it.seq }
    )
}

/**
 * 把这次请求自带的 Referer / Cookie 一起留下。
 *
 * 解析页给出的地址常常只认「从我这个页面发出去的那次请求」——
 * 只把 URL 抄给播放器会拿到 404（实测西瓜影院那条 m3u8 就是这样）。
 * 这里不伪造任何东西，只是把浏览器本来就要发的头原样交给播放器。
 */
private fun capturedOf(url: String, request: WebResourceRequest): Captured {
    val src = request.requestHeaders.orEmpty()
    fun pick(name: String): String? =
        src.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val headers = buildMap {
        pick("Referer")?.let { put("Referer", it) }
        (pick("Cookie") ?: CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() })
            ?.let { put("Cookie", it) }
    }
    return Captured(url, headers)
}
