package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer16
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 播放源管理 —— 这个产品跟普通视频 App 最不一样的一页。
 *
 * 源不是藏在角落的高级设置，而是用户随时要看的列表：
 * 健康状态、延迟、优先级、启停，一屏之内全部可见。
 */
@Composable
fun SourceScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()
    val checking by vm.checking.collectAsState()

    var showAdd by remember { mutableStateOf(false) }

    val ok = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Ok }
    val slow = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Slow }
    val down = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Down }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Sp.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.ArrowBack, null, tint = T.Text, modifier = Modifier.size(19.dp))
                    }
                    Spacer8()
                    Column(Modifier.weight(1f)) {
                        Text("播放源管理", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
                        Text(
                            "影枢会解析并重渲染以下站点的内容",
                            color = T.TextFaint,
                            fontSize = Fs.xxs,
                        )
                    }
                    Row(
                        Modifier.height(32.dp).clip(R.full)
                            .background(Brush.verticalGradient(listOf(T.Play400, T.Play500)))
                            .clickable { showAdd = true }
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer4()
                        Text("添加", color = Color.White, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().clip(R.lg)
                        .background(
                            Brush.linearGradient(
                                listOf(T.Src500.copy(alpha = .09f), T.Src500.copy(alpha = .02f))
                            )
                        )
                        .border(1.dp, T.Src500.copy(alpha = .22f), R.lg)
                        .padding(Sp.s4),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("源健康状态", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                        Spacer8()
                        Text(
                            "已接入 ${sources.size} 个源",
                            color = T.TextFaint,
                            fontSize = Fs.xxs,
                        )
                        Box(Modifier.weight(1f))
                        Row(
                            Modifier.clip(R.full)
                                .background(T.Src500.copy(alpha = .10f))
                                .border(1.dp, T.Src500.copy(alpha = .34f), R.full)
                                .clickable { vm.refreshHealth(true) }
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Refresh, null,
                                tint = T.Src500,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer4()
                            Text(
                                if (checking) "检测中…" else "全部重测",
                                color = T.Src500,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer12()
                    Row(Modifier.fillMaxWidth()) {
                        Stat(ok.toString(), "正常", T.Ok, Modifier.weight(1f))
                        Stat(slow.toString(), "较慢", T.Warn, Modifier.weight(1f))
                        Stat(down.toString(), "不可用", T.Err, Modifier.weight(1f))
                    }
                    Spacer12()
                    Text(
                        "长按拖动排序优先级 · 数字越小越优先",
                        color = T.TextDim,
                        fontSize = Fs.xxs,
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = Sp.s5, bottom = Sp.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已接入", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                    Box(Modifier.weight(1f))
                    Text("按优先级排序", color = T.TextFaint, fontSize = Fs.xxs)
                }
            }

            // 内置源被删掉后可以从这里补回，不必重装
            val missing = net.cinehub.data.Seed.sources.count { s ->
                sources.none { it.id == s.id }
            }
            if (missing > 0) {
                item {
                    var restored by remember { mutableStateOf<String?>(null) }
                    Row(
                        Modifier.fillMaxWidth().clip(R.md)
                            .background(T.Warn.copy(alpha = .07f))
                            .border(1.dp, T.Warn.copy(alpha = .28f), R.md)
                            .padding(Sp.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "有 $missing 个内置源不在列表里",
                                color = T.Text,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer4()
                            Text(
                                restored ?: "删掉的内置源可以补回来，你自己添加的源不受影响。",
                                color = T.TextFaint,
                                fontSize = Fs.xxs,
                                lineHeight = Fs.xxs * 1.6f,
                            )
                        }
                        Spacer12()
                        Text(
                            "补回",
                            color = T.Warn,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(R.full)
                                .border(1.dp, T.Warn.copy(alpha = .5f), R.full)
                                .clickable {
                                    vm.restoreDefaultSources { n ->
                                        restored = if (n > 0) "已补回 $n 个内置源" else "内置源都在，无需补回"
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                    Spacer12()
                }
            }

            items(sources.size) { i ->
                val cfg = sources[i]
                SourceRow(
                    cfg = cfg,
                    report = health[cfg.id],
                    onToggle = { vm.toggleSource(cfg.id, it) },
                    onDelete = { vm.deleteSource(cfg.id) },
                    onOpenSite = { vm.openSiteBrowser(cfg.baseUrl, cfg.name) },
                    onUp = {
                        if (i > 0) {
                            val ids = sources.map { it.id }.toMutableList()
                            val tmp = ids[i - 1]; ids[i - 1] = ids[i]; ids[i] = tmp
                            vm.reorder(ids)
                        }
                    },
                )
                Spacer8()
            }

            item {
                Spacer16()
                Text(
                    "关于「重渲染」：影枢不套用源站页面，而是把抓取到的内容归一化成自己的数据模型，" +
                        "由本机播放器渲染画面。源的直链如果需要你本人的站点账号，请在添加源时填入你自己的凭证 —— " +
                        "影枢只转发凭证，不代为登录。",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                    lineHeight = Fs.xxs * 1.7f,
                )
            }
        }

        if (showAdd) {
            AddSourceSheet(
                onDismiss = { showAdd = false },
                onTest = { name, url, kind, token, playPath, cb -> vm.testSource(name, url, kind, token, playPath, cb) },
                onSave = { cfg ->
                    vm.saveSource(cfg)
                    showAdd = false
                },
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = color, fontSize = Fs.title, fontWeight = FontWeight.ExtraBold)
        Text(label, color = T.TextFaint, fontSize = Fs.xxs)
    }
}

@Composable
private fun SourceRow(
    cfg: SourceConfig,
    report: HealthReport?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenSite: () -> Unit,
    onUp: () -> Unit,
) {
    val h = report?.health ?: Health.Unknown
    val border = when (h) {
        Health.Down -> T.Err.copy(alpha = .26f)
        Health.Ok -> T.Src500.copy(alpha = .28f)
        else -> T.Border
    }
    Row(
        Modifier.fillMaxWidth().clip(R.lg)
            .background(if (h == Health.Down) T.Err.copy(alpha = .035f) else T.Surface)
            .border(1.dp, border, R.lg)
            .padding(Sp.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        when (h) {
                            Health.Down -> listOf(Color(0xFF64748B), Color(0xFF334155))
                            Health.Ok -> listOf(Color(0xFF3B82F6), Color(0xFF1E3A8A))
                            else -> listOf(Color(0xFFF59E0B), Color(0xFFB45309))
                        }
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cfg.name.take(2),
                color = Color.White,
                fontSize = Fs.body,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer12()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cfg.name, color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                if (cfg.priority == 1) {
                    Spacer8()
                    Pill("默认", cyan = true)
                }
                if (h == Health.Down) {
                    Spacer8()
                    Text(
                        "连接失败",
                        color = T.Err,
                        fontSize = Fs.tiny,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(T.Err.copy(alpha = .10f))
                            .border(1.dp, T.Err.copy(alpha = .30f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer4()
            Text(
                cfg.baseUrl,
                color = T.TextFaint,
                fontSize = Fs.xxs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer8()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    when (cfg.kind) {
                        SourceKind.Html -> "网页解析"
                        SourceKind.JsonApi -> "JSON 接口"
                        SourceKind.M3u -> "M3U 列表"
                        SourceKind.AiMovie -> "签名接口"
                    },
                    ghost = true,
                )
                cfg.tags.take(1).forEach { Pill(it, cyan = true) }
                if (h == Health.Down && report?.detail != null) {
                    Text(
                        report.detail!!.take(18),
                        color = T.Err,
                        fontSize = Fs.tiny,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(5.dp))
                            .background(T.Err.copy(alpha = .08f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Switch(
                checked = cfg.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = T.Src600,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = T.Surface4,
                ),
            )
            Spacer4()
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(h, 5)
                Spacer4()
                Text(
                    when (h) {
                        Health.Ok -> "${report?.latencyMs ?: "-"}ms"
                        Health.Slow -> "${report?.latencyMs ?: "-"}ms 较慢"
                        Health.Down -> "不可用"
                        Health.Unknown -> "未检测"
                    },
                    color = T.TextFaint,
                    fontSize = Fs.tiny,
                )
            }
            Spacer4()
            Row {
                // 「站点」= 在影枢里打开对方自己的页面。用户在这里登自己的账号，
                // 凭证只留在本机 WebView 的 cookie 里 —— 影枢不代收账号密码。
                Text(
                    "站点",
                    color = T.Src400,
                    fontSize = Fs.tiny,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(R.sm)
                        .border(1.dp, T.Src500.copy(alpha = .40f), R.sm)
                        .clickable(onClick = onOpenSite)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer8()
                Icon(
                    Icons.Filled.Refresh, null,
                    tint = T.TextFaint,
                    modifier = Modifier.size(14.dp).clickable(onClick = onUp),
                )
                Spacer8()
                Icon(
                    Icons.Filled.Delete, null,
                    tint = T.TextFaint,
                    modifier = Modifier.size(14.dp).clickable(onClick = onDelete),
                )
            }
        }
    }
}

/** 添加播放源：底部弹层，带实时连通性反馈。 */
@Composable
private fun AddSourceSheet(
    onDismiss: () -> Unit,
    onTest: (String, String, SourceKind, String?, String?, (Boolean, String, SourceConfig?) -> Unit) -> Unit,
    onSave: (SourceConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var playPath by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SourceKind.Html) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var tested by remember { mutableStateOf<SourceConfig?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 任何字段改动都让上一次的探测结果失效，否则会把旧配置存下来
    fun invalidate() {
        tested = null
        result = null
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .68f))
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // 表单比屏幕高时（小屏 / 横屏）按钮会被裁掉，所以整块可滚，最多占四分之三屏
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .clip(R.sheet)
                .background(T.Surface)
                .border(1.dp, T.BorderStrong, R.sheet)
                .padding(horizontal = Sp.s5, vertical = Sp.s3),
        ) {
            Box(
                Modifier.size(38.dp, 4.dp).clip(R.full)
                    .background(Color.White.copy(alpha = .24f))
                    .align(Alignment.CenterHorizontally),
            )
            Spacer16()
            Text("添加播放源", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
            Spacer4()
            Text(
                "填入站点地址，影枢会自动识别站点类型、抓取资源并生成统一播放列表。",
                color = T.TextFaint,
                fontSize = Fs.xs,
                lineHeight = Fs.xs * 1.6f,
            )
            Spacer16()

            Field("名称", "用于界面展示") {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(R.md)
                        .background(T.Surface2).border(1.dp, T.Border, R.md)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("例如：我的源", color = T.TextFaint, fontSize = Fs.body2)
                    BasicTextField(
                        name, { name = it; invalidate() },
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.body2),
                        cursorBrush = SolidColor(T.Src500),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Field("站点地址", "支持 http / https") {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(R.md)
                        .background(T.Surface2)
                        .border(1.dp, T.Src500.copy(alpha = .50f), R.md)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (url.isEmpty()) Text("https://", color = T.TextFaint, fontSize = Fs.body2)
                    BasicTextField(
                        url, { url = it; invalidate() },
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.body2),
                        cursorBrush = SolidColor(T.Src500),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Field("接入方式", "不确定就选网页解析") {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                        KindTile("网页解析", "抓取页面", SourceKind.Html, kind, Modifier.weight(1f)) { kind = it; invalidate() }
                        KindTile("JSON 接口", "标准 API", SourceKind.JsonApi, kind, Modifier.weight(1f)) { kind = it; invalidate() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                        KindTile("M3U 列表", "直链列表", SourceKind.M3u, kind, Modifier.weight(1f)) { kind = it; invalidate() }
                        KindTile("签名接口", "需签名的站", SourceKind.AiMovie, kind, Modifier.weight(1f)) { kind = it; invalidate() }
                    }
                }
            }

            Field("站点凭证", "选填 · 需要登录的源填你自己的 Cookie") {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(R.md)
                        .background(T.Surface2).border(1.dp, T.Border, R.md)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (token.isEmpty()) Text("不填则匿名访问", color = T.TextFaint, fontSize = Fs.body2)
                    BasicTextField(
                        token, { token = it; invalidate() },
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.body2),
                        cursorBrush = SolidColor(T.Src500),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Field("播放页路径", "选填 · 接口只给爱奇艺/腾讯播放页时才需要") {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(R.md)
                        .background(T.Surface2).border(1.dp, T.Border, R.md)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (playPath.isEmpty()) Text(
                        "如 /index.php/vod/play/id/{id}/sid/{sid}/nid/{nid}.html",
                        color = T.TextFaint, fontSize = Fs.body2,
                    )
                    BasicTextField(
                        playPath, { playPath = it; invalidate() },
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.body2),
                        cursorBrush = SolidColor(T.Src500),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            result?.let { (ok, msg) ->
                Row(
                    Modifier.fillMaxWidth().clip(R.md)
                        .background(
                            if (ok) T.Ok.copy(alpha = .07f) else T.Err.copy(alpha = .07f)
                        )
                        .border(
                            1.dp,
                            if (ok) T.Ok.copy(alpha = .26f) else T.Err.copy(alpha = .26f),
                            R.md,
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (ok) Icons.Filled.Check else Icons.Filled.Delete,
                        null,
                        tint = if (ok) T.Ok else T.Err,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer12()
                    Column {
                        Text(
                            if (ok) "连接成功" else "连接失败",
                            color = if (ok) T.Ok else T.Err,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(msg, color = T.TextDim, fontSize = Fs.xxs)
                    }
                }
                Spacer12()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(R.md).background(T.Surface2)
                        .border(1.dp, T.Border, R.md)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", color = T.TextDim, fontSize = Fs.body, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier.weight(1.6f).height(46.dp).clip(R.md)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (busy) T.Surface4 else T.Play400,
                                    if (busy) T.Surface4 else T.Play500,
                                )
                            )
                        )
                        .clickable(enabled = !busy && url.isNotBlank()) {
                            val ready = tested
                            if (ready != null) {
                                // 已经探测通过：这一步只负责落库
                                onSave(ready)
                                return@clickable
                            }
                            busy = true
                            result = null
                            onTest(name, url, kind, token, playPath) { ok, msg, cfg ->
                                busy = false
                                result = ok to msg
                                tested = cfg
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            busy -> "正在测试连通性…"
                            tested != null -> "保存并启用"
                            else -> "测试并保存"
                        },
                        color = Color.White,
                        fontSize = Fs.body,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer16()
        }
    }
}

@Composable
private fun Field(label: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Sp.s3)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = T.TextDim, fontSize = Fs.xs, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(hint, color = T.TextFaint, fontSize = Fs.xxs)
        }
        Spacer8()
        content()
    }
}

@Composable
private fun KindTile(
    title: String,
    sub: String,
    value: SourceKind,
    current: SourceKind,
    modifier: Modifier = Modifier,
    onPick: (SourceKind) -> Unit,
) {
    val on = value == current
    Column(
        modifier.height(62.dp).clip(R.md)
            .background(if (on) T.Play500.copy(alpha = .10f) else T.Surface2)
            .border(1.dp, if (on) T.Play500.copy(alpha = .46f) else T.Border, R.md)
            .clickable { onPick(value) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            color = if (on) T.Play400 else T.Text,
            fontSize = Fs.xs,
            fontWeight = FontWeight.Bold,
        )
        Spacer4()
        Text(sub, color = T.TextFaint, fontSize = Fs.tiny)
    }
}
