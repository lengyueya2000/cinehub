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
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
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
    suspend fun search(keyword: String): List<AggregatedMedia> = coroutineScope {
        val active = sources.active()
        val per = active.map { cfg ->
            // 这里不按健康度设闸门：探根路径失败不代表这个源取不到内容，
            // 拿 Down 当闸门会让一次抖动把源整个会话判死 —— 表现就是「搜索失效、少了几个源」。
            async {
                val t0 = System.currentTimeMillis()
                val list = runCatching {
                    SourceAdapters.create(cfg).search(keyword, 1)
                }.getOrElse { emptyList() }
                // 真取回了内容，就是比探根路径更硬的证据 —— 用它把「不可用」洗掉，
                // 否则界面上会出现「这个源给了结果、却标着不可用」的自相矛盾。
                if (list.isNotEmpty()) {
                    val cost = System.currentTimeMillis() - t0
                    healthCache[cfg.id] = HealthReport(if (cost < 800) Health.Ok else Health.Slow, cost)
                    healthAt[cfg.id] = System.currentTimeMillis()
                }
                cfg to list
            }
        }.map { it.await() }

        val merged = LinkedHashMap<String, MutableList<SourceAvailability>>()
        val seed = HashMap<String, Media>()

        per.forEach { (cfg, list) ->
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

        merged.entries.map { (key, av) ->
            AggregatedMedia(
                media = seed.getValue(key),
                availability = av.sortedWith(
                    compareBy(
                        { it.health == Health.Down },
                        { it.health == Health.Slow },
                        { it.latencyMs ?: Long.MAX_VALUE },
                    )
                ),
            )
        }.sortedByDescending { it.availability.size }
    }

    /**
     * 首页 / 片库：取优先级最高的可用源的内容。
     *
     * 之前 logic 只从一个源取，导致「片库」整页都空着、且聚合不出别的源。
     * 现在优先取北觅这类真能给内容的源（按优先级），但至少把命中的那一个铺满。
     * v1.0.3 起片库页直接复用这个列表。
     */
    suspend fun home(): List<Media> = coroutineScope {
        val active = sources.active()
        for (cfg in active) {
            // 同样不按健康度跳过：探根路径失败的源照样可能给出内容，取不到自然会落到下一个
            val list = runCatching { SourceAdapters.create(cfg).browse(1) }.getOrElse { emptyList() }
            if (list.isNotEmpty()) return@coroutineScope list
        }
        emptyList()
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
            runCatching { SourceAdapters.create(cfg).resolveStream(episodeUrl) }
                .getOrElse { e ->
                    VideoPlatforms.platformOf(episodeUrl)
                        ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
                        ?: StreamResolution.Failed(e.message ?: "解析直链时出错")
                }
        }

    /**
     * 同一部片在其它源里的对应条目 —— 切源不丢进度的基础。
     * 用标题 + 年份归并，所以换源后进度能续上。
     */
    suspend fun siblingsOf(key: String): List<SourceAvailability> = coroutineScope {
        val title = key.substringBefore('|')
        val active = sources.active()
        active.map { cfg ->
            async {
                // 切源列表更不能因为探测失败就少一项 —— 少了那一项用户就换不过去
                val health = healthCache[cfg.id]?.health ?: Health.Unknown
                runCatching {
                    SourceAdapters.create(cfg).search(title, 1)
                }.getOrElse { emptyList() }
                    .filter { it.key == key }
                    .map {
                        SourceAvailability(
                            sourceId = cfg.id,
                            sourceName = cfg.name,
                            item = it,
                            health = health,
                            quality = qualityTag(it),
                            latencyMs = healthCache[cfg.id]?.latencyMs,
                        )
                    }
            }
        }.flatMap { it.await() }
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
