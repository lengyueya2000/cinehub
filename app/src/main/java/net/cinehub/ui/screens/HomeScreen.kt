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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
    // 选中的类型是本页的临时状态：退出首页就消失，不写进 ViewModel、不落盘。
    // 键在 home 上 —— 换了一页内容就是另一批条目，旧筛选不该继续作用在它上面。
    var cat by remember(home) { mutableStateOf<String?>(null) }

    val hero = home.firstOrNull()
    // 下面的列表从第二条起列（hero 已经是这一页的第一条），否则同一部片会在屏幕上出现两次。
    val rest = home.drop(1)
    val enabledSources = sources.count { it.enabled }

    // 芯片只列这一页里真出现过的类型（写死一串名字就会造出点了没反应的假芯片）。
    // 一个可信的类型都取不到时整排不渲染，而不是留一排骗人的按钮。
    val chips = remember(rest) { categoryChips(rest) }
    // 不给类型的条目在选中某类型时被排除 —— 这是筛选的定义，不是列表坏了，
    // 所以筛空了要专门说一句，并留一个清掉筛选的动作。
    val shown = if (cat == null) rest else rest.filter { it.category?.trim() == cat }

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
                Spacer8()
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(T.Surface2)
                        .clickable(onClick = onOpenSources),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, null, tint = T.TextDim, modifier = Modifier.size(17.dp))
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

        if (chips.isNotEmpty()) {
            item {
                LazyRow(
                    Modifier.padding(top = Sp.s4),
                    contentPadding = PaddingValues(horizontal = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(Sp.s2),
                ) {
                    item {
                        CatChip("全部", on = cat == null) { cat = null }
                    }
                    items(chips) { (name, count) ->
                        // 再点一次已选中的芯片等于取消，回到不过滤的原样
                        CatChip("$name $count", on = cat == name) {
                            cat = if (cat == name) null else name
                        }
                    }
                }
            }
        }

        if (rest.isNotEmpty()) {
            item {
                SectionHead(
                    homeSectionTitle(rest, sources),
                    trailing = if (cat != null) "清除筛选" else null,
                    onTrailing = if (cat != null) ({ cat = null }) else null,
                    modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s4),
                )
            }

            if (shown.isEmpty()) {
                // 芯片是从这份列表里现取的，正常筛不空；真筛空了只可能是内容刚被换掉。
                // 这时候要说的是「这个条件下没有」而不是「没有内容」，并给一个能点的退出口。
                item {
                    Column(Modifier.padding(horizontal = Sp.s4)) {
                        Text("「$cat」在这一页里没有内容", color = T.TextDim, fontSize = Fs.sm)
                        Spacer4()
                        Text(
                            "有些播放源不给条目返回类型信息，这类内容会被类型筛选排除掉。",
                            color = T.TextFaint,
                            fontSize = Fs.xs,
                        )
                        Spacer8()
                        Text(
                            "清除筛选",
                            color = T.Play400,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { cat = null },
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
 * 仓库给的 home() 是「第一个能取到内容的源」返回的那一页，顺序就是源自己列的顺序 ——
 * 既没有热度也没有更新时间，所以「热播榜」「最近更新」两个标题都在承诺没做过的排序。
 * 这里只陈述真是如此的事：这些条目出自哪个源的这一页。
 */
private fun homeSectionTitle(items: List<Media>, sources: List<SourceConfig>): String {
    val ids = items.map { it.sourceId }.distinct()
    // 万一将来首页改成多源拼接，这句「均来自」就不成立了，退回一句不会错的话
    val name = if (ids.size == 1) sources.firstOrNull { it.id == ids.first() }?.name else null
    return if (name != null) "均来自「$name」· 这一页" else "这一页给的"
}

/**
 * 从这一页里现取可信的类型芯片（类型名 + 命中条数）。
 *
 * 芯片写的必须是数据里真有的东西：点了就能改列表，数量也一并标出来，
 * 免得「点了以后什么都不剩」被当成坏了。
 */
private fun categoryChips(items: List<Media>): List<Pair<String, Int>> =
    items.mapNotNull { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

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
