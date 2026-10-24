package net.cinehub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.qualityTagOf
import net.cinehub.data.model.SearchOutcome
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.model.titleFingerprint
import net.cinehub.data.model.titleSearchVariants
import net.cinehub.data.model.typeFingerprint
import net.cinehub.data.net.Http
import net.cinehub.data.source.SourceAdapters
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨源聚合。
 *
 * 关键行为：搜索时并发打所有启用的源，然后把结果按「标题 + 年份」归并 ——
 * 一部片只出一条，但带着它在每个源的可播情况。这正是设计稿 02 屏的形态。
 */
class MediaRepository(private val sources: SourceRepository) {

    private val healthCache = ConcurrentHashMap<String, HealthReport>()
    private val healthAt = ConcurrentHashMap<String, Long>()

    /**
     * 这一份健康报告是**对着哪个地址**量出来的。
     *
     * 只按源 id 缓存会出这样一件事：用户在「播放源管理」里把某个源的 baseUrl 改了、
     * 存下之后 10 分钟内（HEALTH_TTL_MS）界面继续挂着上一个站的状态和毫秒数 ——
     * 搜索页那行延迟数字是直接打印它的。地址换了，这一栏的结论就该作废重来。
     */
    private val healthProbeKey = ConcurrentHashMap<String, String>()

    /**
     * 健康度多久算过期。
     *
     * 原来只有 `Down` 会过期（一分钟）—— 意思是「一次都没探通」会自己重试，
     * 而「上周三它还挺快」这条结论永远作数。屏幕上一个源挂着 12ms 绿点，
     * 实际那个站三天前就换了 CDN，用户照着这个绿点去缓存，等来的是一整夜的失败。
     * 探测本来就是易碎的瞬时事实，和 Down 一样得让它自己褪色。
     *
     * 顺带一句：`Down` 只算「这一刻根路径没答话」，过一分钟就自动重试。
     * 探的是站点根路径，跟真正取内容的接口不是一回事：根路径抖一下就把源判死，
     * 用户看到的就成了「搜索没结果」「某几个源凭空消失」—— 所以外面一律拿它当参考、
     * 不当闸门（见 [search]）。
     */
    suspend fun healthOf(cfg: SourceConfig, force: Boolean = false): HealthReport {
        val cached = healthCache[cfg.id]
        val at = healthAt[cfg.id] ?: 0L
        val age = System.currentTimeMillis() - at
        val expired = cached != null &&
            (if (cached.health == Health.Down) age > DOWN_RETRY_MS else age > HEALTH_TTL_MS)
        // 量的是这一条：baseUrl / 探测路径 或「要不要走浏览器内核」换了，旧的那份就不是这一路的结论了
        val probeKey = "${cfg.probeUrl()}|${cfg.needsBrowser}"
        if (!force && cached != null && !expired && healthProbeKey[cfg.id] == probeKey) return cached
        val report = check(cfg)
        healthCache[cfg.id] = report
        healthAt[cfg.id] = System.currentTimeMillis()
        healthProbeKey[cfg.id] = probeKey
        return report
    }

    private suspend fun check(cfg: SourceConfig): HealthReport = withContext(Dispatchers.IO) {
        runCatching {
            // 探 probeUrl()：有的后端根路径本来就 404（内容全在 /api 下的接口型源），
            // 拿根路径去量会把一个完全取得出内容的源永远画成红字「连接失败」。
            val ms = Http.probe(cfg.probeUrl(), referer = null, viaBrowser = cfg.needsBrowser)
            val health = when {
                ms < 800 -> Health.Ok
                else -> Health.Slow
            }
            HealthReport(health, ms)
        }.getOrElse {
            HealthReport(Health.Down, null, it.message ?: "连接失败")
        }
    }

    suspend fun healthAll(force: Boolean = false): Map<String, HealthReport> = coroutineScope {
        sources.all().map { cfg ->
            async { cfg.id to healthOf(cfg, force) }
        }.map { it.await() }.toMap()
    }

