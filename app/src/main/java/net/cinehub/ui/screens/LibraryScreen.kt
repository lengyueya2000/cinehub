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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import net.cinehub.ui.theme.T

/**
 * 片库 = 豆瓣榜单。
 *
 * 原来这一屏铺的是「启用源的最新内容」，和首页那份同源，等于把首页重抄一遍。
 * 换成豆瓣之后这一页才有它自己的价值：先在豆瓣挑片，点一条由影枢拿片名去
 * 接的源里找 —— 找到就进详情页，找不到就明说找不到，并给一条去豆瓣的路。
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

    var kindMovie by remember { mutableStateOf(true) }
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
                            subtitle = doubanLine(d),
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
        miss?.let { (m, why) ->
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(Sp.s4).clip(R.lg).background(T.Surface2).border(1.dp, T.Border, R.lg)
                    .padding(Sp.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "《${m.title}》${m.year?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""} 豆瓣有，$why",
                        color = T.Text,
                        fontSize = Fs.sm,
                    )
                    Text(
                        "在 $enabledSources 个启用的源里搜过 —— 可能是源里真没有，也可能只是片名写法不一样",
                        color = T.TextFaint,
                        fontSize = Fs.xxs,
                    )
                }
                Spacer8()
                Row(
                    Modifier.height(30.dp).clip(R.full).background(T.Surface).border(1.dp, T.Border, R.full)
                        .clickable { onOpenDoubanPage(m.url) }.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("去豆瓣看", color = T.Text, fontSize = Fs.xs) }
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable { miss = null },
                    contentAlignment = Alignment.Center,
                ) { Text("×", color = T.TextDim, fontSize = Fs.body) }
            }
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

/** 卡片副标题：先给豆瓣评分，没有分再给类型 · 地区。两个都空就留空。 */
private fun doubanLine(d: DoubanItem): String? =
    (if (d.rate.isNullOrBlank()) d.whereFrom else "豆瓣 ${d.rate}").takeIf { it.isNotBlank() }

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

private fun Long.ofTime(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(this))

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

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s3)) {
            Column {
                Text("下载", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    if (tasks.isEmpty()) "还没有缓存任何内容"
                    else "${tasks.count { it.status == DownloadStatus.Done }} / ${tasks.size} 完成 · 共 ${fmtBytes(space)}",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                )
            }
        }

        if (tasks.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(Sp.s6),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("没有离线内容", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                    Spacer8()
                    Text(
                        "在详情页点「缓存」，或在播放器里点「缓存」，就能把这一集存到本机；" +
                            "存下来之后不联网也能播。\n" +
                            "文件放在应用自己的目录里，卸载应用就会一并清掉。",
                        color = T.TextDim,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.7f,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = Sp.s4),
                verticalArrangement = Arrangement.spacedBy(Sp.s3),
                contentPadding = PaddingValues(bottom = Sp.s6),
            ) {
                items(tasks.sortedByDescending { it.createdAt }) { t ->
                    DownloadCard(
                        task = t,
                        onPlay = { vm.playCached(t) },
                        onPause = { vm.pauseDownload(t.id) },
                        onResume = { vm.resumeDownload(t.id) },
                        onDelete = { vm.deleteDownload(t.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = task.status == DownloadStatus.Running || task.status == DownloadStatus.Queued
    Column(
        Modifier.fillMaxWidth().clip(R.md).background(T.Surface2)
            .border(1.dp, T.Border, R.md).padding(Sp.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${task.mediaTitle} · ${task.episodeName}",
                color = T.Text,
                fontSize = Fs.sm,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer8()
            Text(
                when (task.status) {
                    DownloadStatus.Done -> "可离线播放"
                    DownloadStatus.Failed -> "失败"
                    DownloadStatus.Paused -> "已暂停"
                    DownloadStatus.Queued -> "排队中"
                    DownloadStatus.Running -> "${(task.progress * 100).toInt()}%"
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
            task.sourceName,
            color = T.TextFaint,
            fontSize = Fs.tiny,
        )

        if (task.status != DownloadStatus.Done && task.status != DownloadStatus.Failed) {
            Spacer8()
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(R.full)
                    .background(Color.White.copy(alpha = .12f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(task.progress).fillMaxHeight().clip(R.full)
                        .background(Brush.horizontalGradient(listOf(T.Play400, T.Play500))),
                )
            }
            if (task.segmentsTotal > 0) {
                Spacer4()
                Text(
                    "已取 ${task.segmentsDone} / ${task.segmentsTotal} 个分片",
                    color = T.TextFaint,
                    fontSize = Fs.tiny,
                )
            }
        }

        task.error?.let {
            Spacer8()
            Text(
                it,
                color = T.Err,
                fontSize = Fs.xxs,
                lineHeight = Fs.xxs * 1.6f,
            )
        }

        Spacer8()
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
            if (task.status == DownloadStatus.Done) {
                DownloadAction("播放", T.Play400, onPlay)
            }
            if (running) DownloadAction("暂停", T.TextDim, onPause)
            if (task.status == DownloadStatus.Paused || task.status == DownloadStatus.Failed) {
                DownloadAction("继续", T.Play400, onResume)
            }
            Box(Modifier.weight(1f))
            DownloadAction("删除", T.TextFaint, onDelete)
        }
    }
}

@Composable
private fun DownloadAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = Fs.xs,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(R.sm)
            .border(1.dp, color.copy(alpha = .35f), R.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
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
