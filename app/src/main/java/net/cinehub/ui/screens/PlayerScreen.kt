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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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
import net.cinehub.playback.PlayerBuffering
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.SiblingsQuery
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
private enum class Panel { Episodes, Line, Quality, Speed, Resize, Source }

/** 横滑划满一屏对应的跳转量。 */
private const val SCRUB_SPAN_MS = 120_000L

/** 「换过 N 次才播起来」那句在屏上留多久 —— 控件 3.5 秒就收，这句话得比控件活得长。 */
private const val SETTLED_NOTICE_MS = 10_000L

/** 播完后留给「取消连播」的窗口。 */
private const val AUTO_NEXT_MS = 3_000L

/**
 * 播放器报的毫秒 → 秒。
 *
 * Media3 用 C.TIME_UNSET（Long.MIN_VALUE+1）表示「还没解出来」，位置在未就绪时也是负的。
 * 这里先认出来再换算 —— 这两个值一路会写进观看记录，
 * 而「看完了没」是拿时长算的，靠「除一千再取非负」刚好抹平是在赌上游不改。
 */
private fun msToSec(ms: Long): Long = if (ms < 0L) 0L else ms / 1000

/**
 * 本条线路清单里，当前这一集的下一（上）一条是谁；没有就返回 null。
 *
 * 手动那颗上下集按钮和自动连播的倒计时共用这一份查法：两处各写一套「往后挪一格」，
 * 迟早有一处在「线路之间集号对不上」（清单里找不到当前集）时把用户扔到清单第一集 ——
 * 那是当年 `-1 加一挡落到第一集` 的翻版。缺集也是同一类：第 2 集的下一集是清单里的
 * 下一条（第 4 集），不是算术上的第 3 集。
 */
internal fun nextEpisodeOf(
    episodes: List<Episode>,
    currentIndex: Int,
    delta: Int,
): Episode? {
    val i = episodes.indexOfFirst { it.index == currentIndex }
    if (i < 0) return null
    return episodes.getOrNull(i + delta)
}

/**
 * 这一集该不该自动跳到片头结束的位置；该跳就给出落点（秒），否则 null。
 *
 * 抽成纯函数是为了能单测：它牵着一串边界（时长还没解出来、已经播过那一格、
 * 整集比两头的和还短），埋在 500ms 的轮询里既没法验证、出错又全是静默的。
 *
 * 判据用**当前位置**而不是续播点：续播到第 18 分钟进来时位置本来就在 90 秒之后，
 * 同一条判据就顺带管住了，不必再引第二个概念。而「位置已过那一格就不跳」是必须的 ——
 * 时长晚一拍才解出来的时候，只看续播点会把用户从第 2 分钟**往回**拖到 90 秒，
 * 那不叫跳过片头，叫倒带。
 *
 * 「整集不足三倍就不跳」那条最不显眼也最要紧：45 秒的短片设着 90 秒的跳过，
 * 片头吃掉一份、片尾再吃掉一份，中间剩不了几秒 —— 那等于把这一集跳没了。
 */
internal fun introSkipTarget(
    durationSec: Long,
    skipSec: Int,
    posSec: Long,
): Long? {
    if (skipSec <= 0) return null
    if (durationSec <= 0L) return null
    if (posSec >= skipSec) return null
    if (durationSec <= skipSec * 3L) return null
    return skipSec.toLong()
}

/**
 * 这一集该不该自动跳到结尾（触发播完 → 连播那一套）；该跳就给出落点，否则 null。
 *
 * 只认「顺着播进那片区域」：上一拍还在区域外、这一拍进了区域、且两拍之间挪得不比
 * 播放本身该挪的多。[maxAdvanceSec] 由调用方按「这一跳真的隔了多久 × 此刻倍速」算出来
 * —— 写死一个 2 秒的话，轮询那 500ms 被 GC 拖长、或缓冲期 100ms 一跳切回 500ms 时，
 * 正常的播放会被误判成「人是被拖过来的」，这一集的片尾就再也不跳了。
 * 用户自己把进度条拖到片尾附近不算 —— 他想在那儿看，播放器不该替他按结束。
 */
internal fun outroSkipTarget(
    posSec: Long,
    prevPosSec: Long,
    durationSec: Long,
    skipSec: Int,
    maxAdvanceSec: Long = 2L,
): Long? {
    if (skipSec <= 0) return null
    if (durationSec <= 0L) return null
    if (durationSec <= skipSec * 3L) return null
    val edge = durationSec - skipSec
    if (posSec < edge) return null
    // 没有上一拍（第一次采样）不动手：分不清这是播进来的还是拖进来的
    if (prevPosSec < 0L) return null
    if (prevPosSec >= edge) return null
    if (posSec - prevPosSec > maxAdvanceSec) return null
    return durationSec
}

