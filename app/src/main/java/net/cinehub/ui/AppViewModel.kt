package net.cinehub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.cinehub.data.DoubanRepository
import net.cinehub.data.DownloadRepository
import net.cinehub.data.MediaRepository
import net.cinehub.data.SourceRepository
import net.cinehub.data.WatchRepository
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
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.model.normalizeTitle
import net.cinehub.data.model.userNote
import net.cinehub.data.source.SourceAdapters

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sourceRepo = SourceRepository(app)
    private val mediaRepo = MediaRepository(sourceRepo)
    private val watchRepo = WatchRepository(app)
    private val doubanRepo = DoubanRepository(app)
    private val downloadRepo = DownloadRepository(app, sourceRepo, mediaRepo)

    val downloads: StateFlow<List<DownloadTask>> = downloadRepo.tasks

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

    private val _homeLoading = MutableStateFlow(false)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private val _homeError = MutableStateFlow<String?>(null)
    val homeError: StateFlow<String?> = _homeError.asStateFlow()

    // ---------- 搜索 ----------
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<AggregatedMedia>>(emptyList())
    val results: StateFlow<List<AggregatedMedia>> = _results.asStateFlow()

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
        loadHome()
    }

    // ---------- 源管理 ----------

    fun refreshHealth(force: Boolean = true) {
        viewModelScope.launch {
            _checking.value = true
            _health.value = mediaRepo.healthAll(force)
            _checking.value = false
        }
    }

    fun toggleSource(id: String, enabled: Boolean) = viewModelScope.launch {
        sourceRepo.setEnabled(id, enabled)
    }

    fun deleteSource(id: String) = viewModelScope.launch {
        sourceRepo.delete(id)
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
                val n = SourceAdapters.create(cfg).browse(1).size
                "识别到 $n 个资源 · 平均延迟 ${ms}ms"
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

    fun loadHome() = viewModelScope.launch {
        _homeLoading.value = true
        _homeError.value = null
        val list = mediaRepo.home()
        _home.value = list
        if (list.isEmpty()) {
            _homeError.value = "所有播放源都没有返回内容，请在「播放源管理」里检查"
        }
        _homeLoading.value = false
    }

    // ---------- 搜索 ----------

    fun onQueryChange(q: String) { _query.value = q }

    fun search(q: String = _query.value) {
        if (q.isBlank()) return
        _query.value = q
        viewModelScope.launch {
            _searching.value = true
            _results.value = mediaRepo.search(q)
            _searching.value = false
            _history.value = (listOf(q) + _history.value).distinct().take(8)
        }
    }

    fun clearQuery() { _query.value = ""; _results.value = emptyList() }

    // ---------- 详情 ----------

    fun openDetail(sourceId: String, mediaId: String) {
        viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = null
            _detail.value = null
            _activeSourceId.value = sourceId
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
            if (cfg == null) {
                _detailError.value = "播放源不存在"
                _detailLoading.value = false
                return@launch
            }
            runCatching { mediaRepo.detail(cfg, mediaId) }
                .onSuccess { d ->
                    _detail.value = d
                    // 先把当前源放进去，避免面板空着；随后异步补齐其它源
                    _siblings.value = listOf(
                        SourceAvailability(
                            sourceId = cfg.id,
                            sourceName = cfg.name,
                            item = d.media,
                            health = _health.value[cfg.id]?.health ?: Health.Unknown,
                            quality = qualityOf(d.media),
                            latencyMs = _health.value[cfg.id]?.latencyMs,
                        )
                    )
                    mediaRepo.siblingsOf(d.media.key)
                        .filter { it.sourceId != cfg.id }
                        .let { others -> _siblings.value = _siblings.value + others }
                }
                .onFailure { _detailError.value = it.message ?: "详情加载失败" }
            _detailLoading.value = false
        }
    }

    /** 在详情页切源：拿新源里同一部片的详情。 */
    fun switchDetailSource(av: SourceAvailability) {
        viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = null
            val cfg = sourceRepo.all().firstOrNull { it.id == av.sourceId } ?: return@launch
            _activeSourceId.value = av.sourceId
            runCatching { mediaRepo.detail(cfg, av.item.id) }
                .onSuccess { _detail.value = it }
                .onFailure { _detailError.value = it.message ?: "该源没有这部片" }
            _detailLoading.value = false
        }
    }

    // ---------- 播放 ----------

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
    ) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
            val name = cfg?.name ?: sourceId

            // 立刻进入播放器，先把「我收到了」这一步给出去
            _playing.value = Playing(
                mediaTitle, episodeName, episodeIndex, name, sourceId, null, episodeUrl,
                sourceBaseUrl = cfg?.baseUrl?.trimEnd('/'),
                mediaId = mediaId,
                mediaKey = mediaKey,
                mediaYear = mediaYear,
                mediaCover = mediaCover,
            )
            _resumePosition.value =
                if (resumeFrom >= 0L) resumeFrom
                else mediaKey?.let { watchRepo.resumeAt(it, episodeIndex) } ?: 0L
            // 上一部的时长不能跟到这一部上：「看不看完」是拿它算的
            lastDurationSec = 0L

            if (cfg == null) {
                _playing.value = _playing.value?.copy(note = "播放源已不存在，可能在「播放源管理」里被删掉了")
                return@launch
            }
            if (episodeUrl.isNullOrBlank()) {
                _playing.value = _playing.value?.copy(note = "该源的播放地址无效")
                return@launch
            }

            _resolving.value = true
            val res = mediaRepo.resolveStream(cfg, episodeUrl)
            _resolving.value = false

            _playing.value = _playing.value?.copy(
                streamUrl = (res as? StreamResolution.Ready)?.url,
                note = noteOf(res),
                sitePageUrl = sitePageFor(cfg, mediaId, episodeIndex, episodeUrl),
            )
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

    /** 播放器里切源：保持进度。 */
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
        val d = _detail.value?.takeIf { it.media.id == mediaId } ?: return
        cache(
            p.sourceId,
            d.media,
            Episode(p.episodeName, p.episodeIndex, p.episodeUrl),
        )
    }

    fun pauseDownload(id: String) = downloadRepo.pause(id)

    fun resumeDownload(id: String) = downloadRepo.resume(id)

    fun deleteDownload(id: String) = downloadRepo.delete(id)

    fun downloadSpace(): Long = downloadRepo.spaceUsed()

    /** 直接播已缓存好的文件，不再碰网络。 */
    fun playCached(task: DownloadTask) {
        val uri = downloadRepo.localUri(task.id) ?: return
        _resumePosition.value = watchRepo.resumeAt(task.mediaKey, task.episodeIndex)
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
        )
    }

    /**
     * 播放器报上来的当前位置。
     *
     * 每 10 秒才落一次盘：DataStore 是写文件的，一秒一次是在磨盘。
     * 退出播放（[stopPlaying]）时再补一次，所以最多丢 10 秒。
     */
    fun updatePosition(sec: Long, durationSec: Long = 0L) {
        _resumePosition.value = sec
        if (durationSec > 0L) lastDurationSec = durationSec
        if (sec <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastRecordAt >= 10_000L) {
            lastRecordAt = now
            recordWatch(sec, durationSec)
        }
    }

    private var lastRecordAt = 0L

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
     */
    fun resumeFromHistory(e: HistoryEntry) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == e.sourceId } ?: return@launch
            val d = runCatching { mediaRepo.detail(cfg, e.mediaId) }.getOrNull()
            val ep = d?.lines?.firstOrNull()?.episodes?.firstOrNull { it.index == e.episodeIndex }
            if (ep == null || ep.playUrl.isNullOrBlank()) {
                // 源改版或这一集没了：把记录去掉，别让它一直躺在「继续观看」里点不动
                watchRepo.removeHistory(e.key)
                return@launch
            }
            play(
                sourceId = e.sourceId,
                mediaTitle = d?.media?.title ?: e.title,
                episodeName = ep.name,
                episodeIndex = ep.index,
                episodeUrl = ep.playUrl,
                resumeFrom = e.positionSec,
                mediaId = e.mediaId,
                mediaKey = e.key,
                mediaYear = d?.media?.year ?: e.year,
                mediaCover = d?.media?.cover ?: e.cover,
            )
        }
    }

    // ---------- 豆瓣榜单 ----------

    /**
     * 豆瓣的一条 → 影枢接的源里到底有没有这部。
     *
     * 豆瓣不给播放地址，所以只能拿片名去搜。这里**只认归一化后完全同名**的：
     * 差一点就「猜」进去，用户看到的是另一部片，比说搜不到更糟。
     *
     * 命中与否都交回调用方处理 —— 导航是界面那边的事（ ViewModel 里那个
     * `openDetail` 只把详情取回来，不改路由）。
     */
    fun openFromDouban(
        item: DoubanItem,
        onHit: (Media) -> Unit,
        onMiss: (DoubanItem) -> Unit,
    ) {
        viewModelScope.launch {
            _doubanResolving.value = item.id
            val hits = runCatching { mediaRepo.search(item.title) }.getOrDefault(emptyList())
            _doubanResolving.value = null
            val want = normalizeTitle(item.title)
            val match = hits.firstOrNull { normalizeTitle(it.media.title) == want }
            if (match == null) onMiss(item) else onHit(match.media)
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
