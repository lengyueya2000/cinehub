package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.DownloadTask
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
 * 下载：本机已缓存的任务。
 *
 * 文件落在应用专属目录，卸载即清；这里只列真实存在的任务，
 * 失败原因照抄解析/下载那一步给出的话，不粉饰成「请稍后重试」。
 */
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val tasks by vm.downloads.collectAsState()

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s3)) {
            Column {
                Text("下载", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    if (tasks.isEmpty()) "还没有缓存任何内容"
                    else "${tasks.count { it.status == DownloadStatus.Done }} / ${tasks.size} 完成 · 共 ${fmtBytes(vm.downloadSpace())}",
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
