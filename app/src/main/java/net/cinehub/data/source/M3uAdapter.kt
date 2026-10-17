package net.cinehub.data.source

import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
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

    @Volatile
    private var cachedAt = 0L

    private suspend fun load(): List<Media> {
        // 缓存要有期限。原来一旦拉成功就永久有效（这个适配器随进程活着）：
        // 列表里撤了一部片，界面上它照样挂着「可播」，点进去 404 —— 用户会以为是影枢坏了。
        // 一份公共测试列表隔半小时重新看一次，代价是一次 GET，比留着一屏过期条目便宜。
        cache?.let { if (System.currentTimeMillis() - cachedAt < TTL_MS) return it }
        val text = Http.get(config.baseUrl, referer = null, timeoutSec = 40)
        val list = parse(text).takeIf { it.any { m -> m.id.startsWith("http") } }
            ?: singleStream()
        cachedAt = System.currentTimeMillis()
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
        // 原来还加了一条 `config.name.contains(k)`：那是拿**源的名字**当片名匹配 ——
        // 搜「演示」会把这份列表里的每一条都算命中（因为源叫「演示源」），
        // 搜任何一个撞上源名的词都会冒出整列表。单直链那种源的条目标题本来就是源名，
        // 上面那半句已经覆盖它了，不需要这条。
        val hit = load().filter { it.title.lowercase().contains(k) }
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
    override suspend fun resolveStream(episodeUrl: String): StreamResolution =
        if (episodeUrl.startsWith("http")) StreamResolution.Ready(episodeUrl)
        else StreamResolution.Failed("播放列表里这一项不是可直接播放的地址")

    /**
     * 标题分隔符（第一个不在引号里的逗号）的下标；没有则 -1。
     *
     * 见 [parse]：只要一个 `indexOf(',')` 就够了，除非某个属性值里带逗号
     * （`tvg-logo="…/a,b.png"` 这类地址）—— 那个逗号不属于语法，跳过它继续找。
     */
    private fun titleSeparator(body: String): Int {
        var inQuote = false
        for (i in body.indices) {
            when (body[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return i
            }
        }
        return -1
    }

    private fun parse(text: String): List<Media> {
        val out = ArrayList<Media>()
        var pendingTitle: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    // m3u 的语法是「时长,标题」—— 标题从**第一个**逗号之后开始。
                    // 原来取的是最后一个逗号，片名自带逗号时（「老师，好」这类）会被截掉一截。
                    // 没有逗号时按整段当标题（属性那几种写法只有带逗号的才合语法，这里不猜）。
                    // 引号里的逗号不算分隔（`tvg-logo="http://…/a,b.png"` 这种地址里的逗号
                    // 一按下去就会把属性尾巴连同标题一起当成标题）。
                    val comma = titleSeparator(body)
                    pendingTitle = (if (comma >= 0) body.substring(comma + 1) else body)
                        .trim().ifBlank { null }
                    // 这两条正则原来在循环里现建：一份几万条的公共列表就是几万次 Pattern.compile
                    pendingLogo = LOGO.find(body)?.groupValues?.get(1)
                    pendingGroup = GROUP.find(body)?.groupValues?.get(1)
                }

                line.startsWith("#") -> Unit

                else -> {
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

    private companion object {
        /** 列表缓存的有效期。见 [load]。 */
        const val TTL_MS = 30 * 60_000L

        /** `#EXTINF` 行里的 `tvg-logo="…"` 与 `group-title="…"`（循环外建好，见 [parse]）。 */
        val LOGO = Regex("""tvg-logo="([^"]*)"""")
        val GROUP = Regex("""group-title="([^"]*)"""")
    }
}
