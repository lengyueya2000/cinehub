package net.cinehub.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import net.cinehub.data.net.Http
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
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf(label) }
    var progress by remember { mutableIntStateOf(0) }
    /** 站点页面请求过的媒体地址，按出现顺序留着让用户自己挑。 */
    var links by remember { mutableStateOf<List<String>>(emptyList()) }
    val queue = remember { ConcurrentLinkedQueue<String>() }
    var web by remember { mutableStateOf<WebView?>(null) }
    // 从「站点」入口进来只是为了登录，此时没有正在放的片子，接住的地址无处可交
    val playing by vm.playing.collectAsState()

    BackHandler(onBack = onClose)

    // shouldInterceptRequest 在 WebView 自己的线程上回调，跨到主线程只能靠轮询这份队列
    LaunchedEffect(queue) {
        while (true) {
            val drained = generateSequence { queue.poll() }.toList()
            if (drained.isNotEmpty()) {
                links = (links + drained).distinct()
            }
            delay(400)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 登录态就存在本机 WebView 的 cookie 里，退出时落盘，下次进这个源不用再登一遍
            runCatching { CookieManager.getInstance().flush() }
            web?.stopLoading()
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
                Modifier.size(32.dp).clip(CircleShape).background(T.Surface2)
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
                    "站点页面 · 登录与解析都在对方自己的页面里完成",
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

        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 站点的播放器要点一下才开始请求地址，这里不替用户设卡
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = Http.UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = run {
                            val u = request.url.toString()
                            if (StreamUrls.isMediaUrl(u)) queue.add(u)
                            null
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
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        if (links.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(T.Surface)
                    .padding(horizontal = Sp.s4, vertical = Sp.s3),
                verticalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                Text(
                    if (playing == null) "站点给了 ${links.size} 个地址 —— 先在影枢里点播一部影片，再回来挑一个"
                    else "站点给了 ${links.size} 个地址 —— 挑一个交给影枢播放",
                    color = T.Text,
                    fontSize = Fs.xs,
                    fontWeight = FontWeight.SemiBold,
                )
                links.take(3).forEach { u ->
                    val canHandOff = playing != null
                    Row(
                        Modifier.fillMaxWidth().clip(R.sm).background(T.Surface2)
                            .border(1.dp, T.Border, R.sm)
                            .clickable(enabled = canHandOff) { vm.useSiteLink(u); onClose() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            u.substringBefore('?').substringAfterLast('/').take(28),
                            color = T.Src400,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(Modifier.weight(1f))
                        Text(
                            if (canHandOff) "播放" else "待播放",
                            color = if (canHandOff) T.Text else T.TextFaint,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
