package net.cinehub.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Health
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T
import kotlin.math.abs
import kotlin.math.roundToInt

/** 播放器右侧浮层的内容。 */
private enum class Panel { Episodes, Line, Quality, Speed }

/** 横滑划满一屏对应的跳转量。 */
private const val SCRUB_SPAN_MS = 120_000L

/**
 * 播放器。
 *
 * 这里是「应用重渲染」最直接的体现：画面由影枢自己的 ExoPlayer 渲染，
 * 源只是背后的一个数据来源，进度通过 ViewModel 跨源携带。
 *
 * 交互分两层：底下是一整块手势层（拖进度、双击快进退、竖滑音量/亮度、单击显隐），
 * 上面是会自动隐藏的控件。全屏时只留这两层 —— 渲染层徽标、右侧切源栏、选集抽屉
 * 都让位给画面，否则用户看到的是界面而不是片子。
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
    /** 用户意图，不是 player.isPlaying —— 后者在 HLS seek/buffer 期间会短暂为 false，按钮会闪成「已暂停」。 */
    var wantsPlay by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var errText by remember { mutableStateOf<String?>(null) }

    // 点播放就进全屏，不用用户再找按钮
    var fullscreen by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var lastUi by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var lineIndex by remember { mutableIntStateOf(0) }
    /** 0 = 交给 ExoPlayer 自适应；否则是选定的视频高度。 */
    var maxVideoHeight by remember { mutableIntStateOf(0) }
    var videoHeights by remember { mutableStateOf(listOf<Int>()) }
    /** 手势反馈的浮动提示，null 表示不显示。 */
    var hint by remember { mutableStateOf<String?>(null) }
    /** 横向拖动时的目标位置（秒），非空表示正在拖、先预览不真的 seek。 */
    var previewSec by remember { mutableStateOf<Long?>(null) }
    val shownPos = previewSec ?: pos

    val activity = ctx.activity()
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // NaN = 用户还没碰过亮度，一次都不会去改窗口亮度
    var brightness by remember { mutableFloatStateOf(Float.NaN) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // BUFFERING / READY / ENDED —— 首帧渲染前 UI 要说清「在缓冲」，
                    // 否则用户看到的就是一个不动的黑屏，像是点了没反应。
                    buffering = state == Player.STATE_BUFFERING
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    // 自动暂停（缓冲上限、失去音频焦点）也会走到这里，比在点击回调里手动翻状态可靠
                    wantsPlay = playWhenReady
                }

                override fun onPlayerError(e: PlaybackException) {
                    errText = "播放失败：${e.message ?: e.cause?.message ?: "未知错误"}"
                }
            })
        }
    }

    // 换源 / 换集时重建媒体源；用 resumeFrom 保证进度接续
    LaunchedEffect(playing?.streamUrl, playing?.episodeIndex, playing?.sourceId) {
        val url = playing?.streamUrl
        player.stop()
        player.clearMediaItems()
        errText = null
        buffering = true
        if (url.isNullOrBlank()) return@LaunchedEffect

        // 站点的 CDN 会校验 Referer/Origin，段地址常常只在带 Referer 时才给内容。
        // 从站点页面接住的地址优先按它被请求时那套条件发 —— 再叠一层源站根地址的
        // Referer/Origin 反而会把对方绕糊涂（解析页只认自己那一页）。
        val captured = playing?.streamHeaders.orEmpty()
        val props = buildMap {
            putAll(captured)
            if (!captured.containsKey("Referer")) {
                val origin = playing?.sourceBaseUrl
                if (!origin.isNullOrBlank()) {
                    put("Referer", "$origin/")
                    put("Origin", origin)
                }
            }
        }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(net.cinehub.data.net.Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(props)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        // 缓存好的片子是 file:// 地址，只挂 HTTP 数据源会读不到 —— 按 scheme 分派一层
        val data = androidx.media3.datasource.DefaultDataSource.Factory(
            ctx.applicationContext,
            http,
        )

        val item = MediaItem.fromUri(url)
        val source = if (url.contains(".m3u8", true)) {
            // chunkless：不必先把整份 1 小时的分段列表拉完才起播
            HlsMediaSource.Factory(data)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(data)
                .createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        if (resumeFrom > 0) player.seekTo(resumeFrom * 1000)
        player.play()
    }

    // 轮询播放位置；顺带把这一条流里真实存在的视频高度取出来给「画质」用 ——
    // 只有 Media3 解出 master playlist 之后才知道有几档，不能凭空列。
    LaunchedEffect(player) {
        while (true) {
            pos = (player.currentPosition / 1000).coerceAtLeast(0)
            dur = (player.duration / 1000).coerceAtLeast(0)
            wantsPlay = player.playWhenReady
            vm.updatePosition(pos, dur)
            val hs: List<Int> = runCatching {
                player.currentTracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i).height } }
                    .filter { it > 0 }
            }.getOrDefault(emptyList()).distinct().sortedDescending()
            if (hs.isNotEmpty() && hs != videoHeights) videoHeights = hs
            delay(500)
        }
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(maxVideoHeight) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (maxVideoHeight > 0) maxVideoHeight else Int.MAX_VALUE)
            .build()
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1000)
            hint = null
        }
    }

    // 控件在播放 3.5 秒后自己收起来；浮层开着时不收，否则选到一半界面就没了。
    // 出错 / 拿不到直链时更不能收 —— 收了就没有返回按钮，用户只能强杀应用。
    val stuck = errText != null || playing?.streamUrl == null
    LaunchedEffect(controls, lastUi, wantsPlay, panel, stuck) {
        if (stuck) {
            controls = true
            return@LaunchedEffect
        }
        if (controls && wantsPlay && panel == null) {
            delay(3500)
            controls = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val insetsController = remember(activity) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }

    // 全屏：横屏 + 隐藏系统栏 + 常亮。退出时逐项还原，不能只还原一半 ——
    // 否则从播放器返回后状态栏一直不回来，看起来像应用卡死了。
    LaunchedEffect(fullscreen) {
        val a = activity ?: return@LaunchedEffect
        if (fullscreen) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController?.apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // 回到竖屏用 PORTRAIT 而不是 UNSPECIFIED：模拟器上自动旋转关着时，
            // UNSPECIFIED 会留在当前横屏不动，退出全屏看起来像没生效。
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 亮度交还给系统，否则退出播放器后整个手机都跟着变暗
            restoreSystemBrightness(a)
        }
    }
    // 离开播放器时必须还原，否则返回后停留在横屏 + 无状态栏
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.let { restoreSystemBrightness(it) }
        }
    }

    val lines = detail?.lines.orEmpty()
    val safeLine = lineIndex.coerceAtMost((lines.size - 1).coerceAtLeast(0))
    val episodes = lines.getOrNull(safeLine)?.episodes.orEmpty()

    // 缓存状态跟着「正在播的这一集」走，本地回放时不显示（它本来就是本机文件）
    val downloads by vm.downloads.collectAsState()
    val cacheTask = playing?.mediaId?.let { mid ->
        val p = playing
        if (p == null || p.sourceId == "local") null
        else downloads.firstOrNull {
            it.sourceId == p.sourceId && it.mediaId == mid && it.episodeIndex == p.episodeIndex
        }
    }
    val cacheLabel = when (cacheTask?.status) {
        DownloadStatus.Done -> "已缓存"
        DownloadStatus.Running -> "缓存 ${(cacheTask.progress * 100).toInt()}%"
        DownloadStatus.Queued -> "排队中"
        DownloadStatus.Paused -> "继续缓存"
        DownloadStatus.Failed -> "缓存失败"
        else -> "缓存"
    }

    fun playEpisode(e: Episode, keepPos: Long = 0) {
        val p = playing ?: return
        vm.play(
            sourceId = p.sourceId,
            mediaTitle = p.mediaTitle,
            episodeName = e.name,
            episodeIndex = e.index,
            episodeUrl = e.playUrl,
            resumeFrom = keepPos,
            mediaId = p.mediaId,
        )
    }

    fun stepEpisode(delta: Int) {
        val i = episodes.indexOfFirst { it.index == playing?.episodeIndex }
        val next = episodes.getOrNull((i + delta).coerceAtLeast(0)) ?: return
        playEpisode(next)
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0, (player.duration.coerceAtLeast(0)))
        player.seekTo(target)
        hint = if (deltaMs > 0) "快进 ${deltaMs / 1000} 秒" else "快退 ${-deltaMs / 1000} 秒"
        controls = true; lastUi = System.currentTimeMillis()
    }

    // 系统返回键：浮层开着先关浮层，其次退全屏，最后离开播放器
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = fullscreen && panel == null) { fullscreen = false }

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

            if (!fullscreen || controls) {
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
            }

            // ---------- 手势层：在控件之下，吃掉画面区的拖动与点击 ----------
            Box(
                Modifier
                    .fillMaxSize()
                    .playerGestures(
                        currentPosition = { player.currentPosition.coerceAtLeast(0) },
                        durationMs = { player.duration.coerceAtLeast(0) },
                        currentVolume = {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                        },
                        currentBrightness = { if (brightness.isNaN()) 0.5f else brightness },
                        onScrubPreview = { ms ->
                            previewSec = ms / 1000
                            hint = "${fmt(ms / 1000)} / ${fmt(dur)}"
                        },
                        onScrubCommit = { ms ->
                            player.seekTo(ms)
                            previewSec = null
                            controls = true
                            lastUi = System.currentTimeMillis()
                        },
                        onVolume = { frac ->
                            setVolume(audio, frac)
                            hint = "音量 ${(frac * 100).roundToInt()}%"
                        },
                        onBrightness = { frac ->
                            brightness = frac
                            hint = "亮度 ${(frac * 100).roundToInt()}%"
                        },
                        onDoubleTapSeek = { seekBy(if (it > 0) 10_000 else -10_000) },
                        onToggleControls = {
                            controls = !controls
                            lastUi = System.currentTimeMillis()
                        },
                    ),
            )

            LaunchedEffect(brightness) {
                if (!brightness.isNaN()) activity?.let { applyBrightness(it, brightness) }
            }

            // ---------- 顶栏 ----------
            if (!fullscreen || controls) {
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
                    Spacer8()
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
                        // 渲染层标识挂在标题下面而不是另起一层：
                        // 之前它按固定 top 定位，退出全屏时正好压在「正在播放」那一行上。
                        if (!fullscreen) {
                            Spacer4()
                            Row(
                                Modifier.clip(R.full).background(Color.Black.copy(alpha = .58f))
                                    .border(1.dp, T.Src500.copy(alpha = .30f), R.full)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("◈", color = T.Src500, fontSize = Fs.tiny)
                                Spacer4()
                                Text(
                                    "影枢渲染层 · 画面由本机解码",
                                    color = T.Src500,
                                    fontSize = Fs.tiny,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    if (!fullscreen) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = .42f))
                                .clickable { vm.refreshHealth(true) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Refresh, null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
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
                        Spacer8()
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
                            // 地址在对方脚本手里时，唯一不越界的路是让站点自己的页面把它算出来。
                            // 源站配了解析端，打开的就是那个端而不是平台页 —— 后者只会弹「APP内打开」
                            if (playing?.sourceId != "local" && !playing?.sitePageUrl.isNullOrBlank()) {
                                Spacer8()
                                Text(
                                    "在站点页面里解析",
                                    color = T.Src400,
                                    fontSize = Fs.sm,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(R.full)
                                        .border(1.dp, T.Src500.copy(alpha = .5f), R.full)
                                        .clickable {
                                            vm.openSiteBrowser(
                                                playing?.sitePageUrl ?: return@clickable,
                                                playing?.sourceName ?: "站点页面",
                                                playing?.sourceBaseUrl?.let { "$it/" },
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                            Spacer8()
                            Text(
                                if (fullscreen) "退出全屏后可切换右侧的源" else "用右侧的源切换 →",
                                color = T.Src500,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(R.full)
                                    .clickable { if (fullscreen) fullscreen = false else Unit }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            } else if (errText != null) {
                // 直链是通的、但解码/拉取失败时，把原因说出来而不是留一个黑屏
                Column(
                    Modifier.align(Alignment.Center).padding(start = Sp.s7, end = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("播放失败", color = Color.White, fontSize = Fs.sec, fontWeight = FontWeight.Bold)
                    Spacer8()
                    Text(
                        errText ?: "",
                        color = T.TextDim,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.7f,
                    )
                    Spacer8()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "重试",
                            color = Color.White,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(R.full)
                                .background(Color.White.copy(alpha = .14f))
                                .clickable {
                                    errText = null
                                    player.prepare()
                                    player.play()
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer8()
                        Text(
                            "或用其它源切换 →",
                            color = T.Src500,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(R.full)
                                .clickable { if (fullscreen) fullscreen = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            } else if (!wantsPlay && !buffering) {
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .42f))
                        .border(1.5.dp, Color.White.copy(alpha = .30f), CircleShape)
                        .clickable { player.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(27.dp))
                }
            } else if (buffering && playing?.streamUrl != null) {
                // 只有「点了播放但还没出首帧」才提示缓冲
                Text(
                    "缓冲中…",
                    color = Color.White,
                    fontSize = Fs.xs,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 手势反馈
            hint?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = Fs.sm,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                        .clip(R.full).background(Color.Black.copy(alpha = .55f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }

            // ---------- 右侧竖排切源：全屏时让位 ----------
            if (!fullscreen) {
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
            }

            // ---------- 底部控件 ----------
            if (!fullscreen || controls) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Sp.s4, vertical = Sp.s4),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(shownPos), color = T.TextOnArt, fontSize = Fs.xxs)
                        Spacer8()
                        SeekBar(
                            positionSec = shownPos,
                            durationSec = dur,
                            modifier = Modifier.weight(1f),
                            onScrub = { sec -> previewSec = sec },
                            onCommit = { sec ->
                                player.seekTo(sec * 1000)
                                pos = sec
                                previewSec = null
                                controls = true; lastUi = System.currentTimeMillis()
                            },
                        )
                        Spacer8()
                        Text(fmt(dur), color = T.TextOnArt, fontSize = Fs.xxs)
                    }
                    Spacer8()
                    if (fullscreen) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBtn(Icons.Filled.SkipPrevious) { stepEpisode(-1) }
                                IconBtn(
                                    if (wantsPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                ) { if (wantsPlay) player.pause() else player.play() }
                                IconBtn(Icons.Filled.SkipNext) { stepEpisode(1) }
                                IconBtn(Icons.Filled.Replay10) { seekBy(-10_000) }
                                IconBtn(Icons.Filled.Forward10) { seekBy(10_000) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Chip("选集") { panel = Panel.Episodes }
                                Spacer8()
                                Chip("倍速 ${fmtSpeed(speed)}") { panel = Panel.Speed }
                                Spacer8()
                                if (lines.size > 1) {
                                    Chip("线路 ${safeLine + 1}") { panel = Panel.Line }
                                    Spacer8()
                                }
                                if (videoHeights.isNotEmpty()) {
                                    Chip(if (maxVideoHeight == 0) "画质 自动" else "${maxVideoHeight}P") {
                                        panel = Panel.Quality
                                    }
                                    Spacer8()
                                }
                                if (playing?.mediaId != null && playing?.sourceId != "local") {
                                    Chip(cacheLabel) { vm.cachePlaying() }
                                    Spacer8()
                                }
                                Icon(
                                    Icons.Filled.FullscreenExit, null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp).clickable { fullscreen = false },
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val noStream = playing?.streamUrl == null
                            Text(
                                when {
                                    resolving -> "◌ 解析中"
                                    noStream -> "↻ 重新解析"
                                    wantsPlay -> "⏸ 暂停"
                                    else -> "▶ 播放"
                                },
                                color = if (noStream) T.Src500 else Color(0xFFE2E8F0),
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    when {
                                        resolving -> Unit
                                        noStream -> vm.retryPlay()
                                        wantsPlay -> player.pause()
                                        else -> player.play()
                                    }
                                },
                            )
                            Box(Modifier.weight(1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(R.full)
                                    .background(Color.White.copy(alpha = .14f))
                                    .clickable { fullscreen = !fullscreen }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer4()
                                Text(
                                    "全屏",
                                    color = Color.White,
                                    fontSize = Fs.xxs,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
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
            }

            // ---------- 右侧浮层：选集 / 线路 / 画质 / 倍速 ----------
            panel?.let { p ->
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .45f))
                        .clickable { panel = null },
                )
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        .width(if (p == Panel.Episodes) 300.dp else 200.dp)
                        .background(T.Surface.copy(alpha = .97f))
                        .padding(horizontal = Sp.s4, vertical = Sp.s3),
                ) {
                    when (p) {
                        Panel.Episodes -> {
                            Text(
                                "选集 · ${playing?.mediaTitle ?: ""}",
                                color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold,
                            )
                            Spacer8()
                            if (episodes.isEmpty()) {
                                Text("这一条没有分集列表", color = T.TextFaint, fontSize = Fs.xxs)
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    items(episodes) { e ->
                                        val on = e.index == playing?.episodeIndex
                                        Box(
                                            Modifier.height(34.dp).clip(R.sm)
                                                .background(if (on) T.Play500.copy(alpha = .18f) else T.Surface2)
                                                .border(
                                                    1.dp,
                                                    if (on) T.Play500.copy(alpha = .50f) else T.Border,
                                                    R.sm,
                                                )
                                                .clickable {
                                                    playEpisode(e)
                                                    panel = null
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
                        }

                        Panel.Speed -> {
                            Text("倍速", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().clip(R.sm)
                                        .background(
                                            if (s == speed) T.Play500.copy(alpha = .16f) else Color.Transparent
                                        )
                                        .clickable { speed = s; panel = null }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${fmtSpeed(s)}x",
                                        color = if (s == speed) T.Play400 else T.Text,
                                        fontSize = Fs.sm,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        Panel.Line -> {
                            Text("线路", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            lines.forEachIndexed { i, l ->
                                Text(
                                    l.name.take(18),
                                    color = if (i == safeLine) T.Play400 else T.Text,
                                    fontSize = Fs.sm,
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(R.sm)
                                        .background(
                                            if (i == safeLine) T.Play500.copy(alpha = .16f) else Color.Transparent
                                        )
                                        .clickable {
                                            // 换线路 = 换一套地址，集数按 index 对齐
                                            val cur = playing?.episodeIndex ?: 1
                                            lines.getOrNull(i)?.episodes?.firstOrNull { it.index == cur }
                                                ?.let { playEpisode(it) }
                                            lineIndex = i
                                            panel = null
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }

                        Panel.Quality -> {
                            Text("画质", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            (listOf(0) + videoHeights).forEach { h ->
                                Text(
                                    if (h == 0) "自动（按网络）" else "${h}P",
                                    color = if (h == maxVideoHeight) T.Play400 else T.Text,
                                    fontSize = Fs.sm,
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(R.sm)
                                        .background(
                                            if (h == maxVideoHeight) T.Play500.copy(alpha = .16f)
                                            else Color.Transparent
                                        )
                                        .clickable { maxVideoHeight = h; panel = null }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                            Spacer8()
                            Text(
                                "只有源站给了多档分辨率时这里才有得选。",
                                color = T.TextFaint,
                                fontSize = Fs.tiny,
                                lineHeight = Fs.tiny * 1.6f,
                            )
                        }
                    }
                }
            }
        }

        // 选集抽屉 —— 只在非全屏显示；全屏用上面的浮层
        if (!fullscreen) {
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
                    Text(playing?.sourceName ?: "", color = T.TextFaint, fontSize = Fs.xxs)
                }
                Spacer8()
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
                                    .clickable { playEpisode(e) },
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
}

/**
 * 画面区手势：
 * 横向拖 = 按整条时间轴跳转；右半竖拖 = 系统音量；左半竖拖 = 窗口亮度；
 * 单击 = 显隐控件；双击左/右半屏 = 快退/快进 10 秒。
 *
 * 自己写 awaitEachGesture 而不是叠 detectTapGestures + detectDragGestures ——
 * 两个探测器叠在同一层时，拖动结束会被 tap 再判一次，点一下控件就乱跳。
 */
private fun Modifier.playerGestures(
    currentPosition: () -> Long,
    durationMs: () -> Long,
    currentVolume: () -> Float,
    currentBrightness: () -> Float,
    onScrubPreview: (Long) -> Unit,
    onScrubCommit: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    onDoubleTapSeek: (side: Int) -> Unit,
    onToggleControls: () -> Unit,
): Modifier = pointerInput(Unit) {
    val lastTap = longArrayOf(0L)
    awaitEachGesture {
        // requireUnconsumed：控件（时间栏、按钮、浮层）在手势层之上，
        // 它们吃掉的下一次这里就不该再判成「点画面显隐控件」。
        val down = awaitFirstDown(requireUnconsumed = true)
        val width = size.width.toFloat().coerceAtLeast(1f)
        val height = size.height.toFloat().coerceAtLeast(1f)
        val slop = viewConfiguration.touchSlop
        val leftSide = down.position.x < width / 2f
        val baseMs = currentPosition()
        val totalMs = durationMs()
        var axis = 0 // 0 未定 1 横向 2 右竖(音量) 3 左竖(亮度)
        var accX = 0f
        var accY = 0f
        var v0 = 0f
        var b0 = 0f
        var prev: Offset = down.position
        var targetMs = baseMs
        var up: PointerInputChange? = null

        while (up == null) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
            if (ch.changedToUpIgnoreConsumed()) {
                up = ch
                break
            }
            val d = ch.position - prev
            prev = ch.position
            accX += d.x
            accY += d.y
            if (axis == 0) {
                if (abs(accX) > slop && abs(accX) > abs(accY)) axis = 1
                else if (abs(accY) > slop) {
                    // 竖滑要从当前值起步，否则每一下都从中间开始
                    axis = if (leftSide) 3 else 2
                    if (axis == 3) b0 = currentBrightness() else v0 = currentVolume()
                } else continue
            }
            when (axis) {
                // 预览不真的 seek：HLS 上每帧 seek 会卡出花来
                1 -> if (totalMs > 0) {
                    // 横滑按「划满一屏 = 2 分钟」算，不按整片时长 ——
                    // 一部 3 小时的片子按总时长映射的话，手指动一下就飞到片尾了。
                    targetMs = (baseMs + accX / width * SCRUB_SPAN_MS)
                        .coerceIn(0f, totalMs.toFloat()).toLong()
                    onScrubPreview(targetMs)
                }

                2 -> onVolume((v0 - accY / height).coerceIn(0f, 1f))
                3 -> onBrightness((b0 - accY / height).coerceIn(0.02f, 1f))
            }
            ch.consume()
        }

        when (axis) {
            0 -> {
                val now = System.currentTimeMillis()
                if (now - lastTap[0] < 320L) {
                    lastTap[0] = 0L
                    onDoubleTapSeek(if (leftSide) -1 else 1)
                } else {
                    lastTap[0] = now
                    onToggleControls()
                }
            }

            1 -> onScrubCommit(targetMs)
            else -> Unit
        }
    }
}

/** 可拖动的时间栏。点一下跳到那里，按住拖动实时预览、松手才真的 seek。 */
@Composable
private fun SeekBar(
    positionSec: Long,
    durationSec: Long,
    modifier: Modifier = Modifier,
    onScrub: (targetSec: Long) -> Unit,
    onCommit: (targetSec: Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val frac = when {
        dragging -> dragFrac
        durationSec > 0 -> (positionSec.toFloat() / durationSec).coerceIn(0f, 1f)
        else -> 0f
    }
    Box(
        modifier.height(22.dp).pointerInput(durationSec) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat().coerceAtLeast(1f)
                fun targetAt(x: Float) =
                    ((x / w).coerceIn(0f, 1f) * durationSec).toLong()
                if (durationSec > 0) {
                    dragging = true
                    dragFrac = (down.position.x / w).coerceIn(0f, 1f)
                    onScrub(targetAt(down.position.x))
                }
                var up: PointerInputChange? = null
                while (up == null) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.changedToUpIgnoreConsumed()) {
                        up = ch
                    } else if (durationSec > 0) {
                        dragFrac = (ch.position.x / w).coerceIn(0f, 1f)
                        onScrub(targetAt(ch.position.x))
                        ch.consume()
                    }
                }
                if (durationSec > 0) {
                    onCommit((dragFrac * durationSec).toLong())
                    dragging = false
                }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = .22f)),
        )
        Box(
            Modifier.fillMaxWidth(frac).height(if (dragging) 6.dp else 4.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(T.Play400, T.Play500))),
        )
        // 拖动手柄：贴着已填充段的右端，宽度用 fillMaxWidth(frac) 定位
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier.size(if (dragging) 14.dp else 10.dp).clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun IconBtn(
    image: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = .10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(image, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = Fs.xxs,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(R.sm)
            .border(1.dp, Color.White.copy(alpha = .26f), R.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun setVolume(audio: AudioManager, frac: Float) {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audio.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (frac * max).roundToInt().coerceIn(0, max),
        0,
    )
}

private fun applyBrightness(a: Activity, frac: Float) {
    val lp = a.window.attributes
    lp.screenBrightness = frac.coerceIn(0.02f, 1f)
    a.window.attributes = lp
}

private fun restoreSystemBrightness(a: Activity) {
    val lp = a.window.attributes
    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    a.window.attributes = lp
}

/** 从任意 Context 往上找到宿主 Activity —— 横屏与系统栏都必须由它来改。 */
private fun Context.activity(): Activity? = generateSequence(this) {
    (it as? ContextWrapper)?.baseContext
}.firstOrNull { it is Activity } as? Activity

private fun fmt(sec: Long): String {
    if (sec <= 0) return "00:00"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun fmtSpeed(s: Float): String =
    if (s % 1f == 0f) "%.1f".format(s) else "%.2f".format(s).trimEnd('0')