    /**
     * 跨源搜索并归并。
     *
     * 整趟套在 IO 上：`async { }` 继承的是调用方的调度器，而调用方是 viewModelScope ——
     * 主线程。取正文那几步 Http 自己会切到 IO，可**解析**是留在调用线程上做的，
     * Jsoup 把几个站的 HTML 捋一遍就是几十毫秒起步，源越多越久。表现就是搜索时整屏卡住、
     * 点什么都没反应，最后 Android 甩一个「应用没有响应」把它自己杀掉。
     */
    suspend fun search(keyword: String): SearchOutcome = withContext(Dispatchers.IO) {
        searchOnIo(keyword)
    }

    private suspend fun searchOnIo(keyword: String): SearchOutcome = coroutineScope {
        val active = sources.active()
        // 用户拖出来的那个顺序，排序时要用（SourceAvailability 上不带优先级，所以单独传一份）
        val priority = active.withIndex().associate { (i, cfg) -> cfg.id to (i + 1) }
        val per = active.map { cfg ->
            // 这里不按健康度设闸门：探根路径失败不代表这个源取不到内容，
            // 拿 Down 当闸门会让一次抖动把源整个会话判死 —— 表现就是「搜索失效、少了几个源」。
            async {
                val r = quietly { SourceAdapters.create(cfg).search(keyword, 1) }
                val list = r.getOrNull().orEmpty()
                if (list.isNotEmpty()) washHealth(cfg.id)
                Triple(cfg, list, r.exceptionOrNull())
            }
        }.map { it.await() }

        // 归并的桶按「片名指纹」开，年份只在两个桶真打架时才分家。
        //
        // 原来直接用 Media.key（归一化片名 + 年份）当桶，于是「有的源给年份、有的不给」
        // 会被当成两部不同的片 —— 实测这批站里 4kku 的卡片带年份、懂片帝与 FreeOK 不带：
        // 同一部片搜成两行，各自只挂一个源徽标，那句「1 个源可播」就成了假话，
        // 而点进任意一行都能给出对方的源（详情那条路用的是宽松判据，见 [sameWork]）。
        // 两条路口径不一致，就该按宽松那一条统一：有一方没年份即算同一部。
        val byFp = LinkedHashMap<String, MutableList<Pair<SourceConfig, Media>>>()
        per.forEach { (cfg, list, _) ->
            // 同一个源可能对同一部片返回多条（不同线路各算一条），先在本源内去掉，
            // 否则徽标会重复出现。
            //
            // 去重的键要把**类型**算进去：同名同年但类型不同的两条是两部片（实测
            // 「余红旧事」2026 的漫剧与连续剧），只按 Media.key 去重会把后一条整个丢掉 ——
            // 而丢掉的那条恰恰是带 4K 线路的，用户于是永远只看到先回来的那组线路。
            list.distinctBy { it.key + "|" + typeFingerprint(it.category).orEmpty() }
                .forEach { m ->
                    byFp.getOrPut(titleFingerprint(m.title)) { mutableListOf() } += cfg to m
                }
        }

        val merged = LinkedHashMap<String, MutableList<SourceAvailability>>()
        val seed = HashMap<String, Media>()
        byFp.forEach { (fp, bucket) ->
            val years = bucket.mapNotNull { yearOf(it.second) }.distinct()
            val yearGroups: List<Pair<String?, List<Pair<SourceConfig, Media>>>> = if (years.size <= 1) {
                listOf(years.firstOrNull() to bucket)
            } else {
                // 同名不同年（重拍、同名续作）才按年份分家；没带年份的那些归进第一档，
                // 因为这里没有更多信息可判，而把它们单独成一行同样是在骗人
                bucket.groupBy { yearOf(it.second) ?: years.first() }.map { (y, ms) -> y to ms }
            }
            yearGroups.forEach { (year, items) ->
                // 同名同年也未必是同一部片，再按类型归一值分一次家（见 [typeFingerprint]）。
                // 判据与年份那一条完全一样：**有一方没写类型就不分家** —— 各源给不给这一列
                // 差别很大，拿「一边没有」当「两边不同」会把本来对得上的片子全拆成两行，
                // 那是为了修一条而弄坏一片。
                val types = items.mapNotNull { typeFingerprint(it.second.category) }.distinct()
                val typeGroups: List<Pair<String?, List<Pair<SourceConfig, Media>>>> =
                    if (types.size <= 1) {
                        listOf(types.firstOrNull() to items)
                    } else {
                        items.groupBy { typeFingerprint(it.second.category) ?: types.first() }
                            .map { (t, ms) -> t to ms }
                    }
                typeGroups.forEach { (type, group) ->
                    // 键里带上年份与类型：两条不同片必须落在两个 slot 里，
                    // 否则下面那句「同一源只留一条」又会把后一条挡掉。
                    val key = buildString {
                        append(fp)
                        if (year != null) append('|').append(year)
                        if (type != null) append('|').append(type)
                    }
                    group.forEach { (cfg, m) ->
                        val slot = merged.getOrPut(key) { mutableListOf() }
                        if (slot.none { it.sourceId == cfg.id }) {
                            slot += SourceAvailability(
                                sourceId = cfg.id,
                                sourceName = cfg.name,
                                item = m,
                                health = healthCache[cfg.id]?.health ?: Health.Unknown,
                                quality = qualityTag(m),
                                latencyMs = healthCache[cfg.id]?.latencyMs,
                            )
                            seed.putIfAbsent(key, m)
                        }
                    }
                }
            }
        }

        // 没查动的源单独带回去：少了这一笔，界面就只能说「没有这部片」，
        // 而真相可能是「七个源里三个没连上」—— 那是两回事。
        val failures = per.mapNotNull { (cfg, _, err) ->
            err?.let { SearchOutcome.Failure(cfg.name, it.shortReason()) }
        }

        SearchOutcome(
            items = merged.entries.map { (key, av) ->
                AggregatedMedia(
                    media = seed.getValue(key),
                    // 先按用户在「播放源管理」里拖出来的顺序，再看能不能播、多快：
                    // 顺序是他表达过的意图，比一次探测到的延迟更该被尊重（延迟只用来在同优先级里分先后）
                    availability = av.sortedWith(
                        compareBy(
                            { priority[it.sourceId] ?: Int.MAX_VALUE },
                            { it.health == Health.Down },
                            { it.health == Health.Slow },
                            { it.latencyMs ?: Long.MAX_VALUE },
                        )
                    ),
                )
            }.sortedByDescending { it.availability.size },
            failures = failures,
        )
    }

