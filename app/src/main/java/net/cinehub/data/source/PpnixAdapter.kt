package net.cinehub.data.source

import kotlinx.coroutines.delay
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.net.Http
import net.cinehub.data.net.KeyTrimmer
import net.cinehub.data.net.LocalKeyProxy
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * PPnix（www.ppnix.com）这一型站点。
 *
 * ## 它为什么不能并进 [HtmlAdapter]
 *
 * 前三步（浏览、搜索、详情）就是普通网页解析，和 [HtmlAdapter] 一模一样；
 * 卡住的是另外两件：
 *
 * 1. **分集不在 HTML 里。** 详情页的 `.playlists` 是个空 `div`，由页面末尾那段
 *    `<script>classid=2;infoid=8509;m3u8=['1','2',…]</script>` 在浏览器里渲染出来。
 *    照 [HtmlRules.episodeSelector] 去 DOM 里找锚点，一条都找不到 ——
 *    界面表现是「这一部没有分集」，而它其实有 47 集。
 * 2. **密钥不是规范形态。** 取链地址 `/info/m3u8/{infoid}/{参数}.m3u8` 给的是
 *    一份标准 HLS，但 `#EXT-X-KEY` 指向的那个地址回的不是 16 字节密钥，
 *    而是 32 个 ASCII 字符（真密钥是前 16 个字符）。播放器照规范读它只会解出乱码。
 *    这一件由 [LocalKeyProxy] 处理，见那边的说明。
 *
 * 所以单独写一个适配器：前三步复用 jsoup 的写法，后两步各走自己的路。
 *
 * ## 站点形状（实测）
 *
 * - 分类 `/cn/movie/`、`/cn/tv/`，分页 `?page=N`
 * - 搜索 `/cn/search/{词}--.html`
 * - 详情 `/cn/movie/{id}.html`、`/cn/tv/{id}.html`
 * - 取链 `/info/m3u8/{infoid}/{参数}.m3u8`：电影参数是 `1080P`，电视剧是集号字符串
 * - 分片在 `ipfs.ppnix.com`，会 301 到随机的公共 IPFS 网关
 */
class PpnixAdapter(override val config: SourceConfig) : SourceAdapter {

    private val root: String get() = config.baseUrl.trimEnd('/')

    /** 域名根（不含 /cn 语言前缀）：取链 /info/m3u8 挂在这一层。 */
    private val origin: String get() = java.net.URI(root).let { "${it.scheme}://${it.host}" }

    private fun abs(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> "$root/${path.trimStart('/')}"
    }

    // ---------------------------------------------------------------- 浏览 / 搜索

    override suspend fun browse(page: Int): List<Media> {
        // 站点只有电影 / 电视剧两个分类。浏览取电影分类的第 page 页：
        // 电视剧那一栏内容重叠度高，一次浏览只出一栏，用户要另一栏去搜索或从首页进。
        val doc = fetchDoc("$root/movie/?page=$page")
        val list = parseList(doc)
        if (list.isEmpty()) error(
            "这个源的列表页一个条目都没读出 —— 多半是它改版了，不是它没有内容",
        )
        return list
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val word = pathEncode(keyword)
        val url = "$root/search/$word--.html" + if (page > 1) "?page=$page" else ""
        return parseList(fetchDoc(url))
    }

    private suspend fun fetchDoc(url: String): Document {
        val html = Http.get(url, referer = root)
        return Jsoup.parse(html, url)
    }

    /**
     * 给单测开的口子：列表解析不碰网络，拿一份 HTML 就能验。
     *
     * 之所以值得单测 —— 模拟器上实测「6 个源检索、4 个有结果」，PPnix 是没答上话的
     * 那两个之一，而宿主机用同一份 HTML、同一个选择器取得出 15 条。差别只在解析，
     * 所以拿真实页面把它钉住（见 `PpnixParseListTest`）。
     */
    internal fun parseListPublic(doc: Document): List<Media> = parseList(doc)

