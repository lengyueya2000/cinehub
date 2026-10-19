package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.DoubanItem
import net.cinehub.data.model.HistoryEntry
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.DoubanMissBanner
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.SectionHead
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer16
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 首页 = 豆瓣「剧集 · 热门」这一张榜。
 *
 * 原来这一屏摆的是「第一个能答上话的播放源的第 1 页」，底下配三排从这一页现取的筛选芯片。
 * 换成豆瓣之后那些都没了依据：首页不再由哪个源决定内容，「均来自〈源名〉」和
 * 「能连上 / 连不通」这两句就不该再说；榜单的顺序是豆瓣给的，页面上也不该再造一层过滤。
 *
 * 代价说在前面：**点一张海报不等于能播**。豆瓣不给播放地址，影枢得拿片名去接的源里搜，
 * 片名指纹对得上才进详情页；对不上就明说对不上（那条横幅和片库共用一个实现）。
 *
 * 这一张榜和片库的状态各走各的：在片库切到「美剧」不该把首页换了脸。
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDoubanPage: (String) -> Unit,
) {
    val home by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    val error by vm.homeError.collectAsState()
    val sources by vm.sources.collectAsState()
    val history by vm.watchHistory.collectAsState()
    val homeAt by vm.homeAt.collectAsState()
    val resolving by vm.doubanResolving.collectAsState()

    val enabledSources = sources.count { it.enabled }

    // 这一屏出现在眼前就取一次。判据不再是「快照过没过期 / 源顺序变没变」——
    // 那两条是为了别每次进首页都把每个源敲一遍，现在只剩豆瓣一处，代价不是一个量级。
    LaunchedEffect(Unit) { vm.loadHome() }

    val hero = home.firstOrNull()
    // 下面从第二条起列（hero 摆的就是这一张榜的第一条），否则同一部片会在屏幕上出现两次。
    val rest = home.drop(1)
    var miss by remember { mutableStateOf<Pair<DoubanItem, String>?>(null) }

    /**
     * 这一页还在不在。
     *
     * 拿片名去源里搜要一两秒，回来时用户可能已经切去别的 Tab —— 那种「迟到的命中」
     * 照原样去改路由，就是把人从首页抛进一个他根本没点的详情页。
     */
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }

    fun openDouban(d: DoubanItem) = vm.openFromDouban(
        d,
        onHit = { m -> if (mounted) onOpenDetail(m.sourceId, m.id) },
        onMiss = { it2, why -> if (mounted) miss = it2 to why },
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 豆瓣对不上时那条横幅贴在底部，会盖住最后一行海报：它不在滚动流里，
            // 只留 Sp.s6 的话最后一行既被挡住、又没有可滚的余量把它挪出来。
            // 横幅起来时把底垫加高（横幅本身约 70~80dp），松手后恢复原样。
            contentPadding = PaddingValues(bottom = if (miss != null) 96.dp else Sp.s6),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(T.Play500, Color(0xFF7F1D3A)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    Spacer8()
                    Text("影枢", color = T.Text, fontSize = Fs.sec, fontWeight = FontWeight.ExtraBold)
                    Spacer12()
                    Row(
                        // 44dp：这是全应用的主搜索门，36dp 在手里按不准
                        Modifier.weight(1f).height(44.dp).clip(R.full).background(T.Surface2)
                            .clickable(onClick = onOpenSearch).padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, null, tint = T.TextFaint, modifier = Modifier.size(14.dp))
                        Spacer8()
                        // 「演员」两个字曾经写在这里，但源站 LIKE 匹配的是片名，
                        // 搜演员搜不出东西 —— 占位符不能许愿自己兑现不了的。
                        Text("搜索影片、播放源", color = T.TextDim, fontSize = Fs.sm)
                    }
                }
            }

            if (loading && home.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(222.dp).padding(horizontal = Sp.s4),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在向豆瓣取「剧集 · 热门」…", color = T.TextDim, fontSize = Fs.sm)
                    }
                }
            }

            // 刷新没成但旧榜单还在屏上：这句必须说，否则用户会以为下面这一页就是刚取回来的。
            if (error != null && home.isNotEmpty()) {
                item {
                    Text(
                        "最新一次没刷新成功：" + error!!,
                        color = T.Warn,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.6f,
                        modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s2),
                    )
                }
            }

            if (error != null && home.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(Sp.s4).clip(R.lg)
                            .background(T.Err.copy(alpha = .07f))
                            .padding(Sp.s4),
                    ) {
                        Column {
                            // 这句说的是豆瓣，不是源。首页已经不敲源站的门了，
                            // 把它挂到「播放源管理」上等于把人支使去查一个不相干的地方。
                            Text("没从豆瓣取到这一页", color = T.Err, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                            Spacer4()
                            Text(error!!, color = T.TextDim, fontSize = Fs.xs)
                            Spacer12()
                            Text(
                                if (loading) "正在取…" else "↻ 再取一次",
                                color = if (loading) T.TextFaint else T.Src500,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                                // 这是这一屏唯一的出路，得点得中
                                modifier = Modifier.clip(R.sm).heightIn(min = 44.dp)
                                    .clickable(enabled = !loading, onClick = vm::refreshHome)
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            if (hero != null) {
                item {
                    Box(Modifier.padding(horizontal = Sp.s4)) {
                        HeroCard(
                            title = hero.title,
                            cover = hero.cover,
                            meta = listOfNotNull(
                                hero.rate?.takeIf { it.isNotBlank() }?.let { "豆瓣评分 $it" },
                                hero.year,
                                hero.whereFrom.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            resolving = resolving == hero.id,
                            onPlay = { openDouban(hero) },
                        )
                    }
                }
            }

            // 继续观看：打开应用最想干的事就是接上上次那一集，所以放在 hero 正下面。
            // 只列没看完的 —— 看完的那一部不该再「继续」一次，它留在「我的 · 最近观看」里。
            // 一条都没有时整段不出现，不留一个空壳。
            val resume = history.filter { it.resumable }
            if (resume.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = Sp.s4)) {
                        Text(
                            "继续观看",
                            color = T.Text,
                            fontSize = Fs.body,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = Sp.s4),
                        )
                        Spacer8()
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Sp.s4),
                            horizontalArrangement = Arrangement.spacedBy(Sp.s3),
                        ) {
                            items(resume, key = { it.key }) { e ->
                                ResumeCard(e) { vm.resumeFromHistory(e) }
                            }
                        }
                    }
                }
            }

            if (rest.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = Sp.s4)) {
                        SectionHead(
                            "豆瓣 · 剧集热门",
                            modifier = Modifier.padding(top = Sp.s4, bottom = Sp.s1),
                        )
                        // 这一页可能是不知多久以前落盘的那份先上屏的，不说几点取回的就没人知道它新不新。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (homeAt > 0L) {
                                // 这一句承载的是「这份榜新不新」这个事实，别用最小最弱的那一号字
                                Text(
                                    "取回于 ${formatFetchedAt(homeAt)}",
                                    color = T.TextDim,
                                    fontSize = Fs.xs,
                                )
                            }
                            Box(Modifier.weight(1f))
                            Text(
                                if (loading) "正在取…" else "↻ 刷新这一页",
                                color = if (loading) T.TextFaint else T.Src500,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                                // 44dp：同一件事在页顶那条横幅上是 44dp，这里两处的门不一样高
                                modifier = Modifier
                                    .clip(R.full)
                                    .heightIn(min = 44.dp)
                                    .clickable(enabled = !loading, onClick = vm::refreshHome)
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                        Spacer4()
                    }
                }
                item {
                    // 一整张榜按三格一行铺，行高由内容自己包出来。
                    //
                    // 原来这里是「固定高度的嵌套网格」：`gridHeight()` 按每行 218dp 估，
                    // 而一张海报下面还挂着评分那一行，估矮了 —— 最后一行被网格自己的高度
                    // 剪掉半截，而且外层以为那就是全部，滚都滚不到。
                    // 榜单一共 30 条，摊成十行普通 Row 不费什么，也不用再猜行高。
                    // （这里不能再套一层 LazyColumn：同方向嵌在 LazyColumn 里拿到的是无限高度约束，会直接崩。）
                    Column(Modifier.fillMaxWidth()) {
                        rest.chunked(3).forEach { row ->
                            Spacer16()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Sp.s4),
                                horizontalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                row.forEach { d ->
                                    Box(Modifier.weight(1f)) {
                                        PosterCard(
                                            title = d.title,
                                            subtitle = d.displayLine,
                                            cover = d.cover,
                                            badge = if (resolving == d.id) "找片中" else null,
                                            onLongClick = { onOpenDoubanPage(d.url) },
                                            onClick = { openDouban(d) },
                                        )
                                    }
                                }
                                // 最后一行不满三格时补空位：不补的话那两张会被 weight 拉宽，
                                // 和上面每一行的海报尺寸不一样。
                                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

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

/**
 * 榜单取回的时刻，只到分钟。
 *
 * 日期只在不是今天的时候才带 —— 「今天 09:32」比「10-14 09:32」更直接回答「这页新不新」。
 * 用 SimpleDateFormat 而不是 java.time：minSdk 24，后者要 API 26 才在设备上跑得起来。
 */
private fun formatFetchedAt(at: Long): String {
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
    val sameDay = day.format(java.util.Date(at)) == day.format(java.util.Date())
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
    return if (sameDay) "今天 $hm" else "${java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(at))} $hm"
}

/**
 * 榜首那一部的大图。
 *
 * 角标原来写的是「〈源名〉· 能连上 / 连不通」，那三个值全是从源那边现算的；
 * 这一屏已经不替任何源作保了，角标改成说这一条的出处 —— 豆瓣的剧集热门榜。
 * 名次不写：标签榜（热门就是标签榜）豆瓣不回 rank，写「第 1 名」是替它编的。
 */
@Composable
private fun HeroCard(
    title: String,
    cover: String?,
    meta: String,
    resolving: Boolean,
    onPlay: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(222.dp)
            .clip(R.xl)
            .background(T.Surface2),
    ) {
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .60f),
                    0.55f to Color.Black.copy(alpha = .20f),
                    1f to Color.Black.copy(alpha = .97f),
                )
            )
        )
        // 这一张卡不再在封面上挂一遍「豆瓣 · 剧集热门」：同一个名字在 60dp 之下那个
        // 区块的标题上已经写过一次（SectionHead），这里第二份只是装饰，不答任何问题。
        Column(Modifier.align(Alignment.BottomStart).padding(Sp.s4)) {
            Text(
                title,
                color = T.Text,
                fontSize = Fs.hero,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer8()
            Text(meta, color = T.TextOnArt, fontSize = Fs.xs, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer12()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    // 44dp：这一颗是这一屏唯一会把人带进播放的门
                    Modifier
                        .height(44.dp)
                        .clip(R.full)
                        .background(Brush.verticalGradient(listOf(T.Play400, T.Play500)))
                        .clickable(enabled = !resolving, onClick = onPlay)
                        .padding(horizontal = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer4()
                    // 「立即播放」是句谎话：豆瓣不给地址，这一按下去先去的是源里搜片名。
                    Text(
                        if (resolving) "找片中…" else "找这一部",
                        color = Color.White,
                        fontSize = Fs.sm,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 「继续观看」的一格：封面 + 片名 + 看到哪一集、第几分钟。 */
@Composable
private fun ResumeCard(e: HistoryEntry, onClick: () -> Unit) {
    Column(
        Modifier.width(108.dp).clip(R.md).background(T.Surface2)
            .border(1.dp, T.Border, R.md).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(T.Surface3),
        ) {
            if (!e.cover.isNullOrBlank()) {
                AsyncImage(
                    model = e.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    e.title.take(2),
                    color = T.TextFaint,
                    fontSize = Fs.sec,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // 这条片看到哪儿了，用一截进度条说，比只写数字快
            if (e.durationSec > 0) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .height(3.dp).background(Color.Black.copy(alpha = .55f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(
                            (e.positionSec.toFloat() / e.durationSec).coerceIn(0f, 1f),
                        ).fillMaxHeight().background(T.Play500),
                    )
                }
            }
        }
        Spacer4()
        Text(
            e.title,
            color = T.Text,
            fontSize = Fs.xs,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer4()
        Text(
            e.where,
            // 这一行说的是「第几集 · 看到第几分钟」，是这张卡的重点信息，
            // 和 PosterCard 的副标题同一条规矩：承载信息的文字一律 TextDim，
            // TextFaint 在 T.Surface2 上只有 3.02:1，够不上正文地板
            color = T.TextDim,
            fontSize = Fs.tiny,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 7.dp),
        )
    }
}
