package net.cinehub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.cinehub.data.DoubanRepository
import net.cinehub.data.DownloadRepository
import net.cinehub.data.HomeRepository
import net.cinehub.data.MediaRepository
import net.cinehub.data.SourceRepository
import net.cinehub.data.WatchRepository
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
import net.cinehub.data.source.SourceAdapters

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sourceRepo = SourceRepository(app)
    private val mediaRepo = MediaRepository(sourceRepo)
    private val watchRepo = WatchRepository(app)
    private val doubanRepo = DoubanRepository(app)
    private val homeRepo = HomeRepository(app)
    private val downloadRepo = DownloadRepository(app, sourceRepo, mediaRepo)

    val downloads: StateFlow<List<DownloadTask>> = downloadRepo.tasks

    /** 缓存占掉的字节数。仓库在协程里数好，界面只读这个值。 */
    val downloadSpace: StateFlow<Long> = downloadRepo.space

    /** 每个任务当前的下载速度（字节/秒）。只在内存里，重启后从 0 重新算。 */
    val downloadSpeed: StateFlow<Map<String, Long>> = downloadRepo.speed

    /** 单集内同时下几个分片（「我的 → 下载设置」里调，对正在下的任务不生效）。 */
    val segParallel: StateFlow<Int> = downloadRepo.segParallel

    fun setSegParallel(n: Int) = downloadRepo.setSegParallel(n)

    /** 收藏与最近观看。名字避开 [history] —— 那个是搜索历史。 */
    val favorites: StateFlow<List<Favorite>> = watchRepo.favorites
    val watchHistory: StateFlow<List<HistoryEntry>> = watchRepo.history

    // ---------- 豆瓣榜单 ----------

    private val _doubanChart = MutableStateFlow(DoubanChart.MOVIE.first())
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
    private val _home = MutableStateFlow<List<Media>>(emptyList())
    val home: StateFlow<List<Media>> = _home.asStateFlow()

    /** 这份首页内容是几点取回来的；0 = 还没有快照。 */
    private val _homeAt = MutableStateFlow(0L)
    val homeAt: StateFlow<Long> = _homeAt.asStateFlow()

    /** 这一页内容来自哪个源（标题上要说它）。 */
    private val _homeSource = MutableStateFlow<String?>(null)
    val homeSource: StateFlow<String?> = _homeSource.asStateFlow()

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

    // ---------- 详情 ----------
    private val _detail = MutableStateFlow<MediaDetail?>(null)
    val detail: StateFlow<MediaDetail?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

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
     * 只有一处能产生它：播放器上的「在站点页面里解析」和源管理里的「在站点里登录」。
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
     * 收下站点自己解析出来的那条地址。
     *
     * 只换 streamUrl，源、Referer、这一集都不动 —— 换源会让 Referer 对不上，
     * 而这条地址恰恰是原来那个源给出来的。
     */
    /**
     * 把站点页面里接住的地址交给当前这一集。
     *
     * [headers] 是那条地址被页面请求时带的条件（Referer / Cookie）。
     * 一起带过去，播放器才可能拿到和页面一样的响应 —— 只抄 URL 过去，
     * 那些「只认自己页面」的地址会 404。
     */
    fun useSiteLink(link: String, headers: Map<String, String> = emptyMap()) {
        val cur = _playing.value ?: return
        if (!link.startsWith("http")) return
        _playing.value = cur.copy(streamUrl = link, note = null, streamHeaders = headers)
    }

    init {
        viewModelScope.launch {
            sourceRepo.sources.collect { _sources.value = it }
        }
        refreshHealth()
        // 首页那一份不在这里取：什么时候该重新拉，判据是「这一页是用哪套源顺序拉回来的」，
        // 而只有正在显示首页的那一页知道自己该不该重拉 —— 交给 HomeScreen 驱动。
    }

    // ---------- 源管理 ----------

    fun refreshHealth(force: Boolean = true) {
        viewModelScope.launch {
            _checking.value = true
            _health.value = mediaRepo.healthAll(force)
            _checking.value = false
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
        detailJob = viewModelScope.launch {
            try {
                _checking.value = true
                _health.value = mediaRepo.healthAll(force = true)
                if (mine != detailGen) return@launch
                val s = mediaRepo.siblingsOf(d.media)
                if (mine != detailGen) return@launch
                _siblingFailures.value = s.failures
                // 当前这个源仍排第一：那一栏的次序就是「你正在用哪个源」的次序
                val act = _activeSourceId.value
                _siblings.value = s.items.sortedByDescending { it.sourceId == act }
                _siblingsOf = d.media.key
            } finally {
                _checking.value = false
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
     * 补回被删掉的内置源。
     *
     * 用户误删后不需要重装 —— 只补缺失的那几个，自己加的源和已有的启停状态都不动。
     */
    fun restoreDefaultSources(onDone: (Int) -> Unit) = viewModelScope.launch {
        val n = sourceRepo.restoreDefaults()
        refreshHealth(true)
        loadHome()
        onDone(n)
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
        onResult: (ok: Boolean, message: String, tested: SourceConfig?) -> Unit,
    ) = viewModelScope.launch {
        val cfg = buildConfig(name, baseUrl, kind, authToken, playPath)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val ms = net.cinehub.data.net.Http.probe(cfg.baseUrl)
                val n = try {
                    SourceAdapters.create(cfg).browse(1).size
                } finally {
                    // 探测用的这份配置还没落库，id 是现造的 —— 不丢掉就是每测一次留一份
                    // 适配器（和它那份站点会话）在内存里没人认领
                    SourceAdapters.forget(cfg.id)
                }
                // 「识别到 0 个资源」不是成功。根路径能连通是事实，但一个资源都没给，
                // 存进来就是个占位的死源 —— 两句话都得说，别合成一句「连接成功」。
                if (n == 0) "连上了（${ms}ms），但列表里一个资源都没给 —— 先搜一次再决定存不存"
                else "识别到 $n 个资源 · 平均延迟 ${ms}ms"
            }
        }
        result.onSuccess { msg -> onResult(true, msg, cfg) }
            .onFailure { onResult(false, it.message ?: "连接失败", null) }
    }

    /** 第二步：把已经探测通过的配置落库。 */
    fun saveSource(cfg: SourceConfig) = viewModelScope.launch {
        sourceRepo.upsert(cfg)
        refreshHealth(false)
        loadHome()
    }

    private fun buildConfig(
        name: String,
        baseUrl: String,
        kind: SourceKind,
        authToken: String?,
        playPath: String? = null,
    ): SourceConfig = SourceConfig(
        id = "src_" + System.currentTimeMillis().toString(36),
        name = name.ifBlank { baseUrl },
        baseUrl = baseUrl.trim().trimEnd('/'),
        kind = kind,
        // 排到最后：默认值 0/1 会和内置源撞上，界面上就同时出现两个「默认」位次
        priority = (_sources.value.maxOfOrNull { it.priority } ?: 0) + 1,
        authToken = authToken?.takeIf { it.isNotBlank() },
        authMode = if (authToken.isNullOrBlank()) net.cinehub.data.model.AuthMode.None
        else net.cinehub.data.model.AuthMode.Cookie,
        playPath = playPath?.takeIf { it.isNotBlank() },
        html = when (kind) {
            SourceKind.Html -> net.cinehub.data.source.DefaultRules.generic
            else -> null
        },
    )

    // ---------- 首页 ----------

    /**
     * 同一时刻只让一个首页加载在跑。
     *
     * 进首页会调一次（[HomeScreen] 按源指纹驱动），存新源、补回内置源又各调一次 ——
     * 并发跑就是同时敲站点两遍、两份快照互相覆盖，谁后落地谁说了算。
     * 后发起的那次把前一次顶掉：源列表都变了，上一次拉回来的内容已经是为旧的一套拉的。
     */
    private var homeJob: Job? = null

    /** 第几代加载。只有还是当代的那次才有权改 [_homeLoading]，否则被顶掉的那次会在新的一次已经开始转圈之后把转圈关掉。 */
    private var homeGen = 0

    /**
     * 首页那一页。
     *
     * 三件事一起做：
     * ① 盘上有旧的就先上屏，哪怕已经过期 —— 冷启动对着空白页转圈，而失败那句
     *    「下面还是上次取回的那一页」会对着一片空白说出来，等于骗人；
     * ② 只跑一次，见 [homeJob]；
     * ③ 转圈标记放在 finally 里 —— 取的过程抛异常时不能把它留在屏上。
     */
    fun loadHome(force: Boolean = false) {
        val mine = ++homeGen
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            try {
                // 先等落盘那份读完：不等就会把「还没读到」当成「没有快照」，白敲一次站点
                homeRepo.ready.first { it }
                // 指纹按启用源的 id:priority 现算。读 _sources 那份内存值不行 ——
                // 冷启动那一刻它可能还是空的，指纹就成了空串，等于每次都重拉
                val active = sourceRepo.active()
                val fp = active.joinToString("|") { "${it.id}:${it.priority}" }
                val snap = homeRepo.snapshot()
                if (snap != null && _home.value.isEmpty()) {
                    _home.value = snap.items.map { it.toMedia() }
                    _homeAt.value = snap.at
                    _homeSource.value = snap.sourceId
                }
                if (!force && snap != null && !homeRepo.staleFor(fp)) return@launch
                _homeLoading.value = true
                _homeError.value = null
                val res = mediaRepo.homeWithSource()
                // 取空了不能把旧快照冲掉：一次抖动不该让用户对着一个空白首页
                if (res.items.isNotEmpty()) {
                    _home.value = res.items
                    _homeSource.value = res.sourceId
                    _homeAt.value = System.currentTimeMillis()
                    homeRepo.save(res.items, res.sourceId, fp)
                } else {
                    _homeError.value = if (_home.value.isEmpty()) {
                        "所有播放源都没有返回内容，请在「播放源管理」里检查"
                    } else {
                        "最新一次没刷新成功：所有播放源都没有返回内容（下面还是上次取回的那一页）"
                    }
                }
            } finally {
                if (mine == homeGen) _homeLoading.value = false
            }
        }
    }

    /** 手动刷新：不管过没过期，现在就去取一次。 */
    fun refreshHome() = loadHome(force = true)

    // ---------- 搜索 ----------

    fun onQueryChange(q: String) { _query.value = q }

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

    fun clearQuery() {
        _query.value = ""
        _results.value = emptyList()
        _searchFailures.value = emptyList()
    }

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
                runCatching { mediaRepo.detail(cfg, mediaId) }
                    .onSuccess { raw ->
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
                        _siblingFailures.value = s.failures
                        _siblings.value = _siblings.value + s.items.filter { it.sourceId != cfg.id }
                    }
                    .onFailure { _detailError.value = it.message ?: "详情加载失败" }
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
     */
    private fun yearKnownFor(sourceId: String, mediaId: String, title: String): String? {
        _home.value.firstOrNull { it.sourceId == sourceId && it.id == mediaId }?.year?.let { return it }
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
        val mine = ++detailGen
        detailJob?.cancel()
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
                runCatching { mediaRepo.detail(cfg, av.item.id) }
                    .onSuccess {
                        _detail.value = it.withYear(it.media.year ?: keepYear)
                        // 取回来了才认这个源：失败了还标着新源，界面就是在说一件没发生的事
                        _activeSourceId.value = av.sourceId
                    }
                    .onFailure {
                        // 换不过去就退回原来那一页：把人手上正在看的详情整个撤掉，
                        // 等于让他为了换一个源而丢掉刚才那半屏
                        _detail.value = prev
                        _detailError.value = it.message ?: "该源没有这部片"
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
     * 点下去就是拿另一部片去解析。所以只有对得上号才带过来，对不上宁可空着
     * （空着面板就不显示，比摆一排假的强）。
     */
    private val _playingSiblings = MutableStateFlow<List<SourceAvailability>>(emptyList())
    val playingSiblings: StateFlow<List<SourceAvailability>> = _playingSiblings.asStateFlow()

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

    /** 一次尝试：哪个源、为什么没成。 */
    data class Attempt(val sourceName: String, val reason: String)

    /**
     * 这一轮自动换源的样子，播放器照它说话。
     *
     * 「取链没拿到地址」「播放器报错」「地址通了却 20 秒不出第一帧」对用户是同一句话 ——
     * 这个源放不出来这一集 —— 所以三条都汇到这里，界面上也只有一种说法。
     */
    data class FallbackUi(
        /** 正在试候选里的第几个（1 起）。 */
        val step: Int,
        val total: Int,
        val tryingSourceName: String,
        val failures: List<Attempt>,
        /** 候选全试完了：停下说话，不再自己动。 */
        val exhausted: Boolean,
        /** 已经播开了：横幅留着说明刚才发生了什么，但不再表示「还在试」。 */
        val settled: Boolean = false,
    )

    private val _fallback = MutableStateFlow<FallbackUi?>(null)
    val fallback: StateFlow<FallbackUi?> = _fallback.asStateFlow()

    /**
     * 一轮之内试过些什么。
     *
     * [anchorSec] 是这一轮的位置锚点。**不能**直接读 [_resumePosition]：换源时媒体源重建会
     * 先 `player.stop()`，那一刻轮询会把 0 写回去，级联到第三个源时进度就归零了 ——
     * 只有真的听到过 >0 的位置才配更新它。
     *
     * [candidates] 会长大：从首页直接进播放器时手上没有这份跨源列表，
     * 等真的失败了才去别的源里找这一部片（见 [reportPlaybackFailure]）。
     */
    private class Round(
        val episodeIndex: Int,
        var candidates: List<SourceAvailability>,
        var cursor: Int = 0,
        val reasons: LinkedHashMap<Int, String> = LinkedHashMap(),
        var anchorSec: Long = 0L,
        var findingSiblings: Boolean = false,
    )

    private var round: Round? = null

    /**
     * 起播时开一轮：当前源打头，后面接其它源（就是「播放源管理」里拖出来的那个顺序）。
     *
     * 手上没有跨源列表时也照样开这一轮：只有一个候选时什么都不会自动发生，
     * 真失败了再去查别的源（见 [reportPlaybackFailure]）—— 首页点「立即播放」那条路才有的救。
     */
    private fun beginRound(p: Playing, siblings: List<SourceAvailability>) {
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
        round = Round(p.episodeIndex, cands, anchorSec = p.resumeFrom)
        _fallback.value = null
    }

    /**
     * 一个源在这一集上放不出来。
     *
     * 只认「现在播的确实是这一轮游标指着的那个源」的汇报 —— 换源之后旧播放器还是会晚一拍
     * 把它的错误回调送上来，那条属于上一个源，放它进来就会跳过本该试的源。
     */
    fun reportPlaybackFailure(reason: String) {
        val r = round ?: return
        val p = _playing.value ?: return
        if (p.episodeIndex != r.episodeIndex) return
        if (r.candidates.getOrNull(r.cursor)?.sourceId != p.sourceId) return
        if (r.reasons.containsKey(r.cursor)) return
        _resumePosition.value.takeIf { it > 0L }?.let { r.anchorSec = it }
        r.reasons[r.cursor] = reason
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

    /** 往下一个候选源走；没有下一个了就把这一轮的账摊开说清楚。 */
    private fun advance(r: Round) {
        if (r.cursor + 1 >= r.candidates.size) {
            // 从头到尾就一个源时不弹「第 1/1 个也失败」那种废话：
            // 下方「这个源拿不到直链」那块说的已经是同一件事，而且它还留着站点页取链那个出口
            if (r.candidates.size > 1) publish(r, exhausted = true)
            return
        }
        r.cursor++
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
            val ep = d?.lines?.firstOrNull()?.episodes?.firstOrNull { it.index == r.episodeIndex }
            if (ep == null) {
                r.reasons[r.cursor] =
                    if (d == null) "这个源取不到详情" else "这个源没有第 ${r.episodeIndex} 集"
                advance(r)
                return@launch
            }
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
                detail = d,
                autoAdvance = true,
            )
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

    /** 「再试一轮」：游标回到开头、原因清空，位置仍用这一轮听到的最后一个有效值。 */
    fun retryFallbackRound() {
        val r = round ?: return
        r.cursor = 0
        r.reasons.clear()
        _fallback.value = null
        switchWithinRound(r, 0)
    }

    private fun publish(r: Round, exhausted: Boolean) {
        _fallback.value = FallbackUi(
            step = r.cursor + 1,
            total = r.candidates.size,
            tryingSourceName = r.candidates[r.cursor].sourceName,
            failures = r.reasons.entries.sortedBy { it.key }
                .map { Attempt(r.candidates[it.key].sourceName, it.value) },
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
                _playingSiblings.value =
                    if (_playingDetail.value != null && _siblingsOf == mediaKey) _siblings.value
                    else emptyList()
                // 自动换源走到这里是同一轮里的下一次尝试，流水和游标都不能动；
                // 用户自己点的播/换集/续播才开新的一轮。
                val keepRound = autoAdvance && round?.episodeIndex == episodeIndex
                if (!keepRound) _playing.value?.let { beginRound(it, _playingSiblings.value) }
                _resumePosition.value = start
                // 上一部的时长不能跟到这一部上：「看不看完」是拿它算的
                lastDurationSec = 0L

                if (cfg == null) {
                    _playing.value = _playing.value?.copy(
                        note = "播放源已不存在，可能在「播放源管理」里被删掉了",
                    )
                    return@launch
                }
                if (episodeUrl.isNullOrBlank()) {
                    _playing.value = _playing.value?.copy(note = "该源的播放地址无效")
                    return@launch
                }

                _resolving.value = true
                val res = mediaRepo.resolveStream(cfg, episodeUrl)
                if (mine != playGen) return@launch
                _resolving.value = false

                _playing.value = _playing.value?.copy(
                    streamUrl = (res as? StreamResolution.Ready)?.url,
                    note = noteOf(res),
                    sitePageUrl = sitePageFor(cfg, mediaId, episodeIndex, episodeUrl),
                )
                // 拿不到可播地址就是「这个源放不出来这一集」，交给自动换源往下走。
                // 另开一个协程去汇报：这里正跑在本轮的 playJob 里，就地切换会把它取消掉。
                if (res !is StreamResolution.Ready) {
                    val why = noteOf(res) ?: "这个源给不出可播地址"
                    viewModelScope.launch { reportPlaybackFailure(why) }
                }
            } finally {
                if (mine == playGen) _resolving.value = false
            }
        }
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

    // ---------- 离线缓存 ----------

    fun downloadTask(sourceId: String, mediaId: String, episodeIndex: Int): DownloadTask? =
        downloadRepo.taskOf(sourceId, mediaId, episodeIndex)

    fun cache(sourceId: String, media: Media, episode: Episode) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId } ?: return@launch
            downloadRepo.enqueue(cfg, media, episode)
        }
    }

    fun cacheLine(sourceId: String, media: Media, episodes: List<Episode>) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId } ?: return@launch
            downloadRepo.enqueueAll(cfg, media, episodes)
        }
    }

    /** 播放器里的「缓存」：把当前这一集存下来，用的就是它刚解析过的那条直链。 */
    fun cachePlaying() {
        val p = _playing.value ?: return
        val mediaId = p.mediaId ?: return
        // 先看播放器自己那份快照（起播时对过号），再退回详情页当前那一份 ——
        // 只认得得上号的那部，否则会把另一部片的字段写进这条缓存任务
        val d = _playingDetail.value?.takeIf { it.media.id == mediaId }
            ?: _detail.value?.takeIf { it.media.id == mediaId } ?: return
        cache(
            p.originSourceId ?: p.sourceId,
            d.media,
            Episode(p.episodeName, p.episodeIndex, p.episodeUrl),
        )
    }

    fun pauseDownload(id: String) = downloadRepo.pause(id)

    fun resumeDownload(id: String) = downloadRepo.resume(id)

    fun deleteDownload(id: String) = downloadRepo.delete(id)

    /** 直接播已缓存好的文件，不再碰网络。 */
    fun playCached(task: DownloadTask) {
        val uri = downloadRepo.localUri(task.id) ?: return
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
    }

    /**
     * 播放器报上来的当前位置。
     *
     * 每 10 秒才落一次盘：DataStore 是写文件的，一秒一次是在磨盘。
     * 退出播放（[stopPlaying]）时再补一次，所以最多丢 10 秒。
     */
    fun updatePosition(sec: Long, durationSec: Long = 0L) {
        _resumePosition.value = sec.coerceAtLeast(0L)
        // Media3 在没解出时长时给的是 C.TIME_UNSET（Long.MIN_VALUE+1）。轮询那里现在会先归 0，
        // 但这条是通向落盘的唯一入口，判一下比依赖上游的算术更稳 ——
        // 那个哨兵值一旦写进记录，「看完了没」就是拿一个负的天文数字在算。
        val dur = durationSec.takeIf { it > 0L && it <= MAX_DURATION_SEC } ?: 0L
        if (dur > 0L) lastDurationSec = dur
        if (sec <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastRecordAt >= 10_000L) {
            lastRecordAt = now
            // 写 lastDurationSec 而不是这一次上报的 dur：换集刚起播那几秒 Media3 常常
            // 只有位置、还没有时长（TIME_UNSET 归 0），拿 dur 写就等于把记录里已经存好的
            // 时长冲成 0 —— 而「看完没看完」「还剩多少」全是拿时长算的。
            recordWatch(sec, lastDurationSec)
        }
    }

    private var lastRecordAt = 0L

    private companion object {
        /** 一集再长也不会超过一天。长过这个的就是哨兵值而不是时长。 */
        const val MAX_DURATION_SEC = 24 * 3600L
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
            val cfg = sourceRepo.all().firstOrNull { it.id == e.sourceId } ?: return@launch
            val fetched = runCatching { mediaRepo.detail(cfg, e.mediaId) }
            val d = fetched.getOrElse { err ->
                refuseToPlay(
                    e, cfg,
                    "没能从「${cfg.name}」问到这一集的最新地址：" +
                        "${err.shortReason()} —— 进度给你留着，稍后再试，或者在播放器里换一个源。",
                )
                return@launch
            }
            val ep = d.lines.firstOrNull()?.episodes?.firstOrNull { it.index == e.episodeIndex }
            if (ep == null || ep.playUrl.isNullOrBlank()) {
                // 源改版或这一集真的没了：把记录去掉，别让它一直躺在「继续观看」里点不动
                refuseToPlay(
                    e, cfg,
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
    private fun refuseToPlay(e: HistoryEntry, cfg: SourceConfig, why: String) {
        _playing.value = Playing(
            mediaTitle = e.title,
            episodeName = "第 ${e.episodeIndex} 集",
            episodeIndex = e.episodeIndex,
            sourceName = cfg.name,
            sourceId = cfg.id,
            streamUrl = null,
            note = why,
            mediaId = e.mediaId,
            mediaKey = e.key,
            mediaYear = e.year,
            mediaCover = e.cover,
            resumeFrom = e.positionSec,
        )
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
        // 连点两次会并发跑两份搜索、回来两笔导航 —— 先占住，正在找的时候点不动
        if (_doubanResolving.value != null) return
        viewModelScope.launch {
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
        if (!_doubanLoading.value) refreshDouban()
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

    fun refreshDouban() {
        val chart = _doubanChart.value
        _doubanLoading.value = true
        viewModelScope.launch {
            val res = runCatching { doubanRepo.fetch(chart) }
            // 拉回来这段时间里用户可能已经切走了，别把结果泼到新的榜上
            if (chart.id != _doubanChart.value.id) {
                _doubanLoading.value = false
                return@launch
            }
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
            _doubanLoading.value = false
        }
    }

    private fun qualityOf(m: Media): String? {
        val t = listOfNotNull(m.remark, m.category, m.title).joinToString(" ")
        return when {
            Regex("4k", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "4K"
            Regex("hdr", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "HDR"
            Regex("1080").containsMatchIn(t) -> "1080P"
            Regex("720").containsMatchIn(t) -> "720P"
            else -> null
        }
    }
}
