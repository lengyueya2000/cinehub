package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 跨源搜索结果。
 *
 * 一部片只出一条，下面挂各源徽标 —— 不用点进去就能判断哪个源能播、画质如何。
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
    onBack: () -> Unit,
) {
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val history by vm.history.collectAsState()
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()

    val enabled = sources.filter { it.enabled }
    val hitSources = results.flatMap { it.availability }.map { it.sourceId }.distinct().size

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(38.dp).clip(R.full).background(T.Surface2)
                    .border(1.dp, T.Play500.copy(alpha = .42f), R.full).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = T.TextDim, modifier = Modifier.size(15.dp))
                Spacer8()
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索影片、演员", color = T.TextFaint, fontSize = Fs.sm)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = vm::onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.sm),
                        cursorBrush = SolidColor(T.Play400),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { vm.search() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer12()
            Text(
                "取消",
                color = T.TextDim,
                fontSize = Fs.sm,
                modifier = Modifier.clickable { vm.clearQuery(); onBack() },
            )
        }

        if (results.isNotEmpty()) {
            LazyRow(
                Modifier.padding(bottom = Sp.s3),
                contentPadding = PaddingValues(horizontal = Sp.s4),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item {
                    Text(
                        "全部 ${results.size}",
                        color = Color.White,
                        fontSize = Fs.sm,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(R.full).background(T.Play500.copy(alpha = .16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                items(listOf("电影", "剧集", "动漫", "综艺")) { c ->
                    Text(
                        c,
                        color = T.TextDim,
                        fontSize = Fs.sm,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(R.full).background(T.Surface2)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2),
        ) {
            if (results.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(R.md)
                            .background(T.Src500.copy(alpha = .055f))
                            .border(1.dp, T.Src500.copy(alpha = .20f), R.md)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(R.sm).background(T.Src500.copy(alpha = .14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("源", color = T.Src500, fontSize = Fs.xxs, fontWeight = FontWeight.Bold)
                        }
                        Spacer8()
                        Text(
                            "已同时检索 ${enabled.size} 个播放源，命中 ${results.size} 条，" +
                                "${hitSources} 个源有结果，重复条目已合并。",
                            color = T.TextDim,
                            fontSize = Fs.xxs,
                            lineHeight = Fs.xxs * 1.5f,
                        )
                    }
                    Spacer12()
                }
            }

            if (searching) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("正在并发查询 ${enabled.size} 个源…", color = T.TextDim, fontSize = Fs.sm)
                    }
                }
            }

            if (!searching && results.isEmpty()) {
                item {
                    Column {
                        if (history.isNotEmpty()) {
                            Text("搜索历史", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                            Spacer8()
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                                items(history) { h ->
                                    Text(
                                        h,
                                        color = T.TextDim,
                                        fontSize = Fs.xs,
                                        modifier = Modifier.clip(R.full).background(T.Surface2)
                                            .clickable { vm.search(h) }
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                            Spacer12()
                        }
                        Spacer8()
                        Text("当前接入的播放源", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                        Spacer8()
                        enabled.forEach { cfg ->
                            val h = health[cfg.id]?.health ?: Health.Unknown
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(h)
                                Spacer8()
                                Text(cfg.name, color = T.Text, fontSize = Fs.body2, modifier = Modifier.weight(1f))
                                Text(
                                    when (h) {
                                        Health.Ok -> "${health[cfg.id]?.latencyMs ?: "-"}ms"
                                        Health.Slow -> "较慢"
                                        Health.Down -> "不可用"
                                        Health.Unknown -> "未检测"
                                    },
                                    color = T.TextFaint,
                                    fontSize = Fs.xxs,
                                )
                            }
                        }
                    }
                }
            }

            items(results, key = { it.media.key }) { agg ->
                ResultRow(agg) { av -> onOpenDetail(av.sourceId, av.item.id) }
            }
        }
    }
}

private fun Modifier.flex(f: Float): Modifier = this

@Composable
private fun ResultRow(agg: AggregatedMedia, onClick: (net.cinehub.data.model.SourceAvailability) -> Unit) {
    val best = agg.availability.firstOrNull()
    Row(
        Modifier.fillMaxWidth()
            .clickable { best?.let(onClick) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(58.dp, 82.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                .background(T.Surface2)
                .border(1.dp, T.Border, androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
        ) {
            if (!agg.media.cover.isNullOrBlank()) {
                AsyncImage(
                    model = agg.media.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer12()
        Column(Modifier.weight(1f)) {
            Text(
                agg.media.title,
                color = T.Text,
                fontSize = Fs.body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer4()
            Text(
                listOfNotNull(
                    agg.media.year,
                    agg.media.category ?: agg.media.remark,
                    agg.media.area,
                ).joinToString(" · ").ifBlank { "—" },
                color = T.TextFaint,
                fontSize = Fs.xxs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer8()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(agg.availability) { av ->
                    val dim = av.health == Health.Down
                    Row(
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(
                                if (dim) Color.White.copy(alpha = .04f)
                                else T.Src500.copy(alpha = .09f)
                            )
                            .border(
                                1.dp,
                                if (dim) T.Border else T.Src500.copy(alpha = .30f),
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(av.health, 5)
                        Spacer4()
                        Text(
                            buildString {
                                append(av.sourceName)
                                av.quality?.let { append(" ").append(it) }
                                if (dim) append(" 不可用")
                            },
                            color = if (dim) T.TextFaint else T.Src500,
                            fontSize = Fs.tiny,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Spacer8()
        Column(horizontalAlignment = Alignment.End) {
            agg.availability.firstNotNullOfOrNull { it.quality }?.let { Pill(it, cyan = true) }
            Spacer8()
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(T.Surface2)
                    .border(1.dp, T.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = T.TextDim,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .055f)))
}
