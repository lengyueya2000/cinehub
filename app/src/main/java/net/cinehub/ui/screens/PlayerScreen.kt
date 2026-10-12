package net.cinehub.ui.screens

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import net.cinehub.data.model.Health
import net.cinehub.data.model.SourceAvailability
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
 * 播放器。
 *
 * 这里是「应用重渲染」最直接的体现：画面由影枢自己的 ExoPlayer 渲染，
 * 源只是背后的一个数据来源。右侧竖排的切源按钮让用户随时换源，
 * 进度通过 ViewModel 跨源携带。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
) {
    val ctx = LocalContext.current
    val playing by vm.playing.collectAsState()
    val siblings by vm.siblings.collectAsState()
    val health by vm.health.collectAsState()
    val detail by vm.detail.collectAsState()
    val resumeFrom by vm.resumePosition.collectAsState()
    val resolving by vm.resolving.collectAsState()

    var pos by remember { mutableLongStateOf(resumeFrom) }
    var dur by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply { playWhenReady = true }
    }

    // 换源 / 换集时重建媒体源；用 resumeFrom 保证进度接续
    LaunchedEffect(playing?.streamUrl, playing?.episodeIndex, playing?.sourceId) {
        val url = playing?.streamUrl
        player.stop()
        player.clearMediaItems()
        if (url.isNullOrBlank()) return@LaunchedEffect

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(net.cinehub.data.net.Http.UA)
            .setAllowCrossProtocolRedirects(true)

        val item = MediaItem.fromUri(url)
        val source = if (url.contains(".m3u8", true)) {
            HlsMediaSource.Factory(http).createMediaSource(item)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http)
                .createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        if (resumeFrom > 0) player.seekTo(resumeFrom * 1000)
        player.play()
    }

    LaunchedEffect(player) {
        while (true) {
            pos = (player.currentPosition / 1000).coerceAtLeast(0)
            dur = (player.duration / 1000).coerceAtLeast(0)
            isPlaying = player.isPlaying
            vm.updatePosition(pos)
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val lines = detail?.lines.orEmpty()
    val episodes = lines.firstOrNull()?.episodes.orEmpty()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (playing?.streamUrl != null) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF0B1220), Color(0xFF05070C), Color.Black))
                    ),
                )
            }

            Box(
                Modifier.fillMaxWidth().height(70.dp).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .86f), Color.Transparent))
                )
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .95f)))
                )
            )

            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(Sp.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Spacer12()
                Column(Modifier.weight(1f)) {
                    Text(
                        "${playing?.mediaTitle ?: ""} · ${playing?.episodeName ?: ""}",
                        color = Color.White,
                        fontSize = Fs.sm,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "正在播放 · ${playing?.sourceName ?: ""}",
                        color = T.TextDim,
                        fontSize = Fs.tiny,
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f))
                        .clickable { vm.refreshHealth(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            // 渲染层标识 —— 强调画面由影枢接管
            Row(
                Modifier.padding(start = Sp.s4, top = 56.dp)
                    .clip(R.full).background(Color.Black.copy(alpha = .58f))
                    .border(1.dp, T.Src500.copy(alpha = .30f), R.full)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("◈", color = T.Src500, fontSize = Fs.xxs)
                Spacer4()
                Text(
                    "影枢渲染层 · 画面由本机解码",
                    color = T.Src500,
                    fontSize = Fs.tiny,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 直链拿不到时给出明确原因，而不是留一个黑屏
            if (playing?.streamUrl == null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(start = Sp.s7, end = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (resolving) "正在解析直链…" else "这个源拿不到直链",
                        color = Color.White,
                        fontSize = Fs.sec,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer8()
                    Text(
                        if (resolving) {
                            "已收到你的播放请求，正在向「${playing?.sourceName ?: ""}」取这一集的地址。"
                        } else {
                            playing?.note ?: "未知原因"
                        },
                        color = T.TextDim,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.7f,
                    )
                    if (!resolving) {
                        Spacer12()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "重试",
                                color = Color.White,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(R.full)
                                    .background(Color.White.copy(alpha = .14f))
                                    .clickable { vm.retryPlay() }
                                    .padding(horizontal = 16.dp, vertical = 7.dp),
                            )
                            Spacer8()
                            Text(
                                "用右侧的源切换 →",
                                color = T.Src500,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else if (!isPlaying) {
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .42f))
                        .border(1.5.dp, Color.White.copy(alpha = .30f), CircleShape)
                        .clickable { player.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, null,
                        tint = Color.White,
                        modifier = Modifier.size(27.dp),
                    )
                }
            }

            // 右侧竖排切源
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = Sp.s3),
                verticalArrangement = Arrangement.spacedBy(Sp.s2),
            ) {
                siblings.take(3).forEach { av ->
                    val on = av.sourceId == playing?.sourceId
                    val h = health[av.sourceId]?.health ?: av.health
                    Column(
                        Modifier.width(50.dp).clip(R.md)
                            .background(
                                if (on) T.Play500.copy(alpha = .26f)
                                else Color.Black.copy(alpha = .55f)
                            )
                            .border(
                                1.dp,
                                if (on) T.Play500.copy(alpha = .60f) else Color.White.copy(alpha = .14f),
                                R.md,
                            )
                            .clickable {
                                vm.switchPlayingSource(av, playing?.episodeIndex ?: 1)
                            }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            av.sourceName.take(3),
                            color = if (on) Color.White else T.TextDim,
                            fontSize = Fs.tiny,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when (h) {
                                Health.Ok -> "${av.latencyMs ?: "-"}ms"
                                Health.Slow -> "较慢"
                                Health.Down -> "不可用"
                                Health.Unknown -> "—"
                            },
                            color = if (on) Color.White.copy(alpha = .72f) else T.TextFaint,
                            fontSize = Fs.tiny,
                        )
                    }
                }
            }

            // 底部进度与控制
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = Sp.s4, vertical = Sp.s4),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(pos), color = T.TextOnArt, fontSize = Fs.xxs)
                    Spacer8()
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(R.full)
                            .background(Color.White.copy(alpha = .22f)),
                    ) {
                        if (dur > 0) {
                            Box(
                                Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(Brush.horizontalGradient(listOf(T.Play400, T.Play500))),
                            )
                        }
                    }
                    Spacer8()
                    Text(fmt(dur), color = T.TextOnArt, fontSize = Fs.xxs)
                }
                Spacer12()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val noStream = playing?.streamUrl == null
                    Text(
                        when {
                            resolving -> "◌ 解析中"
                            noStream -> "↻ 重新解析"
                            isPlaying -> "⏸ 暂停"
                            else -> "▶ 播放"
                        },
                        color = if (noStream) T.Src500 else Color(0xFFE2E8F0),
                        fontSize = Fs.xs,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            when {
                                resolving -> Unit
                                noStream -> vm.retryPlay()
                                isPlaying -> player.pause()
                                else -> player.play()
                            }
                        },
                    )
                    Box(Modifier.weight(1f))
                    Text(
                        "1.0x",
                        color = Color.White,
                        fontSize = Fs.xxs,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(R.sm)
                            .border(1.dp, Color.White.copy(alpha = .26f), R.sm)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    Spacer8()
                    Text(
                        "跨源续播",
                        color = T.Src500,
                        fontSize = Fs.xxs,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(R.sm)
                            .border(1.dp, T.Src500.copy(alpha = .5f), R.sm)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // 选集抽屉
        Column(
            Modifier.fillMaxWidth().height(180.dp).background(T.Surface)
                .padding(horizontal = Sp.s4, vertical = Sp.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选集 · ${playing?.mediaTitle ?: ""}",
                    color = T.Text,
                    fontSize = Fs.sm,
                    fontWeight = FontWeight.Bold,
                )
                Box(Modifier.weight(1f))
                StatusDot(health[playing?.sourceId]?.health ?: Health.Unknown)
                Spacer4()
                Text(
                    playing?.sourceName ?: "",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                )
            }
            Spacer12()
            if (episodes.isEmpty()) {
                Text(
                    "没有解析到分集列表。返回详情页可切换线路重试。",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    userScrollEnabled = true,
                ) {
                    items(episodes) { e ->
                        val on = e.index == playing?.episodeIndex
                        Box(
                            Modifier.height(32.dp).clip(R.sm)
                                .background(if (on) T.Play500.copy(alpha = .18f) else T.Surface2)
                                .border(
                                    1.dp,
                                    if (on) T.Play500.copy(alpha = .50f) else T.Border,
                                    R.sm,
                                )
                                .clickable {
                                    vm.play(
                                        sourceId = playing?.sourceId ?: return@clickable,
                                        mediaTitle = playing?.mediaTitle ?: "",
                                        episodeName = e.name,
                                        episodeIndex = e.index,
                                        episodeUrl = e.playUrl,
                                        resumeFrom = 0,
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                e.name.take(5),
                                color = if (on) T.Play400 else T.TextDim,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Spacer8()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("播放进度已跨源同步", color = T.TextFaint, fontSize = Fs.xxs)
                Box(Modifier.weight(1f))
                Text(
                    "切换源不丢进度",
                    color = T.Src500,
                    fontSize = Fs.xxs,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun fmt(sec: Long): String {
    if (sec <= 0) return "00:00"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
