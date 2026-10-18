package net.cinehub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.cinehub.data.DoubanRepository
import net.cinehub.data.DownloadRepository
import net.cinehub.data.MediaRepository
import net.cinehub.data.PlaybackRepository
import net.cinehub.data.SeedSync
import net.cinehub.data.SourceRepository
import net.cinehub.data.WatchRepository
import net.cinehub.data.quietly
import net.cinehub.data.shortReason
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.DoubanChart
import net.cinehub.data.model.DoubanItem
import net.cinehub.data.model.DownloadTask
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Favorite
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.HistoryEntry
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.qualityTagOf
import net.cinehub.data.model.SearchOutcome
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.titleFingerprint
import net.cinehub.data.model.titleSearchVariants
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.model.normalizeTitle
import net.cinehub.data.model.userNote
import net.cinehub.data.net.LocalKeyProxy
import net.cinehub.data.source.SourceAdapters

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sourceRepo = SourceRepository(app)
    private val mediaRepo = MediaRepository(sourceRepo)
    private val watchRepo = WatchRepository(app)
    private val doubanRepo = DoubanRepository(app)
    private val downloadRepo = DownloadRepository(app, sourceRepo, mediaRepo)
    private val playbackRepo = PlaybackRepository(app)

    /**
     * 一次性的一句话告知，界面那侧弹出来。
     *
     * 「点了没反应」是最难自己发现的 bug：入口看着是活的，按下去既没结果也没说法。
     * 凡是走不通的分支都该在这儿留一句 —— 尤其那些 `?: return` 的入口。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun showNotice(text: String) { _notice.value = text }

    fun consumeNotice() { _notice.value = null }

    val downloads: StateFlow<List<DownloadTask>> = downloadRepo.tasks

    /** 缓存占掉的字节数。仓库在协程里数好，界面只读这个值。 */
    val downloadSpace: StateFlow<Long> = downloadRepo.space

    /** 盘上那份任务清单这一版读不通了（[net.cinehub.data.DownloadRepository.unreadable]）。 */
    val downloadsUnreadable: StateFlow<Boolean> = downloadRepo.unreadable

    /** 每个任务当前的下载速度（字节/秒）。只在内存里，重启后从 0 重新算。 */
    val downloadSpeed: StateFlow<Map<String, Long>> = downloadRepo.speed

    /** 单集内同时下几个分片（「我的 → 下载设置」里调，对正在下的任务不生效）。 */
    val segParallel: StateFlow<Int> = downloadRepo.segParallel

    /** 那两个上限要说实话就得从仓库拿，别在界面上抄第二份写死的字。 */
    val downloadMaxParallel: Int get() = downloadRepo.maxParallelEpisodes
    val downloadMaxSegParallel: Int get() = downloadRepo.maxSegParallel

    fun setSegParallel(n: Int) = downloadRepo.setSegParallel(n)

    /**
     * VM 没了（Activity 真的销毁）就把下载引擎也关掉。
     *
     * 不关的话，退出应用再进来会得到第二颗引擎：两份内存清单指向同一批目录和同一个
     * DataStore 键，而「同时最多 3 集」那个闸门变成每颗一份 —— 实际并发翻倍。
     * 盘上已缓存的东西一律不动，用户下次进来照旧能离线播。
     */
    override fun onCleared() {
        downloadRepo.close()
        super.onCleared()
    }

    // ---------- 播放偏好 ----------

    /** 全局倍速：不分作品、不分源，起播/换线路/换集都读它，用户调一次就一直是那一档。 */
    val playbackSpeed: StateFlow<Float> = playbackRepo.speed
    val speedChoices: List<Float> get() = playbackRepo.speedChoices
    fun setPlaybackSpeed(value: Float) = playbackRepo.setSpeed(value)

    /** 自动连播：播完一集后要不要倒计时接下一集。全局一份，与倍速同一语义。 */
    val autoPlayNext: StateFlow<Boolean> = playbackRepo.autoNext
    fun setAutoPlayNext(value: Boolean) = playbackRepo.setAutoNext(value)

    /** 画面比例模式（fit/fill/zoom），全局一份，起播、换集都照它摆。 */
    val playbackResizeMode: StateFlow<String> = playbackRepo.resizeMode
    val resizeModeChoices: List<String> get() = playbackRepo.resizeModes
    fun setPlaybackResizeMode(value: String) = playbackRepo.setResizeMode(value)

    /** 「画面」芯片一次点击轮换到的下一档：fit → fill → zoom → fit。 */
    fun nextResizeMode(current: String): String {
        val modes = resizeModeChoices
        val i = modes.indexOf(current)
        return if (i < 0) modes.first() else modes[(i + 1) % modes.size]
    }

    // ---------- 小窗播放 ----------

    /**
     * 播放器这一屏每半秒报一次的「此刻视频的宽高比」，`null` 表示不进小窗 ——
     * 画面还没解出来，或者播放器那一屏早就不挂着了。
     *
     * 为什么在 VM 里递这一句：进小窗只能由 Activity 发起（只有它收得到「用户按了 Home」），
     * 而「放得开与否、什么形状」只有播放器那一屏知道。
     */
    private val _pipRatio = MutableStateFlow<Float?>(null)
    val pipRatio: StateFlow<Float?> = _pipRatio.asStateFlow()
    fun publishPipRatio(ratio: Float?) { _pipRatio.value = ratio }

    /** 此刻在小窗里吗：Activity 写，界面读它把控件、浮层、告知横幅全收掉。 */
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()
    fun setInPip(on: Boolean) { _inPip.value = on }

    /**
     * 「小窗被关掉了（不看了）」这一声。
     *
     * 点小窗那个 × 有两种收场，取决于系统：有的直接把 Activity 收掉，有的只是把窗口藏起来、
     * 进程和 Activity 都留着。后一种才是会出事的那条 —— 播放器那一屏的「退后台就停」
     * 依赖 ON_STOP，而进小窗时那次 ON_STOP 已经被「在小窗里」豁免掉了，关小窗时
     * 又不会再收到一次，于是声音留在后台一直响，用户还找不到任何入口关掉它。
     *
     * Activity 在离开小窗后确认「没回到前台」时递一声过来（见 MainActivity），
     * 播放器那一屏据此暂停并把最后那一段位置补写一次。
     *
     * 这是一件**一次性的事**，所以用 SharedFlow 而不是 StateFlow 里存一个时间戳。
     * 存时间戳那版有个致命的坑：值只增不清，而播放器那一屏是按 `route` 装卸的 ——
     * 下一次进播放器（新组合）时 `LaunchedEffect(key)` 会拿着这个**陈旧的**值再跑一遍，
     * 于是用户刚点的这一集起播就被暂停，屏上还没有任何解释；顺便把跨源续播的锚点
     * 也冲成 0（见 [updatePosition]，它在 `sec <= 0` 早退之前就写了 `_resumePosition`）。
     * `replay = 0` 正是要的语义：没人听的时候这一声就丢掉 —— 那一屏都不在了，
     * 播放器也早就 release 了，没有什么可停的。
     */
    private val _pipDismissed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val pipDismissed: SharedFlow<Unit> = _pipDismissed.asSharedFlow()
    fun notePipDismissed() { _pipDismissed.tryEmit(Unit) }

    /** 收藏与最近观看。名字避开 [history] —— 那个是搜索历史。 */
    val favorites: StateFlow<List<Favorite>> = watchRepo.favorites
    val watchHistory: StateFlow<List<HistoryEntry>> = watchRepo.history

    /** 盘上那份收藏/观看记录这一版读不通了（[net.cinehub.data.WatchRepository.unreadable]）。 */
    val watchUnreadable: StateFlow<Boolean> = watchRepo.unreadable

    // ---------- 豆瓣榜单 ----------

    /**
     * 片库当前显示的那张榜。默认落在「剧集 · 热门」，和首页那一片内容同一个出处 ——
     * 两个 Tab 冷启动看到的是同一批片，切榜只影响片库自己。
     */
    private val _doubanChart = MutableStateFlow(DoubanChart.TV.first())
    val doubanChart: StateFlow<DoubanChart> = _doubanChart.asStateFlow()

    private val _doubanItems = MutableStateFlow<List<DoubanItem>>(emptyList())
    val doubanItems: StateFlow<List<DoubanItem>> = _doubanItems.asStateFlow()

    private val _doubanLoading = MutableStateFlow(false)
    val doubanLoading: StateFlow<Boolean> = _doubanLoading.asStateFlow()

    /** 拉取失败的原因。列表还在的时候它是一条附注，不是把列表换掉。 */
    private val _doubanError = MutableStateFlow<String?>(null)
    val doubanError: StateFlow<String?> = _doubanError.asStateFlow()

    /** 当前这份列表是什么时候取回来的；0 = 还没成功过。 */
    private val _doubanAt = MutableStateFlow(0L)
    val doubanAt: StateFlow<Long> = _doubanAt.asStateFlow()

    /** 正在按片名去源里找的那一条的 id，用来在卡片上给个反馈。 */
    private val _doubanResolving = MutableStateFlow<String?>(null)
    val doubanResolving: StateFlow<String?> = _doubanResolving.asStateFlow()

    /** 正在找的那一片叫什么 —— 只用来给「被这一闸门挡住」那一句说出挡的是谁。 */
    private var doubanResolvingTitle: String? = null

    /** 落盘那份源列表这一版读不通了（[net.cinehub.data.SourceRepository.unreadable]）。 */
    val sourcesUnreadable: StateFlow<Boolean> = sourceRepo.unreadable

    // ---------- 播放源 ----------
    private val _sources = MutableStateFlow<List<SourceConfig>>(emptyList())
    val sources: StateFlow<List<SourceConfig>> = _sources.asStateFlow()

    private val _health = MutableStateFlow<Map<String, HealthReport>>(emptyMap())
    val health: StateFlow<Map<String, HealthReport>> = _health.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    // ---------- 首页 ----------

    /**
     * 首页那一片内容：豆瓣「剧集 · 热门」这一张榜。
     *
     * 它是片库状态之外**另立的一份**，不复用 [_doubanItems] —— 那个跟着片库的选榜走，
     * 用户在片库切到「美剧」就把首页也换了脸，而首页只有一个出处。
     */
    private val _home = MutableStateFlow<List<DoubanItem>>(emptyList())
    val home: StateFlow<List<DoubanItem>> = _home.asStateFlow()

    /** 这份榜单是几点取回来的；0 = 还没有。 */
    private val _homeAt = MutableStateFlow(0L)
    val homeAt: StateFlow<Long> = _homeAt.asStateFlow()

    private val _homeLoading = MutableStateFlow(false)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private val _homeError = MutableStateFlow<String?>(null)
    val homeError: StateFlow<String?> = _homeError.asStateFlow()

    // ---------- 搜索 ----------
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<AggregatedMedia>>(emptyList())
    val results: StateFlow<List<AggregatedMedia>> = _results.asStateFlow()

    /** 这次搜索里根本没查动的源（连同短原因）。空列表 = 每个源都答了「没有」。 */
    private val _searchFailures = MutableStateFlow<List<SearchOutcome.Failure>>(emptyList())
    val searchFailures: StateFlow<List<SearchOutcome.Failure>> = _searchFailures.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /**
     * 上一次**真的发出去**搜过的那个词；`null` = 这一屏还没搜过。
     *
     * 搜索页要判断「说『都没有〈关键词〉』有没有依据」。以前它拿搜索历史兜底
     * （`history.firstOrNull() == query`），而历史是**跨会话持久**的：成功搜过「三体」
     * 之后，重进搜索页、只在框里再打一遍这三个字（一个请求都没发），屏上就会写
     * 「检索了 N 个播放源，都没有「三体」」—— 正是那一行注释声称要避免的假话。
     * 所以这个信号只在 [search] 里立、在框子改了字或这一屏被擦掉时落。
     */
    private val _searchedTerm = MutableStateFlow<String?>(null)
    val searchedTerm: StateFlow<String?> = _searchedTerm.asStateFlow()

    // ---------- 详情 ----------
    private val _detail = MutableStateFlow<MediaDetail?>(null)
    val detail: StateFlow<MediaDetail?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

    /**
     * 「换源没换成」这一件专门的事。
     *
     * 以前详情页把 [_detailError] 同时当两件事用：整页加载失败、和换源失败。后果是
     * 换源失败一次之后，再点「重新检测」就算成功了，那一栏还挂着「还在原来这个源上：
     * 〈上一笔换源的错误〉」—— 而这一句说的是刚发生的事，不是历史。分开记，
     * 换源这一笔一开始就清、成功就清。
     */
    private val _switchError = MutableStateFlow<String?>(null)
    val switchError: StateFlow<String?> = _switchError.asStateFlow()

    private val _siblings = MutableStateFlow<List<SourceAvailability>>(emptyList())
    val siblings: StateFlow<List<SourceAvailability>> = _siblings.asStateFlow()

    /**
     * [_siblings] 是**哪一部片**的那份查询结果。
     *
     * 播放器起播时要决定能不能把这份带过去 —— 列表本身看不出来它是为谁查的，
     * 而条目自己的 key 又常常和查询用的那条差一年（各源年份写法不一致）。
     * 记下出处，比拿 key 去猜可靠。
     */
    private var _siblingsOf: String? = null

    /** 查这一部片的其它源时，根本没答上话的那几个（界面要单独说一句）。 */
    private val _siblingFailures = MutableStateFlow<List<SearchOutcome.Failure>>(emptyList())
    val siblingFailures: StateFlow<List<SearchOutcome.Failure>> = _siblingFailures.asStateFlow()

    /** 当前详情页选中的源。 */
    private val _activeSourceId = MutableStateFlow<String?>(null)
    val activeSourceId: StateFlow<String?> = _activeSourceId.asStateFlow()

    // ---------- 播放器 ----------
    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing.asStateFlow()

    /** 跨源续播的位置（秒）。换源时带过去，这就是「切源不丢进度」。 */
    private val _resumePosition = MutableStateFlow(0L)
    val resumePosition: StateFlow<Long> = _resumePosition.asStateFlow()

    data class Playing(
        val mediaTitle: String,
        val episodeName: String,
        val episodeIndex: Int,
        val sourceName: String,
        val sourceId: String,
        val streamUrl: String?,
        /** 该集的播放页地址，用于「重试」时重新解析。 */
        val episodeUrl: String? = null,
        val note: String? = null,
        /** 源站根地址 —— 播放器要带着它做 Referer，CDN 才认。 */
        val sourceBaseUrl: String? = null,
        /**
         * 「在站点页面里解析」该打开的地址。
         *
         * 源站给了自己的解析端时它是那个端的页面（在那里源站会像对自己的播放器一样
         * 把流交出来）；没给就退回这一集的播放页。两者不是一回事：
         * 直接打开爱奇艺的页面只会看到「APP内打开」。
         */
        val sitePageUrl: String? = null,
        /** 接住的地址被页面请求时携带的条件，起播时原样带给播放器。 */
        val streamHeaders: Map<String, String> = emptyMap(),
        /** 缓存这一集时要写进任务的键；本地回放也带着它，切回在线源才对得上。 */
        val mediaId: String? = null,
        /**
         * 这部片的归并键与卡片字段。
         *
         * 观看记录要按 [Media.key] 存，而位置是播放器每 10 秒报上来的 ——
         * 那一刻手上只有 Playing，所以起播时就把这几样带在身上。
         */
        val mediaKey: String? = null,
        val mediaYear: String? = null,
        val mediaCover: String? = null,
        /** 播的是本机缓存时，这部片原本属于哪个源 —— [sourceId] 那时是 "local"。 */
        val originSourceId: String? = null,
        /**
         * 这一次播放请求要从第几秒开始。
         *
         * 它必须跟着请求冻结在这里，不能读 [_resumePosition]：那个值是播放器每 500ms
         * 报上来的活值，而换源时媒体源重建会先 `player.stop()` —— 那一刻位置是 0，
         * 轮询会把 0 写回去。等新地址解析完（HTML 抓取 + 线路探测，通常不止 500ms）
         * 再去读，读到的就是 0，「切源不丢进度」当场失效，还会把这个假位置落进记录。
         */
        val resumeFrom: Long = 0L,
    )

    /** 正在解析直链 —— 播放器靠它显示「已收到你的点击」，而不是一个不动的黑屏。 */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    // ---------- 站点页面取链 ----------

    /**
     * 要在站点自己的页面里打开的地址，以及它是给哪个源用的。
     *
     * 只有播放这一条链路会打开它：播放器上的「在站点页面里解析」，以及取链走到
     * 「地址要站点自己的页面现算」那一格时的自动打开（见 [play]）。「播放源管理」里
     * 那个「站点」入口已经去掉 —— 这一页只管源本身，开对方的页面是取链的手段，
     * 不该在用户只是在列表里翻一个源时冒出来。
     * [referer] 是源站根地址 —— 源站的解析端按来源域名放行，
     * WebView 不带它就会被当成陌生域名拒掉（实测西瓜影院的解析页就这么回过话）。
     */
    data class Browser(
        val startUrl: String,
        val label: String,
        val referer: String? = null,
    )

    private val _browser = MutableStateFlow<Browser?>(null)
    val browser: StateFlow<Browser?> = _browser.asStateFlow()

    fun openSiteBrowser(startUrl: String, label: String, referer: String? = null) {
        _browser.value = Browser(startUrl, label, referer)
    }

    fun closeSiteBrowser() { _browser.value = null }

    /**
     * 把站点页面里接住的地址交给当前这一集。
     *
     * 只换 streamUrl，源、Referer、这一集都不动 —— 换源会让 Referer 对不上，
     * 而这条地址恰恰是原来那个源给出来的。
     *
     * [headers] 是那条地址被页面请求时带的条件（Referer / Cookie）。
     * 一起带过去，播放器才可能拿到和页面一样的响应 —— 只抄 URL 过去，
     * 那些「只认自己页面」的地址会 404。
     *
     * **位置要跟着挪**：`resumeFrom` 是起播那一刻冻结的（换源时必须冻结，见那一段注释），
     * 而这条路是「同一集换一条地址」，不是换源。不把当前位置并进去，一个看到 45:00
     * 的人挑对了地址之后会被送回这一集开头 —— 「接住站点给的地址在本机接着播」就成了空话。
     * 取两边的较大值：用户可能刚拖过进度条（活值更大），也可能活值还没建起来（冻结值更大）。
     */
    fun useSiteLink(link: String, headers: Map<String, String> = emptyMap()) {
        val cur = _playing.value ?: return
        if (!link.startsWith("http")) return
        _playing.value = cur.copy(
            streamUrl = link,
            note = null,
            streamHeaders = headers,
            resumeFrom = maxOf(cur.resumeFrom, _resumePosition.value),
        )
    }

    /**
     * 「这一次对内置源做了什么」那一句，只在真的动过的这一次启动里有话。
     *
     * 不藏：悄悄多一个源、悄悄换一条地址，和悄悄少一个源是同一种毛病，都得让用户看得见。
     * 一句话把三种动作报齐（补回 / 重写 / 撤下），因为「内置源已按新版更新」这句笼统话
     * 不足以让人知道他自己加的那几条有没有被动过。
     *
     * **不说「这次更新」**：内置源每次启动都跟版本对一次，而「上次删掉的那条又回来了」
     * 发生在普通重启里，跟更新无关。写「这次更新」就是替一次普通启动安了个不存在的由头。
     */
    private val _seedNotice = MutableStateFlow<String?>(null)
    val seedNotice: StateFlow<String?> = _seedNotice.asStateFlow()
    fun dismissSeedNotice() { _seedNotice.value = null }

    /**
     * 内置源同步这一趟跑完了没有。
     *
     * 「有 N 个内置源不在列表里 · 补回」那条横幅不能只看当前列表：同步是一笔异步写，
     * 而 `sources` 这条流会在它落地**之前**先把盘上那份发出来 —— 上一次会话里删掉的内置源
     * 于是先以「缺失」的样子闪一下，几十毫秒后才被补回来。那等于替一次正常启动报了假警，
     * 而这一版恰恰规定「缺的会自动补回来」。所以横幅要等这一位翻成 true 再判。
     *
     * 同步失败时也翻 true（在 finally 里翻）：否则横幅会永远不出现，
     * 用户连「手动补回」这条路都没有了。
     */
    private val _seedsReady = MutableStateFlow(false)
    val seedsReady: StateFlow<Boolean> = _seedsReady.asStateFlow()

    // 上面这两位必须声明在 init 之前。`viewModelScope` 跑在 Main.immediate 上：
    // 属性初始化还没轮到它们时，init 里那个协程的第一段就已经在主线程上同步执行了 ——
    // 只要 syncSeeds() 还没挂起就抛（runCatching 接住，finally 照跑），
    // 读到的就是两个还没赋值的 val。顺序摆对，这个地雷不用存在。

    init {
        viewModelScope.launch {
            // 内置源跟版本走：升级后缺的补回来、内容按新版重写（用户自己加的源不动），
            // 然后才量健康度 —— 健康表是照着同步完的那份列表探的。
            try {
                val sync = runCatching { sourceRepo.syncSeeds() }.getOrNull()
                if (sync != null) seedNoticeOf(sync)?.let { _seedNotice.value = it }
            } finally {
                // 失败也要翻：这一位管着「缺失横幅能不能出现」，
                // 卡在 false 上等于把用户手动补回的那条路也一起关掉。
                //
                // 翻之前先把列表重读一遍再放行：`sources` 那条订阅可能还停在同步前那一帧，
                // 于是「缺不缺内置源」会拿旧列表判一次，横幅闪一下又消失。
                // 同步失败时这次重读什么也改不了（盘上那份本来就没动），照旧放行。
                runCatching { sourceRepo.sources.first() }.getOrNull()?.let { _sources.value = it }
                _seedsReady.value = true
            }
            // 这一句不能排在 sources.collect 后面：那是一个永不返回的订阅
            // （DataStore 的流不会结束），排在它后面的代码一行都跑不到。
            // 上一版就是这么把开机首轮探测弄成死代码的 —— 后果是整个列表全灰「未检测」。
            refreshHealth()
        }
        viewModelScope.launch {
            sourceRepo.sources.collect { _sources.value = it }
        }
        // 首页那一份不在这里取：和片库一样，只在真的显示这一屏时才敲豆瓣的门 ——
        // 由 HomeScreen 那边驱动，省得用户直接进「我的」也白取一趟榜单。
    }

    /**
     * 把 [SeedSync] 说成一句话。文案规则见 [_seedNotice] 那段注释。
     */
    private fun seedNoticeOf(sync: SeedSync): String? {
        val parts = buildList {
            if (sync.added > 0) {
                add("补进来 ${sync.added} 个内置源：" + sync.fresh.joinToString("、") { it.name })
            }
            if (sync.updated > 0) add("按这一版重写了 ${sync.updated} 个内置源的内容")
            if (sync.removed > 0) {
                // 报名字：只写「撤下 1 个」的话，用户没法判断少的是哪条 ——
                // 而他手上那条线路突然不见了，第一反应就是「是不是我删错了」。
                add("撤下 ${sync.removed} 个内置源：" + sync.dropped.joinToString("、") { it.name })
            }
        }
        if (parts.isEmpty()) return null
        // 最后一句得说对「不想要怎么办」：内置源删掉会在下次启动补回来（缺的照补），
        // 所以真正管用的动作是**停用** —— 停用是用户自己的决定，同步一律保留。
        // 上一版这里写的是「删了不会再自动加回来」，这一版规则反过来了，话也必须跟着反。
        return "内置源已按这一版对齐：" + parts.joinToString("，") + "。" +
            "你自己添加的源一条都没动，启停和排序也照旧。" +
            "不想用某一条就把它停用 —— 内置源被删掉的话，下次启动会补回来。"
    }

    // ---------- 源管理 ----------

    /**
     * 探一轮所有源的连通性。
     *
     * 三个洞：① 没有 `finally`，探测里抛一下就永远停在转圈（源多、网差时这是常态）；
     * ② 没有代号，前一慢后一新 —— 慢那趟回来会把新那趟的结果盖掉，
     * 而它的 `_checking=false` 还会把新那趟正在转的圈关掉，界面显示的是过期健康表却没有加载痕迹；
     * ③ 多路同时扇出，同一个站被连打几次。
     * 所以和详情一样：只跑一个，后来的顶掉前面的，标记只由当代那一次来关。
     */
    private var healthJob: Job? = null
    private var healthGen = 0

    fun refreshHealth(force: Boolean = true) {
        val mine = ++healthGen
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            try {
                _checking.value = true
                val h = mediaRepo.healthAll(force)
                if (mine == healthGen) _health.value = h
            } finally {
                if (mine == healthGen) _checking.value = false
            }
        }
    }

    /**
     * 详情页的「重新检测」。
     *
     * 不能只刷全局健康表：那一栏渲染的是打开详情那一刻定格的**本片跨源快照**，
     * 每个芯片的毫秒数取的是快照里已经烤好的那份（siblingsOf 里 latencyMs 直接
     * 从健康缓存抄走）。只刷健康表的话，探测真的跑了、表也真的变了，屏幕上一个字
     * 都不动 —— 用户看到的就是「这个按钮没反应」。所以刷完健康表，紧接着把这部片
     * 的跨源比对重跑一遍。
     */
    fun rescanDetail() {
        val d = _detail.value
        if (d == null) {
            refreshHealth(true)
            return
        }
        val mine = ++detailGen
        detailJob?.cancel()
        // 重新检测跑通了，上一笔「换源没换成」那句话就该跟着收回去 —— 它原来会一直挂在面板上
        _switchError.value = null
        // 这一趟本身就在探所有源，所以把并发的那轮普通探测顶掉：
        // 两趟同时扇出等于挨着把每个站打两遍，而且谁先结束不好说，转圈标记会先灭。
        healthJob?.cancel()
        val mineH = ++healthGen
        detailJob = viewModelScope.launch {
            try {
                _checking.value = true
                val h = mediaRepo.healthAll(force = true)
                if (mineH == healthGen) _health.value = h
                if (mine != detailGen) return@launch
                val s = mediaRepo.siblingsOf(d.media)
                if (mine != detailGen) return@launch
                _siblingFailures.value = s.failures
                // 当前这个源仍排第一：那一栏的次序就是「你正在用哪个源」的次序
                val act = _activeSourceId.value
                val list = s.items.sortedByDescending { it.sourceId == act }
                // 「先塞当前源再补齐」这条口径 openDetail 有、这一条没有 —— 于是点一次
                // 「重新检测」就能把面板清空：当前源被停用（siblingsOf 只扇出启用的源），
                // 或这一部在该源自搜搜不回来，items 就是空。空的 siblings 到了界面上
                // 渲染成「正在比对该片在其它源的情况…」，而比对明明已经跑完 ——
                // 那句谎永不撤回，当前源那一格也一起没了。这里按 openDetail 同一口径补上。
                _siblings.value = if (act != null && list.none { it.sourceId == act }) {
                    val cfg = sourceRepo.all().firstOrNull { it.id == act }
                    listOf(
                        SourceAvailability(
                            sourceId = act,
                            sourceName = cfg?.name ?: act,
                            item = d.media,
                            health = _health.value[act]?.health ?: Health.Unknown,
                            quality = qualityOf(d.media),
                            latencyMs = _health.value[act]?.latencyMs,
                        ),
                    ) + list
                } else {
                    list
                }
                _siblingsOf = d.media.key
            } finally {
                // 只由当代这一次关转圈：被顶掉的那趟把新一次的圈关掉，
                // 用户看到的就是「重新检测」点了以后探测还在跑、界面上却已经不动了
                if (mine == detailGen && mineH == healthGen) _checking.value = false
            }
        }
    }

    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch {
        sourceRepo.setEnabled(id, enabled)
    }

    fun deleteSource(id: String) = viewModelScope.launch {
        sourceRepo.delete(id)
        // 实例连带它那份站点会话一起丢：留着它，同名 id 将来被另一个站复用时会带着旧会话去敲门
        SourceAdapters.forget(id)
        refreshHealth(false)
    }

    /**
     * 内置源不在列表里时手动补回。
     *
     * 这一版起内置源在每次启动按 [SourceRepository.syncSeeds] 跟版本走，缺的自己就回来了，
     * 所以这里只剩一个用处：让用户**当场**看见效果，不必重开应用。
     * 走的是同一条并表规则，因此不会出现「手动补的那份和自动同步的那份长得不一样」。
     */
    /**
     * 内置源不在列表里时手动补回。
     *
     * 回传整份 [SeedSync] 而不是只回传「补了几个」：这条路走的是同一条并表规则，
     * 除了补回还可能顺带按新版重写内容，只报一个数就会把另一半动作瞒下来 ——
     * 而界面上那句话是用户唯一能看到的交代。
     */
    fun restoreDefaultSources(onDone: (SeedSync) -> Unit) = viewModelScope.launch {
        val sync = sourceRepo.syncSeeds()
        refreshHealth(true)
        onDone(sync)
    }

    fun reorder(ids: List<String>) = viewModelScope.launch { sourceRepo.reorder(ids) }

    /**
     * 第一步：只探测，不落库。
     *
     * 探测成功的配置会回传给调用方暂存，等用户确认后再 [saveSource]，
     * 避免「测试」这个动作本身就把源写进列表（重复点就会多出重复源）。
     */
    fun testSource(
        name: String,
        baseUrl: String,
        kind: SourceKind,
        authToken: String? = null,
        playPath: String? = null,
        browsePath: String? = null,
        authMode: net.cinehub.data.model.AuthMode = net.cinehub.data.model.AuthMode.Cookie,
        onResult: (ok: Boolean, message: String, tested: SourceConfig?) -> Unit,
    ) = viewModelScope.launch {
        val cfg = buildConfig(name, baseUrl, kind, authToken, playPath, browsePath, authMode)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // 先判能力，再量延迟。原来这两步是反的：probe(站点根路径) 一抛就当场判死，
                // browse() 根本不会被调用 —— 于是「首页对非浏览器客户端回 500、但接口取到得出内容」
                // 的站加不进来（实测 hqvod：浏览器打得开，根路径 500）。内置列表那边有
                // 「健康度一律不当闸门」兜着，用户主动添加这条路没有，所以门槛得架在真信号上。
                val n = try {
                    SourceAdapters.create(cfg).browse(1).size
                } finally {
                    // 探测用的这份配置还没落库，id 是现造的 —— 不丢掉就是每测一次留一份
                    // 适配器（和它那份站点会话）在内存里没人认领
                    SourceAdapters.forget(cfg.id)
                }
                // 探测路径只用来报延迟。它失败不再是「这个源不行」，只是「这一项没量到」。
                val ms = runCatching {
                    net.cinehub.data.net.Http.probe(cfg.probeUrl(), viaBrowser = cfg.needsBrowser)
                }.getOrNull()
                val latency = if (ms != null) " · 平均延迟 ${ms}ms" else " · 根路径没答话（不影响取内容）"
                // 「识别到 0 个资源」不是成功。一个资源都没给，存进来就是个占位的死源。
                if (n == 0) "接口连上了，但列表里一个资源都没给 —— 先搜一次再决定存不存"
                else "识别到 $n 个资源$latency"
            }
        }
        result.onSuccess { msg -> onResult(true, msg, cfg) }
            .onFailure { onResult(false, it.message ?: "连接失败", null) }
    }

    /** 第二步：把已经探测通过的配置落库。 */
    fun saveSource(cfg: SourceConfig) = viewModelScope.launch {
        sourceRepo.upsert(cfg)
        refreshHealth(false)
    }

    private fun buildConfig(
        name: String,
        baseUrl: String,
        kind: SourceKind,
        authToken: String?,
        playPath: String? = null,
        /** 网页解析源的分类页。陌生站点没有它，浏览就只能拿搜索页凑数（见 HtmlAdapter.browse）。 */
        browsePath: String? = null,
        /** 用户选了凭证怎么带。没填凭证时这条没有意义。 */
        authMode: net.cinehub.data.model.AuthMode = net.cinehub.data.model.AuthMode.Cookie,
    ): SourceConfig = SourceConfig(
        // 毫秒时间戳做 id 会撞：连着「探测并保存」两个站点（或者脚本一样的两个源）
        // 完全可能落在同一毫秒里，后存的那个把前一个原地覆盖 —— 界面上是
        // 「我加了两个，只剩一个」。补一段随机，id 才是唯一的。
        id = "src_" + System.currentTimeMillis().toString(36) +
            java.util.UUID.randomUUID().toString().take(4),
        name = name.ifBlank { baseUrl },
        baseUrl = baseUrl.trim().trimEnd('/'),
        kind = kind,
        // 排到最后：默认值 0/1 会和内置源撞上，界面上就同时出现两个「默认」位次
        priority = (_sources.value.maxOfOrNull { it.priority } ?: 0) + 1,
        authToken = authToken?.takeIf { it.isNotBlank() },
        // 用户在这张表单里选的是哪种就按哪种发。以前这里写死 Cookie：
        // 只有 Authorization 头的站点（不少 m3u8 聚合站是这个形态）填了凭证也等于没填，
        // 而界面上那句「已带上凭证」照样亮着 —— 它带的是一个对方不收的头。
        authMode = if (authToken.isNullOrBlank()) net.cinehub.data.model.AuthMode.None else authMode,
        playPath = playPath?.takeIf { it.isNotBlank() },
        // 自建签名接口这一型：后端根路径本来就 404（内容全在 /api 下），
        // 健康栏默认改探一个无签名也回 200 的接口点；用户手动加的这类源不该一进来就红字。
        probePath = when (kind) {
            SourceKind.MinoApi -> "/api/v1/app/tabs"
            else -> null
        },
        html = when (kind) {
            // 通用兜底规则里唯一一格用户能自己填的就是浏览路径 —— 搜索模板对陌生站往往是错的，
            // 而浏览路径给了，这个源才既能在片库里翻、也能在「测试并保存」里被正确判一次。
            SourceKind.Html -> net.cinehub.data.source.DefaultRules.generic.copy(
                browsePath = browsePath?.trim()?.takeIf { it.isNotBlank() }
                    ?: net.cinehub.data.source.DefaultRules.generic.browsePath,
            )
            else -> null
        },
    )

    // ---------- 首页 ----------

    /** 首页固定用这一张榜：豆瓣的「剧集 · 热门」。 */
    private val homeChart = DoubanChart.TV.first()

    /**
     * 同一时刻只让一个首页加载在跑。
     *
     * 进首页调一次（[HomeScreen] 驱动），那一栏的手动刷新再调一次 —— 并发跑就是同时敲
     * 豆瓣两遍、两份结果互相覆盖，谁后落地谁说了算。后发起的那次把前一次顶掉。
     */
    private var homeJob: Job? = null

    /** 第几代加载。只有还是当代的那次才有权改 [_homeLoading]，否则被顶掉的那次会在新的一次已经开始转圈之后把转圈关掉。 */
    private var homeGen = 0

    /**
     * 首页那一页：豆瓣「剧集 · 热门」。
     *
     * 盘上有旧的就先上屏，哪怕它是不知多久以前存的 —— 冷启动对着空白页转圈，而失败那句
     * 「下面还是上次取回的那一页」会对着一片空白说出来，等于骗人。
     * 没有「12 小时内不重取」这一说了：原来那一条是为了别每次进首页都重敲每个源的门，
     * 而现在只剩豆瓣一个出处，进来取一次和片库取一次是一个代价。
     */
    fun loadHome(force: Boolean = false) {
        if (_home.value.isEmpty()) doubanRepo.cached(homeChart.id)?.let {
            _home.value = it.items
            _homeAt.value = it.at
        }
        // 正在取的时候不排队重取：手动那颗按钮自己会变灰，这里是挡住别处同时进来的第二笔
        if (!force && homeJob?.isActive == true) return
        val mine = ++homeGen
        homeJob?.cancel()
        _homeLoading.value = true
        homeJob = viewModelScope.launch {
            try {
                val res = quietly { doubanRepo.fetch(homeChart) }
                if (mine != homeGen) return@launch
                res.onSuccess { items ->
                    doubanRepo.put(homeChart, items)
                    _home.value = items
                    _homeAt.value = System.currentTimeMillis()
                    _homeError.value = null
                }.onFailure {
                    // 落盘那一份是另一条协程读的，冷启动时可能比这一趟失败还晚到。
                    // 认一次旧榜再说这句，免得对着空白页报「没取回任何内容」。
                    if (_home.value.isEmpty()) doubanRepo.cached(homeChart.id)?.let {
                        _home.value = it.items
                        _homeAt.value = it.at
                    }
                    _homeError.value = it.message ?: "没从豆瓣取到内容"
                }
            } finally {
                if (mine == homeGen) _homeLoading.value = false
            }
        }
    }

    /** 手动刷新：现在就再去取一次。 */
    fun refreshHome() = loadHome(force = true)

    // ---------- 搜索 ----------

    /**
     * 搜索框改了字。
     *
     * 改了词就等于上一趟不作了数：不顶掉它的话，框里已经是「三体 2」、屏上还是「三体」的
     * 命中，还挂着一条「正在并发查询…」—— 两趟的账并成一行显示，用户分不清哪条是哪个词的。
     * 在跑的那一笔回来时会发现代号对不上，自己就把结果丢了。
     */
    fun onQueryChange(q: String) {
        if (q == _query.value) return
        ++searchGen
        _query.value = q
        // 改了词 = 这一趟不作数了，连同「搜过没有」一起落下来
        _searchedTerm.value = null
        _searching.value = false
        _results.value = emptyList()
        _searchFailures.value = emptyList()
    }

    /**
     * 搜一次。
     *
     * 两个洞一起补：① 换词时不取消上一次 —— 上一个词的结果晚回来一步，就会盖掉
     * 这一个词的，屏幕上是一串跟搜索框里那三个字无关的片；② 新词发出去到结果回来
     * 那一段，屏上挂的还是上一次的结果，看着就像「搜了，就这些」。
     * 所以先清空、只跑一个，回来的那一次还要对代号。
     */
    private var searchJob: Job? = null
    private var searchGen = 0

    fun search(q: String = _query.value) {
        if (q.isBlank()) return
        val mine = ++searchGen
        searchJob?.cancel()
        _query.value = q
        // 立起「这一词真的发出去过」——搜索页只有拿它才敢说「都没有〈关键词〉」。
        // 立在这里而不是拿搜索历史兜底，见 [_searchedTerm]。
        _searchedTerm.value = q
        _results.value = emptyList()
        _searchFailures.value = emptyList()
        searchJob = viewModelScope.launch {
            try {
                _searching.value = true
                val out = mediaRepo.search(q)
                if (mine != searchGen) return@launch
                _results.value = out.items
                _searchFailures.value = out.failures
                _history.value = (listOf(q) + _history.value).distinct().take(8)
            } finally {
                if (mine == searchGen) _searching.value = false
            }
        }
    }

    /**
     * 退出搜索页时把这一页擦干净。
     *
     * 光清结果不够：在跑的那次搜索不取消，人已经回首页了，它晚一步回来还是会把旧结果
     * 重新摆满屏幕，`_searching` 也一路挂着 —— 下次进来先看到「正在并发查询 N 个源…」，
     * 然后落回上一轮那个词的结果，看着像是搜索页记住了自己上次搜了什么。
     */
    fun resetSearch() {
        searchGen++
        searchJob?.cancel()
        searchJob = null
        _searching.value = false
        _query.value = ""
        _searchedTerm.value = null
        _results.value = emptyList()
        _searchFailures.value = emptyList()
    }

    /** 清搜索框：回到「历史 + 当前接入的源」那一屏，但人还留在搜索页。 */
    fun clearQuery() {
        searchGen++
        searchJob?.cancel()
        _searching.value = false
        _query.value = ""
        _searchedTerm.value = null
        _results.value = emptyList()
        _searchFailures.value = emptyList()
    }

    /** 删一条搜索历史。不弹确认 —— 删错了重新搜一次就回来了。 */
    fun removeSearchHistory(term: String) {
        _history.value = _history.value.filterNot { it == term }
    }

    fun clearSearchHistory() { _history.value = emptyList() }

    // ---------- 详情 ----------

    /**
     * 详情页那一次加载。
     *
     * 同一时刻只跑一个，后发起的顶掉前一次：连点两部片、或者在加载途中切源时，
     * 前一次的 `siblingsOf` 扇出还在跑，它回来会把后一次的列表盖掉 ——
     * 屏幕上是一部片的选集，底下挂着另一部片的源徽标。
     * 转圈标记也只由当代那次来关（[detailGen]），否则被顶掉的那次会把新一次的转圈关掉。
     */
    private var detailJob: Job? = null
    private var detailGen = 0

    fun openDetail(sourceId: String, mediaId: String) {
        val mine = ++detailGen
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                _detailLoading.value = true
                _detailError.value = null
                _detail.value = null
                _siblings.value = emptyList()
                _siblingFailures.value = emptyList()
                _siblingsOf = null
                _activeSourceId.value = sourceId
                val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
                if (cfg == null) {
                    _detailError.value = "播放源不存在"
                    return@launch
                }
                // 用 quietly 而不是 runCatching：这一笔是会被后一次顶掉的，
                // runCatching 把 CancellationException 也当成「加载失败」，
                // 于是换得越快，屏幕上越会闪出一句莫名其妙的报错。
                quietly { mediaRepo.detail(cfg, mediaId) }
                    .onSuccess { raw ->
                        // 被顶掉的那一次不能往下写：它手里这份是上一部片的，
                        // 落下去就把新一次刚摆好的详情盖成了旧的（选集和源徽标分属两片）。
                        if (mine != detailGen) return@onSuccess
                        // 详情页常常不写年份，卡片写了。留着空的走下去，同一部片会因为
                        // 「卡片那条带 |2026、详情那条不带」变成两条收藏 / 两条观看记录。
                        val d = raw.withYear(yearKnownFor(cfg.id, mediaId, raw.media.title))
                        _detail.value = d
                        // 先把当前源放进去，避免面板空着；随后异步补齐其它源
                        _siblingsOf = d.media.key
                        _siblings.value = listOf(
                            SourceAvailability(
                                sourceId = cfg.id,
                                sourceName = cfg.name,
                                item = d.media,
                                health = _health.value[cfg.id]?.health ?: Health.Unknown,
                                quality = qualityOf(d.media),
                                latencyMs = _health.value[cfg.id]?.latencyMs,
                            ),
                        )
                        val s = mediaRepo.siblingsOf(d.media)
                        if (mine != detailGen) return@onSuccess
                        _siblingFailures.value = s.failures
                        _siblings.value = _siblings.value + s.items.filter { it.sourceId != cfg.id }
                    }
                    .onFailure { if (mine == detailGen) _detailError.value = it.message ?: "详情加载失败" }
            } finally {
                if (mine == detailGen) _detailLoading.value = false
            }
        }
    }

    /** 把详情补齐成年份一致的那条（detail() 常常解析不出年份）。 */
    private fun MediaDetail.withYear(y: String?): MediaDetail =
        if (media.year == null && !y.isNullOrBlank()) copy(media = media.copy(year = y)) else this

    /**
     * 这一部（源 id + 条目 id）在哪儿见到过年份。
     *
     * 按 id 对上最准；id 变了（换源、详情页另开一次）才退到同名，
     * 因为 [Media.key] 是收藏和观看记录的主键，缺了年份就会分裂成两条。
     *
     * 首页那一处已经不在这里了：那一份现在装的是豆瓣榜单，没有源 id 也没有条目 id，
     * 对不上「（源 id + 条目 id）在哪儿见过」这个问题。
     */
    private fun yearKnownFor(sourceId: String, mediaId: String, title: String): String? {
        _results.value.forEach { agg ->
            agg.availability.firstOrNull { it.sourceId == sourceId && it.item.id == mediaId }
                ?.item?.year?.let { return it }
        }
        val want = normalizeTitle(title)
        return favorites.value.firstOrNull { normalizeTitle(it.title) == want }?.year
            ?: watchHistory.value.firstOrNull { normalizeTitle(it.title) == want }?.year
    }

    /** 在详情页切源：拿新源里同一部片的详情。 */
    fun switchDetailSource(av: SourceAvailability) {
        // 点的就是现在这个源：什么也不用做。原来它会照走一遍（把详情整个撤成 null、
        // 再发一次详情请求 + 一遍跨源扇出），用户看到的是手指下这一格把整页清空重来 ——
        // 代价是白等一轮网络，收益是零。
        if (av.sourceId == _activeSourceId.value && _detail.value != null) return
        val mine = ++detailGen
        detailJob?.cancel()
        _switchError.value = null
        detailJob = viewModelScope.launch {
            try {
                val cfg = sourceRepo.all().firstOrNull { it.id == av.sourceId } ?: return@launch
                val prev = _detail.value
                // 先把手上这份详情撤掉再取新的：不清的话，从「发请求」到「取回来」这一段时间里
                // 点播放/缓存，会把**旧源**那条地址配着**新源**的凭证发出去（activeSourceId 已经换了）
                _detail.value = null
                _detailLoading.value = true
                _detailError.value = null
                val keepYear = _siblings.value.firstNotNullOfOrNull { it.item.year }
                    ?: yearKnownFor(av.sourceId, av.item.id, av.item.title)
                quietly { mediaRepo.detail(cfg, av.item.id) }
                    .onSuccess {
                        if (mine != detailGen) return@onSuccess
                        val d = it.withYear(it.media.year ?: keepYear)
                        _detail.value = d
                        // 取回来了才认这个源：失败了还标着新源，界面就是在说一件没发生的事
                        _activeSourceId.value = av.sourceId
                        // 那一栏的次序就是「你正在用哪个源」的次序，切了源必须跟着换；
                        // _siblingsOf 也要落在这部片的新 key 上，否则下面那排芯片还挂在
                        // 上一次的比对结果上，点它会把人带回刚离开的那个源。
                        _siblingsOf = d.media.key
                        // 这一栏里当前源必须排第一，而且得有一条 —— 跨源比对那趟可能压根
                        // 没探到这个源（它就是因此才排在后面的），切过来之后芯片上却没有
                        // 「正在用的这个」，看着就像列表漏了一项。
                        val others = _siblings.value.filterNot { it.sourceId == av.sourceId }
                        _siblings.value = listOf(
                            SourceAvailability(
                                sourceId = av.sourceId,
                                sourceName = av.sourceName,
                                item = d.media,
                                health = _health.value[av.sourceId]?.health ?: Health.Unknown,
                                quality = qualityOf(d.media),
                                latencyMs = _health.value[av.sourceId]?.latencyMs,
                            ),
                        ) + others
                    }
                    .onFailure {
                        if (mine != detailGen) return@onFailure
                        // 换不过去就退回原来那一页：把人手上正在看的详情整个撤掉，
                        // 等于让他为了换一个源而丢掉刚才那半屏
                        _detail.value = prev
                        _switchError.value = it.message ?: "该源没有这部片"
                    }
            } finally {
                if (mine == detailGen) _detailLoading.value = false
            }
        }
    }

    // ---------- 播放 ----------

    /**
     * 播放器那份分集与线路。
     *
     * 不能直接给播放器读 [_detail]：那是「详情页当前显示的那一部」，而从「继续观看」、
     * 从搜索结果、从缓存列表进播放器时，它可能还停在上一部 —— 于是选集和线路面板
     * 列的是别的片的集数，点下去要的也是别的片的地址。
     * 所以起播时快照一份：调用方给了就用给的，没给就只保留对得上号的那一份。
     */
    private val _playingDetail = MutableStateFlow<MediaDetail?>(null)
    val playingDetail: StateFlow<MediaDetail?> = _playingDetail.asStateFlow()

    /**
     * 播放器那份「这部片在其它源里的样子」。
     *
     * 和 [_playingDetail] 同一个道理：[_siblings] 是详情页那一次的查询结果，
     * 从继续观看直接进播放器时它还是上一部片的 —— 换源面板会列出别的片的源，
     * 点下去就是拿另一部片去解析。所以只有对得上号才带过来，对不上宁可空着。
     * 空着不等于「就这一个源」，那是没查过，见 [_playingSiblingsQuery]。
     */
    private val _playingSiblings = MutableStateFlow<List<SourceAvailability>>(emptyList())
    val playingSiblings: StateFlow<List<SourceAvailability>> = _playingSiblings.asStateFlow()

    /** 播放器那份跨源列表查过没有：这一格决定「换源」上摆不摆得出数字。 */
    private val _playingSiblingsQuery = MutableStateFlow(SiblingsQuery.Idle)
    val playingSiblingsQuery: StateFlow<SiblingsQuery> = _playingSiblingsQuery.asStateFlow()

    /** 这一趟里根本没答上的源（名字 + 原因）。「其它源里没有」和「有几个源没查到」是两回事。 */
    private val _playingSiblingsGaps = MutableStateFlow<List<SearchOutcome.Failure>>(emptyList())
    val playingSiblingsGaps: StateFlow<List<SearchOutcome.Failure>> = _playingSiblingsGaps.asStateFlow()

    private var playingSiblingsJob: Job? = null

    /**
     * 播放器那份跨源列表属于**哪一部片**（片名指纹）。
     *
     * 用指纹而不是各源的 key：换源以后 key 就变了，而这份列表本来就是跨源的，
     * 拿 key 比会逼着用户每换一次源重查一遍。
     */
    private var playingSiblingsOf: String? = null

    /**
     * 第几次起播。
     *
     * 连点两集时前一次的解析可能更晚回来，而它写的是 `_playing.value?.copy(...)` ——
     * 于是屏幕上写着第 5 集、手里攥着的是第 3 集的直链，用户完全看不出来。
     * 代号对不上就地丢掉，同时把上一次那个还在跑的解析取消掉（它的结果已经没有意义了）。
     */
    private var playGen = 0
    private var playJob: Job? = null

    // ---------- 播放失败自动换源 ----------

    /** 一次尝试：哪个源的哪条线路、为什么没成。[lineName] 在只有一条线路的源上是 null。 */
    data class Attempt(val sourceName: String, val lineName: String?, val reason: String)

    /**
     * 这一轮自动重试的样子，播放器照它说话。
     *
     * 「取链没拿到地址」「播放器报错」「地址通了却 20 秒不出第一帧」对用户是同一句话 ——
     * 这一条线路放不出来这一集 —— 所以三条都汇到这里，界面上也只有一种说法。
     *
     * 一格 = 一个「源 · 线路」组合，不是「一个源」。同一个源有两条线路时，
     * 先把它的第二条试掉再换下一个源：换线路不重取详情（懂片帝的线路包本来就在缓存里），
     * 换源却每次都要重新拉一遍详情，先试便宜的。
     */
    data class FallbackUi(
        /** 这是这一轮的第几次尝试（1 起）。 */
        val step: Int,
        /** 候选里源有几个（线路数走一步才知道一步，所以不报「总共几次」）。 */
        val sourceCount: Int,
        val tryingSourceName: String,
        /** 正在试的那条线路叫什么；这个源只有一条线路时为 null。 */
        val tryingLineName: String?,
        val failures: List<Attempt>,
        /** 候选全试完了：停下说话，不再自己动。 */
        val exhausted: Boolean,
        /** 已经播开了：横幅再留一会儿说明刚才发生了什么（留多久由界面定），但不再表示「还在试」。 */
        val settled: Boolean = false,
    )

    private val _fallback = MutableStateFlow<FallbackUi?>(null)
    val fallback: StateFlow<FallbackUi?> = _fallback.asStateFlow()

    /**
     * 一轮之内试过些什么。
     *
     * [anchorSec] 是这一轮的位置锚点。**不能**直接读 [_resumePosition]：换源换线路时媒体源
     * 重建会先 `player.stop()`，那一刻轮询会把 0 写回去，级联到第三个源时进度就归零了 ——
     * 只有真的听到过 >0 的位置才配更新它。
     *
     * [candidates] 会长大：从首页直接进播放器时手上没有这份跨源列表，
     * 等真的失败了才去别的源里找这一部片（见 [reportPlaybackFailure]）。
     *
     * [attemptUrl] 与 [accounted] 是这一轮的认账凭据：`Playing` 上没有线路 id，
     * 而区分「第 1 条线路失败」和「第 2 条线路失败」的只有那条 `episodeUrl`
     * （懂片帝是 `aiep:` 的第三段不同，苹果 CMS 与网页解析每条线路各一个地址）。
     * 迟到的播放器回调说的是上一笔，凭这两条把它挡在外面。
     */
    private class Round(
        val episodeIndex: Int,
        var candidates: List<SourceAvailability>,
        var cursor: Int = 0,
        /** 当前这个源试到第几条线路。0 = 详情页默认那条。 */
        var lineCursor: Int = 0,
        val tried: MutableList<Attempt> = mutableListOf(),
        var anchorSec: Long = 0L,
        var findingSiblings: Boolean = false,
        var attemptUrl: String? = null,
        var accounted: Boolean = false,
        /** 当前这个源那份多线路详情。换线路靠它，不用重新拉详情。 */
        var detail: MediaDetail? = null,
    )

    private var round: Round? = null

    /**
     * 起播时开一轮：当前源打头，后面接其它源（就是「播放源管理」里拖出来的那个顺序）。
     *
     * 手上没有跨源列表时也照样开这一轮：只有一个候选、且这个源只有一条线路时什么都不会
     * 自动发生，真失败了再去查别的源（见 [reportPlaybackFailure]）—— 首页点「找这一部」那条路才有的救。
     *
     * [detail] 是这一次起播手上那份多线路详情。留着它，第一条线路失败时才能就地换第二条，
     * 不必回头再拉一次详情。
     */
    private fun beginRound(
        p: Playing,
        siblings: List<SourceAvailability>,
        detail: MediaDetail?,
    ) {
        val self = SourceAvailability(
            sourceId = p.sourceId,
            sourceName = p.sourceName,
            item = Media(
                id = p.mediaId ?: "",
                title = p.mediaTitle,
                cover = p.mediaCover,
                year = p.mediaYear,
                sourceId = p.sourceId,
            ),
            health = Health.Unknown,
        )
        val cands = (listOf(self) + siblings.filter { it.sourceId != p.sourceId && it.item.id.isNotEmpty() })
            .distinctBy { it.sourceId }
        val d = detail?.takeIf { it.media.sourceId == p.sourceId }
        round = Round(
            episodeIndex = p.episodeIndex,
            candidates = cands,
            // 从用户手上这一条线路往后接着试，而不是从第 1 条重来：
            // 在详情页转过线路再点播放，第 1 条他已经看不上眼了。
            lineCursor = lineIndexOf(d, p.episodeIndex, p.episodeUrl),
            anchorSec = p.resumeFrom,
            attemptUrl = p.episodeUrl,
            detail = d,
        )
        _fallback.value = null
    }

    /**
     * 手上这一笔放不出来这一集。
     *
     * 认账要过三道闸：集号对得上（换过集就是另一轮）、源对得上游标、那条 `episodeUrl` 对得上
     * 这一笔。最后那道是这一轮里最难处理的：换了线路之后，旧播放器还会晚一拍把上一条线路的错误
     * 送上来，而它和新一笔同源同集，只有那条地址能分辨 —— 放它进来就会跳过本该试的线路。
     * 所以**播放器报上来的那一笔要带上它自己当时在放哪条地址**（[url]）：拿 `_playing` 现值去比
     * 是分不出来的，迟到的回调送进来时，屏上写的已经是新一笔了。
     *
     * 往下走时先在**当前这个源**里换线路，线路走完了才换源：换线路不用重取详情（那份
     * `MediaDetail` 就在手上，懂片帝那种连线路包都缓存好了），换源每次都要重新拉一遍详情。
     * 便宜的先试 —— 而且**同源的线路一定先走完**：一个源里十几条线路，第一条撞上的临时状况
     * （限流、对方那一路正在抽风）常常第二条就好了，一上来就判死这个源、换到别处去，
     * 是拿片库最全的那条去换一个小的。
     *
     * [wholeSource] 只留给一种例外：那个原因写在**规则**上（地址由站点脚本现算 / 要凭证 /
     * 没配解析规则）。这种换线路不会有第二个答案，而且适配器看一眼模式就返回、不花网络，
     * 所以直接跳到下一个源，别把流水写成「换过 13 次」。
     * 对方按 IP/客户端挡下来那一类（403 / 429 / 5xx）**不算这种例外**：它是对这个源的
     * 一次回答，不是对每一条线路的回答。
     */
    fun reportPlaybackFailure(reason: String, url: String? = null, wholeSource: Boolean = false) {
        val r = round ?: return
        val p = _playing.value ?: return
        if (p.episodeIndex != r.episodeIndex) return
        val cand = r.candidates.getOrNull(r.cursor) ?: return
        if (cand.sourceId != p.sourceId) return
        if ((url ?: p.episodeUrl) != r.attemptUrl) return
        if (r.accounted) return
        _resumePosition.value.takeIf { it > 0L }?.let { r.anchorSec = it }
        r.accounted = true
        r.tried += Attempt(cand.sourceName, lineNameOf(r), reason)
        if (!wholeSource && nextLine(r)) return
        if (r.candidates.size > 1) {
            advance(r)
            return
        }
        // 手上只有当前这一个源：先去看看别的源里有没有这一部，有了才谈得上换
        if (r.findingSiblings) return
        r.findingSiblings = true
        viewModelScope.launch {
            val self = r.candidates.firstOrNull()?.item ?: return@launch
            val found = mediaRepo.siblingsOf(self)
            // 这一趟是全源扇出，回来时用户早可能换集甚至退出了 —— 认一次代再说
            if (round !== r || _playing.value?.episodeIndex != r.episodeIndex) return@launch
            r.candidates = (r.candidates + found.items.filter {
                it.sourceId != self.sourceId && it.item.id.isNotEmpty()
            }).distinctBy { it.sourceId }
            advance(r)
        }
    }

    /**
     * 当前这个源还有没试过的线路就换一条，返回有没有换成。
     *
     * 某条线路没给这一集时直接跳过去看下一条 —— 那不算一次失败，它压根没有这一格，
     * 记进流水里就成了「这个源试过了、不行」的假账。
     */
    private fun nextLine(r: Round): Boolean {
        val d = r.detail ?: return false
        val av = r.candidates.getOrNull(r.cursor) ?: return false
        var i = r.lineCursor + 1
        while (i < d.lines.size) {
            val ep = d.lines[i].episodes.firstOrNull { it.index == r.episodeIndex }
                ?.takeIf { !it.playUrl.isNullOrBlank() }
            if (ep != null) {
                r.lineCursor = i
                dispatchLine(r, av, ep, d)
                return true
            }
            i++
        }
        return false
    }

    /**
     * 在当前这个源里把某一条线路递出去。集号没变，所以走 [play] 的 `autoAdvance` 那一条 ——
     * 整张候选表和已经记下的原因都得留着，换集才等于重开一轮。
     */
    private fun dispatchLine(r: Round, av: SourceAvailability, ep: Episode, detail: MediaDetail) {
        r.attemptUrl = ep.playUrl
        r.accounted = false
        play(
            sourceId = av.sourceId,
            mediaTitle = av.item.title,
            episodeName = ep.name,
            episodeIndex = ep.index,
            episodeUrl = ep.playUrl,
            resumeFrom = r.anchorSec,
            mediaId = av.item.id,
            mediaKey = av.item.key,
            mediaYear = av.item.year,
            mediaCover = av.item.cover,
            detail = detail,
            autoAdvance = true,
        )
        publish(r, exhausted = false)
    }

    /** 这一格的线路名。只有一条线路的源不出现「线路」这两个字 —— 它没有得选。 */
    private fun lineNameOf(r: Round): String? {
        val lines = r.detail?.lines ?: return null
        return if (lines.size < 2) null else lines.getOrNull(r.lineCursor)?.name
    }

    /** 手上这条地址是这个源的第几条线路。对不上就当第 1 条。 */
    private fun lineIndexOf(d: MediaDetail?, episodeIndex: Int, url: String?): Int =
        d?.lines?.indexOfFirst { l ->
            url != null && l.episodes.any { it.index == episodeIndex && it.playUrl == url }
        }?.takeIf { it >= 0 } ?: 0

    /** 当前这个源的线路走完了：往下一个候选源走；没有下一个了就把这一轮的账摊开说清楚。 */
    private fun advance(r: Round) {
        if (r.cursor + 1 >= r.candidates.size) {
            // 只试过一次、又只有一个源时不弹横幅：下方「这个源拿不到直链」那块说的已是同一件事，
            // 而且它还留着站点页取链那个出口。但只要真走过第二格（同一个源的下一条线路也算），
            // 这张流水就有话要说了。
            if (r.candidates.size > 1 || r.tried.size > 1) publish(r, exhausted = true)
            return
        }
        r.cursor++
        r.lineCursor = 0
        r.detail = null
        publish(r, exhausted = false)
        switchWithinRound(r)
    }

    /**
     * 由这一轮发起的切源。和用户在播放器里手动切源 ([switchPlayingSource]) 不是一条路，
     * 因为它多了两道锁：
     *
     * - **detail 回来之前可能已经过了好几秒**。这期间用户换了集、点了重试、或自己切了源，
     *   这一笔就得丢掉 —— 不然屏幕上写着第 6 集、递出去的却是第 5 集的地址，
     *   而这一轮的游标也早就不指这个源了。
     * - **只认那一集**。这个源没有第 5 集就算它放不出来，不能拿它的第一集顶上：
     *   集号一变 [play] 就当成新的一轮开，整张候选表和已经记下的原因一起作废，
     *   剩下的源再也试不到，还可能和上一台源来回踢皮球。
     */
    private fun switchWithinRound(r: Round, at: Int = r.cursor) {
        val av = r.candidates.getOrNull(at) ?: return
        val genAtAsk = playGen
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == av.sourceId }
            val d = cfg?.let { c -> runCatching { mediaRepo.detail(c, av.item.id) }.getOrNull() }
            if (!roundAlive(r, genAtAsk)) return@launch
            r.detail = d
            r.lineCursor = 0
            // 「取不到详情」和「这个源没有这一集」是**这个源**整体不行，不占线路名额：
            // 它连一格能播的地址都没有，后面几条线路也就无从谈起，直接换下一个源。
            // 从第一条**真有这一集**的线路起跳 —— 苹果 CMS 那种会塞一条空地址的占位线路在前面。
            val at0 = d?.lines?.indexOfFirst { l ->
                l.episodes.any { it.index == r.episodeIndex && !it.playUrl.isNullOrBlank() }
            } ?: -1
            val ep = if (at0 < 0) null else d?.lines?.get(at0)?.episodes?.first {
                it.index == r.episodeIndex && !it.playUrl.isNullOrBlank()
            }
            if (d == null || ep == null) {
                r.tried += Attempt(
                    av.sourceName, null,
                    if (d == null) "这个源取不到详情" else "这个源没有第 ${r.episodeIndex} 集",
                )
                advance(r)
                return@launch
            }
            r.lineCursor = at0
            dispatchLine(r, av, ep, d)
        }
    }

    /** 这一轮还算不算数：换集 / 重试 / 手动切源都会 [beginRound] 换成新的一组对象。 */
    private fun roundAlive(r: Round, genAtAsk: Int) =
        round === r && playGen == genAtAsk && _playing.value?.episodeIndex == r.episodeIndex

    /**
     * 第一帧出来了：横幅从此只作说明。
     *
     * **不清这一轮**。播了二十分钟后 CDN 把流掐了，`onPlayerError` 一样是该换源 ——
     * 那属于用户选的「播放器报错」，和被排除的「播到一半卡住」不是一回事：
     * 报错是硬证据，卡顿和用户自己拖进度条长得一样。看门狗只盯首帧之前，自己会收手。
     */
    fun playbackStarted() {
        val cur = _fallback.value
        if (cur != null && !cur.exhausted) _fallback.value = cur.copy(settled = true)
    }

    fun dismissFallbackNotice() { _fallback.value = null }

    /** 「再试一轮」：游标回到开头、流水清空，位置仍用这一轮听到的最后一个有效值。 */
    fun retryFallbackRound() {
        val r = round ?: return
        r.cursor = 0
        r.lineCursor = 0
        r.detail = null
        r.tried.clear()
        r.accounted = false
        // 上一轮如果是靠「去别的源找过这一部」才判的没戏，这个位还立着。
        // 它是 reportPlaybackFailure 的一句 `if (r.findingSiblings) return` 的闸门：
        // 不跟着这一轮一起清零，重试以后当前源的线路再走完时那句 return 会当场拦下 ——
        // 既不重新去别的源找，也不再 publish(exhausted=true)，横幅就永远停在
        // 「正在试 · 第 N 次」，而实际上什么都没在跑，用户也没有出口。
        r.findingSiblings = false
        _fallback.value = null
        switchWithinRound(r, 0)
    }

    private fun publish(r: Round, exhausted: Boolean) {
        val cand = r.candidates.getOrNull(r.cursor) ?: return
        _fallback.value = FallbackUi(
            step = r.tried.size + 1,
            sourceCount = r.candidates.size,
            tryingSourceName = cand.sourceName,
            tryingLineName = if (exhausted) null else lineNameOf(r),
            failures = r.tried.toList(),
            exhausted = exhausted,
        )
    }

    /**
     * 起播。
     *
     * 先落一个 streamUrl 为 null 的 Playing，让播放器立刻切过去并显示「正在解析直链」——
     * 这样点下去就有反馈，不会出现「点了没反应」的观感。
     * 解析完成后要么填入直链，要么带上如实的原因；两种情况下右侧的切源按钮都可用。
     */
    fun play(
        sourceId: String,
        mediaTitle: String,
        episodeName: String,
        episodeIndex: Int,
        episodeUrl: String?,
        /** -1 表示「让仓库按（这部片，这一集）决定从哪续」。 */
        resumeFrom: Long = -1L,
        mediaId: String? = null,
        mediaKey: String? = null,
        mediaYear: String? = null,
        mediaCover: String? = null,
        /** 这一集所属那部片的分集与线路；见 [playingDetail]。 */
        detail: MediaDetail? = null,
        /** 自动换源走这一条：不换集，就不该重开一轮流水。 */
        autoAdvance: Boolean = false,
    ) {
        val mine = ++playGen
        playJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
                val name = cfg?.name ?: sourceId
                val start =
                    if (resumeFrom >= 0L) resumeFrom
                    else mediaKey?.let { watchRepo.resumeAt(it, episodeIndex) } ?: 0L

                // 立刻进入播放器，先把「我收到了」这一步给出去
                _playing.value = Playing(
                    mediaTitle, episodeName, episodeIndex, name, sourceId, null, episodeUrl,
                    sourceBaseUrl = cfg?.baseUrl?.trimEnd('/'),
                    mediaId = mediaId,
                    mediaKey = mediaKey,
                    mediaYear = mediaYear,
                    mediaCover = mediaCover,
                    resumeFrom = start,
                )
                // 分集与线路那两份快照：调用方给了就用给的；没给（详情页点播放、重试、
                // 换集、播缓存）就只认「id 和源都对得上正在播这一部」的那一份 ——
                // 对不上宁可空着，拿上一部的集数列表播这一部，点第 3 集就是去要另一部的地址
                fun fits(d: MediaDetail?) = d != null && mediaId != null &&
                    d.media.id == mediaId &&
                    (d.media.sourceId == sourceId || sourceId == "local")
                _playingDetail.value = detail
                    ?: _detail.value?.takeIf { fits(it) }
                    ?: _playingDetail.value?.takeIf { fits(it) }
                retainPlayingSiblings(
                    mediaTitle = mediaTitle,
                    sourceId = sourceId,
                    inherited = _siblings.value.takeIf {
                        _playingDetail.value != null && _siblingsOf == mediaKey
                    },
                )
                // 自动换源走到这里是同一轮里的下一次尝试，流水和游标都不能动；
                // 用户自己点的播/换集/续播才开新的一轮。
                val keepRound = autoAdvance && round?.episodeIndex == episodeIndex
                if (keepRound) {
                    // 这一轮的账得跟着挪到刚递出去的这一格上：换线路是同一个源同一个集，
                    // 只有那条地址能分辨是哪一笔。不挪的话，用户在播放器里手动换的线路
                    // 失败时会因为「地址对不上」被当成迟到的回调丢掉 —— 那一轮就此停住。
                    round?.let {
                        it.detail = _playingDetail.value?.takeIf { d -> d.media.sourceId == sourceId }
                            ?: it.detail
                        it.lineCursor = lineIndexOf(it.detail, episodeIndex, episodeUrl)
                        it.attemptUrl = episodeUrl
                        it.accounted = false
                        // 已经播开了以后那句「现在是 源 · 线路」也跟着改口。原来这里只挪游标、
                        // 不重发横幅：用户手动换过线路，播的是这一条、屏上写的还是上一轮那一条，
                        // 而这一句是「此刻在放什么」的唯一屏幕内交代。
                        _fallback.value?.takeIf { fb -> fb.settled }?.let { fb ->
                            val nm = lineNameOf(it)
                            if (nm != null) _fallback.value = fb.copy(tryingLineName = nm)
                        }
                    }
                } else _playing.value?.let { beginRound(it, _playingSiblings.value, _playingDetail.value) }
                _resumePosition.value = start
                // 上一部的时长不能跟到这一部上：「看不看完」是拿它算的
                lastDurationSec = 0L

                if (cfg == null) {
                    val why = "播放源已不存在，可能在「播放源管理」里被删掉了"
                    _playing.value = _playing.value?.copy(note = why)
                    reportAndContinue(why)
                    return@launch
                }
                if (episodeUrl.isNullOrBlank()) {
                    val why = "这个源没给出这一集的播放地址"
                    _playing.value = _playing.value?.copy(note = why)
                    reportAndContinue(why)
                    return@launch
                }

                _resolving.value = true
                val res = mediaRepo.resolveStream(cfg, episodeUrl)
                if (mine != playGen) return@launch
                _resolving.value = false

                val why = noteOf(res) ?: "这个源给不出可播地址"
                val page = sitePageFor(cfg, mediaId, episodeIndex, episodeUrl)
                _playing.value = _playing.value?.copy(
                    streamUrl = (res as? StreamResolution.Ready)?.url,
                    note = why,
                    sitePageUrl = page,
                )
                // 拿不到可播地址就是「这个源放不出来这一集」，交给自动换源往下走。
                // 另开一个协程去汇报：这里正跑在本轮的 playJob 里，就地切换会把它取消掉。
                if (res !is StreamResolution.Ready) {
                    // 例外：**用户自己按下的这一格**，而这个源从规则上就给不出直链
                    // （地址要由它自己的页面现算）。这种时候把它判给「下一个源」等于把这一格
                    // 白白扔掉 —— 高清点播实测就是如此：13 条线路全部同一个原因，一路空转，
                    // 最后从 4kku 播起来，而它自己那一页从没被打开过。
                    // 所以这里停在这一格，直接把这一集的站点页开给他，让站点把地址算给它自己的播放器。
                    // 自动换源走到这一格时不走这条路：那是「这个源不行，换一个」，不该顺手开一扇窗。
                    if (!autoAdvance && sourceLevel(res) && page != null) {
                        _playing.value = _playing.value?.copy(note = siteLinkNote(name))
                        openSiteBrowser(page, "$name · $mediaTitle $episodeName", cfg?.baseUrl?.let { "$it/" })
                    } else {
                        reportAndContinue(why, sourceLevel(res))
                    }
                }
            } finally {
                if (mine == playGen) _resolving.value = false
            }
        }
    }

    /**
     * 这个「拿不到」是整个源的性质，不是这一条线路的。
     *
     * 这几种都在**发出任何请求之前**就定下来了（`resolveMode` 写在源规则里，13 条线路共用一份），
     * 所以换线路不会有任何变化 —— 该跳的是整个源，而不是把它的线路挨个试一遍再跳。
     * 反过来，「播放页里没有 player_aaaa」「站点回 403 / 429」这类是逐条线路的回答，
     * 照旧一条一报、把这一源的线路走完才换源：对方这一次挡的未必每一条都挡。
     */
    private fun sourceLevel(res: StreamResolution): Boolean = when (res) {
        is StreamResolution.NeedsClientScript, is StreamResolution.NeedsSession -> true
        is StreamResolution.Failed -> res.reason.startsWith("这个源还没有配置直链解析规则")
        else -> false
    }

    /**
     * 放掉一个源的本机 key 代理。
     *
     * 只有「站点给的密钥不是规范形态」那一型（PPnix）才有代理，其余源这一行是空转 ——
     * [LocalKeyProxy.release] 按 id 找不到就直接返回，不发请求、不起线程。
     *
     * 在播放器那一屏卸掉时调（见 PlayerScreen 的 onDispose）：引用计数归零才真关端口，
     * 所以换集、换线路那几次重放不会把它提前收掉。
     */
    fun releaseKeyProxy(sourceId: String) {
        // sourceRepo.all() 是 suspend 的（要读一次 DataStore），所以整个方法在协程里跑完。
        // onDispose 那一下不等着它 —— 关端口的早晚差几毫秒没有意义。
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId } ?: return@launch
            LocalKeyProxy.release(cfg)
        }
    }

    /** 停在「等站点自己的页面把地址算出来」这一格上时，屏幕上该写的那句话。 */
    private fun siteLinkNote(sourceName: String): String =
        "「$sourceName」不给直链：这一集的地址要由它自己的页面现算出来。" +
            "已经替你打开那一页 —— 等页面下面列出接住的地址，挑一条点「播放」，影枢就在本机把它播起来。" +
            "关掉那一页也不算丢：播放器上那颗「在站点页面里解析」随时能再打开它。"

    /**
     * 起播这一步就地失败，也要把这一轮推下去。
     *
     * 不汇报的话这一轮就停在「正在试第 N 个」不动了 —— 横幅说它在换源，屏上却再没有任何动作，
     * 用户看到的就是「点了没反应」。另开一个协程汇报：这里正跑在本轮的 playJob 里，
     * 就地切源会把自己取消掉，取消掉的那一半又会被当成一次新的失败。
     */
    private fun reportAndContinue(reason: String, wholeSource: Boolean = false) {
        viewModelScope.launch { reportPlaybackFailure(reason, wholeSource = wholeSource) }
    }

    /**
     * 这一集在**源站自己**的页面上是哪个地址；给不出就 null。
     *
     * 只有这一页属于源站才有意义：打开它，源站的播放器会去把流取回来，
     * 影枢在旁边接住那条请求（4K影视 与西瓜影院走的都是这一条）。
     * 接口型源给的是爱奇艺那类平台页 —— 那种页面面对内嵌 WebView 只会弹
     * 「APP内打开」，拿它当入口等于骗用户点，所以宁可不给。
     *
     * 分享也用这一个判断：能拿出去给别人点的，就是这同一个地址。
     */
    fun sitePageFor(
        cfg: SourceConfig?,
        mediaId: String?,
        episodeIndex: Int,
        episodeUrl: String?,
    ): String? {
        cfg?.playPageFor(mediaId, episodeIndex)?.let { return it }
        // 只有 http(s) 才可能是个「页」。懂片帝AI 那类源的 episodeUrl 是内部取流凭据
        // （aiep:av_xxx|av_yyy），拿它当分享链接既打不开，又把凭据白印到别人脸上。
        val url = episodeUrl?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        return if (VideoPlatforms.platformOf(url) == null) url else null
    }

    /** 按源 id 走一遍 [sitePageFor]，界面那边不必自己去查配置。 */
    fun sitePageOf(
        sourceId: String,
        mediaId: String?,
        episodeIndex: Int,
        episodeUrl: String?,
    ): String? = sitePageFor(
        _sources.value.firstOrNull { it.id == sourceId }, mediaId, episodeIndex, episodeUrl,
    )

    /** 重试解析：用户在播放器里点「重试」时走这里，不换集也不换源。 */
    fun retryPlay() {
        val p = _playing.value ?: return
        play(
            sourceId = p.sourceId,
            mediaTitle = p.mediaTitle,
            episodeName = p.episodeName,
            episodeIndex = p.episodeIndex,
            episodeUrl = p.episodeUrl,
            resumeFrom = _resumePosition.value,
            mediaId = p.mediaId,
            mediaKey = p.mediaKey,
            mediaYear = p.mediaYear,
            mediaCover = p.mediaCover,
        )
    }

    /**
     * 把解析结果翻译成给用户看的一句话。
     *
     * 几种「拿不到」的成因要分得开 —— 尤其要让用户知道哪些是换个源就能解决的、
     * 哪些是填自己的凭证可能走通的、哪些是本站根本不打算给匿名用户的。
     */
    private fun noteOf(res: StreamResolution): String? = res.userNote()

    /**
     * 播放器里手动切源：保持进度。
     *
     * 集号对不上时退回这个源的第一集 —— 手动切源的人看得见自己在切什么，
     * 拿一集能播的顶上也比白屏强。自动换源不走这条路，见 [switchWithinRound]。
     */
    fun switchPlayingSource(av: SourceAvailability, episodeIndex: Int) {
        viewModelScope.launch {
            val pos = _resumePosition.value
            val cfg = sourceRepo.all().firstOrNull { it.id == av.sourceId } ?: return@launch
            val d = runCatching { mediaRepo.detail(cfg, av.item.id) }.getOrNull()
            val ep = d?.lines?.firstOrNull()?.episodes?.firstOrNull { it.index == episodeIndex }
                ?: d?.lines?.firstOrNull()?.episodes?.firstOrNull()
            play(
                sourceId = av.sourceId,
                mediaTitle = av.item.title,
                episodeName = ep?.name ?: "第 $episodeIndex 集",
                episodeIndex = ep?.index ?: episodeIndex,
                episodeUrl = ep?.playUrl,
                resumeFrom = pos,
                mediaId = av.item.id,
                mediaKey = av.item.key,
                mediaYear = av.item.year,
                mediaCover = av.item.cover,
                detail = d,
            )
        }
    }

    /**
     * 换源面板那份列表要跟着「正在播的是哪一部片」走。
     *
     * 认的是片名指纹，不是各源自己的 key：换源以后 key 就变了（懂片帝不给年份、4kku 给 2026），
     * 拿 key 比的话每换一次源都退回「没查过」，得再查一遍。指纹对不上就是换了片，就地清空 ——
     * 面板列出别的片的源，点下去要的是另一部的地址。
     * [inherited] 是详情页刚比对过的那一份，直接采信。
     */
    private fun retainPlayingSiblings(
        mediaTitle: String,
        sourceId: String,
        inherited: List<SourceAvailability>?,
    ) {
        val fingerprint = normalizeTitle(mediaTitle)
        val kept = inherited ?: _playingSiblings.value.takeIf { playingSiblingsOf == fingerprint }
        val has = !kept.isNullOrEmpty()
        _playingSiblings.value = (kept ?: emptyList()).sortedByDescending { it.sourceId == sourceId }
        _playingSiblingsGaps.value = when {
            inherited != null -> _siblingFailures.value
            has -> _playingSiblingsGaps.value
            else -> emptyList()
        }
        // 空着不等于「就这一个源」，那是没查过；界面这两句话说得不一样，见 SiblingsQuery
        _playingSiblingsQuery.value = if (has) SiblingsQuery.Done else SiblingsQuery.Idle
        playingSiblingsOf = if (has) fingerprint else null
    }

    /**
     * 播放器里现查一次「这部片在其它源里有没有」。
     *
     * 从首页、继续观看、缓存列表直接进播放器时手上没有这份比对，换源那一格只能空着。
     * 这一趟要挨个源搜一遍（网络扇出，几个站各自几秒），所以只在用户点「查一次」时跑，
     * 不进播放器就自动来一次 —— 自动查等于每开一集就把所有源打一遍。
     */
    fun queryPlayingSiblings() {
        if (_playingSiblingsQuery.value == SiblingsQuery.Running) return
        val p = _playing.value
        // 比对只用得到片名和年份，这两样起播时一定带进来了；详情快照却可能没有
        // （一进去就解析失败那种）。别因为手上没整份详情就不给查 —— 越是不通的源，
        // 用户越需要这一格来看「别的源有没有」。
        val media = _playingDetail.value?.media ?: p?.let {
            Media(
                id = it.mediaId ?: "",
                title = it.mediaTitle,
                cover = it.mediaCover,
                year = it.mediaYear,
                sourceId = it.sourceId,
            )
        }
        if (media == null) {
            showNotice("这一笔连片名都没带进播放器，没法比对 —— 回到详情页从片子本身进一次再试。")
            return
        }
        val mine = p?.sourceId
        _playingSiblingsQuery.value = SiblingsQuery.Running
        playingSiblingsJob?.cancel()
        playingSiblingsJob = viewModelScope.launch {
            // quietly 而不是 runCatching：这一趟会被下一次起播顶掉，
            // 顶掉的时候不该在屏上留一句「加载失败」
            quietly { mediaRepo.siblingsOf(media) }
                .onSuccess { s ->
                    val found = s.items.sortedByDescending { it.sourceId == mine }
                    // 一个源都没对上也仍然要摆出正在播的这个：它确实就在这儿播着，
                    // 面板里报「没有别的源」比报「这部片没有源」诚实
                    _playingSiblings.value = found.ifEmpty {
                        if (p == null) emptyList() else listOf(
                            SourceAvailability(
                                sourceId = p.sourceId,
                                sourceName = p.sourceName,
                                item = media,
                                health = _health.value[p.sourceId]?.health ?: Health.Unknown,
                                quality = qualityOf(media),
                                latencyMs = _health.value[p.sourceId]?.latencyMs,
                            ),
                        )
                    }
                    _playingSiblingsGaps.value = s.failures
                    _playingSiblingsQuery.value = SiblingsQuery.Done
                    playingSiblingsOf = normalizeTitle(media.title)
                }
                .onFailure { _playingSiblingsQuery.value = SiblingsQuery.Idle }
        }
    }

    // ---------- 离线缓存 ----------

    fun downloadTask(sourceId: String, mediaId: String, episodeIndex: Int): DownloadTask? =
        downloadRepo.taskOf(sourceId, mediaId, episodeIndex)

    fun cache(sourceId: String, media: Media, episode: Episode) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
            if (cfg == null) {
                showNotice("这个播放源已经不在了，缓存不了 —— 到「播放源管理」里换一个源再存。")
                return@launch
            }
            downloadRepo.enqueue(cfg, media, episode)
        }
    }

    fun cacheLine(sourceId: String, media: Media, episodes: List<Episode>) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
            if (cfg == null) {
                showNotice("这个播放源已经不在了，缓存不了 —— 到「播放源管理」里换一个源再存。")
                return@launch
            }
            if (episodes.isEmpty()) {
                showNotice("这一条线路一个分集都没给，整条存不下来 —— 换一条线路试试。")
                return@launch
            }
            downloadRepo.enqueueAll(cfg, media, episodes)
        }
    }

    /** 播放器里的「缓存」：把当前这一集存下来，用的就是它刚解析过的那条直链。 */
    fun cachePlaying() {
        val p = _playing.value ?: return
        val mediaId = p.mediaId
        val originId = p.originSourceId ?: p.sourceId
        // 先看播放器自己那份快照（起播时对过号），再退回详情页当前那一份 ——
        // 只认得得上号的那部，否则会把另一部片的字段写进这条缓存任务。
        // 光对 mediaId 不够：各家的条目 id 是各自编号，两个源都可能有 id=12345 的两部片，
        // 而这条缓存之后要拿它去重新解析直链 —— 存成另一部，下下来就是另一部。
        fun fits(d: MediaDetail?) = d != null && mediaId != null &&
            d.media.id == mediaId && d.media.sourceId == originId
        val d = _playingDetail.value?.takeIf { fits(it) } ?: _detail.value?.takeIf { fits(it) }
        if (d == null || mediaId == null) {
            showNotice("这一集对不上任何一部片的详情，缓存不了 —— 回详情页从那儿存。")
            return
        }
        cache(
            originId,
            d.media,
            Episode(p.episodeName, p.episodeIndex, p.episodeUrl),
        )
    }

    fun pauseDownload(id: String) = downloadRepo.pause(id)

    fun resumeDownload(id: String) = downloadRepo.resume(id)

    fun deleteDownload(id: String) = downloadRepo.delete(id)

    /** 直接播已缓存好的文件，不再碰网络。 */
    fun playCached(task: DownloadTask) {
        val uri = downloadRepo.localUri(task.id)
        if (uri == null) {
            // 记录说「已缓存」、文件却不在 —— 多半是被清理软件或系统删了。
            // 悄悄 return 的话，这一行看着就是能点、点了没反应。
            showNotice("这一集的文件已经不在盘上了（多半被清理软件删掉了）—— 重新缓存一次。")
            return
        }
        val start = watchRepo.resumeAt(task.mediaKey, task.episodeIndex)
        _resumePosition.value = start
        lastDurationSec = 0L
        _playing.value = Playing(
            mediaTitle = task.mediaTitle,
            episodeName = task.episodeName,
            episodeIndex = task.episodeIndex,
            sourceName = "本机缓存",
            sourceId = "local",
            streamUrl = uri,
            mediaId = task.mediaId,
            mediaKey = task.mediaKey.takeIf { it.isNotBlank() },
            mediaYear = task.mediaYear,
            mediaCover = task.mediaCover,
            // 记录里要写「哪个源的这部片」—— 写 local 的话，
            // 下次从「继续观看」点进去会找不到源。
            originSourceId = task.sourceId,
            resumeFrom = start,
        )
        // 选集/线路那份也一样：从缓存列表直接播一部片时，手上那份快照
        // 可能还是刚才在线看的那一部，留着就是拿别的片的集数列表播这一部
        _playingDetail.value = _playingDetail.value?.takeIf { it.media.id == task.mediaId }
        retainPlayingSiblings(task.mediaTitle, task.sourceId, inherited = null)
    }

    /**
     * 播放器报上来的当前位置。
     *
     * 每 10 秒才落一次盘：DataStore 是写文件的，一秒一次是在磨盘。
     * 退出播放（[stopPlaying]）时再补一次，所以最多丢 10 秒。
     */
    fun updatePosition(sec: Long, durationSec: Long = 0L) {
        // 先归零再写：这一条是通向落盘的唯一入口，负值（C.TIME_UNSET 那一类哨兵）
        // 一旦写进记录，「看完了没」就是拿一个负的天文数字在算。
        // 但**0 也要写**：早先这里把 `_resumePosition` 的赋值排在下面那句
        // `if (sec <= 0L) return` 之前，于是「位置还没解出来」这一拍会把跨源续播的锚点
        // 冲成 0 —— 换源/重试就从 0:00 重来了。现在负值直接不写，0 照写（那本来就是
        // 「这一集还没看到哪儿」的诚实答案）。
        val pos = sec.coerceAtLeast(0L)
        if (sec >= 0L) _resumePosition.value = pos
        val dur = durationSec.takeIf { it > 0L && it <= MAX_DURATION_SEC } ?: 0L
        if (dur > 0L) lastDurationSec = dur
        if (pos <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastRecordAt >= 10_000L) {
            lastRecordAt = now
            // 写 lastDurationSec 而不是这一次上报的 dur：换集刚起播那几秒 Media3 常常
            // 只有位置、还没有时长（TIME_UNSET 归 0），拿 dur 写就等于把记录里已经存好的
            // 时长冲成 0 —— 而「看完没看完」「还剩多少」全是拿时长算的。
            recordWatch(pos, lastDurationSec)
        }
    }

    private var lastRecordAt = 0L

    private companion object {
        /** 一集再长也不会超过一天。长过这个的就是哨兵值而不是时长。 */
        const val MAX_DURATION_SEC = 24 * 3600L

        /**
         * 片库这一份榜单多久算「还新鲜」。
         *
         * 豆瓣那些榜本来就是按天的，十分钟不会变出第二种答案；而片库是个 Tab，
         * 切过去切回来都会重新组合、每次重取就是白敲一趟。
         */
        const val DOUBAN_TTL_MS = 10 * 60_000L
    }

    /**
     * 最后一次听到的真实时长。
     *
     * 退出播放时那一次补写只有位置、没有时长 —— 直接写 0 会把记录里已有的时长冲掉，
     * 而「看不看完」正是拿时长算的，冲成 0 就等于把一条看完的记录改成没看完。
     */
    private var lastDurationSec = 0L

    /** 把当前这一集的位置写进观看记录。没有归并键的（老任务）不记，别留条对不上的。 */
    private fun recordWatch(sec: Long, durationSec: Long) {
        val p = _playing.value ?: return
        val key = p.mediaKey ?: return
        watchRepo.record(
            HistoryEntry(
                key = key,
                sourceId = p.originSourceId ?: p.sourceId,
                mediaId = p.mediaId ?: return,
                title = p.mediaTitle,
                year = p.mediaYear,
                cover = p.mediaCover,
                episodeIndex = p.episodeIndex,
                episodeName = p.episodeName,
                positionSec = sec,
                durationSec = durationSec,
            ),
        )
    }

    fun stopPlaying() {
        // 离开前把位置补上，不然最后那不到 10 秒白看
        if (_resumePosition.value > 0L) recordWatch(_resumePosition.value, lastDurationSec)
        _playing.value = null
        _resolving.value = false
        // 这一轮的流水跟着这一次播放一起结束：回详情页再进来该看到干净的面板
        round = null
        _fallback.value = null
        // 取链那趟还在跑就得当场掐掉、并把代号作废：它后面还会走到「这个源要现算地址，
        // 直接把站点页开给他」那一步（见 play 里的 openSiteBrowser）。不掐的后果是用户
        // 退出播放器回到详情页/首页以后，站点页的 WebView 突然盖上来 —— 他要看的已经不是这一部了。
        playGen++
        playJob?.cancel()
        playJob = null
    }

    // ---------- 收藏与观看记录 ----------

    /** 收藏/取消当前详情页这部片，返回切换后是不是「已收藏」。 */
    fun toggleFavorite(sourceId: String, m: Media): Boolean =
        watchRepo.toggleFavorite(
            Favorite(
                key = m.key,
                sourceId = sourceId,
                mediaId = m.id,
                title = m.title,
                year = m.year,
                category = m.category,
                cover = m.cover,
            ),
        )

    fun removeFavorite(key: String) = watchRepo.removeFavorite(key)

    fun historyOf(key: String?): HistoryEntry? = key?.let { watchRepo.historyOf(it) }

    fun removeHistory(key: String) = watchRepo.removeHistory(key)

    fun clearHistory() = watchRepo.clearHistory()

    /**
     * 从记录里直接续播。
     *
     * 记录存的是（源、片、集），但地址得重新解析 —— 直链普遍带时效，
     * 存下来那条早作废了，这和缓存续传时重新解析是同一个道理。
     *
     * 「取不到详情」和「这一集真的没了」是两件事，以前当成一件办：
     * 网络抖一下、懂片帝AI 的匿名额度用完、站点改版，都会让 `detail()` 抛出来，
     * 而那条分支顺手把记录删了 —— 一次别人的故障吃掉用户看到的进度。
     * 现在只有真的从站点拿到了这一部的分集列表、而里面确实没有这一集，才删记录。
     */
    fun resumeFromHistory(e: HistoryEntry) {
        viewModelScope.launch {
            // 源被删掉了也要给一句说法：静默 return 等于那张卡片根本点不动，
            // 而用户看到的是「我点了继续观看，什么都没发生」。
            val cfg = sourceRepo.all().firstOrNull { it.id == e.sourceId }
            if (cfg == null) {
                refuseToPlay(
                    e, "源已移除",
                    "这部片当初是「${e.sourceId}」这个源给的，它已经在「播放源管理」里被删掉了 —— " +
                        "进度还留着。去播放源管理把它加回来，或者从「片库 / 搜索」里重新找一部。",
                )
                return@launch
            }
            val fetched = quietly { mediaRepo.detail(cfg, e.mediaId) }
            val d = fetched.getOrElse { err ->
                refuseToPlay(
                    e, cfg.name,
                    "没能从「${cfg.name}」问到这一集的最新地址：" +
                        "${err.shortReason()} —— 进度给你留着，稍后再试，或者在播放器里换一个源。",
                )
                return@launch
            }
            val ep = d.lines.firstOrNull()?.episodes?.firstOrNull { it.index == e.episodeIndex }
            if (ep == null || ep.playUrl.isNullOrBlank()) {
                // 源改版或这一集真的没了：把记录去掉，别让它一直躺在「继续观看」里点不动
                refuseToPlay(
                    e, cfg.name,
                    "「${cfg.name}」这一部里已经没有第 ${e.episodeIndex} 集了（源改版或那一集被撤了）" +
                        "—— 这条记录先替你去掉。",
                )
                watchRepo.removeHistory(e.key)
                return@launch
            }
            play(
                sourceId = e.sourceId,
                mediaTitle = d.media.title,
                episodeName = ep.name,
                episodeIndex = ep.index,
                episodeUrl = ep.playUrl,
                resumeFrom = e.positionSec,
                mediaId = e.mediaId,
                mediaKey = e.key,
                mediaYear = d.media.year ?: e.year,
                mediaCover = d.media.cover ?: e.cover,
                detail = d,
            )
        }
    }

    /**
     * 不进播放器也能把「为什么没播起来」写在播放卡片上。
     *
     * 用户点「继续观看」时人已经在播放器门口，什么也不弹等于点了没反应；
     * 但记录不能因为这一次拿不到就删 —— 所以落一个没有地址的 Playing，把原因说清楚。
     */
    private fun refuseToPlay(e: HistoryEntry, sourceName: String, why: String) {
        _playing.value = Playing(
            mediaTitle = e.title,
            episodeName = "第 ${e.episodeIndex} 集",
            episodeIndex = e.episodeIndex,
            sourceName = sourceName,
            sourceId = e.sourceId,
            streamUrl = null,
            note = why,
            mediaId = e.mediaId,
            mediaKey = e.key,
            mediaYear = e.year,
            mediaCover = e.cover,
            resumeFrom = e.positionSec,
        )
        // 这一笔也换了「正在播的是哪一部」：跨源那份列表和那份分集快照都要跟着认片，
        // 不然屏上写的是这一部的片名，点「选集」「线路」摆出来的却是上一部的东西
        _playingDetail.value = _playingDetail.value?.takeIf {
            it.media.id == e.mediaId && it.media.sourceId == e.sourceId
        }
        retainPlayingSiblings(e.title, e.sourceId, inherited = null)
    }

    // ---------- 豆瓣榜单 ----------

    /**
     * 豆瓣的一条 → 影枢接的源里到底有没有这部。
     *
     * 豆瓣不给播放地址，所以只能拿片名去搜。这里**只认片名指纹完全相同**的（见 [titleFingerprint]）：
     * 差一点就「猜」进去，用户看到的是另一部片，比说搜不到更糟。
     *
     * 查询词会换着写法试（[titleSearchVariants]）：豆瓣爱写成「绝望写手 第五季」，源站是把片名
     * 直接 `LIKE '%词%'` 地匹配，那个空格一进去就一条都不剩 —— 只发原样那一次会把
     * 「源里明明有」报成「接的源里搜不到同名的片子」。
     *
     * 命中与否都交回调用方处理 —— 导航是界面那边的事（ ViewModel 里那个
     * `openDetail` 只把详情取回来，不改路由）。
     */
    fun openFromDouban(
        item: DoubanItem,
        onHit: (Media) -> Unit,
        onMiss: (DoubanItem, String) -> Unit,
    ) {
        // 连点两次会并发跑两份搜索、回来两笔导航 —— 先占住，正在找的时候点不动。
        // 但这一闸门得说一句：只挡住、不给说法，用户看到的就是「这张卡片按下去没反应」，
        // 而且「找片中」那枚标记只挂在正在找的那一张上，别的卡片看起来完全是活的。
        if (_doubanResolving.value != null) {
            showNotice("正在源里找《${doubanResolvingTitle ?: "那一片"}》，这一条回来之前别的点不动")
            return
        }
        viewModelScope.launch {
            doubanResolvingTitle = item.title
            _doubanResolving.value = item.id
            try {
                val want = titleFingerprint(item.title)
                var cands = emptyList<AggregatedMedia>()
                var silent = emptyList<String>()
                // 原样那次命中就不要再发第二次：多一趟是全源扇出，不该白付
                for (form in titleSearchVariants(item.title)) {
                    val outcome = mediaRepo.search(form)
                    cands = outcome.items.filter { titleFingerprint(it.media.title) == want }
                    silent = outcome.failures.map { it.sourceName }
                    if (cands.isNotEmpty()) break
                }
                // 同名不同片是常事（翻拍、续集、同名综艺）。豆瓣给了年份就按年份挑；
                // 源那边没写年份时，只有「唯一一条候选」才敢认 —— 两条以上就宁可说认不出。
                val year = item.year?.takeIf { it.isNotBlank() }
                val match = when {
                    cands.isEmpty() -> null
                    year == null -> cands.firstOrNull()
                    else -> cands.firstOrNull { it.media.year == year }
                        ?: cands.singleOrNull()?.takeIf { it.media.year.isNullOrBlank() }
                }
                val why = when {
                    match != null -> null
                    // 「都没连上」和「连上了但没这部片」是两回事，不能都写成「搜不到」
                    cands.isEmpty() && silent.isEmpty() -> "接的源里搜不到同名的片子"
                    cands.isEmpty() -> "接的源里搜不到同名的片子（${silent.joinToString("、")} 这一趟没答上话）"
                    else -> "同名的有 ${cands.size} 部，对不上豆瓣标的 $year 年那部 —— 认不准就不硬指"
                }
                if (match == null) onMiss(item, why!!) else onHit(match.media)
            } finally {
                // 占位标记必须在任何退出路径上放开，否则整张榜单从此点不动
                doubanResolvingTitle = null
                _doubanResolving.value = null
            }
        }
    }

    /**
     * 进片库时调一次：先把落盘那份放上去，再后台刷新。
     *
     * 不在应用启动时拉 —— 用户可能根本不点片库，没必要一开屏就去敲豆瓣的门。
     */
    fun ensureDouban() {
        if (_doubanItems.value.isEmpty()) doubanRepo.cached(_doubanChart.value.id)?.let {
            _doubanItems.value = it.items
            _doubanAt.value = it.at
        }
        // 每次进片库都去敲豆瓣的门没道理（这一屏是 Tab，切来切去都会重新组合），
        // 但也不能拿一个布尔当门闩：那一笔要是中途被切榜顶掉了，标记留在 true 上，
        // 片库从此不再自动刷新。所以判据是**这一份是什么时候取回来的**：
        // 还在有效期内就不动，工具栏那颗刷新按钮走 refreshDouban() 强制重取。
        val fresh = _doubanItems.value.isNotEmpty() &&
            _doubanAt.value != 0L &&
            System.currentTimeMillis() - _doubanAt.value < DOUBAN_TTL_MS
        if (fresh) return
        if (doubanJob?.isActive != true) refreshDouban()
    }

    /** 切榜：先把上次那份缓存放上去，再后台刷新。 */
    fun selectDoubanChart(chart: DoubanChart) {
        if (chart.id == _doubanChart.value.id) return
        _doubanChart.value = chart
        val snap = doubanRepo.cached(chart.id)
        _doubanItems.value = snap?.items ?: emptyList()
        _doubanAt.value = snap?.at ?: 0L
        _doubanError.value = null
        refreshDouban()
    }

    /**
     * 拉一次榜单。
     *
     * 只跑一个，后来的顶掉前一个，转圈标记也只由当代那次来关 —— 不然连切两个榜时，
     * 前一个的请求晚一步回来，会把后一个正在转的圈关掉，还把别的榜的结果泼上来。
     */
    private var doubanJob: Job? = null
    private var doubanGen = 0

    fun refreshDouban() {
        val chart = _doubanChart.value
        val mine = ++doubanGen
        doubanJob?.cancel()
        _doubanLoading.value = true
        doubanJob = viewModelScope.launch {
            try {
                val res = quietly { doubanRepo.fetch(chart) }
                if (mine != doubanGen) return@launch
                res.onSuccess { items ->
                    doubanRepo.put(chart, items)
                    _doubanItems.value = items
                    _doubanAt.value = System.currentTimeMillis()
                    _doubanError.value = null
                }.onFailure {
                    // 有旧数据就留着旧数据，只把原因写在旁边：
                    // 一次网络抖动不该把一整屏片清空。
                    _doubanError.value = it.message ?: "拉取失败"
                }
            } finally {
                if (mine == doubanGen) _doubanLoading.value = false
            }
        }
    }

    /** 判法全仓一份（见 [qualityTagOf]）：这里和详情页封面旁问的是同一件事，以前各写一套。 */
    private fun qualityOf(m: Media): String? = qualityTagOf(m)
}

/**
 * 播放器里那份跨源列表的状态。
 *
 * Idle 是「还没查过」，不是「别处没有」—— 从首页、继续观看直接进播放器时手上就是空的，
 * 界面得把这两种情况分开说，否则「只有一个源」和「没查过」看起来一模一样。
 */
enum class SiblingsQuery { Idle, Running, Done }

