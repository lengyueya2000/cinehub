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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
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
import net.cinehub.data.model.qualityTagOf
import net.cinehub.data.model.SearchOutcome
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.clock
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.ConfirmDialog
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T
import net.cinehub.ui.theme.Touch

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
    val switchError by vm.switchError.collectAsState()
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
                    Spacer12()
                    // 加载这一支也要给出口：卡住时（源不回话、探测慢）屏上只剩系统返回键，
                    // 而这一屏连顶栏都还没画出来。错误那一支一直有「返回」，两支摆齐。
                    Text(
                        "返回",
                        color = T.Src500,
                        fontSize = Fs.sm,
                        modifier = Modifier.heightIn(min = Touch.min).clickable(onClick = onBack),
                    )
                } else {
                    Text(error ?: "没有数据", color = T.Err, fontSize = Fs.body2)
                    Spacer12()
                    Text(
                        "返回",
                        color = T.Src500,
                        fontSize = Fs.sm,
                        // 同样补足可点高度：这一颗是这一屏唯一的出路
                        modifier = Modifier.heightIn(min = Touch.min).clickable(onClick = onBack),
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
    /**
     * 这部片上次看到哪；只有正播到这一集时才提示续播。
     *
     * 订阅着读，别用 vm.historyOf()：那是 StateFlow 的此刻值，组合里读它挂不上订阅。
     * 不订阅的后果是同一屏两个答案 —— 记录在首帧之后才落盘时选集格子还落在第 1 集，
     * 而右下角那个续播点已经画在第 7 集。
     */
    val history by vm.watchHistory.collectAsState()
    val seen = d.media.key?.let { k -> history.firstOrNull { it.key == k } }
    val resumeEp = seen?.takeIf { it.resumable }

    /**
     * 线路与选集落在哪 —— 一个 effect 说清两种「换了」。
     *
     * ① 换了一部片：线路回第一条，选集落在上次看到的那一集（没记录就第 1 集）。
     *    这两格是记在这一屏上的普通状态，换一部片它们不会跟着走 —— 上一部挑到第 20 集，
     *    进来一部只有 12 集的片就停在第 12 集，还一句解释都没有。
     * ② 同一部片换到另一个源：同样归零，但**只能在源真的换过来之后**。
     *    原来是在「点击换源」那一刻清的，而换源失败时仓库会退回原来那份详情 ——
     *    用户挑的线路和那一集被无声抹掉，界面上只说「还在原来这个源上」。
     *
     * 已经落过点的（这一部 + 这个源）不再动：换线路时 `eps.size` 也会变，
     * 每次都重落一遍的话，用户刚挑的那条线路会被拽回第一条。
     */
    val landed = remember { mutableStateOf<Pair<String, String?>?>(null) }
    LaunchedEffect(d.media.id, activeSourceId, eps.size, seen?.episodeIndex) {
        val key = d.media.id to activeSourceId
        if (landed.value == key || eps.isEmpty()) return@LaunchedEffect
        landed.value = key
        lineIdx = 0
        val at = seen?.let { s -> eps.indexOfFirst { it.index == s.episodeIndex } } ?: -1
        epIdx = if (at > 0) at else 0
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

    fun cache(ep: Episode) = vm.cache(sid, d.media, ep)

    /**
     * 这一部在**当前源**下的缓存任务，按集号索引好。
     *
     * 原来是每格各自 `downloads.firstOrNull { ... }` 扫一遍：选集是个固定高度、
     * `userScrollEnabled = false` 的网格（整块就是一个 item，视口等于全部），
     * 200 集就是 200 次线性扫，而 `downloads` 每来一次进度就换一份新列表 ——
     * 下载中这里会按进度频率把 O(集数 × 任务数) 重算一遍，底部那颗按钮还再扫一次。
     * 扫一次建个表，两边都查它。
     */
    val taskByEp = remember(downloads, sid, d.media.id) {
        downloads.filter { it.sourceId == sid && it.mediaId == d.media.id }
            .associateBy { it.episodeIndex }
    }

    fun taskFor(ep: Episode) = taskByEp[ep.index]

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Backdrop(d, fav, onBack, { vm.toggleFavorite(sid, d.media) }) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(Sp.s2),
                ) {
                    // 这一行只剩两件别处没有的事。原来四格并排里：「收藏」和封面下面那颗心
                    // 是同一个动作的两个入口，「缓存」和底部那颗下载键也是同一个动作
                    //（连「暂停之后点继续」那套分支都重复了一份），而「在豆瓣看评分」
                    // 在封面上还另有一颗星形图标 —— 同级重复入口一律砍掉。
                    // 缓存进度本来就有地方说：选集格子右上角那一格。
                    ActionTile("分享", Icons.Outlined.Share, false, Modifier.weight(1f)) { doShare() }
                    ActionTile("在豆瓣看评分", Icons.Outlined.OpenInNew, false, Modifier.weight(1f)) { openDouban() }
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
                        switchError = switchError,
                        health = health,
                        checking = checking,
                        onRescan = { vm.rescanDetail() },
                        onPick = { av ->
                            // 这里只递需求，不动 lineIdx/epIdx：换源没成时仓库会退回原来那份详情，
                            // 而我们刚把用户选的线路和集数清成 0 —— 一句「还在原来这个源上」
                            // 背后藏着「你刚才挑的那条线路也没了」。源真的换了以后再归零，
                            // 见下面那个 LaunchedEffect(activeSourceId)。
                            vm.switchDetailSource(av)
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
                        // 报的必须是**下面这一格真的列出来的**集数。
                        // episodeCount 是全剧各线路里的最大值，而这一格只渲染当前线路的 eps ——
                        // 第一条线路只有 12 集、另有线路 28 集时，屏上写着「共 28 集」而底下 12 格，
                        // 旁边那颗「缓存全部」也只作用于这 12 集。数字和动作都得说同一件事。
                        if (d.lines.size <= 1) "共 ${eps.size} 集"
                        else "本线路 ${eps.size} 集 · 全剧共 ${d.episodeCount} 集",
                        color = T.TextDim,
                        fontSize = Fs.xs,
                    )
                    Box(Modifier.weight(1f))
                    if (eps.size > 1) {
                        Text(
                            "缓存全部",
                            color = T.Src500,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                            // 这一行原来只有 ~20dp 高：它是整条线路唯一的批量入口，点不准就等于没有
                            modifier = Modifier.clip(R.sm).heightIn(min = Touch.min).clickable {
                                vm.cacheLine(sid, d.media, eps)
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
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
                            // 这是详情页唯一的换线路入口，原来连内边距都没有（~16dp 高）
                            modifier = Modifier.clip(R.sm).heightIn(min = 44.dp).clickable {
                                lineIdx = (lineIdx + 1) % lines.size
                                epIdx = 0
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
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
                // 演职员：适配器解析了 actors/director（苹果 CMS、MINO、PPnix 都给），
                // 界面却从来没显示过。有值才显示这一段 —— 大多数源只有其中一样，
                // 空着一条「导演：」比不显示更难看。导演与演员同行不同段，各自整段省略。
                val cast = d.media.actors?.takeIf { it.isNotBlank() }
                val director = d.media.director?.takeIf { it.isNotBlank() }
                if (cast != null || director != null) {
                    Column(Modifier.padding(Sp.s4)) {
                        Text("演职员", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                        Spacer8()
                        if (director != null) {
                            Text(
                                "导演：$director",
                                color = T.TextDim,
                                fontSize = Fs.sm,
                                lineHeight = Fs.sm * 1.6f,
                            )
                        }
                        if (cast != null) {
                            Text(
                                "主演：$cast",
                                color = T.TextDim,
                                fontSize = Fs.sm,
                                lineHeight = Fs.sm * 1.6f,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "简介",
                    color = T.Text,
                    fontSize = Fs.body,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = Sp.s4),
                )
                Spacer8()
                Text(
                    // 简介的清洗要 remember：这一屏会随 downloads / health / watchHistory
                    // 的每一次发射重组（下载中就是每秒好几次），而 cleanIntro 里
                    // 走 24 个实体替换 + 四趟正则 —— 每帧重跑一遍纯属白烧。
                    remember(d.intro, d.media.intro) { cleanIntro(d.intro ?: d.media.intro) } ?: "暂无简介",
                    color = T.TextDim,
                    fontSize = Fs.sm,
                    lineHeight = Fs.sm * 1.78f,
                    modifier = Modifier.padding(horizontal = Sp.s4),
                )
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
            Column(
                Modifier.width(60.dp).height(46.dp).clip(R.md).background(T.Surface2)
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Download,
                    "缓存这一集",
                    tint = if (curTask?.status == DownloadStatus.Done) T.Play400 else T.TextDim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer4()
                // 动作行那一格删掉之后，这一格就是这一集唯一的缓存入口 —— 光一颗图标
                // 说不出它现在到底处在哪一步，进度得跟着任务真实状态写。
                // 写什么由 [cacheLabel] 一处决定：这里原来另抄了一份 when，抄的时候
                // 「继续缓存」缩成了「继续」、「缓存中 N%」缩成了「N%」—— 同一个量
                // 在两种写法之间漂，就是 ByteFormat 那份注释里说的那类毛病。
                Text(
                    cacheLabel(curTask),
                    color = if (curTask?.status == DownloadStatus.Done) T.Play400 else T.TextDim,
                    fontSize = Fs.xxs,
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

/**
 * 字节数 → 人话。全应用只有 [ByteFormat] 一份，这里只是转个手。
 *
 * 曾经这里另写了一套：GB 档用 `%.1f`（四舍五入）、KB 档带小数 —— 而 ByteFormat 的口径是
 * **截断 + KB 写整数**。于是「已经下好的 1.4 GB 会一起没掉」这个弹窗报的数，
 * 和缓存页、播放器报的同一条任务的数不一样。这个弹窗的全部作用就是告诉用户要失去什么，
 * 数字对不上比不报更糟。
 *
 * `internal`：这一条被单测钉着（转个手也会被改坏），不给 `private`。
 */
internal fun fmtBytesLocal(b: Long): String = net.cinehub.data.ByteFormat.bytes(b)

@Composable
private fun Backdrop(
    d: MediaDetail,
    fav: Boolean,
    onBack: () -> Unit,
    onFav: () -> Unit,
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
            CircleIcon(Icons.Filled.ArrowBack, "返回", onBack)
            // 只留这一颗心：封面下面那颗心就是收藏的入口（「收藏」那页的空状态也是这么指路的），
            // 底下动作行里再来一格「收藏」是同一件事的两个入口
            CircleIcon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", onFav)
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
                    qualityTagOf(d.media)?.let { Pill(it, cyan = true) }
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
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        // 44dp：这三颗是这一屏最主要的手指去处（返回、收藏），比图标本身还小一点就点不准
        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = .50f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // contentDescription 不能是 null：这三颗全是纯图标，读屏念出来就只有「标签」
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(18.dp))
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

/**
 * 缓存按钮上的字 —— 每一句都要对得上任务真实处在的那一步。
 *
 * `internal`：单测钉着这一份（详情页那颗按钮、缓存页、播放器三处都读它），
 * 不给 `private`。
 */
internal fun cacheLabel(t: DownloadTask?): String = when (t?.status) {
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
            // 高度必须够 44dp：图标 12dp + Fs.xxs 的字，撑起来只有二十来像素，
            // 而这是这一栏唯一的重测入口 —— 点不准就等于没有（下面「缓存全部」同一条规矩）
            Row(
                Modifier.heightIn(min = Touch.min)
                    .clickable(enabled = !checking, onClick = onRescan)
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
                            // 数字要和上面那颗状态点取同一份来源：点用的是活表（health[av.sourceId]），
                            // 数字原来却优先用比对时的旧快照 —— 在源管理里「全部重测」把某源从
                            // 1200ms 测成 200ms 以后，回到这一栏就成了「绿点 + 1200ms」。
                            // 另外拿不到数时写「已连通」，不拼出 "-ms" 这种不可能的字。
                            val ms = health[av.sourceId]?.latencyMs ?: av.latencyMs
                            Text(
                                when (h) {
                                    Health.Ok -> if (ms != null) "${ms}ms" else "已连通"
                                    Health.Slow -> "较慢"
                                    Health.Down -> "不可用"
                                    Health.Unknown -> "未检测"
                                },
                                // 这一格承载的是事实，不该用最小最弱的那一号字
                                color = T.TextDim,
                                fontSize = Fs.xs,
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
 * 源站简介里常见的 HTML 实体。`&amp;` 不在表里 —— 它要最后单独解，否则会二次解码。
 *
 * （这里原来还挂着一份讲「画质徽标」的注释，而那句话真正该待的地方是
 * `Models.qualityTagOf` 那边，函数早搬走了，注释没人管就成了孤魂。）
 */
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
/** 行尾的空格/制表符。要 hoist：原来它每次调用现编一个 Pattern。 */
private val WS_BEFORE_NL = Regex("[ \t]+\n")

/**
 * 源站的简介是 HTML：实体和 `<br/>` 都混在正文里。
 * 不处理就会显示成「…10号房是一个特别的存在&nbsp;，别看…」，那是把转义当内容给用户看。
 *
 * `internal`：单测钉着（实体二次解码、越界码点、行尾空格这几条都静默地错），
 * 不给 `private`。
 */
internal fun cleanIntro(raw: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var t = s.replace(BR, "\n").replace(TAG, "")
    ENTITIES.forEach { (k, v) -> t = t.replace(k, v) }
    t = t.replace("&amp;", "&")
    t = NUMERIC.replace(t) { m ->
        val hex = m.groupValues[1] == "x"
        val n = if (hex) m.groupValues[2].toLongOrNull(16) else m.groupValues[2].toLongOrNull()
        if (n != null && n in 1..0x10FFFFL) String(Character.toChars(n.toInt())) else ""
    }
    return t.replace(WS_BEFORE_NL, "\n").trim().takeIf { it.isNotEmpty() }
}
