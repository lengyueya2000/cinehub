package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.Media
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 片库：聚合所有启用源的最新内容（与首页同源，但按网格铺满、不挑 hero）。
 *
 * 之前「片库」只是底部 Tab，点了没有任何去处 —— 这里把它落成一个真实页面。
 */
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
) {
    val home by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    val error by vm.homeError.collectAsState()
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()

    val enabledSources = sources.count { it.enabled }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s3),
        ) {
            Text("片库", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
            Spacer4()
            Text(
                "来自 $enabledSources 个启用源的最新内容",
                color = T.TextFaint,
                fontSize = Fs.xxs,
            )
        }

        if (loading && home.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("正在从 $enabledSources 个播放源获取内容…", color = T.TextDim, fontSize = Fs.sm)
            }
        } else if (error != null && home.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(Sp.s4), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(error!!, color = T.Err, fontSize = Fs.sm)
            }
        } else if (home.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂时没有可展示的内容，换个源试试", color = T.TextDim, fontSize = Fs.sm)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = Sp.s4),
                contentPadding = PaddingValues(bottom = Sp.s6),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalArrangement = Arrangement.spacedBy(Sp.s4),
            ) {
                items(home) { m ->
                    PosterCard(
                        title = m.title,
                        subtitle = m.remark ?: m.category,
                        cover = m.cover,
                        badge = qualityBadge(m),
                        onClick = { onOpenDetail(m.sourceId, m.id) },
                    )
                }
            }
        }
    }
}

private fun qualityBadge(m: Media): String? {
    val t = "${m.remark ?: ""} ${m.title}"
    return when {
        Regex("4k", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "4K"
        Regex("hdr", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "HDR"
        Regex("1080").containsMatchIn(t) -> "1080P"
        else -> null
    }
}

/**
 * 下载：占位页。
 *
 * 设计稿里有这一 Tab，但应用当前不下载、不缓存任何视频（只做本机解码），
 * 所以这里如实说清楚，不装一个点了没反应的假入口。
 */
@Composable
fun DownloadsScreen() {
    Column(Modifier.fillMaxSize().background(T.Bg)) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s3),
        ) {
            Text("下载", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.fillMaxSize().padding(Sp.s4),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("本版本不提供下载", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                Spacer8()
                Text(
                    "影枢只在本地解码播放，不落地任何视频文件。\n这个入口先留着，之后的版本再补离线缓存。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.6f,
                )
            }
        }
    }
}
