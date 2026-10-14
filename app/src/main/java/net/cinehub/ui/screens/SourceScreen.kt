package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.ConfirmDialog
import net.cinehub.ui.components.NumText
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer16
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.components.TextAction
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
fun SourceScreen(vm: AppViewModel, onBack: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()
    val checking by vm.checking.collectAsState()
    val unreadable by vm.sourcesUnreadable.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    // 删源要过一道确认：凭证是用户自己填的，误删一次就得重新找、重新填
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }

    // 界面上的顺序 = 优先级顺序。落盘那份不保证有序，所以这里自己排一次。
    // 必须是 derivedStateOf 而不是 remember(sources) 取到的那份 List：拖拽手势的回调块只在
    // key 不变时启动一次，抓住一份 List 就永远是那一份 —— 上一次拖完、或中途加了新源之后，
    // 旧列表既会把顺序写回拖之前，也会把不在它里面的那个新源整条删掉（reorder 只写回列表里的 id）。
    val order by remember {
        derivedStateOf { sources.sortedBy { it.priority }.map { it.id } }
    }
    val byId by remember { derivedStateOf { sources.associateBy { it.id } } }

    // 拖拽排序的临时状态：material3 这一版还没有可用的 reorderable 列表，只能自己接手势。
    // dragTo 只是「松手会落到第几位」，真正的顺序要等 vm.reorder 落库、sources 重新发一次才算数。
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var dragTo by remember { mutableStateOf(-1) }
    // 行在哪、多高，一律从 listState 的当前布局里现读，不自建坐标表：
    // LazyColumn 会回收滑出屏幕的行，自己记的那份 HashMap 既不清理也不更新，
    // 滚到底再往回拖就会拿陈旧坐标算落点，把顺序落到完全不相干的位次上。
    val listState = rememberLazyListState()

    val ok = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Ok }
    val slow = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Slow }
    val down = sources.count { (health[it.id]?.health ?: Health.Unknown) == Health.Down }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
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
                        "长按行首的拖动手柄换位置，也可以点某一行的「上移」· 越靠上越优先",
                        color = T.TextDim,
                        fontSize = Fs.xxs,
                        lineHeight = Fs.xxs * 1.6f,
                    )
                    // 落盘那份读不通时不能装作一切正常：现在屏上这几条是内置源，
                    // 用户自己加的那几条还在盘上的存档里，一次保存就会把显示中的这份写回去。
                    if (unreadable) {
                        Spacer8()
                        Text(
                            "注意：本机存的源列表这一版读不通（多半是刚换过格式）。现在显示的是内置源，" +
                                "原来那份已另存了一份没动 —— 别急着改，先试升级到能读的版本。",
                            color = T.Warn,
                            fontSize = Fs.xxs,
                            lineHeight = Fs.xxs * 1.6f,
                        )
                    }
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

            items(count = order.size, key = { order[it] }) { i ->
                val id = order[i]
                val cfg = byId[id]
                if (cfg != null) {
                    val drag = dragId
                    val dragged = drag == id
                    val from = if (drag != null) order.indexOf(drag) else -1
                    // 让位的步长取被抬起那一行的实际高度（这一列表每行都是同一个组件，高度一致）
                    val step = if (drag != null) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == drag }?.size?.toFloat() ?: 0f
                    } else 0f
                    val ty = when {
                        dragged -> dragDy
                        from >= 0 && dragTo > from && i in (from + 1)..dragTo -> -step
                        from >= 0 && dragTo in 0 until from && i in dragTo until from -> step
                        else -> 0f
                    }
                    // zIndex 必须挂在 item 的根节点上：LazyColumn 只把每个 item 的根当兄弟看待，
                    // 挂到里面那层「只有一个孩子的 Column」上等于没挂 —— 向下拖时被抬起那行
                    // 会钻到它下面那几行（不透明底）后面，视觉上直接消失。
                    Column(
                        Modifier.fillMaxWidth()
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer { translationY = ty },
                    ) {
                        SourceRow(
                            cfg = cfg,
                            report = health[cfg.id],
                            dragging = dragged,
                            grip = Modifier.pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = id
                                        dragDy = 0f
                                        dragTo = order.indexOf(id)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragDy += amount.y
                                        dragTo = targetIndexFor(dragId, dragDy, order, listState)
                                    },
                                    onDragEnd = {
                                        val d = dragId
                                        val t = dragTo
                                        dragId = null; dragDy = 0f; dragTo = -1
                                        // order 读定一次：守卫和搬移之间它要是又变了，
                                        // indexOf 的结果就和 add/removeAt 对不上
                                        val cur = order
                                        val f = if (d != null) cur.indexOf(d) else -1
                                        // 两个守卫都得有。拖动途中另一根手指删了源、或者刚保存了新源，
                                        // 列表长度和 f / t 都可能不再成立 —— 少了守卫就是 removeAt(-1) 当场崩。
                                        if (f >= 0 && cur.isNotEmpty() && t in 0..cur.lastIndex && f != t) {
                                            val moved = cur.toMutableList()
                                            moved.add(t, moved.removeAt(f))
                                            vm.reorder(moved)
                                        }
                                    },
                                    onDragCancel = {
                                        dragId = null; dragDy = 0f; dragTo = -1
                                    },
                                )
                            },
                            onToggle = { vm.toggleSource(cfg.id, it) },
                            onDelete = { pendingDelete = cfg },
                            onOpenSite = { vm.openSiteBrowser(cfg.baseUrl, cfg.name) },
                            onUp = if (i == 0) null else {
                                {
                                    // 用 order 而不是 sources：界面按优先级排过，落盘那份不保证有序，
                                    // 拿未排的那份换位次会交换到看不见的两行上
                                    val ids = order.toMutableList()
                                    val tmp = ids[i - 1]; ids[i - 1] = ids[i]; ids[i] = tmp
                                    vm.reorder(ids)
                                }
                            },
                        )
                        Spacer8()
                    }
                }
            }

            item {
                Spacer16()
                Text(
                    "关于「重渲染」：影枢不套用源站页面，而是把抓取到的内容归一化成自己的数据模型，" +
                        "由本机播放器渲染画面。源的直链如果需要你本人的站点账号，" +
                        "请在添加源时填入你自己的凭证，影枢带着它去请求。",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                    lineHeight = Fs.xxs * 1.7f,
                )
            }
        }

        pendingDelete?.let { victim ->
            ConfirmDialog(
                title = "删除「${victim.name}」？",
                body = "这个源的地址、接入方式和你自己填的凭证都会从本机抹掉，"
                    + "用它的观看记录还在，但要再播得重新添加回来。",
                confirm = "删除",
                onConfirm = { vm.deleteSource(victim.id); pendingDelete = null },
                onDismiss = { pendingDelete = null },
            )
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

/**
 * 手指现在压到第几位。
 *
 * 坐标一律从 [LazyListState] 的当前布局现读，不自攒一份表：滑出屏幕的行会被 LazyColumn 回收，
 * 自己记过的那份既不清理也不更新，滚到底再往回拖就会拿陈旧坐标把顺序落到不相干的位次上。
 *
 * 判据是「被抬起那一行的中心」越过了哪一行的中心 —— 越过第一条就把那一条的位次当作落点。
 */
private fun targetIndexFor(
    dragId: String?,
    dragDy: Float,
    order: List<String>,
    listState: LazyListState,
): Int {
    if (dragId == null || order.isEmpty()) return -1
    val visible = listState.layoutInfo.visibleItemsInfo
    val self = visible.firstOrNull { it.key == dragId } ?: return order.indexOf(dragId)
    val center = self.offset + self.size / 2f + dragDy
    for (vi in visible) {
        val pos = (vi.key as? String)?.let { order.indexOf(it) } ?: -1
        if (pos < 0 || vi.key == dragId) continue
        if (vi.offset + vi.size / 2f > center) return pos
    }
    // 已经压过所有可见行的中心：落到这一批里最后一个源的位次。
    // 这一版不做拖到边缘自动滚动，拖不出屏幕就是拖不出这一批 —— 宁可少排一位，也不要凭陈旧的
    // 屏外坐标猜一个落点。
    return visible.lastOrNull { (it.key as? String)?.let { k -> order.indexOf(k) }?.let { p -> p >= 0 } == true }
        ?.let { order.indexOf(it.key as String) }
        ?: order.indexOf(dragId)
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
    dragging: Boolean,
    grip: Modifier,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenSite: () -> Unit,
    onUp: (() -> Unit)?,
) {
    val h = report?.health ?: Health.Unknown
    val border = when {
        dragging -> T.Src500
        h == Health.Down -> T.Err.copy(alpha = .26f)
        h == Health.Ok -> T.Src500.copy(alpha = .28f)
        else -> T.Border
    }
    Column(
        Modifier.fillMaxWidth().clip(R.lg)
            .background(
                when {
                    dragging -> T.Src500.copy(alpha = .13f)
                    h == Health.Down -> T.Err.copy(alpha = .035f)
                    else -> T.Surface
                }
            )
            .border(1.dp, border, R.lg)
            .padding(end = Sp.s3),
    ) {
        // ---------- 第一行：这个源能不能用 ----------
        Row(
            Modifier.padding(top = Sp.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 拖拽手柄单独占一格，而且给满 44dp：整行都接手势会和右边的开关、删除抢事件，
            // 而「按住这里拖」也比「不知道哪能拖」清楚。
            Box(
                grip.size(44.dp).clip(R.sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DragIndicator,
                    "长按拖动排序",
                    tint = if (dragging) T.Src400 else T.TextFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
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
                    fontSize = Fs.xs,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.width(Sp.s2))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cfg.name,
                        color = if (cfg.enabled) T.Text else T.TextDim,
                        fontSize = Fs.body2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (cfg.priority == 1 && cfg.enabled) {
                        Spacer8()
                        Pill("默认", cyan = true)
                    }
                }
                Text(
                    cfg.baseUrl,
                    color = T.TextDim,
                    fontSize = Fs.tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(Sp.s2))
            // 状态：圆点 + 字。只给个颜色的点，色弱和强光下等于没说
            StatusDot(h, 6)
            Spacer4()
            NumText(
                when (h) {
                    Health.Ok -> "${report?.latencyMs ?: 0}ms"
                    Health.Slow -> "${report?.latencyMs ?: 0}ms 较慢"
                    Health.Down -> "不可用"
                    Health.Unknown -> "未检测"
                },
                color = when (h) {
                    Health.Ok -> T.Ok
                    Health.Slow -> T.Warn
                    Health.Down -> T.Err
                    Health.Unknown -> T.TextDim
                },
                fontSize = Fs.xs,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(Sp.s2))
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
        }
        // ---------- 第二行：接入方式 + 这一行的动作 ----------
        // 动作不做 14dp 的小图标：删源和「站点页取链」都是要点准的，撑到 44dp 才算可点
        Row(
            Modifier.padding(start = Sp.s1, top = Sp.s1, bottom = Sp.s1),
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
            cfg.tags.take(1).forEach {
                Spacer(Modifier.width(Sp.s1))
                Pill(it, cyan = true)
            }
            Box(Modifier.weight(1f))
            // 「站点」= 在影枢里打开对方自己的页面。用户在这里登自己的账号，
            // 凭证只留在本机 WebView 的 cookie 里 —— 影枢不代收账号密码。
            TextAction("站点", color = T.Src400, onClick = onOpenSite)
            // 上一档是「上移」而不是刷新图标 —— 同一个符号在一行里表示两件事，
            // 用户按下去之前猜不出来。第一行没有可上的位置，就不摆一个按了没反应的按钮。
            if (onUp != null) {
                TextAction("上移", onClick = onUp)
            }
            TextAction("删除", color = T.Err, onClick = onDelete)
        }
        // 不可用的原因整句留在这里，不再截成 18 个字：
        // 「拒绝访问」和「按地区放行」用户能做的决定不一样，后半句才是有用的那半句。
        if (h == Health.Down && report?.detail != null) {
            Text(
                report.detail,
                color = T.Err,
                fontSize = Fs.xxs,
                lineHeight = Fs.xxs * 1.6f,
                modifier = Modifier.padding(start = Sp.s1, bottom = Sp.s2),
            )
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

    // 探测结论只对「探测当时那套输入」成立。改过名字、地址、类型、凭证或播放页路径里的
    // 任何一项，上一次那份结果就作废 —— 不作废的话第二次点下去会把旧配置直接落库，
    // 「测试并保存」实际只测了第一次，而界面还写着「保存并启用」。
    LaunchedEffect(name, url, kind, token, playPath) {
        tested = null
        result = null
    }

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
