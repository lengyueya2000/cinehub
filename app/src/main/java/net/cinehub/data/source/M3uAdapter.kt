package net.cinehub.data.source

import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.net.Http

/**
 * m3u / m3u8 播放列表适配器。
 *
 * 这是最通用的一种源：任何一份直链列表都能接进来，
 * 不依赖站点结构，也不会因为对方改版而失效。
 */
class M3uAdapter(override val config: SourceConfig) : SourceAdapter {

    @Volatile
    private var cache: List<Media>? = null

    private suspend fun load(): List<Media> {
        cache?.let { return it }
        val text = Http.get(config.baseUrl, referer = null, timeoutSec = 40)
        val list = parse(text).takeIf { it.any { m -> m.id.startsWith("http") } }
            ?: singleStream()
        return list.also { cache = it }
    }

    /**
     * 单个 m3u8/mp4 直链：列表本身就是唯一的内容。
     *
     * 出现两种情况会走到这里：正文没有任何条目（裸直链），
     * 或者条目全是相对路径（对方给的是多码率 master 列表）。
     * 两种情况下这个地址自己就是流，交给播放器去选码率即可。
     */
    private fun singleStream(): List<Media> = listOf(
        Media(
            id = config.baseUrl,
            title = config.name,
            remark = streamFile(),
            category = "直链",
            sourceId = config.id,
        )
    )

    private fun streamFile(): String =
        config.baseUrl.substringAfterLast('/').substringBefore('?')

    override suspend fun browse(page: Int): List<Media> {
        val all = load()
        val per = 60
        val from = (page - 1) * per
        return all.drop(from).take(per)
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val k = keyword.lowercase()
        val hit = load().filter {
            it.title.lowercase().contains(k) || config.name.lowercase().contains(k)
        }
        val per = 60
        return hit.drop((page - 1) * per).take(per)
    }

    override suspend fun detail(id: String): MediaDetail {
        val media = load().firstOrNull { it.id == id } ?: error("未找到该条目")
        return MediaDetail(
            media = media,
            lines = listOf(PlayLine("默认线路", listOf(Episode("正片", 1, media.id)))),
        )
    }

    /** m3u 里存的本身就是直链。 */
    override suspend fun resolveStream(episodeUrl: String): String? =
        episodeUrl.takeIf { it.startsWith("http") }

    private fun parse(text: String): List<Media> {
        val out = ArrayList<Media>()
        var pendingTitle: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var idx = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    pendingTitle = body.substringAfterLast(',').trim().ifBlank { null }
                    pendingLogo = Regex("""tvg-logo="([^"]*)"""").find(body)?.groupValues?.get(1)
                    pendingGroup = Regex("""group-title="([^"]*)"""").find(body)?.groupValues?.get(1)
                }

                line.startsWith("#") -> Unit

                else -> {
                    idx++
                    val url = line
                    out += Media(
                        id = url,
                        title = pendingTitle ?: url.substringAfterLast('/').substringBefore('?'),
                        cover = pendingLogo?.takeIf { it.isNotBlank() },
                        category = pendingGroup?.takeIf { it.isNotBlank() },
                        sourceId = config.id,
                    )
                    pendingTitle = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
        return out
    }
}