    private fun parseList(doc: Document): List<Media> {
        val seen = HashSet<String>()
        return doc.select("a[href*=/cn/movie/], a[href*=/cn/tv/]").mapNotNull { a ->
            val href = a.attr("href")
            // 分类 / 演员 / 导演页也长这样（`/cn/casts/…`、`/cn/movie/剧情----.html`），
            // 只认数字 id 那一档。
            val id = href.substringAfterLast('/').substringBefore('.').substringBefore('?')
            if (id.isBlank() || !id.all { it.isDigit() } || !seen.add(id)) return@mapNotNull null

            val scope = a.itemScope()
            val img = scope.selectFirst("img")
            val title = a.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() && it.length <= 40 }
                ?: return@mapNotNull null

            Media(
                id = id,
                title = title.trim(),
                cover = img?.attr("src")?.takeIf { it.isNotBlank() }?.let { abs(it) },
                // 卡片上那个 `<span class="orange">2025</span>` 就是年份
                year = scope.selectFirst(".countrie span, .orange")?.text()?.trim()
                    ?.takeIf { YEAR.matches(it) } ?: YEAR.find(title)?.value,
                remark = scope.selectFirst(".note span")?.text()?.trim()?.takeIf { it.isNotBlank() },
                score = scope.selectFirst(".rate")?.text()?.trim()?.takeIf { it.isNotBlank() },
                sourceId = config.id,
            )
        }.distinctBy { it.id }
    }

    /** 见 [HtmlAdapter.itemScope]：片名常和封面分属两个 `a`，往上找恰好含一张图的那层。 */
    private fun Element.itemScope(): Element {
        if (selectFirst("img") != null) return this
        var p = parent()
        repeat(3) {
            val cur = p ?: return this
            if (cur.select("img").size == 1) return cur
            p = cur.parent()
        }
        return this
    }

    // ---------------------------------------------------------------- 详情

    override suspend fun detail(id: String): MediaDetail {
        // 电影与电视剧的详情路径不同，而 id 里不带这个信息（`/cn/movie/8582.html` → `8582`）。
        // 先按电影试：失败再按电视剧试。两条都不成再说「没有这一部」——
        // 不分清是哪一类就报「没有这一部」，用户看到的是明明首页挂着的一部片搜不到。
        val doc = fetchDetail(id) ?: error("没有找到 id 为 $id 的条目")

        val titleRaw = doc.selectFirst("h1.product-title")?.text()?.trim().orEmpty()
        val title = titleRaw.substringBefore('(').trim().ifBlank { id }

        val media = Media(
            id = id,
            title = title,
            cover = doc.selectFirst("header img.thumb")?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { abs(it) },
            year = doc.selectFirst("h1.product-title span")?.text()?.trim()
                ?.takeIf { YEAR.matches(it) } ?: YEAR.find(titleRaw)?.value,
            // 详情页那行「类型：剧情 / 喜剧 / 运动」。搜索卡片不带类型，所以这里补上 ——
            // 归并时同一个源对同一部片可能给多条（线路不同），类型参与指纹
            // （见 MediaRepository 的 typeFingerprint），缺了它「同名同年不同类」分不了家。
            category = doc.excerptOf("类型")?.replace(" / ", " "),
            intro = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?: doc.excerptOf("简介"),
            sourceId = config.id,
        )

        // 分集只在那段 script 里。取不到就说明这一部还没有片源
        // （站点有详情页但取链接口还没上片），照实说，别给一份空的选集。
        val vars = PpnixScript.read(doc) ?: return MediaDetail(media, emptyList(), intro = media.intro)
        // 取链要用的 infoid 只有这里有：按这一部的 mediaId 记下来，resolveStream 读它。
        // （换源、换集不一定重走 detail()，所以不能只记「最后一次」—— 见 rememberInfoId。）
        vars.infoId?.let { infoId -> rememberInfoId(media.id, infoId) }

        val params = vars.params
        val isMovie = vars.paramKind == PpnixScript.Kind.Movie
        val lineName = if (isMovie) "正片" else "线路 1"
        val episodes = params.mapIndexed { i, p ->
            Episode(
                name = if (isMovie) "正片" else "第 ${i + 1} 集",
                index = i + 1,
                // 取链地址由 resolveStream 按这一串现拼，所以这里存的就是「参数」本身。
                // 存成 URL 会让上面每一层都以为它是个能打开的地址（它其实是个 1080P 字面量）。
                playUrl = p,
            )
        }

        return MediaDetail(
            media = media,
            lines = listOf(PlayLine(lineName, episodes)),
            intro = media.intro,
        )
    }

    /** 电影与电视剧各试一次。 */
    private suspend fun fetchDetail(id: String): Document? {
        for (seg in listOf("movie", "tv")) {
            val doc = runCatching { fetchDoc("$root/$seg/$id.html") }.getOrNull() ?: continue
            // 详情页的判据是「有标题」：站点对不存在的 id 也回 200，正文是首页形状。
            if (doc.selectFirst("h1.product-title") != null) return doc
        }
        return null
    }

    /** 详情页那几行 `导演：…` `简介：…` 按标签取。 */
    private fun Document.excerptOf(label: String): String? =
        select("div.product-excerpt").firstOrNull { it.text().trimStart().startsWith(label) }
            ?.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------- 取链

    /**
     * 取这一集的播放地址。
     *
     * 三步：向站点要那份 m3u8 → 交给本机代理改写 → 把**代理地址**给播放器。
     *
     * 为什么给的是代理地址而不是站点地址：见 [LocalKeyProxy] 的说明 ——
     * 站点给的密钥不是规范形态，而播放器只认规范的。代理在那里把 `#EXT-X-KEY`
     * 的 URI 换成本机、把密钥截成 16 字节，分片仍由播放器直连 IPFS。
     */
    override suspend fun resolveStream(episodeUrl: String, mediaId: String?): StreamResolution {
        // episodeUrl 在这一型里是「参数」（`1080P` 或集号字符串），不是地址。
        val param = episodeUrl.trim().takeIf { it.isNotBlank() }
            ?: return StreamResolution.Failed("这一集没有可取链的参数")

        // infoid 得从详情页那段 script 里拿，而 resolveStream 手上只有参数。
        // 由 [detail] 按 mediaId 记下（见 [rememberInfoId]），这里照 mediaId 查 ——
        // 查不到就不猜：猜错一次就是拿另一部片的地址去要清单，站点回 200 + 首页 HTML，
        // 界面上还说着「这一集还没有片源」，比说「重新打开一次详情页」难查得多。
        val infoId = mediaId?.let { infoIdFor(it) }
            ?: return StreamResolution.Failed("取不到这部片的取链参数，重新打开一次详情页再试")

        // 取链接口挂在域名根上（`/info/m3u8/...`），不在 /cn 语言前缀下 ——
        // 拼上 /cn 的地址会被站点前端路由接住，回 200 + 一份首页 HTML：
        // 状态码看着一切正常，内容根本不是清单。曾在这里用 root 拼，
        // 模拟器实测 PPnix 一次都取不到链（宿主机 curl 用对路径一直好着，才把病灶藏住）。
        val site = "$origin/info/m3u8/$infoId/$param.m3u8"
        // 先确认那一集真的有清单：站点对没上的集会回 301 到首页，
        // 那时候照给一个代理地址，播放器会拿到一份 HTML 然后报「解析失败」——
        // 而真相是「这一集还没上」，那句话比解析失败有用得多。
        val head = playlistOrRetry(site)
        if (head == null || !head.body.contains("#EXTM3U")) {
            return StreamResolution.Failed(
                if (head == null) "取不到这一集的播放清单（站点没答上话）" else "这一集还没有片源",
            )
        }
        val proxyBase = LocalKeyProxy.acquire(config)
        return StreamResolution.Ready(
            KeyTrimmer.proxiedPlaylistUrl(proxyBase, site),
        )
    }

    /**
     * 取一份**真的是 m3u8** 的清单，最多试 [tries] 次。
     *
     * 站点对这一接口有三种回答：标准 m3u8；301 到首页（这一集没上）；以及 200 +
     * 一份 37KB 的首页 HTML —— 地址拼进 /cn 语言前缀时必是它（前端路由接住了），
     * 地址对了就是清单（见 [resolveStream] 的 origin 说明）。内容说了算：
     * 不是清单就再要一次，仍不是就把最后一轮带回，让调用方如实说话。
     *
     * @return 最后一轮的响应：是清单自然用它；不是清单时也带回（区分「站点没答上话」
     * 和「这一集还没上」两种说法）；网络一路失败则是 null。
     */
    private suspend fun playlistOrRetry(site: String, tries: Int = 3): Http.ResponseBody? {
        var last: Http.ResponseBody? = null
        repeat(tries) { i ->
            val r = runCatching { Http.getKeepStatus(site, referer = root) }.getOrNull()
            if (r != null && r.status in 200..299 && r.body.contains("#EXTM3U")) return r
            if (r != null) last = r
            if (i < tries - 1) delay(300L * (i + 1))
        }
        return last
    }

    private fun pathEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * 最近一次解析出来的 infoid，按 mediaId 各存一份。
     *
     * 这是这一型绕不开的一处妥协：`SourceAdapter.resolveStream(episodeUrl)` 的签名里
     * 只有「这一集的地址」，而取链要的是 `infoid` —— 它在详情页，不在分集里。
     *
     * 三条路都走不通，所以留这一个：改接口签名会动到全部适配器和上层；
     * 把 infoid 塞进 episodeUrl 会让上面每一层都把它当地址解析（它是个数字）；
     * 每次 resolveStream 重新拉一遍详情页 = 每次起播多一次请求，而详情就在手上。
     *
     * **按 mediaId 记，不记一个全局单值**：infoid 是**一部片**的属性，不是这个源的属性。
     * 曾经这里只有一个 `@Volatile` 槽，谁最后打开详情页谁说了算 —— 于是打开过 A 剧
     * （47 集）再打开 B 电影，回去播 A 的第 30 集时，取链地址拼的是 B 的 infoid：
     * 站点回 200 + 一份首页 HTML，界面上说的是「这一集还没有片源」，
     * 而真相是拿错了片子的地址。AppViewModel 换线路、换集并不重走 detail()，
     * 那条「换源时会刷新」的防线在换集这条路上根本不设防。
     *
     * 存在适配器实例上（不是 companion）：换源的地址、凭证一变，`SourceAdapters` 会换一个新实例，
     * 这张表跟着一起换 —— 拿 companion 存就会在「用户改了源地址」之后还拿着旧站的 infoid。
     */
    private val lastInfoIds = LinkedHashMap<String, String>()

    /** 记下这一部的 infoid（覆盖旧的）。 */
    @Synchronized
    fun rememberInfoId(mediaId: String, infoId: String) {
        lastInfoIds.remove(mediaId)
        lastInfoIds[mediaId] = infoId
        // 超过就淘汰最早那个：LinkedHashMap 的迭代顺序就是写入顺序，头一个最旧
        while (lastInfoIds.size > INFO_ID_CACHE_MAX) {
            val oldest = lastInfoIds.keys.firstOrNull() ?: break
            lastInfoIds.remove(oldest)
        }
    }

    /** 这一部的 infoid；没打开过它的详情页就没有。 */
    @Synchronized
    fun infoIdFor(mediaId: String): String? = lastInfoIds[mediaId]

    private companion object {
        val YEAR = Regex("(19|20)\\d{2}")

        /**
         * infoid 缓存留几部片。够「在几部片之间来回点着播」就行，不是为了当数据库 ——
         * 淘汰最早那个，用户回去看很久以前那部时重新打开一次详情页就有。
         */
        const val INFO_ID_CACHE_MAX = 8
    }
}

