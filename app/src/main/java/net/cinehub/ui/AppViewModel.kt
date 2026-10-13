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
import net.cinehub.data.MediaRepository
import net.cinehub.data.SourceRepository
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.source.SourceAdapters

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val sourceRepo = SourceRepository(app)
    private val mediaRepo = MediaRepository(sourceRepo)

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
    )

    /** 正在解析直链 —— 播放器靠它显示「已收到你的点击」，而不是一个不动的黑屏。 */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

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
        onResult: (ok: Boolean, message: String, tested: SourceConfig?) -> Unit,
    ) = viewModelScope.launch {
        val cfg = buildConfig(name, baseUrl, kind, authToken)
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
    ): SourceConfig = SourceConfig(
        id = "src_" + System.currentTimeMillis().toString(36),
        name = name.ifBlank { baseUrl },
        baseUrl = baseUrl.trim().trimEnd('/'),
        kind = kind,
        authToken = authToken?.takeIf { it.isNotBlank() },
        authMode = if (authToken.isNullOrBlank()) net.cinehub.data.model.AuthMode.None
        else net.cinehub.data.model.AuthMode.Cookie,
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
        resumeFrom: Long = _resumePosition.value,
    ) {
        viewModelScope.launch {
            val cfg = sourceRepo.all().firstOrNull { it.id == sourceId }
            val name = cfg?.name ?: sourceId

            // 立刻进入播放器，先把「我收到了」这一步给出去
            _playing.value = Playing(
                mediaTitle, episodeName, episodeIndex, name, sourceId, null, episodeUrl,
                sourceBaseUrl = cfg?.baseUrl?.trimEnd('/'),
            )
            _resumePosition.value = resumeFrom

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
            )
        }
    }

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
        )
    }

    /**
     * 把解析结果翻译成给用户看的一句话。
     *
     * 几种「拿不到」的成因要分得开 —— 尤其要让用户知道哪些是换个源就能解决的、
     * 哪些是填自己的凭证可能走通的、哪些是本站根本不打算给匿名用户的。
     */
    private fun noteOf(res: StreamResolution): String? = when (res) {
        is StreamResolution.Ready -> null

        is StreamResolution.ThirdPartyPage ->
            "这个源给的是${res.platform}的播放页，不是视频文件本身。" +
                "影枢只在本机解码直链，不会去解析、转码别人的页面。"

        is StreamResolution.NeedsSession ->
            "直链只对登录用户开放，影枢不代为登录。" +
                "可以在「播放源管理」里填入你自己的站点凭证，让影枢只做转发。"

        is StreamResolution.NeedsClientScript ->
            "直链地址由源站页面里的脚本现算（带签名），不执行它的脚本就构造不出请求。" +
                "影枢不执行站点脚本、也不逆它的签名，所以这里只能如实说拿不到 —— " +
                "填凭证也解决不了这一步。"

        is StreamResolution.Failed ->
            "解析直链失败：${res.reason}"
    }

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
            )
        }
    }

    fun updatePosition(sec: Long) { _resumePosition.value = sec }

    fun stopPlaying() {
        _playing.value = null
        _resolving.value = false
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