/**
 * 播放器。
 *
 * 这里是「应用重渲染」最直接的体现：画面由影枢自己的 ExoPlayer 渲染，
 * 源只是背后的一个数据来源，进度通过 ViewModel 跨源携带。
 *
 * 交互分两层：底下是一整块手势层（拖进度、双击快进退、竖滑音量/亮度、单击显隐），
 * 上面是会自动隐藏的控件。全屏时只留这两层 —— 右侧切源栏、选集抽屉
 * 都让位给画面，否则用户看到的是界面而不是片子。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val playing by vm.playing.collectAsState()
    // 播放器读的是起播时快照的那两份：vm.detail / vm.siblings 是「详情页当前那一部」的状态，
    // 从继续观看、搜索结果、缓存列表直接进播放器时它们可能还停在上一部 ——
    // 于是选集、线路、换源三处列的都是别的片的东西。
    val siblings by vm.playingSiblings.collectAsState()
    /** 这份跨源列表查过没有：没查过的时候「只有一个源」和「还没比对」是两回事。 */
    val sibQuery by vm.playingSiblingsQuery.collectAsState()
    val sibGaps by vm.playingSiblingsGaps.collectAsState()
    val health by vm.health.collectAsState()
    val detail by vm.playingDetail.collectAsState()
    val resolving by vm.resolving.collectAsState()
    val fallback by vm.fallback.collectAsState()
    /** 此刻在小窗里吗 —— 由 Activity 写进来，界面读它决定控件摆不摆。 */
    val inPip by vm.inPip.collectAsState()

    // 只取**此刻**那个值当初始位置，不订阅：vm.resumePosition 是播放器每 500ms 报一次的
    // 活值，collectAsState 会让这整屏（1800 行）跟着以 2 Hz 全量重组，而这里只用得上一次。
    var pos by remember { mutableLongStateOf(vm.resumePosition.value) }
    var dur by remember { mutableLongStateOf(0L) }
    /** 已缓冲到的位置（秒），进度条第二根条与缓冲提示用。0 = 还没量到。 */
    var buf by remember { mutableLongStateOf(0L) }
    /**
     * 缓冲领先播放点的毫秒数，喂给缓冲提示里「已缓冲 N 秒」那一项。
     *
     * 不能拿 [buf] 减 [pos] 来算：那两个都是取整到秒的，相减只剩几个粗档位。
     * 这里直接用播放器的毫秒原值，档位足够细。
     */
    var bufAheadMs by remember { mutableLongStateOf(0L) }
    /**
     * 缓冲速度（字节每秒），只喂缓冲提示那一句「· 1.2 MB/s」。
     *
     * 取的是 [PlayerBuffering.SpeedMeter] 那份自己数的字节，**不是** Media3 的带宽估计：
     * 估计只在真的发生加载时更新，连接一挂就冻结在最后一次成功的值上 ——
     * 那正是缓冲提示最需要说真话的时刻，照它写就成了「1.2 MB/s」而其实一个字都没来。
     */
    var bufSpeed by remember { mutableLongStateOf(0L) }
    /**
     * 已经多久没有新字节了（毫秒），0 表示没停住。
     *
     * 和 [bufSpeed] 分开存，是因为「速度为零」有两种完全不同的意思：
     * 一种是还没量到（刚起播、样本不够），一种是**量到了零**（这三秒确实一个字节都没进来）。
     * 前者只能闭嘴，后者必须说出来 —— 追播中卡住时，屏幕上那句「已缓冲 3 秒」不会自己动，
     * 没有这一项的话整行字是静止的，用户分不清是在走还是死了。
     */
    var bufStalledMs by remember { mutableLongStateOf(0L) }
    /** 用户意图，不是 player.isPlaying —— 后者在 HLS seek/buffer 期间会短暂为 false，按钮会闪成「已暂停」。 */
    var wantsPlay by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    /**
     * 这一轮缓冲已经等了多久（秒）。
     *
     * prepare 阶段（清单还没解析完、第一个分片还没落地）`duration` 和
     * `bufferedPosition` 都是 0，算不出任何比例 —— 而那段往往是最长的等待。
     * 这时候只能报等待时长，让用户知道它还在走。
     */
    var bufferingSec by remember { mutableIntStateOf(0) }
    var errText by remember { mutableStateOf<String?>(null) }
    /**
     * 播放器手里这一条媒体属于哪一次尝试（就是那一条 `episodeUrl`）。
     *
     * 报错时要拿它去汇报，不能用屏上现值：换线路之后旧播放器还会晚一拍把上一条线路的错误
     * 送上来，那时候 `playing` 已经翻到新一笔，用现值去认账就会把新那一笔冤枉掉。
     */
    var attemptUrl by remember { mutableStateOf<String?>(null) }
    /** 这一次播放渲染过第一帧没有 —— 起播看门狗只看这一位。 */
    var firstFrame by remember { mutableStateOf(false) }
    /**
     * 播放器这一集播到了结尾（STATE_ENDED）。自动连播的倒计时和横幅都认这一位。
     *
     * 键里换集/换源/换线路都要把它归零（见主媒体 effect）：旧那一集的「播完了」
     * 不该让新那一集一挂上媒体源就弹倒计时 —— setMediaSource 之后 STATE_IDLE 之前
     * 未必再经过一次别的状态回调。
     */
    var playerEnded by remember { mutableStateOf(false) }
    /** 这一集里用户点过「取消连播」：本集不再弹，下一集 ENDED 时重新计。 */
    var autoNextCancelled by remember { mutableStateOf(false) }
    /**
     * 用户在这一笔上手点了「重试」几次。
     *
     * 它只用来给看门狗换一个键（见下面那条 effect）：重试时地址/集号/源一个都没变，
     * 而看门狗那 20 秒早就走完了 —— 不做这件事，重试后的流卡在缓冲里就再没人管。
     */
    var attemptTick by remember { mutableIntStateOf(0) }
    /** 告知横幅上的「一路试过哪些源」展开没有。 */
    var fbExpanded by remember { mutableStateOf(false) }

    // 点播放就进全屏，不用用户再找按钮
    var fullscreen by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var lastUi by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    /**
     * 触屏锁住了没有。
     *
     * 锁住之后这一屏只剩一件事能办：点画面右中那一颗图标解锁。手势整块摘下、控件不弹、
     * 返回键也不退出播放器 —— 设这个开关的场景就是「揣兜里接着放、接着听」：
     * 那种场合下误触一次比少一个功能疼得多。
     *
     * 只在这一次播放会话里有效（[remember]，不落盘）：退出播放器再进来一定是没锁的 ——
     * 「进了播放器先得找锁在哪」比「忘了 unlock 出来费点劲」更让人烦。
     */
    var screenLocked by remember { mutableStateOf(false) }
    /**
     * 倍速读的是全局那一份（见 `PlaybackRepository`），不是这一屏的本地态。
     *
     * 以前它是 `remember { mutableFloatStateOf(1f) }`：同一部剧连看八集，每一集都得
     * 重新点一遍 1.5x —— 用户的耳朵记着上一集那档，界面不记。
     */
    val speed by vm.playbackSpeed.collectAsState()
    /**
     * 倍速面板里滑杆的本地预览值。只活在面板开合之间：
     * 拖动途中每一像素都去写全局那份，等于把「正在拖」这一半截状态也持久化了 ——
     * 松手那一刻才落盘（见面板里的 onValueChangeFinished）。
     * 打开面板时同步成当前档（芯片的 onClick 里做），下次进来滑杆停在他真正用的那一格。
     */
    var customSpeed by remember { mutableStateOf(speed) }
    /** 自动连播也是全局那一份偏好：和倍速同理由，不在这一屏抄本地态。 */
    val autoNext by vm.autoPlayNext.collectAsState()
    /** 画面比例模式（fit/fill/zoom），同样读全局那份。 */
    val resizeMode by vm.playbackResizeMode.collectAsState()
    /**
     * 视频自己的宽高比（还没解出来是 null）。
     *
     * 「自适应」不再只是 PlayerView 的 RESIZE_MODE_FIT：那一档把 16:9 的画面摆在
     * 16:9 的**全屏**视口里，于是上下各留一条与画面同宽的黑带 —— 而黑带本可以不占地方。
     * 做法是让装视频的那个 Box 自己按这个比例收窄（宽度撑满、高度按比例算），
     * 剩下的地方归顶栏与底部控件：画面一点没少，控件还多了一块不乱压画面的落脚处。
     * 详见下面 AndroidView 外面那个 Box 的注释。
     */
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    /** 跳过片头/片尾的秒数（0 = 关），全局一份；改动从下一集起算（见轮询里的判法）。 */
    val skipSec by vm.skipIntroOutroSec.collectAsState()
    var lineIndex by remember { mutableIntStateOf(0) }
    /** 0 = 不封顶，交给 ExoPlayer 自适应；否则是用户选的视频高度上限。 */
    var maxVideoHeight by remember { mutableIntStateOf(0) }
    var videoHeights by remember { mutableStateOf(listOf<Int>()) }
    /**
     * **此刻真的在放**哪一档（0 = 还不知道，Media3 还没解出 master playlist）。
     *
     * 和上面两个分开是必须的：[maxVideoHeight] 是用户许不许往下压的**上限**，
     * [videoHeights] 是这一条流里**有哪些**档，只有这一个回答「屏幕上现在这几万个像素是多高」。
     * 画质那一格要说「现在在放 1080P」，只能拿它当依据 —— 拿上限当依据就是句假话：
     * 上限写 1080、网络撑不住掉到 480 的时候，芯片还写着 1080P。
     */
    var playingHeight by remember { mutableIntStateOf(0) }
    /** 手势反馈的浮动提示，null 表示不显示。 */
    var hint by remember { mutableStateOf<String?>(null) }
    /** 横向拖动时的目标位置（秒），非空表示正在拖、先预览不真的 seek。 */
    var previewSec by remember { mutableStateOf<Long?>(null) }
    val shownPos = previewSec ?: pos

    val activity = ctx.activity()
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // NaN = 用户还没碰过亮度，一次都不会去改窗口亮度
    var brightness by remember { mutableFloatStateOf(Float.NaN) }

    // 带宽估计器：起始那一档保守一点（见 PlayerBuffering）。
    // 它必须同时挂在播放器和数据源上 —— 只挂一边等于 ABR 永远停在初始值。
    val bandwidth = remember { PlayerBuffering.bandwidthMeter(ctx) }

    /**
     * 缓冲速度那一份测量，挂在数据源的 transfer listener 上。
     *
     * 它是**包着** [bandwidth] 的一层，不是替掉它：一个数据源只接一个监听器，
     * 直接把这颗塞进 `setTransferListener` 就等于把测量从带宽估计器上摘下来，ABR 当场失效。
     * 转发的事由 [PlayerBuffering.SpeedMeter] 自己做，这里只负责把它接上去。
     */
    val speedMeter = remember { PlayerBuffering.SpeedMeter(bandwidth) }

    /**
     * ABR 码率上限（比特每秒）的**当前值**。
     *
     * 正常时是 Long.MAX_VALUE（不封顶，ABR 自由选档）；一次重缓冲把它压到实测 × 80%，
     * 稳定够久之后由轮询循环按 [PlayerBuffering.releaseTarget] 放开一次。
     * 放在状态里而不是直接写 trackSelectionParameters：真正的参数应用只有
     * `LaunchedEffect(abrMaxBitrate)` 那一处，和画质上限同一个入口。
     */
    var abrMaxBitrate by remember { mutableLongStateOf(Long.MAX_VALUE) }

    /**
     * 上限是不是**正被压着、在等放开**。
     *
     * 这一位不能从 [abrMaxBitrate] 的数值推：放开之后它仍然是一个有限的数
     * （实测的 90%），照数值判就永远以为「还压着」，于是每一跳都重建一次选档参数 ——
     * 而每次重建都会让 ABR 重新选一次档，那本身就是一种抖动。
     */
    var abrClamped by remember { mutableStateOf(false) }

    /** 这一条流上第几次重缓冲。窗口按它翻倍（见 [PlayerBuffering.stableWindowMs]）。 */
    var rebufferCount by remember { mutableIntStateOf(0) }

    /** 距离上一次状态变化稳定播放了多久（毫秒）。进 BUFFERING 就归零。 */
    var stableMs by remember { mutableLongStateOf(0L) }

    val player = remember {
        // 缓冲的几档阈值集中在 PlayerBuffering，不在这里散着写
        ExoPlayer.Builder(ctx)
            .setLoadControl(PlayerBuffering.loadControl())
            .setBandwidthMeter(bandwidth)
            // 音频焦点两件套 + 拔耳机暂停。曾经一样都没配：
            // 来电话、别的 App 放歌，影枢照响不误；拔掉耳机外放继续，
            // 尴尬是用户自己的。setAudioAttributes(audioFocus=true) 让播放器
            // 自己去申请/监听焦点（丢失即暂停、拿回不自动恢复 —— 恢不恢复交给用户），
            // setHandleAudioBecomingNoisy 认「耳机被拔」那个系统广播并暂停。
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        // BUFFERING / READY / ENDED —— 首帧渲染前 UI 要说清「在缓冲」，
                        // 否则用户看到的就是一个不动的黑屏，像是点了没反应。
                        buffering = state == Player.STATE_BUFFERING
                        // 每进一轮缓冲就把等待计时归零：报的是「这一轮等了多久」，
                        // 累计值在第二次卡顿时会显示成「已等 40 秒」，像是卡了很久
                        if (state == Player.STATE_BUFFERING) bufferingSec = 0
                        // 连播认这一位，而且只认 ENDED：从 ENDED 走向下一条媒体时
                        // 这里会跟着归位，主媒体 effect 里那句复位是双保险。
                        playerEnded = state == Player.STATE_ENDED
                        // 播开之后又掉进 BUFFERING = 一次重缓冲：多半是当前档位的码率
                        // 超过了出口带宽（消费 > 生产，桶再大也治不了）。把 ABR 上限
                        // 压到实测带宽的 80%，让 ABR 自己降档，而不是硬扛着反复卡。
                        // 首帧之前那次 BUFFERING 是起播，不动 —— 那时估计还没量准，
                        // 压上去只会把起播档限死在最低档。
                        if (state == Player.STATE_BUFFERING && firstFrame) {
                            abrMaxBitrate = PlayerBuffering.clampBitrate(
                                bandwidth.bitrateEstimate,
                                abrMaxBitrate,
                            )
                            // 压过就要等放开；第几次决定等多久（见 stableWindowMs）。
                            // 这一句只在状态**变化**时进来（onPlaybackStateChanged 的语义），
                            // 所以一次重缓冲计一次，不会重复累加。
                            abrClamped = true
                            rebufferCount += 1
                        }
                        // 稳定计时以播放器状态为准，不以轮询为准：退到后台、被音频焦点
                        // 打断时它既不是 READY 也不是 BUFFERING，那种时间不该算进「稳定播放」。
                        // 进 BUFFERING 则当场归零 —— 窗口数的是「上一次卡住之后」的稳定时长。
                        if (state == Player.STATE_BUFFERING) stableMs = 0L
                    }

                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        // 自动暂停（缓冲上限、失去音频焦点）也会走到这里，比在点击回调里手动翻状态可靠
                        wantsPlay = playWhenReady
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        val why = "播放失败：${e.message ?: e.cause?.message ?: "未知错误"}"
                        // 换了线路之后，旧播放器还会**晚一拍**把上一条线路的错误送上来
                        // （这一条在 reportPlaybackFailure 那段的注释里写着）。
                        // 状态机靠 attemptUrl 把那一笔挡在账外；界面这一句也得一样挡 ——
                        // 否则新那条线路明明已经在放，屏上却盖着一整页「播放失败」，
                        // 底下还摆一颗会去 prepare 这条健康流的「重试」。
                        if (attemptUrl == playing?.episodeUrl) errText = why
                        vm.reportPlaybackFailure(why, attemptUrl)
                    }

                    override fun onRenderedFirstFrame() {
                        // 第一帧是「这一轮可以收工」的唯一硬证据：缓冲计数、isPlaying 都骗得过人
                        firstFrame = true
                        vm.playbackStarted()
                    }
                })
            }
    }

    // 换源 / 换集 / 换线路时重建媒体源；用 resumeFrom 保证进度接续
    LaunchedEffect(playing?.streamUrl, playing?.episodeUrl, playing?.episodeIndex, playing?.sourceId) {
        val url = playing?.streamUrl
        // 右侧那栏「线路 N/M」得跟着换：自动换源现在会在同一个源里换线路，
        // 不跟着挪的话屏上写的是第 1 条、实际播的是第 2 条 —— 和当年换源不重置 lineIndex 是同一类谎。
        // 认不出这条地址属于哪一条线路时回到第 1 条（本地缓存那种就没有线路可选）。
        run {
            val d = vm.playingDetail.value
            val u = playing?.episodeUrl
            val at = d?.lines?.indexOfFirst { l ->
                u != null && l.episodes.any { it.playUrl == u }
            } ?: -1
            lineIndex = if (at >= 0) at else 0
        }
        errText = null
        firstFrame = false
        buffering = true
        // 缓冲进度也归零：上一条流攒下的数字留着，缓冲提示会在新流起播前
        // 先闪一个旧百分比 —— 那正好是这次要修掉的「一闪而过」
        buf = 0L
        bufAheadMs = 0L
        bufferingSec = 0
        // 换集 / 换源 / 换线路都是新的一集：连播状态位归位，旧那一集「播完了」
        // 不能让它一挂上媒体源就弹倒计时；用户上一集点过的「取消」也不带到下一集。
        playerEnded = false
        autoNextCancelled = false
        if (url.isNullOrBlank()) {
            // 没有地址可播（解析失败、还在解析）—— 这时候才真的停
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }

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
            // 另一半 wiring：测量是从这里流进带宽估计器的，漏掉这一行
            // 播放器看到的就永远是那个初始值，码率再也不会跟着网络动。
            // 现在这里挂的是 speedMeter —— 它把每一趟回调原样转发给 bandwidth，
            // 顺带自己数一份字节给缓冲提示那句速度用（见它的类注释：为什么不能用估计值）。
            .setTransferListener(speedMeter)

        // 缓存好的片子是 file:// 地址，只挂 HTTP 数据源会读不到 —— 按 scheme 分派一层
        val data = androidx.media3.datasource.DefaultDataSource.Factory(
            ctx.applicationContext,
            http,
        )

        val item = MediaItem.fromUri(url)
        val source = if (PlayerBuffering.isHlsUrl(url)) {
            // chunkless：不必先把整份 1 小时的分段列表拉完才起播
            HlsMediaSource.Factory(data)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(data)
                .createMediaSource(item)
        }
        // 换集 / 换源走 setMediaSource + prepare，不走 release/rebuild 那颗播放器：
        // 重建要把 trackSelector、带宽估计、Surface 全搬一遍，那才是真的从头来。
        //
        // 但别把这一句读成「换集一定保住了带宽估计」：vm.play() 会先发出一帧
        // streamUrl=null 的 Playing（那是「我收到了」这一步），主 effect 于是先在
        // 上面那个 null 分支里 player.stop() 一次，才走到这里。stop() 对估计做了什么
        // 没有实测过 —— 原来这段注释断言的是「不 stop 所以估计留着、起播直接落到上一集
        // 那一档」，那句是没有依据的，留着只会让人以为这条优化已经生效。
        //
        // attemptUrl 就在**这里**认账，不能在 effect 开头：那一段里 vm.play() 刚发出来的
        // null-URL 帧会让它先指着新地址，而播放器手里还是上一条媒体 —— 旧媒体晚一拍报错时
        // 就会算到新那一笔头上，把一条从没试过的线路跳掉。放在换源这一刻，
        // 「播放器在放哪一条」与「账上记着哪一条」才是同一件事。
        attemptUrl = playing?.episodeUrl
        // 上一条流攒下的速度不作数：换集 / 换源 / 换线路都是另一条流，
        // 不重置的话新流起播那一瞬会先闪一个旧速度 —— 和上面那句百分比是同一类谎。
        //
        // 位置就在换源这一行前面，不能提前到 effect 开头：换集时 url 非空、不走上面那个
        // stop() 分支，旧数据源在「effect 开头 → setMediaSource」这段时间里还在报字节，
        // 提前清掉的窗口会立刻被上一集的字节填回来 —— 新流起播头两秒的速度就掺了旧量，
        // 而那两秒正是用户盯着这个数看的时候。
        speedMeter.reset()
        bufSpeed = 0L
        bufStalledMs = 0L
        player.setMediaSource(source)
        player.prepare()
        // 用这一次请求冻结下来的起点，不用 vm.resumePosition —— 轮询会把那个活值写成 0，
        // 这里再读就等于放弃续播。
        val from = playing?.resumeFrom ?: 0L
        if (from > 0) player.seekTo(from * 1000)
        player.play()
    }

    // 起播看门狗：地址是通的，但 20 秒还不出第一帧，就当作这个源放不出来这一集。
    // 只管起播这一段 —— 播到一半卡顿和用户自己拖进度条长得一模一样，误判的代价比救回来的收益大。
    //
    // 判据是「还没进 READY」，不是「位置还在 0」：位置那一条会把续播和换源全排除掉 ——
    // 从第 18 分钟续起来的这一笔，位置一上来就不是 0，看门狗于是永远不开火，
    // 而恰恰是这类源最需要在起不来时换走。纯音频流不回首帧回调是真的，
    // 但它会进 READY，那正是「确实播开了」的证据。
    //
    // 键里带上 attemptTick：用户在报错面板上点「重试」时，地址、集号、源都没变，
    // 这一条 effect 的 delay 却早就走完了 —— 不重新起一轮的话，重试后的流要是
    // 一直卡在 BUFFERING，就再没有看门狗会开火，屏上永远停在「缓冲中…」。
    LaunchedEffect(playing?.streamUrl, playing?.episodeUrl, playing?.episodeIndex, playing?.sourceId, attemptTick) {
        if (playing?.streamUrl.isNullOrBlank()) return@LaunchedEffect
        delay(STARTUP_GRACE_MS)
        val st = player.playbackState
        if (!firstFrame && wantsPlay && errText == null &&
            st != Player.STATE_READY && st != Player.STATE_ENDED
        ) {
            vm.reportPlaybackFailure("一直缓冲，没能开播", attemptUrl)
        }
    }

    // 轮询播放位置；顺带把这一条流里真实存在的视频高度取出来给「画质」用 ——
    // 只有 Media3 解出 master playlist 之后才知道有几档，不能凭空列。
    //
    // 键里必须带上「这是哪一集、哪个源、哪条地址」。这个循环里读到的 `playing` 是协程创建
    // 那一刻的快照，媒体一换它就是旧的；而落盘走的是 vm 里**已经翻成新那一集**的 _playing。
    // 原来只键在 player 上，于是换集/换线路以后有一个 tick 会拿着旧播放器里**旧那一集**的
    // 位置，往**新那一集**的记录上写 —— 下次进来直接从上一集的秒数开始播，一点报错都没有。
    LaunchedEffect(player, playing?.sourceId, playing?.episodeIndex, playing?.streamUrl) {
        // 另一条流就是另一条流：上一流的档数与档位不作数，等它自己报
        videoHeights = emptyList()
        playingHeight = 0
        // 换流之后 ABR 上限从头来：上一条流的「出口慢」不代表这一条也慢
        abrMaxBitrate = Long.MAX_VALUE
        abrClamped = false
        rebufferCount = 0
        stableMs = 0L
        var lastPos = -1L
        var lastDur = -1L
        // 片头/片尾的跳过状态，一集一份（循环按集重建，不必手动归零）。
        // 片头只判一次：用户手动拖回开头想再看片曲，播放器不该再把他甩到 90 秒。
        var introDone = false
        var outroDone = false
        // 上一拍的位置：片尾那条要它才分得清「顺着播进片尾区」和「被拖进片尾区」
        var prevPos = -1L
        // 上一跳实际睡了多久。稳定时长加的是这个数，而不是当跳要睡的数：
        // 醒来时能确定「刚才那段时间播放器一直是 READY」，而当跳的时长还没过去。
        var sleptMs = 500L
        // 此刻倍速。轮询这一路顺手读，不另起一条 effect —— 它和位置同频变化，
        // 分两条线早晚对不齐。
        var speedNow = 1f
        while (true) {
            // 这一轮只读一次播放状态：下面「算不算稳定」与「这一跳睡多久」
            // 必须看着同一个状态，分两次读就有中间变了的可能
            val state = player.playbackState
            pos = msToSec(player.currentPosition)
            dur = msToSec(player.duration)
            wantsPlay = player.playWhenReady
            // 此刻倍速（倍速是从仓库读的那一份，播放器跟不上手改的那一小会儿）。
            // 片尾那条判据要它：两拍之间「该挪几秒」是隔了多久 × 几倍速。
            speedNow = player.playbackParameters.speed
            // 缓冲进度（秒）交给进度条第二根条与缓冲提示；没在缓冲时也照读，
            // 进度条上的缓冲段要一直画
            buf = msToSec(player.bufferedPosition).coerceAtLeast(pos)
            // 毫秒原值另算一份给缓冲提示：它的分母是 1.2~2.5 秒的起播阈值，
            // 用上面那个取整到秒的数只剩四五档，进度会跳
            bufAheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            // 缓冲速度：这一跳顺手读一次测量。放在这个循环里而不是单独一条 effect，
            // 是因为缓冲中本来就是 100ms 一采（见下面那句 sleptMs 的调法），
            // 数字跟着同一节拍走，不会和「已缓冲几秒」那句错开一拍。
            bufSpeed = speedMeter.speedPerSec()
            bufStalledMs = speedMeter.stalledMs()
            // 稳定计时与放开。READY 就攒时长，够久才放开一次。
            //
            // **这里原来是每跳（500ms）放宽 25% 的阶梯**，那是画质鬼畜的直接来源：
            // 压下去 → ABR 降档（糊）→ 出口不再被占满 → 带宽估计上涨 → 阶梯一两秒就把
            // 上限抬回高位 → ABR 挑高档（清楚）→ 又占满 → 又卡。每秒抖两轮，
            // 用户看到的是清楚与模糊来回跳。
            // 现在幅度一步到实测的 90%，节奏交给稳定窗口，且每卡一次窗口翻倍。
            val ready = state == Player.STATE_READY
            stableMs = if (ready) stableMs + sleptMs else 0L
            if (PlayerBuffering.readyToRelease(abrClamped, stableMs, rebufferCount)) {
                val target = PlayerBuffering.releaseTarget(bandwidth.bitrateEstimate)
                if (target > 0L) {
                    // 只往上抬：往下压是重缓冲时 clampBitrate 干的事，两条路径别混
                    if (target > abrMaxBitrate) abrMaxBitrate = target
                    // 放开过就收工，等下一次重缓冲重新武装。
                    // 不这么做的话这一位会一直「压着」，每够一个窗口就白重建一次选档参数。
                    abrClamped = false
                    stableMs = 0L
                }
            }
            // 只有真的进了 READY 才把位置交给仓库落盘。
            // prepare 之前那一句 seekTo 就已经让 currentPosition 报出续播起点了 —— 
            // 不问一句就写，等于把「这一集根本没播开」记成「看到了第 18 分钟」，
            // 下次进来「已经看完了」的判断、继续观看的位置全是拿它算的。
            //
            // 而且**只在数字真的变了时**落盘：暂停着、退到后台放着（影枢没有前台服务，
            // ON_STOP 只 pause）都还是 STATE_READY，原来这一句会一路把同一个位置每 500ms
            // 量一次、每 10 秒往 DataStore 写一笔，横穿整个后台期间磨盘。
            if (ready && (pos != lastPos || dur != lastDur)) {
                vm.updatePosition(pos, dur)
                lastPos = pos
                lastDur = dur
            }
            // ---------- 跳过片头 / 片尾 ----------
            //
            // 判在 READY 的拍子里：时长没解出来之前没有任何可跳的依据（判据自己会挡，
            // 但状态位要留到真能判的那一拍再消费 —— 见两个 target 函数的 null 分支）。
            // 每集各判一次：这两个都是协程里的局部位，循环按「哪一集哪条流」重建，
            // 换集换源天然归零，不需要额外的重置点。
            if (ready) {
                // 小窗里只跳不说：那句提示会盖在巴掌大的窗上（进小窗时清 hint 是同一条
                // 「那颗窗只装画面」的规矩，见上面小窗那一节）。跳过本身照做 ——
                // 用户在小窗里看的也是这一集，片头该跳。
                if (!introDone && dur > 0L) {
                    introDone = true
                    introSkipTarget(dur, skipSec, pos)?.let { t ->
                        player.seekTo(t * 1000)
                        pos = t
                        if (!inPip) hint = "已跳过片头"
                    }
                }
                if (!outroDone) {
                    // 「挪得不比播放本身该挪的多」：这一跳真的隔了多久 × 此刻倍速，
                    // 再加半拍的余量。写死 2 秒的话，轮询被拖长一拍就被误判成拖进度，
                    // 这一集的片尾再也不跳（见 outroSkipTarget 的注释）。
                    val advance = (sleptMs * speedNow) / 1000f
                    outroSkipTarget(pos, prevPos, dur, skipSec, maxAdvanceSec = (advance + 0.5f).toLong())?.let { t ->
                        outroDone = true
                        // 跳到结尾而不是「按暂停等用户」：seek 到末尾让播放器自己进
                        // ENDED，下面那套「连播倒计时 / 已是最后一集」全按 ENDED 走 ——
                        // 跳过片尾和看着它自己播完，后续行为就该一模一样。
                        // 落点用播放器的毫秒原值：拿秒截断的 dur*1000 会差最多 999ms，
                        // 那半秒还得等它自己播完才进 ENDED。
                        player.seekTo(player.duration.coerceAtLeast(t * 1000))
                        pos = t
                        if (!inPip) hint = "已跳过片尾"
                    }
                }
                prevPos = pos
            }
            val (hs, sel) = runCatching {
                val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val heights = groups
                    .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i).height } }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                // isSelected 才是「这一条真的在被放」。只读清单的话，拿到的永远是
                // 「这条流有几档」，那不是「画质」这个问题的答案。
                val selected = groups.filter { it.isSelected }
                    .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i).height } }
                    .filter { it > 0 }
                    .maxOrNull() ?: 0
                heights to selected
            }.getOrDefault(emptyList<Int>() to 0)
            if (hs.isNotEmpty() && hs != videoHeights) {
                // 换集 / 换源之后是另一条流，上一流那一档不作数 —— 先当不知道，等它自己报
                videoHeights = hs
                playingHeight = 0
            }
            if (sel > 0 && sel != playingHeight) playingHeight = sel
            // 顺手记一下画面自己的宽高比：「自适应」要靠它把视口收窄（见下面那个 Box）。
            // 顺便报一句「现在能按什么形状进小窗」：画面还没解出来就是 null，
            // 那时候按 Home 就还是普通退出 —— 小窗是给已经在放的东西留的。
            val ratio = playerDisplayRatio(player)
            if (ratio != null && ratio > 0f && ratio != videoAspect) videoAspect = ratio
            vm.publishPipRatio(ratio)
            // 缓冲中把采样加密到 100ms：进度条的分母只有 1.2~2.5 秒，
            // 按平时的 500ms 一采只能画出四五帧，进度看着是跳的而不是走的。
            // 只在 BUFFERING 期间加密，正常播放仍走 500ms —— 上面那些
            // currentTracks / 轨道查询都不便宜，不该为了进度条一直付这份钱。
            //
            // sleptMs 给下一跳的稳定时长用。睡的这一跳里状态若正好从 BUFFERING 翻成
            // READY，下一跳会多算进最多 100ms —— 对一个 20 秒起的窗口无关紧要，
            // 不值得为它换成监听器驱动。
            sleptMs = if (state == Player.STATE_BUFFERING) 100L else 500L
            delay(sleptMs)
        }
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    /**
     * 缓冲等待计时：只在缓冲中走秒，出缓冲就停。
     *
     * 单独一条 effect 而不是塞进那个 500ms 轮询里：轮询的频率本身会变
     * （缓冲时加密到 100ms），拿它的节拍去数秒，两处改一处就会对不上。
     */
    LaunchedEffect(buffering) {
        while (buffering) {
            delay(1_000)
            if (buffering) bufferingSec += 1
        }
    }

    LaunchedEffect(maxVideoHeight) {
        // 走 PlayerBuffering 那一份，别在这里另写一套 setMaxVideoSize ——
        // 两处各改各的，早晚会有一处把用户手选的画质档冲掉
        player.trackSelectionParameters = PlayerBuffering.clampToUserChoice(
            player.trackSelectionParameters,
            maxVideoHeight,
        )
    }

    LaunchedEffect(abrMaxBitrate) {
        // ABR 码率上限只有这一个应用点：重缓冲把它压下去、恢复循环把它抬回来，
        // 都落在这里写进 trackSelectionParameters —— 不再另开一条直写参数的路。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoBitrate(
                if (abrMaxBitrate == Long.MAX_VALUE) Int.MAX_VALUE
                else abrMaxBitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
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
    // 锁屏时也不收 —— 弹出来了这一屏就没有「锁」可言了。
    val stuck = errText != null || playing?.streamUrl == null
    LaunchedEffect(controls, lastUi, wantsPlay, panel, stuck, screenLocked) {
        // 出错时**锁要让位**：那一屏上挂着「重试 / 换源」这些唯一出口，
        // 锁着它们等于把用户关在一个放不出东西的播放器里。锁不住他还会回来锁。
        if (stuck) {
            controls = true
            return@LaunchedEffect
        }
        if (screenLocked) {
            controls = false
            return@LaunchedEffect
        }
        if (controls && wantsPlay && panel == null) {
            delay(3500)
            controls = false
        }
    }

    /**
     * 「换过 N 次才播起来 · 现在是 …」这一句 10 秒后自己收，收法和点 × 是同一件事
     * （[AppViewModel.dismissFallbackNotice]）。
     *
     * 只有这一态收：「正在试」是现场，收了等于说没在重试；「都放不出来」底下挂着
     * 「再试一轮」那一格，收了就把唯一的出口藏了。
     *
     * 上面那份控件倒数是 3.5 秒 —— 跟着它一起收的话，这句话在没人抬头看字的时机就没了，
     * 而换源是应用自己做的，不是用户点的。10 秒是「这句至少被读一遍」那条线；
     * 收掉之后源名还在顶栏「正在播放 · x」那一行，随控件一起出现。
     *
     * 键里没有横幅内容：手动换一次线路会改写「现在是 …」那一行，跟着重置就永远收不掉。
     * 展开着读失败清单时不倒数，折回去重新数 10 秒。
     */
    LaunchedEffect(fallback?.settled, fbExpanded) {
        if (fallback?.settled == true && !fbExpanded) {
            delay(SETTLED_NOTICE_MS)
            vm.dismissFallbackNotice()
        }
    }

    /**
     * 退到后台就把播放和轮询一起停下。
     *
     * 影枢没有前台服务，也不发播放通知 —— 放任它在后台放着，用户既找不到入口也关不掉，
     * 只剩一个还在响的手机；而那条 500ms 的位置轮询会一路把同一个位置反复写进观看记录。
     * 回来时不自动续：没有通知、没有锁屏控件的情况下「自己又放起来了」比「要再点一下」更让人慌。
     *
     * 唯一的例外是小窗：上面那三条抱怨的「看不见、关不掉」，那颗悬浮窗本身就把入口和
     * 关得掉都给了，所以它里面不许停。
     *
     * 判「在小窗里吗」读的是 **Activity 现在的真实状态**，不是 [AppViewModel.inPip] 那个标志位：
     * 用户点小窗的 × 时，onStop 可能先于 onPictureInPictureModeChanged(false) 到达 ——
     * 那一刻标志位还是 true，只认它就会把这一停豁免掉，声音于是留在后台。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            val stillInPip = activity?.isInPictureInPictureMode == true
            if (player.playWhenReady && !stillInPip) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 小窗被关掉时停住。
     *
     * 点小窗那个 ×，有的系统不把 Activity 收掉、只把窗口藏起来 —— 那条路上这里
     * 收不到有效的 ON_STOP（进小窗时那次已被「在小窗里」豁免），于是画面没了、
     * 声音还在后台响。Activity 确认「没回到前台」后递来这一声，这里停播放，
     * 并把最后那一段位置补写一次（平时每 10 秒才落一次盘，不补就丢了）
     * 和其余设置一并留给下次续播。
     *
     * 收的是 `SharedFlow`（replay = 0）而不是一个时间戳：这一声是一次性的，
     * 没人听的时候就该丢掉。早先那版把它存在 StateFlow 里且从不清零，于是**下一次**
     * 进播放器时这个 effect 会拿着陈旧的值再跑一遍，把用户刚点的这一集当场暂停。
     */
    LaunchedEffect(Unit) {
        vm.pipDismissed.collect {
            if (player.playWhenReady) {
                // 先落一次盘：小窗里放到的位置不该因为「还没到那 10 秒」就丢掉
                runCatching { vm.updatePosition(msToSec(player.currentPosition), msToSec(player.duration)) }
                player.pause()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 小窗的发起权在 Activity，它只认这一份报来的比例；这一屏卸了就必须说「不进小窗」，
            // 否则在首页按 Home 也会缩出一颗放着上一部片的小窗。
            vm.publishPipRatio(null)
            // 本机 key 代理是跟着播放器活的：播放器还在读它那份 m3u8 / 密钥，
            // 端口就不能关。放在 release 后面 —— 先让播放器放手，再收端口。
            // 引用计数见 LocalKeyProxy.acquire：换集、换线路都会重放一次媒体源，
            // 那期间端口必须还在，所以只有退出这一屏才真的收到 0。
            playing?.sourceId?.let { sid ->
                vm.releaseKeyProxy(sid)
            }
            player.release()
        }
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

    /**
     * 小窗里只留画面。
     *
     * 不是一块一块去收：全屏那份开关本来就把顶栏、右侧竖排切源和下方选集抽屉全关掉了，
     * 借它一次就够，出小窗再把用户刚才那一档原样还回去。
     * 控件、浮层和手势反馈另收 —— 那颗小窗只收得到系统的拖动和关闭，
     * 在里面摆一颗点不动的「重试」比什么都不摆更骗人。
     */
    var fullscreenBeforePip by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(inPip) {
        if (inPip) {
            if (fullscreenBeforePip == null) fullscreenBeforePip = fullscreen
            fullscreen = true
            controls = false
            panel = null
            hint = null
        } else {
            fullscreenBeforePip?.let { fullscreen = it; fullscreenBeforePip = null }
            // 锁着的时候出小窗**不把控件拉回来**：那颗小窗里只有画面，
            // 用户啥也点不了就回了应用 —— 他走之前锁屏，回来第一眼看到的
            // 应该还是锁着的那块画面，而不是一整排按钮糊上来。
            if (!screenLocked) controls = true
            lastUi = System.currentTimeMillis()
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
        DownloadStatus.Running ->
            if (cacheTask.hasPercent) "缓存 ${(cacheTask.progress * 100).toInt()}%" else "缓存中"
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
            // 这几样必须跟着换集一起传下去：观看记录是按 mediaKey 写的，
            // 少传就等于「从第二集开始，看到哪儿都不再记」—— 换完集退出再进来，
            // 进度还停在第一集，而且一点报错都没有。
            mediaKey = p.mediaKey,
            mediaYear = p.mediaYear,
            mediaCover = p.mediaCover,
            // 集号没变就等于还在同一集里换线路：那一轮自动重试的账得留着。
            // 原来这里一律重开一轮，于是手动换一次线路，
            // 「哪个源为什么不行」那张流水和剩下的候选就一起没了。
            autoAdvance = e.index == p.episodeIndex,
        )
    }

    fun stepEpisode(delta: Int) {
        // 查法交给 nextEpisodeOf：当前集在本条线路的清单里找不到（线路之间集号对不上）时不动。
        // 以前 -1 加一挡就落到清单第一集，用户按「下一集」看到的是第 1 集。
        val next = nextEpisodeOf(episodes, playing?.episodeIndex ?: -1, delta) ?: return
        playEpisode(next)
    }

    /** 本条线路里正在播的这集后面还有没有：连播横幅与倒计时共用这一份判断。 */
    val curInList = episodes.any { it.index == playing?.episodeIndex }
    val nextEp = if (curInList) nextEpisodeOf(episodes, playing?.episodeIndex ?: -1, 1) else null

    // 自动连播：这一集真播到结尾后留 3 秒反悔窗口，到点接下一集。
    //
    // 键里带上「播完没有、开关、取消位、哪一集哪个源哪条线路」：换集/换线路时这一轮
    // 自动作废，不会出现旧那一集的倒计时切新那一集的下一集。醒来时再核一遍
    // **播放器此刻真的还在 ENDED**（协程闭包里的 playerEnded 是创建那一刻的快照，
    // 用户在窗口里拖回进度条重新播了，这里就不该抢切），才真的动。
    LaunchedEffect(playerEnded, autoNext, autoNextCancelled, nextEp, playing?.episodeIndex, playing?.sourceId, lineIndex) {
        if (!playerEnded || !autoNext || autoNextCancelled || nextEp == null) return@LaunchedEffect
        delay(AUTO_NEXT_MS)
        if (player.playbackState == Player.STATE_ENDED) stepEpisode(1)
    }

    /**
     * 双击画面（任意处）= 暂停/播放，和底部那颗暂停键、非全屏那一行同一个动作。
     *
     * 判据用 [wantsPlay]（用户意图）而不是 `player.playWhenReady`（播放器当下）：
     * ExoPlayer 在缓冲、加载失败时也会把 playWhenReady 翻成 false，认它就会「暂停「暂停」。
     * 同一份 `if (playWhenReady) pause() else play()` 在别处出现三次了（全屏暂停键、
     * 非全屏那一行、播放失败重试），这里是第四次 —— 该收进一处了。
     */
    fun togglePlay() {
        // 先取这一刻的意图再动手： wantsPlay 是监听器回写的，pause() 之后
        // 那一笔还没到（它在主线程下一次回调里），晚一拍读就会把文案说反。
        val wasPlaying = wantsPlay
        if (wasPlaying) player.pause() else player.play()
        // 双击之后画面本身没有变化（除非正好停在尾帧），给一句反馈 ——
        // 用户不知道这一下是暂停了还是没吃上。
        hint = if (wasPlaying) "已暂停" else "播放中"
    }

    /**
     * 锁屏 / 解锁：同一颗按钮的两面（见画面右中那颗 [LockScreenToggle]）。
     *
     * 锁定时顺手把 [panel] 也收掉：浮层开着时控件倒数是停着的（见上面那条 effect 的
     * `panel == null` 条件），不关就变成「锁屏了但选集面板还开着」的半锁状态。
     */
    fun lockScreen() {
        screenLocked = true
        panel = null
        controls = false
        hint = "已锁定 · 点右侧图标解锁"
    }

    fun unlockScreen() {
        screenLocked = false
        // 解锁后让控件出来一下：这一屏刚才是全冻结的，不摆出控件，
        // 用户第一眼看到的还是一块「点哪都没反应」的画面。
        controls = true
        lastUi = System.currentTimeMillis()
        hint = null
    }

    fun seekBy(deltaMs: Long) {
        // 时长还没解出来时 `duration` 是 C.TIME_UNSET（负的哨兵）。原来那句
        // `coerceAtLeast(0)` 把钳位变成 `coerceIn(0, 0)` —— 于是准备阶段双击、
        // 按 ±10s 都会**跳到 0:00**，而提示还写着「快进 10 秒」。不知道总长就不许跳：
        // 这一格本来就还没法定一个合法的落点。
        val dur = player.duration
        val cur = player.currentPosition
        if (dur <= 0L || cur < 0L) return
        val target = (cur + deltaMs).coerceIn(0L, dur)
        player.seekTo(target)
        hint = if (deltaMs > 0) "快进 ${deltaMs / 1000} 秒" else "快退 ${-deltaMs / 1000} 秒"
        controls = true; lastUi = System.currentTimeMillis()
    }

    // 系统返回键与左上角那颗箭头走同一个判断，见 goBack()
    fun goBack() {
        when {
            // 锁着的时候返回键什么都不做：口袋里蹭一下就把人甩出播放器 / 退出全屏，
            // 而这一屏的锁本来就是为那个场合设的。说一句话告诉他怎么解。
            screenLocked -> hint = "已锁定 · 点右侧图标解锁"
            panel != null -> panel = null
            fullscreen -> fullscreen = false
            else -> onBack()
        }
    }
    BackHandler { goBack() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // BoxWithConstraints 是为了知道「这一屏自身多大」：画面区按内容宽高比收缩之后，
        // 渐变罩、手势层、右侧浮层、锁屏那颗图标四处都要按「画面区多大」摆，
        // 而 Box 撑的是整块屏幕 —— 两个数不是一回事（见下面 areaW / areaH）。
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val boxW: Dp = maxWidth
            val boxH: Dp = maxHeight
            // 自适应（fit）的画面区按内容自己的宽高比收窄。
            //
            // 原来的样子：PlayerView 撑着整个 Box（也就是整块屏幕），再把 16:9 的画面
            // FIT 进去 —— 于是上下各留一条**与画面同宽**的黑带， controls 的背景渐变
            // 还得压在那两条带子上。用户看到的就是「上下一条大黑边 + 控件叠在黑边上」。
            //
            // 现在让装画面的那个 Box 自己按比例收：**高度**按宽高比算，上下那两块归
            // 顶栏与底部控件。画面一个像素都没少（FIT 还是 FIT），但黑边不再是
            // 「一块占着地方的死区」，而是控件名正言顺的落脚处 —— 横片竖看时尤其明显：
            // 原来控件压在两条大黑带上，现在它们待在画面外面自己的地方。
            //
            // 高度算法（不是 aspectRatio 修饰符）：按比例算出来的高度可能**超过** Box 高
            // （竖屏源、4:3 老片，宽高比小于「Box 宽 / Box 高」时就是），那时候按
            // aspectRatio + fillMaxWidth 会把内容顶出 Box 被父布局裁掉。所以这里自己算：
            // 宽高比够宽就按宽度算高（画面横向铺满，上下收缩）；否则按高度算宽（画面
            // 纵向铺满，左右收缩）—— 两种情况都是「一边贴满、另一边按比例」，
            // 而 PlayerView 的 FIT 会在剩下的那一点余量里居中，最终黑白边只剩一条。
            //
            // 这一对数是这一屏「画面区有多大」的**唯一**说法：装画面的 Box、手势层、
            // 两块渐变罩、右侧浮层、锁屏那颗图标的位置，五处都读它。
            // 各算一遍正是这次改岔子的来源：手势层原来挂着 aspectRatio（拿 Box 宽去算，
            // 窄片时算出来的高度超过 Box 高，手势区会跑到画面外面），浮层自己抄了一份
            // boxW / ratio（同样只在宽片时成立），图标那份也是 —— 那次修完只剩这一处。
            val (areaW, areaH) = fitVideoArea(boxW, boxH, resizeMode, videoAspect)
            if (playing?.streamUrl != null) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(areaW)
                        .height(areaH)
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { c ->
                            PlayerView(c).apply {
                                useController = false
                                // this. 不能省：外层那个同名 val（全局偏好，String）
                                // 会把这里的 PlayerView.resizeMode（Int）整个遮掉
                                this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { view ->
                            view.player = player
                            // 画面比例跟着全局偏好走：update 每次重组都会跑，
                            // 值没变时 setFrameLayoutResizeMode 内部自己去重
                            view.resizeMode = when (resizeMode) {
                                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF0B1220), Color(0xFF05070C), Color.Black))
                    ),
                )
            }

            if (!fullscreen || controls) {
                // 两块渐变罩也只铺**画面区**（收缩后的那一块）。
                //
                // 原来它们是按整块屏幕铺的：收缩之后画面下方多出一块干净的底色，
                // 底部那块 180dp 的罩子就会从画面外面开始发黑 —— 控件像是浮在一块
                // 与画面无关的黑布上，而那块布边缘还能看出一条直线。
                // 跟着画面区走，罩子的上边正好压在画面下边缘上，跟不收缩时一个样。
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(areaW)
                        .height(areaH),
                ) {
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
            }

            // ---------- 手势层：在控件之下，吃掉画面区的拖动与点击 ----------
            //
            // 尺寸跟**画面区**一致（自适应收窄后的那个 Box），不是整块屏幕：
            // 收窄之后上下那两块归控件，手指点在控件区里已经是在点控件，
            // 手势层再铺一层就会把「点一下收起控件」和「点空白处显隐控件」搅在一起。
            //
            // 锁屏时整块**不挂**而不是「挂着、里面判一个 locked 开关」：
            // awaitEachGesture 一旦启动就跑完整个指头生命周期，判开关只是白跑一遍，
            // 还得为「锁着时第一下要不要记 lastTap」这种小事操心。不挂就是真的没有手势 ——
            // 单击/双击/拖进度/音量/亮度一起消失，只剩画面右中那一颗解锁图标
            // （它在更上面一层，自己 clickable 吃掉自己的那一下）。
            if (!screenLocked) {
                Box(
                    Modifier
                        .width(areaW)
                        .height(areaH)
                        .align(Alignment.TopCenter)
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
                            onTogglePlay = { togglePlay() },
                            onToggleControls = {
                                controls = !controls
                                lastUi = System.currentTimeMillis()
                            },
                        ),
                )
            }

            LaunchedEffect(brightness) {
                if (!brightness.isNaN()) activity?.let { applyBrightness(it, brightness) }
            }

            // ---------- 触屏锁：锁屏/解锁同一颗按钮 ----------
            //
            // 位置固定在画面右中：锁着的时候它是唯一还能点的东西（用户伸手就能摸到，
            // 不必等控件弹出 —— 控件在锁定态根本不弹）；没锁的时候它是颗低调的图标，
            // 点一下即锁。锁屏期间它不自动隐藏 —— 一隐藏就再没有人能打开这一屏。
            //
            // 小窗里不摆：那颗窗只有巴掌大，图标比画面还占地方；而小窗本来就是为了
            // 「盯着它」才开的，那种场合下锁不锁无差别。
            //
            // 非全屏也不摆，锁只在横屏全屏里成立：竖屏预览那一屏，右侧竖排切源栏和
            // 底部控件不走 [controls] 那道闸（它们本来就该常驻），锁了也拦不住它们 ——
            // 半锁比不锁更骗人。而锁的场景（揣兜里、横着放）本来就只发生在全屏，
            // 竖屏预览是「正在用这台手机看详情」，不是口袋。
            if (fullscreen && !inPip) {
                // 这一颗要贴在**画面**的右中，不是整个 Box 的右中。
                //
                // 画面区收缩之后两者不再是同一个地方：Box 还撑着整块屏幕，而画面只占上面
                // 一段，CenterEnd 会落到画面下面那块空处里。所以竖向上按画面高度算一次偏移
                // （画面顶天立着，中心 = 画面高度的一半；没收缩时那个偏移恰好是 0，
                // 因为画面高度就是 Box 高度）。
                //
                // 窄片（4:3、竖屏源）时画面是左右收缩、高度仍然满的，areaH == boxH，
                // 偏移正好是 0 —— 同一个式子宽片窄片都对，不必再判一次内容形状。
                LockScreenToggle(
                    locked = screenLocked,
                    onClick = {
                        if (screenLocked) unlockScreen() else lockScreen()
                    },
                    // align 必须由调用方给：这颗要贴在画面右中，而 composable 自己
                    // 不知道会被放进哪种父布局里（这里是 BoxScope，能 align）。
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        // 把「Box 中心」挪到「画面中心」：画面顶着上边，所以向上挪半个差值
                        .offset(y = (areaH - boxH) / 2),
                )
            }

            // ---------- 顶栏 ----------
            if (!fullscreen || controls) {
                Row(
                    Modifier.fillMaxWidth().padding(Sp.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f))
                            // 和系统返回键同一套判断：全屏 + 面板开着时，这颗不该直接把人甩出播放器
                            .clickable { goBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(17.dp))
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
                            fontSize = Fs.xs,
                        )
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

            // ---------- 自动换源的告知 ----------
            // 不跟着 controls 一起收：源是被系统换掉的，不是用户自己点的，控件那 3.5 秒
            // 一过就没了等于悄悄换了个源。各态各自的活法见上面那份倒数 ——
            // 「正在试」和「都放不出来」常驻到它有下文，「换过 N 次才播起来」留 10 秒。
            // 小窗里没有这一句的位置：那颗只装画面，字挤进去谁也读不了，
            // 回到应用再点一下控件，源名就在顶栏那一行。
            if (!inPip) fallback?.let { fb ->
                val last = fb.failures.lastOrNull()
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = Sp.s4,
                            end = Sp.s4,
                            top = if (!fullscreen || controls) 92.dp else Sp.s4,
                        )
                        .widthIn(max = 460.dp)
                        .clip(R.md)
                        .background(Color.Black.copy(alpha = .74f))
                        .border(1.dp, if (fb.exhausted) T.Err.copy(alpha = .45f) else T.Border, R.md)
                        .clickable { fbExpanded = !fbExpanded }
                        .padding(horizontal = Sp.s3, vertical = Sp.s2),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        // 一格 = 一个「源 · 线路」组合，不是「一个源」：同一个源有两条线路时
                        // 先把它的第二条试掉再换源，所以这里报的次数会比源多。
                        // 只有一条线路的源不带线路名 —— 它没有得选，写上就是凑话。
                        Text(
                            when {
                                fb.exhausted ->
                                    if (fb.failures.any { it.lineName != null })
                                        "试了 ${fb.failures.size} 次 · ${fb.sourceCount} 个源，都放不出来这一集"
                                    else "${fb.sourceCount} 个源都放不出来这一集"
                                fb.settled -> "换过 ${fb.failures.size} 次才播起来 · 现在是 " +
                                    fb.tryingSourceName + (fb.tryingLineName?.let { " · $it" } ?: "")
                                else -> "正在试 · 第 ${fb.step} 次 · ${fb.tryingSourceName}" +
                                    (fb.tryingLineName?.let { " · $it" } ?: "")
                            },
                            color = Color.White,
                            fontSize = Fs.xs,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer4()
                        Text(
                            "×",
                            color = T.TextDim,
                            fontSize = Fs.sm,
                            modifier = Modifier.size(20.dp).clip(CircleShape)
                                .clickable { vm.dismissFallbackNotice() },
                        )
                    }
                    if (!fb.settled && !fb.exhausted && last != null) {
                        Text(
                            "刚才 ${last.sourceName}${last.lineName?.let { " · $it" } ?: ""}：${last.reason}",
                            color = T.TextDim,
                            fontSize = Fs.xs,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (fbExpanded) {
                        fb.failures.forEach { a ->
                            Text(
                                "· ${a.sourceName}${a.lineName?.let { " · $it" } ?: ""} —— ${a.reason}",
                                color = T.TextDim,
                                fontSize = Fs.xs,
                                lineHeight = Fs.xs * 1.5f,
                            )
                        }
                        if (fb.exhausted) {
                            Spacer4()
                            Text(
                                "再试一轮",
                                color = T.Src500,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(R.full)
                                    .border(1.dp, T.Src500.copy(alpha = .5f), R.full)
                                    .clickable { vm.retryFallbackRound() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // 小窗里这一整串（拿不到直链 / 播放失败 / 已暂停那颗 / 缓冲中）都不摆：
            // 那颗窗只收得到系统的拖动和关闭，摆一颗点不动的「重试」比留黑屏更骗人。
            // 回应用里操作时控件照旧，那句报错就在那儿等着。
            if (inPip) {
                // 什么都不摆，画面占满那颗窗
            } else if (playing?.streamUrl == null) {
                // 直链拿不到时给出明确原因，而不是留一个黑屏
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
                                "换一个源",
                                color = T.Src500,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(R.full)
                                    .clickable { panel = Panel.Source }
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
                                    // prepare() 之后播放器会回到这一段的开头，所以位置得先抓下来、
                                    // 起播以后再放回去。原来这里是裸 prepare()+play()：
                                    // 看到二十分钟处报了一次错，点「重试」就回到 0:00，
                                    // 而那句「续播」的功劳全在给媒体源的那一处，这里没接上。
                                    val back = player.currentPosition.let {
                                        if (it < 0L || it == androidx.media3.common.C.TIME_UNSET)
                                            (playing?.resumeFrom ?: 0L) * 1000
                                        else it
                                    }
                                    // 这一笔重新算一次「刚起播」：首帧标记归位、缓冲态挂上，
                                    // 再看门狗换一个键重新起一轮 —— 不然重试后卡在缓冲里没人管
                                    firstFrame = false
                                    buffering = true
                                    attemptTick++
                                    player.prepare()
                                    player.play()
                                    if (back > 0L) player.seekTo(back)
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer8()
                        Text(
                            "换一个源",
                            color = T.Src500,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(R.full)
                                .clickable { panel = Panel.Source }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            } else if (playerEnded && !inPip && !screenLocked && (nextEp != null || curInList)) {
                // 这一集播到结尾：有下一集就摆连播倒计时（和上面那条 effect 同一个闸门：
                // 开关关着、或本集已取消，这里也不谎称「3 秒后」，只说播放完成）；
                // 没下一集就老实说播完了。这里**不看 wantsPlay**：ExoPlayer 到结尾会
                // 自动把 playWhenReady 翻成 false，认它等于横幅永远不出现。
                // 用户把进度条拖回去，STATE 离开 ENDED、playerEnded 跟着归位，这句自然消失。
                Column(
                    Modifier.align(Alignment.Center)
                        .clip(R.md).background(Color.Black.copy(alpha = .55f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        nextEp != null && autoNext && !autoNextCancelled -> {
                            Text(
                                "${AUTO_NEXT_MS / 1000} 秒后播放下一集：${nextEp.name}",
                                color = Color.White,
                                fontSize = Fs.sm,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer4()
                            Text(
                                "取消",
                                color = T.Src400,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(R.full)
                                    .border(1.dp, T.Src500.copy(alpha = .5f), R.full)
                                    .clickable { autoNextCancelled = true }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        nextEp == null && curInList -> Text(
                            "已是最后一集",
                            color = Color.White,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                        )
                        else -> Text(
                            "播放完成",
                            color = Color.White,
                            fontSize = Fs.sm,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else if (!wantsPlay && !buffering && !screenLocked) {
                // 锁着的时候这颗**不出现**：暂停不是事故，画面停着 + 中央一颗能点的播放键，
                // 等于告诉用户「这屏没锁」—— 而它恰恰是锁定态下除解锁图标外唯一的可点物。
                // 想让他继续播，先解锁，这是锁的应有之义。
                // （错误卡和换源告知那两枝**不**加这一道：那是逃生口，锁住等于把人
                // 关在放不出东西的播放器里 —— 见上面控件倒数处同样的取舍。）
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
                // 缓冲提示报几件分开的事实，不报百分比 ——
                // 为什么百分比在这条链路上没有意义，见 PlayerBuffering.bufferingLabel 的注释
                // （一句话：ExoPlayer 拿数据是成批灌满几十秒的，百分比成了阶跃函数）。
                // 速度那一项只在真的量到时才写（没量到就整项不出现，不编数字）；
                // 而「三秒没有新数据」是量到的结论，要写出来 —— 追播中卡住时，
                // 那句「已缓冲 N 秒」自己不会动，没这一项整行字就是静止的。
                Text(
                    PlayerBuffering.bufferingLabel(bufAheadMs, bufferingSec, bufSpeed, bufStalledMs),
                    color = Color.White,
                    fontSize = Fs.xs,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 手势反馈 —— 只能由手势写，而那颗窗收不到手势（见上面进小窗时先清掉那一句），
            // 所以这里不再问一遍在不在小窗里
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
                    // 竖排只摆三个，多的得有个说法、也得有个出口：
                    // 底下明明还查到两个源，屏上却像「就这三个」，那是把列表裁掉了还不承认。
                    if (siblings.size > 3) {
                        Column(
                            Modifier.width(50.dp).clip(R.md)
                                .background(Color.Black.copy(alpha = .55f))
                                .border(1.dp, Color.White.copy(alpha = .14f), R.md)
                                .clickable { panel = Panel.Source }
                                .padding(vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "+${siblings.size - 3}",
                                color = T.TextDim,
                                fontSize = Fs.tiny,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("换源", color = T.TextFaint, fontSize = Fs.tiny)
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
                            bufferedSec = buf,
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
                                Chip("倍速 ${fmtSpeed(speed)}") {
                                    customSpeed = speed
                                    panel = Panel.Speed
                                }
                                Spacer8()
                                // 画面比例三档走面板：轮换式一点一圈，按快了会跳过想要的那档，
                                // 也不知道下一点落到哪 —— 摆成三个选项，看一眼再选。
                                Chip("画面 ${resizeLabel(resizeMode)}") { panel = Panel.Resize }
                                Spacer8()
                                if (siblings.size > 1 || sibQuery != SiblingsQuery.Done) {
                                    // 「源」和「线路」不是一回事：源是懂片帝AI、4kku 这种一整个站，
                                    // 线路是同一个站里给出的几套地址。全屏时右边那排竖着的切源栏
                                    // 让位给画面，这一格就是唯一的出口。
                                    Chip(
                                        if (siblings.size > 1) "源 ${siblings.size}" else "源",
                                    ) { panel = Panel.Source }
                                    Spacer8()
                                }
                                if (lines.size > 1) {
                                    // 条数一并写出来：懂片帝AI 那种源有十几条，只报「线路 2」
                                    // 用户不知道后面还有得挑
                                    Chip("线路 ${safeLine + 1}/${lines.size}") { panel = Panel.Line }
                                    Spacer8()
                                }
                                if (videoHeights.isNotEmpty()) {
                                    // 报的是**此刻真在放**的那一档。原来这里报的是设置项
                                    // （没封顶就写「自动」）—— 而「自动」既不是画质也不是档，
                                    // 用户问「现在几 P」它答不上来。还没量到就退回说上限，
                                    // 并且把「≤」写出来：那一格陈述的是设置，不是画面。
                                    Chip(
                                        when {
                                            playingHeight > 0 -> "画质 ${playingHeight}P"
                                            maxVideoHeight > 0 -> "画质 ≤${maxVideoHeight}P"
                                            else -> "画质 最高"
                                        },
                                    ) { panel = Panel.Quality }
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
                        }
                    }
                }
            }

            // ---------- 右侧浮层：选集 / 线路 / 画质 / 倍速 ----------
            panel?.let { p ->
                // 浮层也只覆盖**画面区**：原来它是整块屏幕的一块半透明罩 + 右侧一列，
                // 画面区收缩之后，罩子会盖到画面下面那块本来归控件的干净区域，
                // 看起来像「面板把整个屏幕都盖住了」—— 而它该盖的只是画面。
                Box(
                    Modifier
                        .width(areaW)
                        .height(areaH)
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = .45f))
                        .clickable { panel = null },
                )
                Column(
                    Modifier.align(Alignment.TopEnd)
                        .height(areaH)
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
                                // 跳到指定集：4 列网格在 200 集的剧上得滚很久，手找第 157 集
                                // 纯属折磨。输入集号回车 = 直接播那一集（和点那一格同一个动作）。
                                if (episodes.size > JUMP_MIN_EPISODES) {
                                    JumpToEpisodeRow(
                                        hint = "集号 1-${episodes.maxOf { it.index }}",
                                        onJump = { n ->
                                            val e = episodes.firstOrNull { it.index == n }
                                            if (e == null) {
                                                // 走不通要说一句话：输错集号却「按了没反应」最难查
                                                vm.showNotice("这一条线路没有第 $n 集")
                                            } else {
                                                playEpisode(e)
                                                panel = null
                                            }
                                        },
                                    )
                                    Spacer8()
                                }
                                // 线路面板（Panel.Line）是 verticalScroll 套滚动列，照做一遍：
                                // 固定 4 列 × 高度只到浮层底，一屏 5 行，200 集滚不到底。
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    // 每格 34dp + 格间距 7dp：n 行 = 41n − 7。写**死值**是关键：
                                    // heightIn(min=…) 在 verticalScroll 里拿到的是「无上限」约束，
                                    // LazyVerticalGrid 在无上限高度下会直接抛异常；
                                    // 写死之后它是一个有界块，懒加载照常，外层容器负责滚。
                                    modifier = Modifier.fillMaxWidth().height(
                                        ((episodes.size + 3) / 4 * 41 - 7).dp,
                                    ),
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
                            vm.speedChoices.forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().clip(R.sm)
                                        .background(
                                            if (s == speed) T.Play500.copy(alpha = .16f) else Color.Transparent
                                        )
                                        .clickable { vm.setPlaybackSpeed(s); panel = null }
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
                            // 自定义：那几档之间想要 1.1x、1.75x 的人，拖一下就有。
                            // 上下限就是仓库 clamp 的那一段（见 speedRange）——
                            // 这里不另抄字面量，两处各列各的早晚会有一处摆出对方存不下的值。
                            // 0.05 一格：再细手抖一下就换值，读数跟不上眼睛。
                            // 拖完松手才落盘（onValueChange 只动本地预览）：
                            // 每像素一次 DataStore 写没有意义，偏好记的是「他最后停在哪」。
                            Spacer8()
                            val lo = vm.speedRange.start
                            val hi = vm.speedRange.endInclusive
                            Text(
                                "自定义 ${fmtSpeed(customSpeed)}x",
                                color = if (customSpeed != speed) T.Play400 else T.TextDim,
                                fontSize = Fs.xs,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Slider(
                                value = customSpeed.coerceIn(lo, hi),
                                onValueChange = { customSpeed = ((it * 20f).roundToInt() / 20f).coerceIn(lo, hi) },
                                onValueChangeFinished = { vm.setPlaybackSpeed(customSpeed) },
                                valueRange = vm.speedRange,
                                colors = SliderDefaults.colors(
                                    thumbColor = T.Play400,
                                    activeTrackColor = T.Play500,
                                    inactiveTrackColor = T.Border,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "松手就生效 · 点上面任一格回整档",
                                color = T.TextFaint,
                                fontSize = Fs.xxs,
                            )
                            // 非整档也算「选中」：自定义到 1.50x 时那一档就该亮起来，
                            // 别让人对着两行「都对」的界面猜现在到底几倍速。
                        }

                        Panel.Resize -> {
                            Text("画面", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            // 三档各配一句它是干什么的：轮换式按一圈人不知道刚才跳过了什么，
                            // 选项摆开了每一档都解释得清 —— 尤其「铺满」那档，不说是给
                            // 宽高比写错的源准备的，没人想得到它为什么存在。
                            //
                            // 档位清单取自仓库（vm.resizeModeChoices），这里只补一句说明：
                            // 照旧在界面上抄一遍 "fit"/"fill"/"zoom" 的话，仓库将来加第四档
                            // 存得下、界面上选不到。
                            val resizeDesc = mapOf(
                                "fit" to "等比显示，画面完整、四周留黑边",
                                "fill" to "拉满整个窗口；宽高比写错的源拉平了反而正常",
                                "zoom" to "等比放大裁到没有黑边，画面四边会超出窗口",
                            )
                            vm.resizeModeChoices.forEach { mode ->
                                val desc = resizeDesc[mode]
                                Row(
                                    Modifier.fillMaxWidth().clip(R.sm)
                                        .background(
                                            if (mode == resizeMode) T.Play500.copy(alpha = .16f)
                                            else Color.Transparent
                                        )
                                        .clickable { vm.setPlaybackResizeMode(mode); panel = null }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Column {
                                        Text(
                                            resizeLabel(mode),
                                            color = if (mode == resizeMode) T.Play400 else T.Text,
                                            fontSize = Fs.sm,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        // 新档没配说明就不摆那一行：空着比写一句「（暂无说明）」干净
                                        if (desc != null) {
                                            Spacer4()
                                            Text(
                                                desc,
                                                color = T.TextFaint,
                                                fontSize = Fs.xxs,
                                                lineHeight = Fs.xs,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Panel.Line -> {
                            Text("线路", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            // 懂片帝AI 那种源一次能给出十几条线路，一屏摆不下 —— 滚动列，别把它们裁掉
                            Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            ) {
                                lines.forEachIndexed { i, l ->
                                    val cur = playing?.episodeIndex ?: 1
                                    // 这一条线路有没有正在播的这一集。缺集是真实存在的：
                                    // 自动换源那套 nextLine 就专门跳过这种线路。
                                    val has = l.episodes.any { it.index == cur }
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(R.sm)
                                            .background(
                                                if (i == safeLine) T.Play500.copy(alpha = .16f) else Color.Transparent
                                            )
                                            .clickable(enabled = has) {
                                                // 换线路 = 换一套地址，集数按 index 对齐，
                                                // **进度也要跟着过去**：还是同一部片的同一集，
                                                // 从 0 开始等于把看到一半的位置扔了。
                                                //
                                                // 读的是一刻的 player.currentPosition，不是
                                                // vm.resumePosition：那个活值会被轮询和 stop() 冲成 0。
                                                // 播放器已经进 IDLE（报错以后、还在解析以后）时它是
                                                // TIME_UNSET —— 那正是最需要换线路的时候，于是退回
                                                // 这一笔起播时冻结的 resumeFrom，而不是当 0 处理。
                                                val raw = player.currentPosition
                                                val at = if (raw < 0L || raw == androidx.media3.common.C.TIME_UNSET) {
                                                    (playing?.resumeFrom ?: 0L).coerceAtLeast(0L)
                                                } else {
                                                    msToSec(raw)
                                                }
                                                l.episodes.firstOrNull { it.index == cur }
                                                    ?.let { playEpisode(it, keepPos = at) }
                                                // 只有真的换了才翻页。原来这里无条件 lineIndex = i：
                                                // 缺集的那一条点下去什么也没播，芯片却翻成「线路 i/n」、
                                                // 分集列表跟着换成那一套 —— 屏上说的和耳里放的对不上。
                                                lineIndex = i
                                                panel = null
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            l.name.take(18),
                                            color = if (i == safeLine) T.Play400 else T.Text,
                                            fontSize = Fs.sm,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 按不动的那一条要自己把原因说出来，不能只把点击吞掉
                                        if (!has) {
                                            Text(
                                                "没有第 $cur 集",
                                                color = T.TextFaint,
                                                fontSize = Fs.xs,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Panel.Source -> {
                            Text("换源", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                if (siblings.isEmpty()) {
                                    // 空着分两种：起播时手上就没有这份比对（从首页、继续观看直接进来），
                                    // 和查过了但谁也没对上。别摆一排看不见的源冒充可选，也别把前者说成后者。
                                    when (sibQuery) {
                                        SiblingsQuery.Running -> Text(
                                            "正在挨个源比对这一部…",
                                            color = T.TextFaint, fontSize = Fs.xs, lineHeight = Fs.xs * 1.6f,
                                        )
                                        SiblingsQuery.Idle -> {
                                            Text(
                                                "这一部还没跟其它源比对过 —— 从首页或继续观看直接进播放器时，" +
                                                    "手上没有这份结果。",
                                                color = T.TextFaint, fontSize = Fs.xs, lineHeight = Fs.xs * 1.6f,
                                            )
                                            Spacer8()
                                            Text(
                                                "查一次",
                                                color = T.Play400, fontSize = Fs.sm,
                                                modifier = Modifier.fillMaxWidth().clip(R.sm)
                                                    .background(T.Surface2)
                                                    .clickable { vm.queryPlayingSiblings() }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            )
                                            Spacer8()
                                            Text(
                                                "这一趟要把每个源都搜一遍，几秒起。",
                                                color = T.TextFaint, fontSize = Fs.tiny, lineHeight = Fs.tiny * 1.6f,
                                            )
                                        }
                                        SiblingsQuery.Done -> Text(
                                            "其它源里没对上这一部。",
                                            color = T.TextFaint, fontSize = Fs.xs, lineHeight = Fs.xs * 1.6f,
                                        )
                                    }
                                }
                                siblings.forEach { av ->
                                    val on = av.sourceId == playing?.sourceId
                                    val h = health[av.sourceId]?.health ?: av.health
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clip(R.sm)
                                            .background(
                                                if (on) T.Play500.copy(alpha = .16f) else Color.Transparent
                                            )
                                            .clickable {
                                                vm.switchPlayingSource(
                                                    av, playing?.episodeIndex ?: 1,
                                                )
                                                panel = null
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            StatusDot(h)
                                            Spacer4()
                                            Text(
                                                av.sourceName,
                                                color = if (on) T.Play400 else T.Text,
                                                fontSize = Fs.sm,
                                            )
                                            av.quality?.let {
                                                Spacer4()
                                                Text(it, color = T.TextFaint, fontSize = Fs.tiny)
                                            }
                                        }
                                        Text(
                                            av.item.title.ifBlank { "同一部片" },
                                            color = T.TextFaint,
                                            fontSize = Fs.tiny,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (siblings.size == 1 && sibQuery == SiblingsQuery.Done) {
                                    Spacer8()
                                    Text(
                                        "其它源里没对上这一部 —— 现在这个源是唯一对得上的。",
                                        color = T.TextDim, fontSize = Fs.xs, lineHeight = Fs.xs * 1.6f,
                                    )
                                }
                                if (sibGaps.isNotEmpty()) {
                                    Spacer8()
                                    Text(
                                        "另有 ${sibGaps.size} 个源没答上话：" +
                                            sibGaps.joinToString("、") { "${it.sourceName}（${it.reason}）" },
                                        color = T.TextDim, fontSize = Fs.xs, lineHeight = Fs.xs * 1.6f,
                                    )
                                }
                            }
                        }

                        Panel.Quality -> {
                            Text("画质", color = T.Text, fontSize = Fs.sm, fontWeight = FontWeight.Bold)
                            Spacer8()
                            (listOf(0) + videoHeights).forEach { h ->
                                Text(
                                    // 「自动」那一项原本光写「按网络」—— 它其实是不封顶，
                                    // 也就是最高那一档起、撑不住才降。名字直接说清楚，
                                    // 免得以为选了它和选 1080P 是两件不同的事。
                                    if (h == 0) "最高（撑不住才降）" else "${h}P",
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
                                // 这一行是这一格里唯一一句关于**画面**的话，上面那些是设置。
                                if (playingHeight > 0) "此刻在放 ${playingHeight}P。"
                                else "还没量到 —— 这一条流解不出档位之前，那一格说的是设置，不是画面。",
                                color = T.TextDim,
                                fontSize = Fs.xs,
                                lineHeight = Fs.xs * 1.6f,
                            )
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
 * 单击 = 显隐控件；双击（画面任意处）= 暂停/播放。
 *
 * 双击不再分左右：用户要的是「连点两下就暂停」，落在哪都是暂停才符合这句话。
 * 以前两侧双击是 ±10 秒，那一档现在由底部那两颗 ⟲10 / ⏩10 按钮独家承担 ——
 * 快进退是有准头的动作（看清了再按），暂停是本能动作（点就是了），本能不该为准头让路。
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
    onTogglePlay: () -> Unit,
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
                    // 双击画面任意处 = 暂停/播放。第一下已经把控件显隐过一次（见下面的
                    // else 分支），所以这一下只管播不播，不再去动控件 —— 否则双击一次屏幕闪两下。
                    onTogglePlay()
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
    bufferedSec: Long = 0L,
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
    // 缓冲段：始终画（起播后它通常领先播放位置一大截）；拖动中收掉，
    // 拖的是「要去哪儿」，缓冲段的参考意义反而碍事
    val bufFrac = if (!dragging && durationSec > 0) {
        (bufferedSec.toFloat() / durationSec).coerceIn(frac, 1f)
    } else 0f
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
        if (bufFrac > 0f) {
            // 已缓冲段：比轨道亮、比播放位置暗 —— 它说的是「这些已经在本地了」
            Box(
                Modifier.fillMaxWidth(bufFrac).height(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = .42f)),
            )
        }
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

/**
 * 「跳到指定集」那一行。
 *
 * 200 集的剧在 4 列网格里找第 157 集要滚很久 —— 输入集号回车就播那一集。
 * 只给超过 [JUMP_MIN_EPISODES] 集的条目挂（短剧一屏就摆得下，挂一行纯属占地方）。
 */
@Composable
private fun JumpToEpisodeRow(hint: String, onJump: (Int) -> Unit) {
    // 数字键盘 + 「前往」：数字键直接给数字行，ImeAction.Go 让回车就是「去」
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun submit() {
        val n = text.trim().toIntOrNull()
        if (n != null && n > 0) {
            onJump(n)
            text = ""
            focus.clearFocus()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(R.sm)
            .background(T.Surface2)
            .border(1.dp, T.Border, R.sm)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(hint, color = T.TextFaint, fontSize = Fs.xs)
            }
            BasicTextField(
                value = text,
                onValueChange = { s -> if (s.length <= 4) text = s.filter(Char::isDigit) },
                singleLine = true,
                textStyle = TextStyle(color = T.Text, fontSize = Fs.sm),
                cursorBrush = SolidColor(T.Play400),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        // 键盘弹着时回车可能被 IME 吃掉（个别输入法不发 onGo），这颗就是那条兜底
        Text(
            "跳转",
            color = if (text.isEmpty()) T.TextFaint else T.Play400,
            fontSize = Fs.xs,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(R.full)
                .background(T.Play500.copy(alpha = if (text.isEmpty()) .06f else .16f))
                .clickable(enabled = text.isNotEmpty()) { submit() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 集数超过这个数才显示「跳到指定集」：短剧一屏摆得下，那一行就是占地方。 */
private const val JUMP_MIN_EPISODES = 41

@Composable
private fun LockScreenToggle(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 锁与开锁共用一颗按钮，只是换个图标、换个底色。
    // 锁定态底色更实、图标用警示色 —— 那一态下画面上只剩它能点，
    // 得让人一眼看到「锁着」这件事本身，别以为是画面卡了。
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (locked) .45f else .22f))
            // 和无锁态其他画面图标一个待遇：锁着时的 36dp 是刻意留出来的命中区，
            // 不想让人在口袋里乱摸时还要瞄着一个小点戳。
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "解锁" else "锁屏",
            tint = if (locked) T.Err else Color.White.copy(alpha = .78f),
            modifier = Modifier.size(18.dp),
        )
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

/**
 * 倍速读数。先按百分位归拢再判：1.05f 存成 float 是 1.04999…，直接 trimEnd('0') 会印成「1」，
 * 而 0.05 一格正是自定义滑杆的步进 —— 拖到那一格读数必须说 1.05。
 * 百分位是 0 就一位小数（1.0、1.3），不是 0 才两位（1.05、1.75）。
 *
 * `internal`：这一条被单测钉着（浮点边界太容易说错话），不给 `private`。
 */
internal fun fmtSpeed(s: Float): String {
    val r = (s * 100f).roundToInt()
    return if (r % 10 == 0) "%.1f".format(r / 100f) else "%.2f".format(r / 100f)
}

/** 「画面」芯片上的档位名。认不出的值按自适应说 —— 和仓库那一侧的兜底同一个口径。 */
private fun resizeLabel(mode: String): String = when (mode) {
    "fill" -> "铺满"
    "zoom" -> "裁切"
    else -> "自适应"
}

/**
 * 自适应（fit）的画面区占多大：按内容自己的宽高比收窄，让上下那两块黑边归控件。
 *
 * 原来的样子是 PlayerView 撑着整个 Box（也就是整块屏幕），再把 16:9 的画面 FIT 进去 ——
 * 于是上下各留一条**与画面同宽**的黑带，控件的背景渐变还得压在那两条带子上。
 * 用户看到的就是「上下一条大黑边 + 控件叠在黑边上」。
 *
 * 现在装画面的那个 Box 自己按比例收：画面一个像素都没少（FIT 还是 FIT），
 * 但黑边不再是「一块占着地方的死区」，而是顶栏与底部控件名正言顺的落脚处 ——
 * 横片竖看时尤其明显：原来控件压在两条大黑带上，现在它们待在画面外面自己的地方。
 *
 * 为什么不用 `Modifier.aspectRatio`：按比例算出来的高度可能**超过** Box 高
 * （竖屏源、4:3 老片，宽高比小于「Box 宽 / Box 高」时就是），那时候
 * `aspectRatio + fillMaxWidth` 会把内容顶出 Box 被父布局裁掉。所以按哪一边为基准
 * 分两种情况算：宽高比够宽就按宽度算高（画面横向铺满，上下收缩）；否则按高度算宽
 * （画面纵向铺满，左右收缩）—— 两种都是「一边贴满、另一边按比例」，
 * 而 PlayerView 的 FIT 会在剩下的那一点余量里居中，最终黑白边只剩一条。
 *
 * 「拉满」和「放大」不收窄：那两种本来就是让 PlayerView 自己去裁去拉，
 * 画面区收窄了反而和它对着干。宽高比还解不出来（null 或非正数）时按整块 Box 算 ——
 * 等它自己报，那一瞬之后会重排。
 *
 * 这一屏有**五处**读这个数：装画面的 Box、手势层、两块渐变罩、右侧浮层、
 * 以及锁屏那颗图标的纵向偏移（它要贴在画面右中，而 Box 的右中不是画面的右中）。
 * 五处各算一遍正是出过事的地方 —— 手势层曾挂着 aspectRatio（拿 Box 宽去算，
 * 窄片时算出的高度超过 Box 高，手势区跑到画面外面），浮层与图标也各自抄了一份
 * `boxW / ratio`。所以收成这一个纯函数，谁也别再写第二遍。
 */
internal fun fitVideoArea(
    boxW: Dp,
    boxH: Dp,
    resizeMode: String,
    videoAspect: Float?,
): Pair<Dp, Dp> {
    if (resizeMode in setOf("fill", "zoom") || videoAspect == null || videoAspect <= 0f) {
        return boxW to boxH
    }
    val ar = videoAspect
    return if (ar * boxH.value >= boxW.value) {
        // 画面比 Box 更「宽」⇒ 以宽为基准，上下收缩
        boxW to (boxW.value / ar).dp
    } else {
        // 画面比 Box 更「窄」（竖片、4:3）⇒ 以高为基准，左右收缩
        (boxH.value * ar).dp to boxH
    }
}

/**
 * 画面现在的宽高比，交给小窗决定那颗的形状；还没解出画面就是 null。
 *
 * `unappliedRotationDegrees` 是播放器还没替界面转掉的那一份 —— 小窗的形状要按转完之后的算，
 * 不然竖着拍的片子会拿到一颗横的窗。折错的代价只是那颗窗的形状
 * （画面本身由 PlayerView 的 FIT 管着），不改播放。
 */
private fun playerDisplayRatio(player: Player): Float? {
    val s = player.videoSize
    if (s.width <= 0 || s.height <= 0) return null
    val wide = s.width * s.pixelWidthHeightRatio / s.height
    return if (s.unappliedRotationDegrees % 180 != 0) 1f / wide else wide
}

/**
 * 起播宽限：地址拿到之后，这么久还渲染不出第一帧就当一个源放不出来，交给自动换源。
 *
 * 20 秒是照着「慢但能起」的下限挑的 —— 再短就会把一次正常的蜂窝网抖动误判成坏源。
 */
private const val STARTUP_GRACE_MS = 20_000L