    suspend fun detail(cfg: SourceConfig, id: String): MediaDetail =
        withContext(Dispatchers.IO) { SourceAdapters.create(cfg).detail(id) }

    /**
     * 详情页与「重新检测」、换源都会同时要同一部片的详情 —— 原来每次都打站。
     *
     * 这一层做两件事：**同一 key 的并发请求只发一次**（in-flight 合并），
     * 以及同一 key 在 [DETAIL_TTL_MS] 内直接命中缓存（不再打站）。
     *
     * 为什么需要它（实机抓包证据）：`AppViewModel` 里有四处会拉同一部片的详情 ——
     * `openDetail`、`switchDetailSource`、`switchWithinRound`（自动换源，`advance()` 还
     * 会主动把 `r.detail` 清成 null，于是每换一个源就重拉一次）、`switchPlayingSource`。
     * 「打开详情 → 点另一个源 → 自动换源」这一串里，同一个源同一部片的详情 URL
     * 被反复 GET，而站点那边看到的正是「连着打就回 429」。
     *
     * **为什么只做 in-flight 合并、不做 TTL 缓存**：`MediaDetail` 里装着
     * `lines` → `episodes` 一整棵可变集合（`data\model\Models.kt`）。缓存对象本身
     * 等于让好几个调用方共享同一份可变状态 —— 上层 `switchWithinRound` 会往里读、
     * `retryFallbackRound` 会清 `r.detail`，谁都可能改它。只做 in-flight 合并
     * 就已经消掉了「同一时刻的重复请求」（那是真正重复的那些），
     * 而跨时间的复用交给 `SourceRepository` 那一侧 —— 那一侧本来就是可变的，
     * 加缓存的风险大于收益。
     */
    private val detailInFlight = ConcurrentHashMap<String, CompletableDeferred<Result<MediaDetail>>>()

