package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import net.cinehub.data.model.HistoryEntry
import net.cinehub.data.model.Media
import net.cinehub.data.model.SourceConfig
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.SectionHead
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer16
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val home by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    val error by vm.homeError.collectAsState()
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()
    val history by vm.watchHistory.collectAsState()
    val homeAt by vm.homeAt.collectAsState()
    val homeSource by vm.homeSource.collectAsState()

    // 首页什么时候该重新拉：快照过满 12 小时，或者「取回这一页时那套源的顺序」变了。
    // 每次回到首页都会检查一次，但指纹没变时 loadHome 比完字符串就返回，不会去敲站点的门 ——
    // 用户要的不是「别检查」，是「别每次进来都重拉」。
    val homeFingerprint = sources.filter { it.enabled }.sortedBy { it.priority }
        .joinToString("|") { "${it.id}:${it.priority}" }
    LaunchedEffect(homeFingerprint) { vm.loadHome() }
    // 三排筛选都是本页的临时状态：退出首页就消失，不写进 ViewModel、不落盘。
    // 键在 home 上 —— 换了一页内容就是另一批条目，旧筛选不该继续作用在它上面。
    var cat by remember(home) { mutableStateOf<String?>(null) }
    var area by remember(home) { mutableStateOf<String?>(null) }
    var year by remember(home) { mutableStateOf<String?>(null) }

    val hero = home.firstOrNull()
    // 下面的列表从第二条起列（hero 已经是这一页的第一条），否则同一部片会在屏幕上出现两次。
    val rest = home.drop(1)
    val enabledSources = sources.count { it.enabled }

    // 芯片只列这一页里真出现过的值（写死一串名字就会造出点了没反应的假芯片）。
    // 一个可信的值都取不到时那一排不渲染，而不是留一排骗人的按钮。
    val catChips = remember(rest) { categoryChips(rest) }
    val areaList = remember(rest) { areaChips(rest) }
    val yearList = remember(rest) { yearChips(rest) }
    // 不给类型/地区/年份的条目在选中该条件时被排除 —— 这是筛选的定义，不是列表坏了，
    // 所以筛空了要专门说一句，并留一个清掉筛选的动作。
    val shown = rest.filter { m ->
        (cat == null || m.category?.trim() == cat) &&
            (area == null || m.area?.trim() == area) &&
            (year == null || m.year?.trim() == year)
    }
    // 三个条件是「且」的关系，所以「清除筛选」只要有一个挂着就该出现
    val activeFilters = listOfNotNull(cat?.let { "类型「$it」" }, area?.let { "地区「$it」" }, year?.let { "年份「$it」" })
    val clearFilters = { cat = null; area = null; year = null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Sp.s6),
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
                    Modifier.weight(1f).height(36.dp).clip(R.full).background(T.Surface2)
                        .clickable(onClick = onOpenSearch).padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = T.TextFaint, modifier = Modifier.size(14.dp))
                    Spacer8()
                    Text("搜索影片、演员、播放源", color = T.TextFaint, fontSize = Fs.sm)
                }
            }
        }

        if (loading && home.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(222.dp).padding(horizontal = Sp.s4), contentAlignment = Alignment.Center) {
                    Text("正在从 $enabledSources 个播放源获取内容…", color = T.TextDim, fontSize = Fs.sm)
                }
            }
        }

        // 刷新没成但旧内容还在屏上：这句必须说，否则用户会以为下面这一页就是刚取回来的。
        // 上面那块「没有可用的播放源」只在首页全空时出现，管不到这一种情况。
        if (error != null && home.isNotEmpty()) {
            item {
                Text(
                    error!!,
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
                        Text("没有可用的播放源", color = T.Err, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                        Spacer4()
                        Text(error!!, color = T.TextDim, fontSize = Fs.xs)
                        Spacer12()
                        Text(
                            "去播放源管理 →",
                            color = T.Src500,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onOpenSources),
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
                            hero.year, hero.category ?: hero.remark, hero.area,
                        ).joinToString(" · "),
                        sourceName = sources.firstOrNull { it.id == hero.sourceId }?.name ?: "",
                        sourceHealth = health[hero.sourceId]?.health,
                        onPlay = { onOpenDetail(hero.sourceId, hero.id) },
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
                        items(resume) { e ->
                            ResumeCard(e) { vm.resumeFromHistory(e) }
                        }
                    }
                }
            }
        }

        if (catChips.isNotEmpty()) {
            item {
                ChipRow("全部类型", catChips, cat) { c -> cat = if (cat == c) null else c }
            }
        }
        if (areaList.isNotEmpty()) {
            item {
                ChipRow("全部地区", areaList, area) { a -> area = if (area == a) null else a }
            }
        }
        if (yearList.isNotEmpty()) {
            item {
                ChipRow("全部年份", yearList, year) { y -> year = if (year == y) null else y }
            }
        }

        if (rest.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = Sp.s4)) {
                    SectionHead(
                        homeSectionTitle(rest, sources, homeSource),
                        trailing = if (activeFilters.isNotEmpty()) "清除筛选" else null,
                        onTrailing = if (activeFilters.isNotEmpty()) clearFilters else null,
                        modifier = Modifier.padding(top = Sp.s4, bottom = Sp.s1),
                    )
                    // 这一页是缓存下来的快照，不说清几点取回的就没人知道它有多新；
                    // 手动刷新是唯一的「现在就重取」入口（自动只在过 12 小时或源顺序变了时才发生）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (homeAt > 0L) {
                            Text(
                                "取回于 ${formatFetchedAt(homeAt)} · 12 小时内不重取",
                                color = T.TextFaint,
                                fontSize = Fs.tiny,
                            )
                        }
                        Box(Modifier.weight(1f))
                        Text(
                            if (loading) "正在取…" else "↻ 刷新这一页",
                            color = if (loading) T.TextFaint else T.Src500,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(R.full)
                                .clickable(enabled = !loading, onClick = vm::refreshHome)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer4()
                }
            }

            if (shown.isEmpty()) {
                // 芯片是从这份列表里现取的，正常筛不空；真筛空了只可能是内容刚被换掉。
                // 这时候要说的是「这个条件下没有」而不是「没有内容」，并给一个能点的退出口。
                item {
                    Column(Modifier.padding(horizontal = Sp.s4)) {
                        Text("${activeFilters.joinToString(" 且 ")}在这一页里没有内容", color = T.TextDim, fontSize = Fs.sm)
                        Spacer4()
                        Text(
                            "有些播放源不给条目返回类型、地区、年份信息，这类内容会被筛选排除掉。",
                            color = T.TextFaint,
                            fontSize = Fs.xs,
                        )
                        Spacer8()
                        Text(
                            "清除筛选",
                            color = T.Play400,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { clearFilters() },
                        )
                    }
                }
            }

            if (shown.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Sp.s4),
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        items(shown.take(12)) { m ->
                            PosterCard(
                                title = m.title,
                                subtitle = m.remark ?: m.category,
                                cover = m.cover,
                                badge = qualityTag(m.remark, m.title),
                                modifier = Modifier.width(112.dp),
                                onClick = { onOpenDetail(m.sourceId, m.id) },
                            )
                        }
                    }
                }
                // 这一页比 12 条长，剩下的换成网格接着列 —— 和上面那排是同一条列表的前后段，
                // 不重叠，所以不再另起一个「最近更新」标题（那两句都说的是同一批内容）。
                if (shown.size > 12) {
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxWidth().height(gridHeight(shown.size - 12))
                                .padding(top = Sp.s4),
                            contentPadding = PaddingValues(horizontal = Sp.s4),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            verticalArrangement = Arrangement.spacedBy(Sp.s4),
                            userScrollEnabled = false,
                        ) {
                            items(shown.drop(12)) { m ->
                                PosterCard(
                                    title = m.title,
                                    subtitle = m.remark ?: m.category,
                                    cover = m.cover,
                                    badge = qualityTag(m.remark, m.title),
                                    onClick = { onOpenDetail(m.sourceId, m.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 首页列表的栏目标题：只说这份数据撑得起的话。
 *
 * 仓库给的是「第一个能取到内容的源」返回的那一页，顺序就是源自己列的顺序 ——
 * 既没有热度也没有更新时间，所以「热播榜」「最近更新」两个标题都在承诺没做过的排序。
 * 这里只陈述真是如此的事：这些条目出自哪个源的这一页。
 *
 * [snapshotSource] 是取回这一页时记下的源 id。优先用它而不是从条目现推 ——
 * 条目上的 sourceId 是它们各自的出身，快照换过源以后两者会不一致，那时要说的是这一页出自谁。
 */
private fun homeSectionTitle(items: List<Media>, sources: List<SourceConfig>, snapshotSource: String?): String {
    val id = snapshotSource ?: items.map { it.sourceId }.distinct().singleOrNull()
    val name = id?.let { i -> sources.firstOrNull { it.id == i }?.name }
    return if (name != null) "均来自「$name」· 这一页" else "这一页给的"
}

/**
 * 快照取回的时刻，只到分钟。
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
 * 从这一页里现取可信的类型芯片（类型名 + 命中条数）。
 *
 * 芯片写的必须是数据里真有的东西：点了就能改列表，数量也一并标出来，
 * 免得「点了以后什么都不剩」被当成坏了。
 */
private fun categoryChips(items: List<Media>): List<Pair<String, Int>> =
    chipCount(items) { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }

/** 地区芯片。播放源常把空值写成 "0" 或纯数字，这类当没有处理，不做成芯片。 */
private fun areaChips(items: List<Media>): List<Pair<String, Int>> =
    chipCount(items) {
        it.area?.trim()?.takeIf { a -> a.isNotEmpty() && a.any { c -> !c.isDigit() && !c.isWhitespace() } }
    }

/**
 * 年份芯片，按年份从新到旧排。
 *
 * 只认 1900–2100 的四位数字：源里混着 "0"、"更新至第5集" 这类脏值，
 * 让它们进芯片就等于一排点了没反应的按钮。
 */
private fun yearChips(items: List<Media>): List<Pair<String, Int>> =
    chipCount(items) {
        it.year?.trim()?.takeIf { y -> y.length == 4 && y.all { c -> c.isDigit() } && y.toInt() in 1900..2100 }
    }.sortedByDescending { it.first }

private fun chipCount(items: List<Media>, pick: (Media) -> String?): List<Pair<String, Int>> =
    items.mapNotNull(pick).groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

/** 一排筛选芯片：首格是「全部X」，其余每格带命中条数；再点已选中的那格等于取消。 */
@Composable
private fun ChipRow(
    allLabel: String,
    chips: List<Pair<String, Int>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        Modifier.padding(top = Sp.s2),
        contentPadding = PaddingValues(horizontal = Sp.s4),
        horizontalArrangement = Arrangement.spacedBy(Sp.s2),
    ) {
        item {
            CatChip(allLabel, on = selected == null) { onSelect(null) }
        }
        items(chips) { (name, count) ->
            CatChip("$name $count", on = selected == name) {
                onSelect(if (selected == name) null else name)
            }
        }
    }
}

/** 类型芯片。选中态靠底色 + 文字双重表达，不单靠颜色。 */
@Composable
private fun CatChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.White else T.TextDim,
        fontSize = Fs.sm,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(R.full)
            .background(if (on) T.Play500.copy(alpha = .16f) else T.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun gridHeight(n: Int): androidx.compose.ui.unit.Dp {
    val rows = (n + 2) / 3
    // 112dp 宽的 2:3 海报 ≈ 168dp 高 + 文本 34dp + 行距 16dp
    return (rows * 218).dp
}

private fun qualityTag(remark: String?, title: String): String? {
    val t = "${remark ?: ""} ${title}"
    return when {
        Regex("4k", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "4K"
        Regex("hdr", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "HDR"
        Regex("1080").containsMatchIn(t) -> "1080P"
        else -> null
    }
}

@Composable
private fun HeroCard(
    title: String,
    cover: String?,
    meta: String,
    sourceName: String,
    sourceHealth: net.cinehub.data.model.Health?,
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
        if (sourceName.isNotBlank()) {
            Row(
                Modifier.align(Alignment.TopStart).padding(Sp.s3)
                    .clip(R.full)
                    .background(Color.Black.copy(alpha = .62f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sourceHealth != null) {
                    StatusDot(sourceHealth)
                    Spacer4()
                }
                Text(
                    "$sourceName · 已就绪",
                    color = T.Src500,
                    fontSize = Fs.xxs,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
                    Modifier
                        .height(34.dp)
                        .clip(R.full)
                        .background(Brush.verticalGradient(listOf(T.Play400, T.Play500)))
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer4()
                    Text("立即播放", color = Color.White, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
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
            color = T.TextFaint,
            fontSize = Fs.tiny,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 7.dp),
        )
    }
}
