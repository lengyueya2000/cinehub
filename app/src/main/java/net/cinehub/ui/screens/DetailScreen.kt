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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Health
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceAvailability
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

@Composable
fun DetailScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onPlay: (sourceId: String, title: String, ep: Episode) -> Unit,
) {
    val detail by vm.detail.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val error by vm.detailError.collectAsState()
    val siblings by vm.siblings.collectAsState()
    val activeSourceId by vm.activeSourceId.collectAsState()
    val health by vm.health.collectAsState()

    var lineIdx by remember { mutableIntStateOf(0) }
    var epIdx by remember { mutableIntStateOf(0) }
    var fav by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Backdrop(d, fav, onBack) { fav = !fav } }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s4),
                    horizontalArrangement = Arrangement.spacedBy(Sp.s2),
                ) {
                    ActionTile("已收藏", fav, Modifier.weight(1f)) { fav = !fav }
                    ActionTile("缓存", false, Modifier.weight(1f)) {}
                    ActionTile("分享", false, Modifier.weight(1f)) {}
                    ActionTile("更多", false, Modifier.weight(1f)) {}
                }
            }

            // 播放源 —— 页面视觉重心，放在选集之上
            item {
                Box(Modifier.padding(horizontal = Sp.s4)) {
                    SourcePanel(
                        activeSourceId = activeSourceId,
                        siblings = siblings,
                        health = health,
                        onRescan = { vm.refreshHealth(true) },
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
                    if (lines.size > 1) {
                        Text(
                            "线路 ${lineIdx + 1}/${lines.size}",
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
                            Box(
                                Modifier.height(38.dp).clip(R.sm)
                                    .background(
                                        if (on) Brush.verticalGradient(listOf(T.Play400, T.Play500))
                                        else Brush.verticalGradient(listOf(T.Surface2, T.Surface2))
                                    )
                                    .border(1.dp, if (on) Color.Transparent else T.Border, R.sm)
                                    .clickable { epIdx = i },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    e.name.take(6),
                                    color = if (on) Color.White else T.TextDim,
                                    fontSize = Fs.xs,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(Sp.s4)) {
                    Text("简介", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                    Spacer8()
                    Text(
                        d.intro ?: d.media.intro ?: "暂无简介",
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
            Box(
                Modifier.size(46.dp).clip(R.md).background(T.Surface2)
                    .border(1.dp, T.Border, R.md),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Download, null, tint = T.TextDim, modifier = Modifier.size(20.dp))
            }
            Spacer12()
            Row(
                Modifier.weight(1f).height(46.dp).clip(R.md)
                    .background(Brush.verticalGradient(listOf(T.Play400, T.Play500)))
                    .clickable {
                        val sid = activeSourceId ?: d.media.sourceId
                        curEp?.let { onPlay(sid, d.media.title, it) }
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer8()
                Text(
                    "播放 ${curEp?.name ?: "第 1 集"}",
                    color = Color.White,
                    fontSize = Fs.body,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Backdrop(d: MediaDetail, fav: Boolean, onBack: () -> Unit, onFav: () -> Unit) {
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
                CircleIcon(Icons.Filled.MoreVert, {})
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
                    Pill("4K HDR", cyan = true)
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
private fun ActionTile(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(60.dp).clip(R.md)
            .background(if (active) T.Play500.copy(alpha = .09f) else T.Surface2)
            .border(1.dp, if (active) T.Play500.copy(alpha = .32f) else T.Border, R.md)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            when (label) {
                "已收藏" -> Icons.Filled.Favorite
                "缓存" -> Icons.Outlined.Download
                "分享" -> Icons.Outlined.Share
                else -> Icons.Filled.MoreVert
            },
            null,
            tint = if (active) T.Play400 else T.TextDim,
            modifier = Modifier.size(19.dp),
        )
        Spacer4()
        Text(
            label,
            color = if (active) T.Play400 else T.TextDim,
            fontSize = Fs.xxs,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 播放源面板：横排源卡片，带健康状态与延迟。 */
@Composable
private fun SourcePanel(
    activeSourceId: String?,
    siblings: List<SourceAvailability>,
    health: Map<String, net.cinehub.data.model.HealthReport>,
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
            Row(
                Modifier.clickable(onClick = onRescan),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, null, tint = T.Src500, modifier = Modifier.size(12.dp))
                Spacer4()
                Text("重新检测", color = T.Src500, fontSize = Fs.xxs, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer12()
        if (siblings.isEmpty()) {
            Text(
                "正在比对该片在其它源的情况…",
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
    }
}
