package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
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

private val CATEGORIES = listOf("推荐", "电影", "剧集", "动漫", "综艺", "短剧")

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
    var cat by remember { mutableIntStateOf(0) }

    val hero = home.firstOrNull()
    val rest = home.drop(1)
    val enabledSources = sources.count { it.enabled }

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

        item {
            LazyRow(
                Modifier.padding(top = Sp.s4),
                contentPadding = PaddingValues(horizontal = Sp.s4),
                horizontalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                items(CATEGORIES.size) { i ->
                    val on = i == cat
                    Text(
                        CATEGORIES[i],
                        color = if (on) Color.White else T.TextDim,
                        fontSize = Fs.sm,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(R.full)
                            .background(if (on) T.Play500.copy(alpha = .16f) else T.Surface2)
                            .clickable { cat = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (rest.isNotEmpty()) {
            item {
                SectionHead(
                    "热播榜",
                    trailing = "全部",
                    modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s4),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    itemsIndexed(rest.take(12)) { i, m ->
                        PosterCard(
                            title = m.title,
                            subtitle = m.remark ?: m.category,
                            cover = m.cover,
                            rank = if (i < 9) i + 1 else null,
                            badge = qualityTag(m.remark, m.title),
                            modifier = Modifier.width(112.dp),
                            onClick = { onOpenDetail(m.sourceId, m.id) },
                        )
                    }
                }
            }
        }

        if (rest.size > 6) {
            item {
                SectionHead(
                    "最近更新",
                    modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s4),
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(gridHeight(rest.size - 6)),
                    contentPadding = PaddingValues(horizontal = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(Sp.s4),
                    userScrollEnabled = false,
                ) {
                    items(rest.drop(6)) { m ->
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