    /** 一次详情取回：同 key 合并成一次网络往返。 */
    suspend fun detailShared(cfg: SourceConfig, id: String): MediaDetail {
        val key = "${cfg.id}|$id"
        val mine = CompletableDeferred<Result<MediaDetail>>()
        val winner = detailInFlight.putIfAbsent(key, mine)
        val slot = winner ?: mine
        // 后到的那些在这里等：它们不自己打站，只拿主办者的结果。
        return try {
            if (slot === mine) {
                // 主办者。**必须把取消也写成槽里的结果**：`quietly` 会把
                // CancellationException 原样抛出来（见 [Failures.kt]），而这里要是
                // 直接让它穿过去，`mine` 就永远不 complete —— 排在后面的等待者
                // 会一直挂在 await() 上，直到协程作用域整个被收掉。
                // 用户看到的是「换源之后那一屏再也不动了」。
                val r = try {
                    quietly { detail(cfg, id) }
                } catch (ce: CancellationException) {
                    mine.complete(Result.failure(ce))
                    throw ce
                }
                mine.complete(r)
                r.getOrThrow()
            } else {
                slot.await().getOrThrow()
            }
        } finally {
            // 只有主办者摘自己的那一份：等待者摘早了会放走另一趟。
            if (slot === mine) detailInFlight.remove(key, mine)
        }
    }

    /**
     * 解析直链。
     *
     * 成因由适配器自己判断（只有它知道自己那个站的规则），这里只做一件事：
     * 适配器抛异常时兜底，别让用户看到一个没头没尾的失败。
     *
     * [mediaId] 一路传给适配器：有的型（PPnix）要拿它查「这一部的取链参数」，
     * 见 [SourceAdapter.resolveStream]。
     */
    suspend fun resolveStream(cfg: SourceConfig, episodeUrl: String, mediaId: String? = null): StreamResolution =
        withContext(Dispatchers.IO) {
            quietly { SourceAdapters.create(cfg).resolveStream(episodeUrl, mediaId) }
                .getOrElse { e ->
                    VideoPlatforms.platformOf(episodeUrl)
                        ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
                        ?: StreamResolution.Failed(e.message ?: "解析直链时出错")
                }
        }

    /**
     * [siblingsOf] 的两部分：对得上的其它源，和根本没查动的源（带原因）。
     */
    data class Siblings(
        val items: List<SourceAvailability>,
        val failures: List<SearchOutcome.Failure> = emptyList(),
    )

    /**
     * 同一部片在其它源里的对应条目 —— 切源不丢进度的基础。
     *
     * 搜索词用的是**原标题**，不是归并键里那串去了标点的：站点多是 LIKE 匹配，
     * 「暗影蜘蛛侠：黑白版」被削成「暗影蜘蛛侠黑白版」之后一条都搜不到 ——
     * 表现就是搜索卡片上挂着三个源徽标，点进去只剩两个，少的正是刚见过的那几个。
     * 比对时才用片名指纹（见 [sameWork]）。
     *
     * 匹配条件是「标题相同 + 年份不冲突」，不是整键相等。各源对同一部片的年份写法本来就不一致
     * （实测搜「功夫女足」：有的给 2026、有的不给）。只有两边都写了年份且年份不同，才算另一部片。
     *
     * 原标题一条都对不上时，会换写法再试（见 [findInSource]）—— 片名里的空格和季号写法
     * 是「明明有却说没有」最常见的两个来源。
     */
    suspend fun siblingsOf(media: Media): Siblings = withContext(Dispatchers.IO) { siblingsOnIo(media) }

