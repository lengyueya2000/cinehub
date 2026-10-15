package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.DoubanChart
import net.cinehub.data.model.DoubanItem
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.DownloadTask
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.components.ConfirmDialog
import net.cinehub.ui.components.DoubanMissBanner
import net.cinehub.ui.components.EmptyState
import net.cinehub.ui.components.GroupCard
import net.cinehub.ui.components.HeaderAction
import net.cinehub.ui.components.NumText
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.SectionLabel
import net.cinehub.ui.components.SubHeader
import net.cinehub.ui.components.TextAction
import net.cinehub.ui.components.UnreadableNotice
import net.cinehub.ui.theme.T

/**
 * 片库 = 豆瓣榜单。
 *
 * 原来这一屏铺的是「启用源的最新内容」，和首页那份同源，等于把首页重抄一遍。
 * 换成豆瓣之后这一页才有它自己的价值：先在豆瓣挑片，点一条由影枢拿片名去
 * 接的源里找 —— 找到就进详情页，找不到就明说找不到，并给一条去豆瓣的路。
 *
 * 首页现在也是豆瓣的（固定「剧集 · 热门」那一张），两边的分工由此定下来：
 * 首页只摆那一张榜，这一页是换榜的落脚处 —— 电影 11 张、剧集 7 张都在这儿切。
 * 两边的状态各走各的（首页那份在 [AppViewModel] 里另立一路），切榜不会把首页换了脸。
 *
 * 豆瓣只读公开榜单接口，不登录、不带凭证；它哪天收紧了，这里会如实写原因。
 */
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
    onOpenDoubanPage: (String) -> Unit,
) {
    val chart by vm.doubanChart.collectAsState()
    val list by vm.doubanItems.collectAsState()
    val loading by vm.doubanLoading.collectAsState()
    val error by vm.doubanError.collectAsState()
    val at by vm.doubanAt.collectAsState()
    val resolving by vm.doubanResolving.collectAsState()
    val sources by vm.sources.collectAsState()

    var kindMovie by remember { mutableStateOf(chart.kind == "movie") }
    var miss by remember { mutableStateOf<Pair<DoubanItem, String>?>(null) }
    LaunchedEffect(chart) { kindMovie = chart.kind == "movie" }
    LaunchedEffect(Unit) { vm.ensureDouban() }

    /**
     * 这一页还在不在。
     *
     * 拿片名去源里搜要一两秒，回来时用户可能已经切到别的 Tab 了 —— 那种「迟到的命中」
     * 照原样去改路由，就是把人从片库抛进一个他根本没点的详情页。
     */
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }

    val enabledSources = sources.count { it.enabled }
    val charts = if (kindMovie) DoubanChart.MOVIE else DoubanChart.TV

    Box(Modifier.fillMaxSize().background(T.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s3)) {
                Column {
                    Text("榜单 · 豆瓣", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
                    Spacer4()
                    Text(
                        when {
                            at == 0L && loading -> "正在向豆瓣取榜单…"
                            at == 0L -> "来自豆瓣的公开榜单 · 点一条，影枢拿片名去源里找"
                            else -> "豆瓣公开榜单 · 取回于 " + at.ofTime()
                        },
                        color = T.TextFaint,
                        fontSize = Fs.xxs,
                    )
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).size(30.dp).clip(CircleShape)
                        .clickable(enabled = !loading) { vm.refreshDouban() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        if (loading) "刷新中" else "刷新",
                        tint = if (loading) T.TextFaint else T.TextDim,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Sp.s4),
                horizontalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                Chip("电影", kindMovie) { kindMovie = true; vm.selectDoubanChart(DoubanChart.MOVIE.first()) }
                Chip("剧集", !kindMovie) { kindMovie = false; vm.selectDoubanChart(DoubanChart.TV.first()) }
            }
            Spacer8()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Sp.s4),
                horizontalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                charts.forEach { c ->
                    Chip(c.name, c.id == chart.id) {
                        miss = null
                        vm.selectDoubanChart(c)
                    }
                }
            }

            // 列表还在的时候，失败是一条附注而不是把整屏换掉 ——
            // 一次抖动不该让用户以为片库空了。
            if (error != null && list.isNotEmpty()) {
                Text(
                    "最新一次没刷新成功：" + error!!,
                    color = T.Warn,
                    fontSize = Fs.xxs,
                    modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s2),
                )
            }

            when {
                list.isEmpty() && loading -> CenterNote("正在向豆瓣取「${chart.name}」…")
                list.isEmpty() && error != null -> CenterNote(error!!)
                list.isEmpty() -> CenterNote("这个榜豆瓣没回内容")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(horizontal = Sp.s4),
                    contentPadding = PaddingValues(top = Sp.s2, bottom = Sp.s6),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(Sp.s4),
                ) {
                    items(list, key = { "${it.id}-${it.rank}" }) { d ->
                        PosterCard(
                            title = d.title,
                            subtitle = d.displayLine,
                            cover = d.cover,
                            rank = d.rank.takeIf { n -> n > 0 },
                            badge = if (resolving == d.id) "找片中" else null,
                            onLongClick = { onOpenDoubanPage(d.url) },
                            onClick = {
                                vm.openFromDouban(
                                    d,
                                    onHit = { m -> if (mounted) onOpenDetail(m.sourceId, m.id) },
                                    onMiss = { it2, why -> if (mounted) miss = it2 to why },
                                )
                            },
                        )
                    }
                }
            }
        }

        // 豆瓣有这部、手上的源对不上 —— 如实说完对不上的到底是哪一种，再把去豆瓣的路递过去。
        // 这一条首页也在用，实现挪到了 components/Chrome.kt。
        miss?.let { (m, why) ->
            DoubanMissBanner(
                item = m,
                why = why,
                enabledSources = enabledSources,
                onOpenDoubanPage = onOpenDoubanPage,
                onDismiss = { miss = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(
        Modifier.fillMaxWidth().fillMaxHeight().padding(Sp.s6),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = T.TextDim, fontSize = Fs.sm) }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Color.White else T.TextDim,
        fontSize = Fs.xs,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clip(R.full)
            .then(
                if (active) Modifier.background(Brush.linearGradient(listOf(T.Play400, T.Play500)))
                else Modifier.background(T.Surface)
            )
            .border(1.dp, if (active) Color.Transparent else T.Border, R.full)
            .clickable(onClick = onClick)
            .padding(horizontal = Sp.s3, vertical = 7.dp),
    )
}

