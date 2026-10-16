package net.cinehub.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.DownloadTask
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Health
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SearchOutcome
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.clock
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.ConfirmDialog
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onPlay: (sourceId: String, media: Media, ep: Episode) -> Unit,
) {
    val detail by vm.detail.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val error by vm.detailError.collectAsState()
    val siblings by vm.siblings.collectAsState()
    val siblingFailures by vm.siblingFailures.collectAsState()
    val activeSourceId by vm.activeSourceId.collectAsState()
    val health by vm.health.collectAsState()
    val checking by vm.checking.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val favorites by vm.favorites.collectAsState()

    var lineIdx by remember { mutableIntStateOf(0) }
    var epIdx by remember { mutableIntStateOf(0) }
    // 长按那颗下载键是「把这一条连文件一起删掉」—— 一记误触就没了，得先问一句。
    // （这里以前是就地删：和缓存页、收藏页的规矩也不一致，那两处都是过一道确认。）
    var confirmDrop by remember { mutableStateOf<DownloadTask?>(null) }

    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    Text("正在解析详情…", color = T.TextDim, fontSize = Fs.body2)
                } else {
                    Text(error ?: "没有数据", color = T.Err, fontSize = Fs.body2)
                    Spacer12()
                    Text(
                        "返回",
                        color = T.Src500,
                        fontSize = Fs.sm,
                        modifier = Modifier.clickable(onClick = onBack),
                    )
                }
            }
        }
        return
    }

    val lines: List<PlayLine> = d.lines
    val line = lines.getOrNull(lineIdx.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
    val eps = line?.episodes.orEmpty()
    val curEp = eps.getOrNull(epIdx.coerceIn(0, (eps.size - 1).coerceAtLeast(0)))
    val sid = activeSourceId ?: d.media.sourceId
    val fav = favorites.any { it.key == d.media.key }
    /** 这部片上次看到哪；只有正播到这一集时才提示续播。 */
    val seen = vm.historyOf(d.media.key)
    val resumeEp = seen?.takeIf { it.resumable }

    // 打开一部看过的片，选集默认落在上次那一集，而不是永远从第一集开始
    LaunchedEffect(d.media.id, eps.size) {
        val at = seen?.let { s -> eps.indexOfFirst { it.index == s.episodeIndex } } ?: -1
        if (at > 0) epIdx = at
    }

    val ctx = LocalContext.current

    /**
     * 分享出去的那段话。
     *
     * 影枢自己没有网址，能被别人点开的只有源站那一页 —— 给不出页面地址的源
     * （比如 M3U 直链源）就只发文字，不摆一条打不开的地址。
     */
    fun shareText(): String {
        val m = d.media
        val head = buildString {
            append('《').append(m.title).append('》')
            listOfNotNull(m.year?.take(4), m.area, m.category).joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        }
        val page = vm.sitePageOf(sid, d.media.id, curEp?.index ?: 1, curEp?.playUrl)
        return if (page == null) "$head\n（来自影枢）" else "$head\n来自影枢 · $page"
    }

    fun doShare() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, d.media.title)
            putExtra(Intent.EXTRA_TEXT, shareText())
        }
        ctx.startActivity(Intent.createChooser(i, "分享 ${d.media.title}"))
    }

    /** 跳豆瓣看评分：手上只有标题和年份，所以打开的是豆瓣的影视搜索而不是评分页。 */
    fun openDouban() {
        val q = listOfNotNull(d.media.title, d.media.year?.take(4)).joinToString(" ")
        val url = "https://search.douban.com/movie/subject_search?search_text=" +
            Uri.encode(q)
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(ctx, "没找到能打开豆瓣的应用", Toast.LENGTH_SHORT).show()
        }
    }

    fun taskFor(ep: Episode) = downloads.firstOrNull {
        it.sourceId == sid && it.mediaId == d.media.id && it.episodeIndex == ep.index
    }

    fun cache(ep: Episode) = vm.cache(sid, d.media, ep)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Backdrop(d, fav, onBack, { vm.toggleFavorite(sid, d.media) }, { openDouban() }) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(Sp.s2),
                ) {
                    ActionTile(
                        if (fav) "已收藏" else "收藏",
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        fav,
                        Modifier.weight(1f),
                    ) { vm.toggleFavorite(sid, d.media) }
                    curEp?.let { ep ->
                        val t = taskFor(ep)
                        ActionTile(
                            cacheLabel(t),
                            Icons.Outlined.Download,
                            t?.status == DownloadStatus.Done,
                            Modifier.weight(1f),
                        ) {
                            when (t?.status) {
                                DownloadStatus.Paused, DownloadStatus.Failed -> vm.resumeDownload(t.id)
                                else -> cache(ep)
                            }
                        }
                    } ?: ActionTile(
                        // 这一条线路没给出分集：这颗格子不能长得和能按的时候一模一样。
                        "缓存", Icons.Outlined.Download, false, Modifier.weight(1f), enabled = false,
                    ) {}
                    ActionTile("分享", Icons.Outlined.Share, false, Modifier.weight(1f)) { doShare() }
                    ActionTile("在豆瓣看评分", Icons.Filled.MoreVert, false, Modifier.weight(1f)) { openDouban() }
                }
            }

            // 播放源 —— 页面视觉重心，放在选集之上
            item {
                Box(Modifier.padding(horizontal = Sp.s4)) {
                    SourcePanel(
                        activeSourceId = activeSourceId,
                        siblings = siblings,
                        failures = siblingFailures,
                        // 换源没成时页面会退回原来那份详情（不撤，用户就不白丢一屏），
                        // 所以「没换成」这件事只能在这一行里说 —— 否则点下去毫无动静。
                        switchError = error,
                        health = health,
                        checking = checking,
                        onRescan = { vm.rescanDetail() },
                        onPick = { av ->
                            vm.switchDetailSource(av)
                            lineIdx = 0
                            epIdx = 0
                        },
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(Sp.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选集", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                    Spacer8()
                    Text(
                        "共 ${d.episodeCount} 集",
                        color = T.TextFaint,
                        fontSize = Fs.xs,
                    )
                    Box(Modifier.weight(1f))
                    if (eps.size > 1) {
                        Text(
                            "缓存全部",
                            color = T.Src500,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(R.sm).clickable { vm.cacheLine(sid, d.media, eps) }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                        Spacer8()
                    }
                    if (lines.size > 1) {
                        // 这一格是「换下一条」，所以把当前这条的名字也写出来 ——
                        // 懂片帝AI 能给到二十几条，只报「线路 3/28」等于让用户盲切；
                        // 要一次跳到指定那条，去播放器里的「线路」面板（那里是滚动列表）
                        Text(
                            "线路 ${(line?.name ?: "").take(10)} ${lineIdx + 1}/${lines.size} ›",
                            color = T.Src500,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                lineIdx = (lineIdx + 1) % lines.size
                                epIdx = 0
                            },
                        )
                    }
                }
            }

            if (eps.isNotEmpty()) {
                item {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().height(((eps.size + 4) / 5 * 46).dp),
                        contentPadding = PaddingValues(horizontal = Sp.s4),
                        horizontalArrangement = Arrangement.spacedBy(Sp.s2),
                        verticalArrangement = Arrangement.spacedBy(Sp.s2),
                        userScrollEnabled = false,
                    ) {
                        items(eps.size) { i ->
                            val e = eps[i]
                            val on = i == epIdx
                            val t = taskFor(e)
                            Box(
                                Modifier.height(38.dp).clip(R.sm)
                                    .background(
                                        if (on) Brush.verticalGradient(listOf(T.Play400, T.Play500))
                                        else Brush.verticalGradient(listOf(T.Surface2, T.Surface2))
                                    )
                                    .border(1.dp, if (on) Color.Transparent else T.Border, R.sm)
                                    .combinedClickable(
                                        onClick = { epIdx = i },
                                        onLongClick = { cache(e) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    e.name.take(6),
                                    color = if (on) Color.White else T.TextDim,
                                    fontSize = Fs.xs,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                t?.let {
                                    Text(
                                        when (it.status) {
                                            DownloadStatus.Done -> "✓"
                                            DownloadStatus.Failed -> "!"
                                            DownloadStatus.Paused -> "‖"
                                            else -> if (it.hasPercent) "${(it.progress * 100).toInt()}%" else "…"
                                        },
                                        color = if (on) Color.White else T.Src500,
                                        fontSize = Fs.tiny,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                    )
                                }
                                // 上次停在哪一集：右下角一个点，不跟右上角的缓存标记打架
                                if (seen?.episodeIndex == e.index) {
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                            .size(5.dp).clip(CircleShape).background(T.Play400),
                                    )
                                }
                            }
                        }
                    }
                    Spacer4()
                    Text(
                        "长按某一集 = 只缓存它",
                        color = T.TextFaint,
                        fontSize = Fs.tiny,
                        modifier = Modifier.padding(horizontal = Sp.s4),
                    )
                }
            }

            item {
                Column(Modifier.padding(Sp.s4)) {
                    Text("简介", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                    Spacer8()
                    Text(
                        cleanIntro(d.intro ?: d.media.intro) ?: "暂无简介",
                        color = T.TextDim,
                        fontSize = Fs.sm,
                        lineHeight = Fs.sm * 1.78f,
                    )
                }
            }
        }

        // 底部主 CTA
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .6f),
                        0.4f to T.Bg,
                        1f to T.Bg,
                    )
                )
                .padding(horizontal = Sp.s4, vertical = Sp.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val curTask = curEp?.let { taskFor(it) }
            Box(
                Modifier.size(46.dp).clip(R.md).background(T.Surface2)
                    .border(1.dp, T.Border, R.md)
                    .combinedClickable(
                        onClick = {
                            when (curTask?.status) {
                                DownloadStatus.Paused, DownloadStatus.Failed -> vm.resumeDownload(curTask.id)
                                else -> curEp?.let { cache(it) }
                            }
                        },
                        onLongClick = { curTask?.let { confirmDrop = it } },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Download,
                    null,
                    tint = if (curTask?.status == DownloadStatus.Done) T.Play400 else T.TextDim,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer12()
            Row(
                Modifier.weight(1f).height(46.dp).clip(R.md)
                    .then(
                        // 这一条线路一个分集都没给时不能还涂成能播的样子：那颗键亮着、
                        // 写着「播放 第 1 集」，按下去什么都不发生 —— 而第 1 集并不存在。
                        if (curEp == null) Modifier.background(T.Surface2)
                        else Modifier.background(
                            Brush.verticalGradient(listOf(T.Play400, T.Play500))
                        )
                    )
                    .clickable(enabled = curEp != null) { curEp?.let { onPlay(sid, d.media, it) } },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = if (curEp == null) T.TextFaint else Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer8()
                Text(
                    // 上次没看完这一集就把话头递回去，看完了就正常「播放 第N集」
                    when {
                        curEp == null -> "这一条线路没有给出分集"
                        resumeEp != null && resumeEp.episodeIndex == curEp.index ->
                            "继续播放 ${curEp.name} · ${clock(resumeEp.positionSec)}"
                        else -> "播放 ${curEp.name}"
                    },
                    color = if (curEp == null) T.TextFaint else Color.White,
                    fontSize = Fs.body,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        confirmDrop?.let { victim ->
            ConfirmDialog(
                title = "删掉这条缓存？",
                body = "「${victim.mediaTitle} · ${victim.episodeName}」这条记录，" +
                    (if (victim.status == DownloadStatus.Done)
                        "已经下好的 ${fmtBytesLocal(victim.bytesDone)} 会一起没掉，重新缓存得从头再取。"
                    else "已经下载的部分也会删掉。"),
                confirm = "删除",
                onConfirm = { vm.deleteDownload(victim.id); confirmDrop = null },
                onDismiss = { confirmDrop = null },
            )
        }
    }
}

private fun fmtBytesLocal(b: Long): String {
    val tenthsMb = b * 10 / (1L shl 20)
    return when {
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "${tenthsMb / 10}.${tenthsMb % 10} MB"
        else -> "%.0f KB".format(b / 1024.0)
    }
}

@Composable
private fun Backdrop(
    d: MediaDetail,
    fav: Boolean,
    onBack: () -> Unit,
    onFav: () -> Unit,
    onDouban: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(240.dp)) {
        if (!d.media.cover.isNullOrBlank()) {
            AsyncImage(
                model = d.media.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .30f),
                    0.62f to Color.Black.copy(alpha = .78f),
                    1f to T.Bg,
                )
            )
        )
        Row(
            Modifier.fillMaxWidth().padding(Sp.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CircleIcon(Icons.Filled.ArrowBack, onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                CircleIcon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, onFav)
                CircleIcon(Icons.Filled.Star, onDouban)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(horizontal = Sp.s4),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.width(94.dp).height(134.dp).clip(R.md)
                    .background(T.Surface2).border(1.dp, T.BorderStrong, R.md),
            ) {
                if (!d.media.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = d.media.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer12()
            Column(Modifier.padding(bottom = Sp.s1)) {
                Text(
                    d.media.title,
                    color = T.Text,
                    fontSize = Fs.title,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer8()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(d.media.year, d.media.category, d.media.remark)
                            .joinToString(" · "),
                        color = T.TextDim,
                        fontSize = Fs.xs,
                    )
                    Spacer8()
                    // 画质徽标只写元数据里真的有的那一项。以前硬写「4K HDR」，
                    // 站点明明给的是「高清」，界面上却替它吹成 4K —— 用户点进去才发现不是。
                    qualityBadge(d.media)?.let { Pill(it, cyan = true) }
                }
                if (!d.media.score.isNullOrBlank()) {
                    Spacer8()
                    Text(
                        "★ ${d.media.score}",
                        color = T.Gold,
                        fontSize = Fs.body2,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .50f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier.height(60.dp).clip(R.md)
            .background(if (active) T.Play500.copy(alpha = .09f) else T.Surface2)
            .border(1.dp, if (active) T.Play500.copy(alpha = .32f) else T.Border, R.md)
            // enabled 要落到外观上：一个按下去什么也不做、看起来却随时能按的格子，
            // 比一个明写着「现在不行」的格子更让人反复试。
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            null,
            tint = when {
                !enabled -> T.TextFaint
                active -> T.Play400
                else -> T.TextDim
            },
            modifier = Modifier.size(19.dp),
        )
        Spacer4()
        Text(
            label,
            color = when {
                !enabled -> T.TextFaint
                active -> T.Play400
                else -> T.TextDim
            },
            fontSize = Fs.xxs,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 缓存按钮上的字 —— 每一句都要对得上任务真实处在的那一步。 */
private fun cacheLabel(t: DownloadTask?): String = when (t?.status) {
    DownloadStatus.Done -> "已缓存"
    // 对方不给总大小的那种单文件，百分比压根算不出来（progress 恒为 0）—— 报「0%」是假话
    DownloadStatus.Running -> if (t.hasPercent) "缓存中 ${(t.progress * 100).toInt()}%" else "缓存中"
    DownloadStatus.Queued -> "排队中"
    DownloadStatus.Paused -> "继续缓存"
    DownloadStatus.Failed -> "重试缓存"
    null -> "缓存"
}

/** 播放源面板：横排源卡片，带健康状态与延迟。 */
@Composable
private fun SourcePanel(
    activeSourceId: String?,
    siblings: List<SourceAvailability>,
    failures: List<SearchOutcome.Failure>,
    switchError: String?,
    health: Map<String, net.cinehub.data.model.HealthReport>,
    checking: Boolean,
    onRescan: () -> Unit,
    onPick: (SourceAvailability) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(R.lg).background(T.Surface)
            .border(1.dp, T.Border, R.lg).padding(Sp.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放源", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            // 探测要跑好几秒，不给动静的话这按钮看着就是坏的
            Row(
                Modifier.clickable(enabled = !checking, onClick = onRescan)
                    .padding(horizontal = Sp.s2, vertical = Sp.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, null, tint = if (checking) T.TextDim else T.Src500,
                    modifier = Modifier.size(12.dp))
                Spacer4()
                Text(
                    if (checking) "检测中…" else "重新检测",
                    color = if (checking) T.TextDim else T.Src500,
                    fontSize = Fs.xxs,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer12()
        if (siblings.isEmpty()) {
            // 这句话只该在真的还在跑的时候说。原来它只看 siblings 空不空，
            // 于是比对跑完、结果为空时它长挂成「正在比对…」永不撤回。
            Text(
                if (checking) "正在比对该片在其它源的情况…" else "这一部只在当前这个源上有，别的源没搜到同名",
                color = T.TextFaint,
                fontSize = Fs.xxs,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                items(siblings) { av ->
                    val on = av.sourceId == activeSourceId
                    val h = health[av.sourceId]?.health ?: av.health
                    Column(
                        Modifier.width(122.dp).clip(R.md)
                            .background(if (on) T.Play500.copy(alpha = .10f) else T.Surface2)
                            .border(1.dp, if (on) T.Play500.copy(alpha = .48f) else T.Border, R.md)
                            .clickable { onPick(av) }
                            .padding(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(h, 6)
                            Spacer8()
                            Text(
                                av.sourceName,
                                color = T.Text,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer8()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (av.quality != null) {
                                Pill(av.quality, cyan = true)
                                Spacer8()
                            }
                            Text(
                                when (h) {
                                    Health.Ok -> "${av.latencyMs ?: health[av.sourceId]?.latencyMs ?: "-"}ms"
                                    Health.Slow -> "较慢"
                                    Health.Down -> "不可用"
                                    Health.Unknown -> "—"
                                },
                                color = T.TextFaint,
                                fontSize = Fs.tiny,
                            )
                        }
                    }
                }
            }
        }
        // 没答上话的源单独说一句。悄悄少一项的话，用户看到的就是「这片只有一个源」，
        // 而真相是「另外两个源这次没连上」—— 那该做的是重新检测，不是换一个源试试。
        if (failures.isNotEmpty()) {
            Spacer8()
            Text(
                "另有 ${failures.size} 个源没答上话：" +
                    failures.joinToString("、") { "${it.sourceName}（${it.reason}）" },
                color = T.Warn,
                fontSize = Fs.xxs,
            )
        }
        if (!switchError.isNullOrBlank()) {
            Spacer8()
            Text("还在原来这个源上：$switchError", color = T.Err, fontSize = Fs.xxs)
        }
    }
}

/**
 * 详情页的画质徽标：只写这份元数据里真的存在的说法。
 *
 * 影枢在详情页还不知道实际分辨率（要等播放器解出 master playlist 才有几档高度），
 * 所以这里不能替站点吹 —— 站点写「高清」就显示「高清」本来该显示的样子，没写就不显示。
 */
private fun qualityBadge(m: Media): String? {
    val t = listOfNotNull(m.remark, m.category, m.title).joinToString(" ")
    val fourK = t.contains("4k", true) || t.contains("2160")
    val hdr = t.contains("hdr", true)
    val blue = t.contains("蓝光")
    return when {
        fourK && hdr -> "4K HDR"
        fourK -> "4K"
        hdr -> "HDR"
        blue -> "蓝光"
        t.contains("1080") -> "1080P"
        t.contains("720") -> "720P"
        else -> null
    }
}

/** 源站简介里常见的 HTML 实体。`&amp;` 不在表里 —— 它要最后单独解，否则会二次解码。 */
private val ENTITIES = mapOf(
    "&nbsp;" to " ", "&ensp;" to " ", "&emsp;" to " ", "&thinsp;" to " ",
    "&quot;" to "\u0022", "&apos;" to "\u0027", "&lt;" to "<", "&gt;" to ">",
    "&ldquo;" to "\u201c", "&rdquo;" to "\u201d",
    "&lsquo;" to "\u2018", "&rsquo;" to "\u2019",
    "&hellip;" to "\u2026", "&mdash;" to "\u2014", "&ndash;" to "\u2013",
    "&middot;" to "\u00b7", "&copy;" to "\u00a9", "&reg;" to "\u00ae",
    "&times;" to "\u00d7", "&divide;" to "\u00f7", "&deg;" to "\u00b0",
    "&bull;" to "\u2022", "&rarr;" to "\u2192", "&larr;" to "\u2190",
)

private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")
private val TAG = Regex("<[^>]{0,300}>")
private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

/**
 * 源站的简介是 HTML：实体和 `<br/>` 都混在正文里。
 * 不处理就会显示成「…10号房是一个特别的存在&nbsp;，别看…」，那是把转义当内容给用户看。
 */
private fun cleanIntro(raw: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var t = s.replace(BR, "\n").replace(TAG, "")
    ENTITIES.forEach { (k, v) -> t = t.replace(k, v) }
    t = t.replace("&amp;", "&")
    t = NUMERIC.replace(t) { m ->
        val hex = m.groupValues[1] == "x"
        val n = if (hex) m.groupValues[2].toLongOrNull(16) else m.groupValues[2].toLongOrNull()
        if (n != null && n in 1..0x10FFFFL) String(Character.toChars(n.toInt())) else ""
    }
    return t.replace(Regex("[ \t]+\n"), "\n").trim().takeIf { it.isNotEmpty() }
}