    private suspend fun siblingsOnIo(media: Media): Siblings = coroutineScope {
        val want = titleFingerprint(media.title)
        val year = media.key.substringAfter('|', "").takeIf { it.isNotBlank() }
        // 类型也带过去比对：后端里同名同年可能有两条（漫剧 / 连续剧），不带它就会
        // 把另一条的线路当成这部的 —— 切源切过去看到的是别的片。
        val type = typeFingerprint(media.category)
        // 先把这一部片的跨源命中查出来（走缓存，见 [searchHitsFor]）——
        // 原来是直接 `findInSource`，而它调的是**和 [searchOnIo] 完全相同**的
        // 站点搜索接口。于是「搜索 → 点开」这一个动作，同一批源被搜两遍：
        // 实机抓包显示搜索阶段 5 秒内 45 条新建连接，其中 1 秒 17 条、同一 host 11 条。
        val hitsBySource = searchHitsFor(media.title, want, year, type)
        val active = sources.active()
        val per = active.map { cfg ->
            async {
                // 切源列表更不能因为探测失败就少一项 —— 少了那一项用户就换不过去
                val (hits, err) = hitsBySource[cfg.id] ?: (emptyList<Media>() to null)
                // 真取回了内容，就是比探根路径更硬的证据 —— 和 [searchOnIo] 同一件事，
                // 这里原先没做：于是播放器里「查一次」刚把 FreeOK 列进可换的源，
                // 退回详情页那一栏它却写着「不可用」，同一部片、隔一秒、两句话。
                if (hits.isNotEmpty()) washHealth(cfg.id)
                val health = healthCache[cfg.id]?.health ?: Health.Unknown
                Triple(
                    cfg,
                    hits.map {
                        SourceAvailability(
                            sourceId = cfg.id,
                            sourceName = cfg.name,
                            item = it,
                            health = health,
                            quality = qualityTag(it),
                            latencyMs = healthCache[cfg.id]?.latencyMs,
                        )
                    },
                    err,
                )
            }
        }.map { it.await() }

        Siblings(
            // 一个源只留一条：同一个源可能既给「烬中欢」又给「烬中欢(2026)」这类重复条目，
            // 年份没写的那条也过得去 sameWork —— 于是「播放源」里会出现两张一模一样的 4kku 徽标，
            // 用户分不清自己切的是哪一张（自动换源也照着这个列表往下走）。
            items = per.flatMap { it.component2() }.distinctBy { it.sourceId },
            failures = per.mapNotNull { (cfg, _, err) ->
                err?.let { SearchOutcome.Failure(cfg.name, it.shortReason()) }
            },
        )
    }