/**
 * 缓存那一刻的时间。
 *
 * 原来只有 HH:mm：昨天 14:03 存的那一集和上周 14:03 存的那一集写着同一句话，
 * 而这一列就是拿来回答「这是我什么时候存的」。今天以内给时分，跨天补上日期。
 */
private fun Long.ofTime(): String {
    val now = System.currentTimeMillis()
    val sameDay = java.util.Calendar.getInstance().apply { timeInMillis = now }
        .get(java.util.Calendar.DAY_OF_YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = this@ofTime }
            .get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay || now - this < 86_400_000L) "HH:mm" else "M月d日 HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(java.util.Date(this))
}

/**
 * 下载：本机已缓存的任务。
 *
 * 文件落在应用专属目录，卸载即清；这里只列真实存在的任务，
 * 失败原因照抄解析/下载那一步给出的话，不粉饰成「请稍后重试」。
 */
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val tasks by vm.downloads.collectAsState()
    val space by vm.downloadSpace.collectAsState()
    val speeds by vm.downloadSpeed.collectAsState()
    val tasksUnreadable by vm.downloadsUnreadable.collectAsState()
    // 删一条 = 连文件一起没，重下要重新解析直链 —— 这一刀得先问一句
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var confirmClean by remember { mutableStateOf(false) }

    val done = tasks.filter { it.status == DownloadStatus.Done }
    val failed = tasks.filter { it.status == DownloadStatus.Failed }
    val active = tasks.filter {
        it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued ||
            it.status == DownloadStatus.Paused
    }
    val newestFirst: List<DownloadTask>.() -> List<DownloadTask> = { sortedByDescending { it.createdAt } }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        SubHeader(
            title = "下载",
            sub = when {
                tasks.isEmpty() -> "还没有缓存任何内容"
                done.isEmpty() -> "${active.size + failed.size} 个任务 · 已占 ${fmtBytes(space)}"
                else -> "${done.size} 集可离线 · 共 ${fmtBytes(space)}"
            },
            action = if (done.isNotEmpty()) {
                { HeaderAction("清理已缓存", danger = true) { confirmClean = true } }
            } else {
                null
            },
        )

        if (tasksUnreadable) UnreadableNotice("缓存任务清单")

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "没有离线内容",
                    body = "在详情页点「缓存」，或在播放器里点「缓存」，就能把这一集存到本机；" +
                        "存下来之后不联网也能播。\n文件放在应用自己的目录里，卸载应用就会一并清掉。",
                )
            }
            return@Column
        }

        // 分三段而不是按时间混排：「还要等多久」和「已经能看了」是两种完全不同的来意，
        // 混在一起时用户得逐条读状态才知道有没有能播的。
        LazyColumn(contentPadding = PaddingValues(bottom = Sp.s7)) {
            if (active.isNotEmpty()) {
                item { SectionLabel("进行中") }
                items(active.newestFirst(), key = { it.id }) { t ->
                    DownloadCard(
                        task = t, speed = speeds[t.id] ?: 0L,
                        onPlay = { vm.playCached(t) },
                        onPause = { vm.pauseDownload(t.id) },
                        onResume = { vm.resumeDownload(t.id) },
                        onDelete = { pendingDelete = t },
                    )
                }
            }
            if (done.isNotEmpty()) {
                item { SectionLabel("已缓存 · 不联网也能播") }
                items(done.newestFirst(), key = { it.id }) { t ->
                    DownloadCard(
                        task = t, speed = 0L,
                        onPlay = { vm.playCached(t) },
                        onPause = {}, onResume = {},
                        onDelete = { pendingDelete = t },
                    )
                }
            }
            if (failed.isNotEmpty()) {
                item { SectionLabel("失败") }
                items(failed.newestFirst(), key = { it.id }) { t ->
                    DownloadCard(
                        task = t, speed = 0L,
                        onPlay = {}, onPause = {},
                        onResume = { vm.resumeDownload(t.id) },
                        onDelete = { pendingDelete = t },
                    )
                }
            }
        }
    }

    pendingDelete?.let { t ->
        ConfirmDialog(
            title = "删除「${t.mediaTitle} · ${t.episodeName}」？",
            body = when (t.status) {
                DownloadStatus.Done -> "已缓存的文件会一起删掉（${fmtBytes(t.bytesTotal)}），" +
                    "要再看就得重新缓存一遍。"
                DownloadStatus.Running, DownloadStatus.Queued, DownloadStatus.Paused ->
                    "已经下好的 ${fmtBytes(t.bytesDone)} 会一起没掉，重新缓存得从头再取。"
                else -> "这条记录和它已下载的部分都会删掉。"
            },
            confirm = "删除",
            onConfirm = { vm.deleteDownload(t.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmClean) {
        ConfirmDialog(
            title = "清理 ${done.size} 集已缓存？",
            body = "只删已经下完的，进行中的任务不动。清完这一批要再看就得重新缓存。",
            confirm = "全部清理",
            onConfirm = {
                done.forEach { vm.deleteDownload(it.id) }
                confirmClean = false
            },
            onDismiss = { confirmClean = false },
        )
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    speed: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = task.status == DownloadStatus.Running || task.status == DownloadStatus.Queued
    val failed = task.status == DownloadStatus.Failed
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s1)
            .clip(R.lg).background(T.Surface2)
            .border(1.dp, if (failed) T.Err.copy(alpha = .26f) else T.Border, R.lg)
            .padding(Sp.s3),
    ) {
        Row {
            // 封面是网络图：离线或源站撤图时加载不出来，留灰底占位，
            // 不拿空白冒充「这一条没有图」
            Box(
                Modifier.width(40.dp).height(56.dp).clip(R.sm).background(T.Surface3),
            ) {
                if (!task.mediaCover.isNullOrBlank()) {
                    AsyncImage(
                        model = task.mediaCover, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer8()
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${task.mediaTitle} · ${task.episodeName}",
                        color = T.Text,
                        fontSize = Fs.body2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer8()
                    NumText(
                        when (task.status) {
                            DownloadStatus.Done -> "可离线播放"
                            DownloadStatus.Failed -> "失败"
                            DownloadStatus.Paused -> "已暂停"
                            DownloadStatus.Queued -> "排队中"
                            // 百分比在下面那行报，这里只说状态，不重复一个数
                            DownloadStatus.Running -> "下载中"
                        },
                        color = when (task.status) {
                            DownloadStatus.Done -> T.Ok
                            DownloadStatus.Failed -> T.Err
                            else -> T.Warn
                        },
                        fontSize = Fs.xxs,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer4()
                Text(
                    when {
                        task.status != DownloadStatus.Done && task.status != DownloadStatus.Failed ->
                            "${task.sourceName} · ${downloadStatsLine(task, speed)}"
                        task.status == DownloadStatus.Done ->
                            "${task.sourceName} · 实占 ${fmtBytes(task.bytesTotal)}"
                        else -> task.sourceName
                    },
                    color = T.TextDim,
                    fontSize = Fs.xxs,
                )
                if (task.status != DownloadStatus.Done && !failed) {
                    Spacer4()
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(R.full)
                            .background(Color.White.copy(alpha = .12f)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(task.progress).fillMaxHeight().clip(R.full)
                                .background(Brush.horizontalGradient(listOf(T.Play400, T.Play500))),
                        )
                    }
                }
                // 失败原因整句留着：它决定用户是「换一条线路」还是「换个源」
                task.error?.let {
                    Spacer4()
                    Text(
                        it,
                        color = T.Err,
                        fontSize = Fs.xxs,
                        lineHeight = Fs.xxs * 1.6f,
                    )
                }
            }
        }
        Spacer4()
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (task.status == DownloadStatus.Done) {
                TextAction("播放", color = T.Play400, filled = true, onClick = onPlay)
            }
            if (running) TextAction("暂停", onClick = onPause)
            if (task.status == DownloadStatus.Paused || failed) {
                TextAction(if (failed) "重试" else "继续", color = T.Play400, onClick = onResume)
            }
            TextAction("删除", color = T.Err, onClick = onDelete)
        }
    }
}

/**
 * 一行下载进度：百分比 + 字节 + 速度。
 *
 * 总大小估不出来时（HLS 第一个分片还没落地）直说，不拿 0% 冒充「刚开始下」；
 * 速度只在真有速度时出现 —— 暂停中的任务报 0 B/s 会被读成「网卡了」。
 */
private fun downloadStatsLine(task: DownloadTask, speed: Long): String {
    val parts = mutableListOf("${(task.progress * 100).toInt()}%")
    parts += when {
        task.bytesTotal > 0 ->
            "${fmtBytes(task.bytesDone)} / ${if (task.sizeEstimated) "约 " else ""}${fmtBytes(task.bytesTotal)}"
        task.bytesDone > 0 -> "已下 ${fmtBytes(task.bytesDone)}，总大小还估不出来"
        task.status == DownloadStatus.Queued -> "排队中，还没开始取"
        else -> "正在取第一段，还不知道总共多大"
    }
    if (speed > 0) parts += "${fmtBytes(speed)}/s"
    return parts.joinToString(" · ")
}

private fun fmtBytes(b: Long): String {
    val tenthsGb = b * 10 / (1L shl 30)
    val tenthsMb = b * 10 / (1L shl 20)
    return when {
        b >= 1L shl 30 -> "${tenthsGb / 10}.${tenthsGb % 10} GB"
        b >= 1L shl 20 -> "${tenthsMb / 10}.${tenthsMb % 10} MB"
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}

/**
 * 下载设置。
 *
 * 并发数单独放一页而不是塞在下载页顶部：它说的是「接下来怎么下」，
 * 和某一条任务的操作不是一回事，混在一起会看不清改了它到底影响谁。
 *
 * 之前这一页是三段散文配四个圆片，读起来像说明书。改成分组列表后
 * 每个设置一行：标题说要调什么，helper 说代价，选择器就在下面。
 */
@Composable
fun DownloadSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val parallel by vm.segParallel.collectAsState()
    val space by vm.downloadSpace.collectAsState()
    val tasks by vm.downloads.collectAsState()
    val inFlight = tasks.count { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        SubHeader("下载设置", "只影响下一个开始的任务", onBack)

        SectionLabel("速度")
        GroupCard {
            Column(Modifier.padding(Sp.s4)) {
                Text("同时取几个分片", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    "缓存一集时会同时开几条连接去取它切片。越多越快，" +
                        "但片源的 CDN 也可能把高频请求当成爬虫拦掉。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
                Spacer12()
                // 这个数字得说全：一档是「每一集」各开这么多，不是全局共用。
                // 用户按 8 路想的是「总共 8 条连接」，实际会看到 24 条 —— 说错方向
                // 就等于把这页劝退的话（「容易被拦」）指到了不存在的地方。
                Segmented(
                    options = listOf(1, 2, 4, 8),
                    selected = parallel,
                    label = { "$it 路" },
                    onSelect = { vm.setSegParallel(it) },
                )
                Spacer8()
                Text(
                    "最多同时缓存 3 集，所以这一档是每集各开：调到 8 路时总共会有 24 条连接在飞。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
            }
        }

        SectionLabel("本机内容")
        GroupCard {
            Row(
                Modifier.fillMaxWidth().padding(Sp.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("已占用", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${tasks.size} 个任务 · 文件在应用自己的目录里，卸载应用会一并清掉",
                        color = T.TextDim,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.6f,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer8()
                NumText(fmtBytes(space), color = T.TextDim, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            when {
                inFlight > 0 -> "现在正有 $inFlight 个任务在下：改动不会作用到它们身上，" +
                    "要等这条结束后新开始的才会按 $parallel 路取。"
                else -> "改动从下一个开始的任务生效，正在下的不受影响。"
            } + if (parallel == 8) " 8 路是最快的一档，也最容易被片源拒绝。" else "",
            color = T.TextDim,
            fontSize = Fs.xs,
            lineHeight = Fs.xs * 1.7f,
            modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s4),
        )
    }
}

/** 一排互斥选项。整块高度 44dp，不是只有那行字能点。 */
@Composable
private fun Segmented(
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(R.md)
            .background(Color.White.copy(alpha = .05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { n ->
            val on = n == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .clip(R.sm)
                    .background(
                        if (on) Brush.verticalGradient(listOf(T.Play400, T.Play500))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onSelect(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(n),
                    color = if (on) Color.White else T.TextDim,
                    fontSize = Fs.xs,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
