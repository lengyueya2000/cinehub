package net.cinehub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.SearchOutcome
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.model.titleFingerprint
import net.cinehub.data.model.titleSearchVariants
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
     * Down 只算「这一刻没探通」，过一分钟就自动重试。
     *
     * 探的是站点根路径，跟真正取内容的接口不是一回事：根路径抖一下就把源判死，
     * 用户看到的就成了「搜索没结果」「某几个源凭空消失」。
     */
    suspend fun healthOf(cfg: SourceConfig, force: Boolean = false): HealthReport {
        val cached = healthCache[cfg.id]
        val expired = cached != null && cached.health == Health.Down &&
            System.currentTimeMillis() - (healthAt[cfg.id] ?: 0L) > DOWN_RETRY_MS
        if (!force && cached != null && !expired) return cached
        val report = check(cfg)
        healthCache[cfg.id] = report
        healthAt[cfg.id] = System.currentTimeMillis()
        return report
    }

    private suspend fun check(cfg: SourceConfig): HealthReport = withContext(Dispatchers.IO) {
        runCatching {
            val ms = Http.probe(cfg.baseUrl, referer = null)
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

    /** 跨源搜索并归并。 */
    suspend fun search(keyword: String): SearchOutcome = coroutineScope {
        val active = sources.active()
        // 用户拖出来的那个顺序，排序时要用（SourceAvailability 上不带优先级，所以单独传一份）
        val priority = active.withIndex().associate { (i, cfg) -> cfg.id to (i + 1) }
        val per = active.map { cfg ->
            // 这里不按健康度设闸门：探根路径失败不代表这个源取不到内容，
            // 拿 Down 当闸门会让一次抖动把源整个会话判死 —— 表现就是「搜索失效、少了几个源」。
            async {
                val t0 = System.currentTimeMillis()
                val r = quietly { SourceAdapters.create(cfg).search(keyword, 1) }
                val list = r.getOrNull().orEmpty()
                // 真取回了内容，就是比探根路径更硬的证据 —— 用它把「不可用」洗掉，
                // 否则界面上会出现「这个源给了结果、却标着不可用」的自相矛盾。
                if (list.isNotEmpty()) {
                    val cost = System.currentTimeMillis() - t0
                    healthCache[cfg.id] = HealthReport(if (cost < 800) Health.Ok else Health.Slow, cost)
                    healthAt[cfg.id] = System.currentTimeMillis()
                }
                Triple(cfg, list, r.exceptionOrNull())
            }
        }.map { it.await() }

        val merged = LinkedHashMap<String, MutableList<SourceAvailability>>()
        val seed = HashMap<String, Media>()

        per.forEach { (cfg, list, _) ->
            val health = healthCache[cfg.id]?.health ?: Health.Unknown
            // 同一个源可能对同一部片返回多条（不同线路各算一条），
            // 归并后只保留这个源最好的一条，否则徽标会重复出现。
            list.groupBy { it.key }.forEach { (key, dupes) ->
                val m = dupes.first()
                seed.putIfAbsent(key, m)
                val slot = merged.getOrPut(key) { mutableListOf() }
                if (slot.none { it.sourceId == cfg.id }) {
                    slot += SourceAvailability(
                        sourceId = cfg.id,
                        sourceName = cfg.name,
                        item = m,
                        health = health,
                        quality = qualityTag(m),
                        latencyMs = healthCache[cfg.id]?.latencyMs,
                    )
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

    /** [homeWithSource] 的两部分：内容，和它是哪个源给的 —— 首页标题要说这个名字。 */
    data class HomeFeed(val items: List<Media>, val sourceId: String)

    /**
     * 首页 / 片库那一页：取优先级最高的、真给了内容的那个源的那一页。
     *
     * 之前 logic 只从一个源取，导致「片库」整页都空着、且聚合不出别的源。
     * 现在按优先级依次试，至少把命中的那一个铺满，并把它是谁带回去
     * ——「均来自〈源名〉」这句话得有依据。
     */
    suspend fun homeWithSource(): HomeFeed = coroutineScope {
        val active = sources.active()
        for (cfg in active) {
            // 同样不按健康度跳过：探根路径失败的源照样可能给出内容，取不到自然会落到下一个
            val list = quietly { SourceAdapters.create(cfg).browse(1) }.getOrNull().orEmpty()
            if (list.isNotEmpty()) return@coroutineScope HomeFeed(list, cfg.id)
        }
        HomeFeed(emptyList(), "")
    }

    suspend fun detail(cfg: SourceConfig, id: String): MediaDetail =
        withContext(Dispatchers.IO) { SourceAdapters.create(cfg).detail(id) }

    /**
     * 解析直链。
     *
     * 成因由适配器自己判断（只有它知道自己那个站的规则），这里只做一件事：
     * 适配器抛异常时兜底，别让用户看到一个没头没尾的失败。
     */
    suspend fun resolveStream(cfg: SourceConfig, episodeUrl: String): StreamResolution =
        withContext(Dispatchers.IO) {
            quietly { SourceAdapters.create(cfg).resolveStream(episodeUrl) }
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
    suspend fun siblingsOf(media: Media): Siblings = coroutineScope {
        val want = titleFingerprint(media.title)
        val year = media.key.substringAfter('|', "").takeIf { it.isNotBlank() }
        val active = sources.active()
        val per = active.map { cfg ->
            async {
                // 切源列表更不能因为探测失败就少一项 —— 少了那一项用户就换不过去
                val health = healthCache[cfg.id]?.health ?: Health.Unknown
                val (hits, err) = findInSource(cfg, media.title, want, year)
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
    ): Pair<List<Media>, Throwable?> {
        var lastErr: Throwable? = null
        for (q in titleSearchVariants(title)) {
            // create() 也放在 quietly 里：这趟跑在 viewModelScope 上，外面没有 catch，
            // 一个源构造不出来不该把整张详情页带走
            val r = quietly { SourceAdapters.create(cfg).search(q, 1) }
            val hits = r.getOrNull().orEmpty().filter { sameWork(it, want, year) }
            if (hits.isNotEmpty()) return hits to null
            lastErr = r.exceptionOrNull()
        }
        return emptyList<Media>() to lastErr
    }

    private fun sameWork(m: Media, want: String, year: String?): Boolean {
        if (titleFingerprint(m.title) != want) return false
        val otherYear = m.key.substringAfter('|', "").takeIf { it.isNotBlank() }
        return year == null || otherYear == null || otherYear == year
    }

    private fun qualityTag(m: Media): String? {
        val text = listOfNotNull(m.remark, m.category, m.title).joinToString(" ")
        return when {
            Regex("4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "4K"
            Regex("hdr", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "HDR"
            Regex("1080").containsMatchIn(text) -> "1080P"
            Regex("720").containsMatchIn(text) -> "720P"
            else -> null
        }
    }

    private companion object {
        const val DOWN_RETRY_MS = 60_000L
    }
}