    /**
     * 一部片在各源里的命中：先查缓存，未命中才扇出，并把结果写回缓存。
     *
     * 返回 `源 id -> (命中, 失败原因)`。存的是**每个源的命中**，不是合并后的结果 ——
     * health 与 latency 必须现读现算（它们和搜索结果的生命周期不一样，
     * 冻在缓存里就成了「隔一秒、两句话」）。
     *
     * 缓存的价值（实机抓包）：用户在搜索页搜「三体」→ [searchOnIo] 对 6 个源各发 1 次；
     * 点进结果 → `siblingsOf` 又对**同一批源**各发 1～3 次几乎同名的搜索。
     * 同一个站点在 2 秒内收到 2～4 个同型搜索请求 —— 这正是被限流的那一批站最忌讳的。
     *
     * 有了这份缓存，「搜索 → 点开」这一次的请求数从 `1 + 3N` 降到 `1`（N = 源数）：
     * 点开详情时全部命中缓存，一个请求都不多发。
     */
    private suspend fun searchHitsFor(
        title: String,
        want: String,
        year: String?,
        type: String?,
    ): Map<String, Pair<List<Media>, Throwable?>> {
        val key = searchCacheKey(title, year, type)
        val now = System.currentTimeMillis()
        // 命中缓存的前提是**它覆盖了当前每一个启用的源**。
        //
        // 这一条闸门是功能性的，不是效率性的：`siblingsOnIo` 拿这份结果直接渲染
        // 「播放源」那一栏，缓存里少一个源，那一栏就少一格 —— 用户于是换不到那个源上去。
        // 而源列表是会变的（停用、启用、新增、删掉、改地址），只认「键一样」就会把
        // 上一次那份**不完整**的账当成这一次的结论。
        // 少一格比多打一次请求严重得多 —— 见本文件里那句「切源列表更不能因为探测失败就少一项」。
        val cached = synchronized(searchCache) { searchCache[key] }
        if (cached != null && now - cached.at < SEARCH_TTL_MS) {
            val activeIds = sources.active().map { it.id }
            if (activeIds.all { it in cached.hits }) return cached.hits
        }

        // 同一部片被两处同时要（搜索页点开 + 详情页重新检测，或播放器里「查一次」与
        // 详情页那栏撞上）时，只发一次扇出。后到的在这里等主办者的结果。
        val mine = CompletableDeferred<Map<String, Pair<List<Media>, Throwable?>>>()
        val existing = synchronized(searchInFlight) { searchInFlight.putIfAbsent(key, mine) }
        val slot = existing ?: mine
        try {
            if (slot !== mine) return slot.await()
            // 主办者。**取消也必须写进槽里**：这一趟跑在 viewModelScope 上，用户退搜索页、
            // 换词、切源都会把它掐掉。让它把 CancellationException 直接穿过去的话，
            // `mine` 永远不 complete —— 排在后面的等待者一直挂在 await() 上。
            val out = try {
                val active = sources.active()
                coroutineScope {
                    active.map { cfg ->
                        async { cfg.id to findInSource(cfg, title, want, year, type) }
                    }.map { it.await() }.toMap()
                }
            } catch (ce: CancellationException) {
                mine.completeExceptionally(ce)
                throw ce
            }
            mine.complete(out)
            synchronized(searchCache) {
                searchCache[key] = SearchEntry(at = System.currentTimeMillis(), hits = out)
                while (searchCache.size > SEARCH_CACHE_MAX) {
                    val oldest = searchCache.keys.firstOrNull() ?: return@synchronized
                    searchCache.remove(oldest)
                }
            }
            return out
        } finally {
            if (slot === mine) synchronized(searchInFlight) { searchInFlight.remove(key, mine) }
        }
    }

    /** 同一部片的跨源搜索在飞的那一趟：`片名指纹|年份|类型` -> 结果槽。 */
    private val searchInFlight = LinkedHashMap<String, CompletableDeferred<Map<String, Pair<List<Media>, Throwable?>>>>()

    private class SearchEntry(val at: Long, val hits: Map<String, Pair<List<Media>, Throwable?>>)

    private val searchCache = LinkedHashMap<String, SearchEntry>()

    /**
     * 这一部片的跨源搜索缓存的键。
     *
     * 三项逐项对应 `siblingsOnIo` 的比对口径（[titleFingerprint] + 年份 + 类型）：
     * 少任何一项都会把另一部片认成这一部 —— 同名不同年（翻拍、续集）、
     * 同名同年不同类型（漫剧 / 连续剧）都是实测存在的。
     */
    private fun searchCacheKey(title: String, year: String?, type: String?): String =
        "${titleFingerprint(title)}|${year.orEmpty()}|${type.orEmpty()}"

    /**
     * 三档都有据：
     * - 空格 —— 豆瓣写「绝望写手 第五季」、源里存的是连写的那串，LIKE 匹配认不得空格；
     *   只发原样那次，源会被判成「没这部片」。
     * - 季号写法 —— 懂片帝写「第5季」、4kku 写「第五季」，从任一种写法出发去 LIKE 另一个
     *   都落空，而「绝望写手」这一截两边都在。比对口径由 [titleFingerprint] 兜住，
     *   所以放宽检索词不会把第二季认成第五季。
     *
     * 标题既没空格也没季号时只剩第一档；只有真的没找到才继续往下试。
     */
    private suspend fun findInSource(
        cfg: SourceConfig,
        title: String,
        want: String,
        year: String?,
        type: String?,
    ): Pair<List<Media>, Throwable?> {
        var lastErr: Throwable? = null
        for (q in titleSearchVariants(title)) {
            // create() 也放在 quietly 里：这趟跑在 viewModelScope 上，外面没有 catch，
            // 一个源构造不出来不该把整张详情页带走
            val r = quietly { SourceAdapters.create(cfg).search(q, 1) }
            val hits = r.getOrNull().orEmpty().filter { sameWork(it, want, year, type) }
            if (hits.isNotEmpty()) return hits to null
            lastErr = r.exceptionOrNull()
        }
        return emptyList<Media>() to lastErr
    }