/**
 * 详情页末尾那段 `<script>classid=2;classurl='/tv/';infoid=8509;sub='…';m3u8=['1',…]</script>`
 * 的解析。
 *
 * 为什么必须自己解：分集**只存在于这段脚本里**（详情页的 `.playlists` 是空 div，
 * 由它在浏览器里渲染）。照 DOM 选择器找分集锚点，一条都不会命中。
 *
 * 全部是纯函数，单测见 `PpnixScriptTest`。
 */
internal object PpnixScript {

    enum class Kind { Movie, Tv }

    data class Vars(
        val classId: Int?,
        val infoId: String?,
        val params: List<String>,
        /** 电影是 `['1080P']`，电视剧是 `['1'..'47']`。 */
        val paramKind: Kind,
    )

    /** 从详情页正文里读出那几个变量。没有那段 script 时返回 null。 */
    fun read(doc: Document): Vars? {
        val script = doc.select("script").firstOrNull { it.data().contains("infoid=") }?.data()
            ?: return null
        return read(script)
    }

    /**
     * 从脚本正文里读。电影与电视剧靠参数形状区分（见 [paramKind]）。
     *
     * 只有 `m3u8` 数组是必需的：分集是「这一部能不能播」的依据，缺了它说什么都没用。
     * `infoid` 缺了不算失败 —— 它只在取链时用，那时候由 [PpnixAdapter.resolveStream]
     * 照实说「重新打开一次详情页再试」，比在这里把整部片判成没有分集要好。
     */
    fun read(script: String): Vars? {
        val params = PARAMS.find(script)?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('\'', '"') }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (params.isEmpty()) return null
        return Vars(
            classId = CLASSID.find(script)?.groupValues?.get(1)?.toIntOrNull(),
            infoId = INFORID.find(script)?.groupValues?.get(1),
            params = params,
            paramKind = if (params.singleOrNull()?.equals("1080P", ignoreCase = true) == true) {
                Kind.Movie
            } else {
                Kind.Tv
            },
        )
    }

    private val INFORID = Regex("""infoid\s*=\s*(\d+)""")
    private val CLASSID = Regex("""classid\s*=\s*(\d+)""")
    private val PARAMS = Regex("""m3u8\s*=\s*\[([^\]]*)]""")
}
