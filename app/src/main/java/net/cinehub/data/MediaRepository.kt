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

    suspend fun healthOf(cfg: SourceConfig, force: Boolean = false): HealthReport {
        if (!force) healthCache[cfg.id]?.let { return it }
        val report = check(cfg)
        healthCache[cfg.id] = report
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
            async {
                val health = healthOf(cfg)
                if (health.health == Health.Down) {
                    cfg to emptyList<Media>()
                } else {
                    cfg to runCatching {
                        SourceAdapters.create(cfg).search(keyword, 1)
                    }.getOrElse { emptyList() }
                }
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

    /** 首页：取优先级最高的可用源的内容。 */
    suspend fun home(): List<Media> = coroutineScope {
        val active = sources.active()
        for (cfg in active) {
            val health = healthOf(cfg)
            if (health.health == Health.Down) continue
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
                val health = healthOf(cfg)
                if (health.health == Health.Down) return@async emptyList()
                runCatching {
                    SourceAdapters.create(cfg).search(title, 1)
                }.getOrElse { emptyList() }
                    .filter { it.key == key }
                    .map {
                        SourceAvailability(
                            sourceId = cfg.id,
                            sourceName = cfg.name,
                            item = it,
                            health = health.health,
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
}