    /**
     * 真取回了内容，就是比「探根路径没答话」更硬的证据 —— 用它把不可用洗掉，
     * 否则界面上会出现「这个源给了结果、却标着不可用」的自相矛盾。
     *
     * 只改状态，**不拿这一趟的耗时去覆写 latencyMs**：那个数字里混着浏览器内核排队与解析，
     * 而健康表里 latencyMs 的定义是「只探根路径、不读体」量出来的（见 [check]）。
     * 一个字段两套定义，而界面上三处（搜索行、详情页那一栏、源管理）都拿它当「延迟」显示。
     */
    private fun washHealth(cfgId: String) {
        val prev = healthCache[cfgId]
        if (prev?.health == Health.Ok) return
        healthCache[cfgId] = HealthReport(Health.Ok, prev?.latencyMs)
        healthAt[cfgId] = System.currentTimeMillis()
    }

    /**
     * 是不是同一部片：标题指纹 + 年份不冲突 + **类型不冲突**。
     *
     * 第三条是实测倒逼出来的：后端里同名同年的条目不止一条，而它们往往不是同一部片。
     * 「余红旧事」2026 有两条 —— 一条 `type_name=漫剧`（34 集，3 条 1080P 线路），
     * 一条 `type_name=连续剧`（34 集，蓝光4K / 4K / 小艺）。只按「同名同年」判，
     * 搜索卡片会把两条合成一条、详情页只拿到其中一条的线路：用户看到的就是
     * 「官方源明明有 3 条 4K，影枢里只有 3 条 1080P」。
     *
     * 只在一方写了类型时生效：这一列各源写法差别大（有的给 type_name、有的给 vod_class、
     * 有的根本不写），拿「一边没有」当「两边不同」会把本来对得上的片子全拆开 ——
     * 那就成了为了修一条而弄坏一片。
     */
    private fun sameWork(m: Media, want: String, year: String?, type: String?): Boolean {
        if (titleFingerprint(m.title) != want) return false
        val otherYear = m.key.substringAfter('|', "").takeIf { it.isNotBlank() }
        if (!(year == null || otherYear == null || otherYear == year)) return false
        if (type == null) return true
        val otherType = m.category?.takeIf { it.isNotBlank() } ?: return true
        // 认不出来和「没写」是同一件事：都没有信号，都不该分家。
        // 原来这里直接 `typeFingerprint(otherType) == type`，而认不出来时那个函数返回 null，
        // 于是 `null == type` 恒假 —— 另一源的分类是「科幻」这种表外的写法时，
        // 一条其实对得上的条目被静默丢掉，详情页的「播放源」就少一格。
        // [searchOnIo] 对同一个值用的是「分桶」而不是「丢弃」，两处口径在这里统一。
        val otherFp = typeFingerprint(otherType) ?: return true
        return otherFp == type
    }

    /** [Media.key] 尾上那四位年份；这一条没带年份就是 null。 */
    private fun yearOf(m: Media): String? = m.key.substringAfter('|', "").takeIf { it.isNotBlank() }

    /** 判法一份（见 [qualityTagOf]）。这一处以前是自己一套：不认 2160、也不认「蓝光」。 */
    private fun qualityTag(m: Media): String? = qualityTagOf(m)

    private companion object {
        const val DOWN_RETRY_MS = 60_000L

        /** 一次都没探通之外，「探通过」这条结论也一样会过期。见 [healthOf]。 */
        const val HEALTH_TTL_MS = 10 * 60_000L

        /** 跨源搜索缓存多久算过期。一份搜索几十分钟不会变出第二种答案。 */
        const val SEARCH_TTL_MS = 120_000L

        /** 这张缓存最多留几部片。够了：用户一次搜一部，翻十部就该开始淘汰最旧的。 */
        const val SEARCH_CACHE_MAX = 100
    }
}
