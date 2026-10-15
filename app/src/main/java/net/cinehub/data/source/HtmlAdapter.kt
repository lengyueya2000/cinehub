package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.cinehub.data.model.Episode
import net.cinehub.data.model.HtmlRules
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 通用「网页解析」适配器。
 *
 * 选择器来自 [SourceConfig.html]，所以用户加站点不用改代码。
 * 内置了几个常见站点的默认规则（见 [DefaultRules]），识别不出时用通用兜底。
 */
class HtmlAdapter(override val config: SourceConfig) : SourceAdapter {

    private val rules = config.html ?: DefaultRules.generic
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun abs(path: String): String = when {
        path.startsWith("http") -> path
        // 协议相对地址（`//host/x.jpg`）不能拼到 baseUrl 后面 —— 拼出来是
        // `https://site//host/x.jpg`，图必然取不到
        path.startsWith("//") -> "https:" + path
        else -> config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private suspend fun fetch(path: String, page: Int): Document {
        val url = abs(path.replace("{q}", "").replace("{page}", "$page"))
        return Jsoup.parse(
            Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)),
            url,
        )
    }

    override suspend fun browse(page: Int): List<Media> {
        val path = rules.browsePath.ifBlank { rules.listPath }
        val list = parseList(fetch(path, page))
        // 列表页一条都读不出来，几乎不可能是「这个源今天没有片子」，而是那条选择器
        // 已经追不上它的模板了。这一句得报出来：静默返回空列表的话，首页会把「改版」
        // 显示成「这个源没有内容」，用户就会去删源、换源，而该改的其实是规则。
        if (list.isEmpty()) error(
            "这个源的列表页一个条目都没读出（选择器「${rules.itemSelector}」在这页上没命中）—— " +
                "多半是它改版了，不是它没有内容",
        )
        return list
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val path = rules.listPath
            .replace("{q}", pathEncode(keyword))
            .replace("{page}", "$page")
        return parseList(fetch(path, page))
    }

    /**
     * 填进地址里的片段要按**路径**编码。
     *
     * `URLEncoder` 是表单编码：空格编成 `+`。放在查询串里没错，放在路径段里
     * 站点会把 `+` 当成一个加号字符 —— 带空格的片名（豆瓣那种「绝望写手 第五季」）
     * 于是搜的是一条根本不存在的字符串，结果就是那句假的「源里搜不到」。
     */
    private fun pathEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override suspend fun detail(id: String): MediaDetail {
        val url = abs(rules.detailPath.replace("{id}", pathEncode(id)))
        val doc = Jsoup.parse(
            Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)),
            url,
        )

        val title = doc.detailTitle() ?: id
        // 详情页的封面同样有给相对地址的（4kku 的网格页就是 `/upload/vod/…`），
        // 不 abs 就是拿一个 `/upload/...` 去让图片库加载 —— 结果是海报位空白，
        // 而片名、年份都在，看着像「这个源没图」。占位图（load.svg）也不算封面。
        val cover = (
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.coverFromLazy()
            )?.takeIf { it.isNotBlank() && !it.contains("/load.") && !it.endsWith(".svg") }
            ?.let { abs(it) }
        val intro = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".desc,.introduction,.detail-content,.hl-content-text")?.text()

        val media = Media(
            id = id,
            title = title.trim(),
            cover = cover,
            // 详情页能不能给出年份看站点。缺年份会让同一部片在收藏/观看记录里分裂成两条
            // （卡片那条键带 |2026、详情这条不带），所以取到就带上，取不到由上层从卡片补。
            year = doc.selectFirst("[class*=year],.video-year,.tag-text")?.text()
                ?.let { YEAR.find(it)?.value } ?: YEAR.find(title)?.value,
            intro = intro?.trim(),
            sourceId = config.id,
        )

        // 分集：同一部剧的多个线路会各有自己的分集锚点
        val epAnchors = doc.select(rules.episodeSelector)
        val byLine = epAnchors.groupBy { it.attr("data-line").ifBlank { it.playLine() ?: "1" } }
        val lines = if (byLine.isEmpty()) {
            listOf(PlayLine("默认线路", listOf(Episode("正片", 1, url))))
        } else {
            byLine.map { (line, anchors) ->
                PlayLine(
                    name = doc.lineName(line),
                    episodes = anchors.mapIndexed { i, a ->
                        Episode(
                            name = a.text().trim().ifBlank { "第 ${i + 1} 集" },
                            // 集数写在地址尾段里（44869-3-2 → 第 2 集），
                            // 取不到才按出现顺序数 —— 顺序数在倒序排的模板上会把集号排反
                            index = a.attr("data-episode").ifBlank { a.attr("data-idx") }
                                .toIntOrNull() ?: a.playIndex() ?: (i + 1),
                            playUrl = abs(a.attr(rules.episodeUrlAttr).ifBlank { a.attr("href") }),
                        )
                    },
                )
            }
        }
        return MediaDetail(media = media, lines = lines, intro = media.intro)
    }

    /**
     * 这条分集属于哪条线路。
     *
     * 苹果 CMS 的播放地址写作 `/vodplay/{片id}-{线路号}-{集号}.html`，线路号本来就在名字里，
     * 但模板站常常不给锚点标 `data-line` —— 少这一步就会把几条线路并进同一个「线路 1」、
     * 集号一路顺排（实测北觅两条线路、4kku 三条线路都是这样，界面上看着像 24 集的剧
     * 其实每条各 12 集）。所以没有 `data-line` 时按地址尾段拆。
     *
     * 地址不是这个形状的（第三方平台页、自定义路径）返回 null，交回原来的「全归线路 1」，
     * 不做任何猜测。
     */
    private fun Element.playLine(): String? = playTail()?.groupValues?.get(1)

    /** 见 [playLine]：尾段的第二个数字就是集号。 */
    private fun Element.playIndex(): Int? =
        playTail()?.groupValues?.get(2)?.toIntOrNull()

    private fun Element.playTail(): MatchResult? {
        val last = attr(rules.episodeUrlAttr)
            .ifBlank { attr("href") }
            .substringBefore('?').substringBefore('#').trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('.')
        return PLAY_TAIL.find(last)
    }

    /**
     * 解析真实播放地址。
     *
     * 四条分支各对应一种真实成因，按「先看是不是别人的页面，再看本站怎么取」的顺序判断：
     * 第三方平台的播放页是确定性事实，任何模式下都该如实说明，而不是硬塞给播放器。
     */
    override suspend fun resolveStream(episodeUrl: String): StreamResolution {
        // 1. 爱奇艺 / 腾讯 / B 站那类播放页 —— 本机解不了别人的页面
        VideoPlatforms.platformOf(episodeUrl)?.let {
            return StreamResolution.ThirdPartyPage(episodeUrl, it)
        }

        // 2. 苹果 CMS 模板：地址写在播放页的 player_aaaa 里
        if (rules.resolveMode == HtmlRules.MODE_MACCMS_PLAYER) {
            val html = Http.get(episodeUrl, referer = config.baseUrl, headers = Http.authHeaders(config))
            return macPlayerUrl(html)
                ?.let { StreamResolution.Ready(it) }
                ?: StreamResolution.Failed(
                    "播放页里没有 player_aaaa 数据，这个模板可能改版了",
                )
        }

        // 3. 地址由站点脚本现算（如 4kvm.net 的 WASM 签名）
        if (rules.resolveMode == HtmlRules.MODE_CLIENT_SCRIPT) {
            return StreamResolution.NeedsClientScript
        }

        // 4. 直链只对登录用户开放 —— 填用户自己的凭证可能走通
        if (rules.resolveMode == HtmlRules.MODE_SESSION) {
            return StreamResolution.NeedsSession
        }

        // 5. 没配规则 —— 如实说规则不足，而不是猜一个原因
        return StreamResolution.Failed(
            "这个源还没有配置直链解析规则，影枢只能拿到它的播放页地址",
        )
    }

    /**
     * 苹果 CMS 模板把播放地址写在页面的 `player_aaaa` 里。
     *
     * 这是页面自身的公开数据，不是绕过登录；站点若把它藏起来，这里就拿不到。
     *
     * 原来用一条 `(\{.*?\})\s*[;<]` 加 DOT_MATCHES_ALL 去抓那段 JSON —— 这是会把
     * 一个页面变成一场等待的写法：`\s*[;<]` 只要往后出现一次「不像结尾」的片段，
     * 引擎就要把花括号里的每一个位置重数一遍，页面一大（模板里几十个 script 很常见）
     * 就是指数级回溯，界面表现为「详情页转圈转到天荒地老」。
     * 改成从 `player_xxx =` 这个锚点起做一次配对括号扫描：只看它自己那一段，一遍走完。
     */
    private fun macPlayerUrl(html: String): String? {
        val raw = extractPlayerJson(html) ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val url = (obj["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        return url.takeIf { it.startsWith("http") }
    }

    /** 从 `player_<名字> = { … }` 里按配对括号切出那段对象。 */
    private fun extractPlayerJson(html: String): String? {
        val anchor = Regex("""player_[A-Za-z0-9_]+\s*=\s*\{""").find(html) ?: return null
        val open = html.indexOf('{', anchor.range.first)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until html.length) {
            val c = html[i]
            when {
                inString && escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(open, i + 1)
                }
            }
            // 一坨超过 256 KB 的「脚本」不是站点给播放地址的那份数据，别陪着它耗内存
            if (i - open > 256_000) return null
        }
        return null
    }

    /**
     * 列表项的封面与年份常在标题的上一级 —— 横排卡片就是图在左、字在右，
     * 两边各一个 `a`，选择器命中的是文字那个。
     *
     * 原来只往命中元素自己身上找 img，找不到就把整条丢掉（界面表现为「这个源一条没有」）。
     * 这里往上走最多三层，认**恰好含一张 img** 的那一层：多张说明已经数到列表容器了，
     * 从那一层取年份会把邻居卡片的年份认到自己头上，宁可不取。
     */
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

    private fun parseList(doc: Document): List<Media> {
        val seen = HashSet<String>()
        return doc.select(rules.itemSelector).mapNotNull { a ->
            val href = a.attr("href")
            val id = href.substringAfterLast('/').substringBefore('?').substringBefore('.')
            if (id.isBlank() || !seen.add(id)) return@mapNotNull null

            val scope = a.itemScope()
            val img = scope.selectFirst("img")
            val title = a.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("[class*=title],h3,h4")?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("data-title").takeIf { it.isNotBlank() }
                // 这套皮把片名就写在锚点的文字里。长度限制是防住把整段简介当片名 ——
                // 「更多」「立即播放」这类导航锚点也长 a[href*=…] 这样。
                ?: a.text().trim().takeIf { it.isNotBlank() && it.length <= 30 && !NAV_WORDS.contains(it) }
                ?: return@mapNotNull null

            // 年份与画质都取「整段就是年份 / 就是画质」的小标签，
            // 而不是扫整块文本 —— 简介里出现的年份会把条目年份带偏。
            val badges = scope.select("div,span,p,em,li").map { it.text().trim() }.filter { it.length <= 12 }

            Media(
                id = id,
                title = title.trim(),
                cover = (
                    img?.let {
                        it.attr("data-src").ifBlank { it.attr("data-original") }.ifBlank { it.attr("src") }
                    } ?: scope.attr("data-original").takeIf { it.isNotBlank() }
                    ?: a.attr("data-original").takeIf { it.isNotBlank() }
                    )?.takeIf { it.isNotBlank() && !it.contains("/load.") && !it.endsWith(".svg") }
                    ?.let { abs(it) },
                year = badges.firstOrNull { it.matches(YEAR_ONLY) }
                    ?: YEAR.find(title)?.value,
                remark = scope.selectFirst("[class*=remarks],[class*=pic-text]")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: badges.firstOrNull { it.matches(QUALITY_ONLY) || it.matches(REMARK_ONLY) },
                score = scope.selectFirst("[class*=score]")?.text()?.trim()?.takeIf { it.isNotBlank() },
                sourceId = config.id,
            )
        }.distinctBy { it.id }
    }

    /** 详情页标题：优先用站点标题里更干净的那一段，退化到 h1/h2。 */
    private fun Document.detailTitle(): String? {
        selectFirst("h1,h2.hl-dc-title,h1.stui-content__title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val t = title().substringBefore('_').substringBefore('-').trim()
        return t.takeIf { it.isNotBlank() && it.length <= 40 }
    }

    /** 懒加载站点的封面常放在 data-original / 背景图里。 */
    private fun Document.coverFromLazy(): String? {
        selectFirst("img[data-original],.hl-item-thumb[data-original]")?.let {
            return it.attr("data-original").ifBlank { it.attr("data-src") }.takeIf { s -> s.isNotBlank() }
        }
        selectFirst("[style*=background-image]")?.let { el ->
            Regex("""url\((['"]?)(.*?)\1\)""").find(el.attr("style"))
                ?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** 线路名：优先取线路标签，取不到就退回「线路 N」。 */
    private fun Document.lineName(line: String): String =
        // 这套皮的线路名写在 #source-tabs 的按钮上（data-source="3" → 「TT线路」），
        // 用 ownText 是为了不把图标元素那些空文本一起带进来
        selectFirst("[data-source=$line],[data-sid=$line]")?.ownText()?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 12 }
            ?: selectFirst(".hl-plays-from a[alt],a[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: "线路 $line"

    private companion object {
        /** 播放地址尾段的「-线路-集号」；前面那段是站点自己的片 id，不参与。 */
        val PLAY_TAIL = Regex("""-(\d+)-(\d+)$""")

        val YEAR = Regex("(19|20)\\d{2}")
        val YEAR_ONLY = Regex("^(19|20)\\d{2}$")

        /** 「更新至第12集」「更新至09期」这类连载标记，界面上当画质位一样显示。 */
        val REMARK_ONLY = Regex("^(更新至.+|已完结|全\\d+期|正片|预告|预告片)$")

        /**
         * 锚点文字是这些词时不当片名。
         *
         * 片名改成从锚点文字取之后，「更多」「立即播放」这类导航锚点也长得一样，
         * 不排掉就是往列表里塞几行「更多」。
         */
        val NAV_WORDS = setOf(
            "更多", "查看更多", "立即播放", "播放", "继续播放", "下一页", "上一页",
            "首页", "全部", "展开", "收起", "更新", "请求更新",
        )

        val QUALITY_ONLY = Regex(
            "^(4k|hdr|hd|蓝光|超清|高清|抢先版|正片|完结|全\\d+集|\\d{3,4}p)$",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** 内置站点默认规则。 */
object DefaultRules {
    val generic = HtmlRules(
        listPath = "/search?q={q}&page={page}",
        itemSelector = "a[href*=/play/]",
        detailPath = "/play/{id}",
        episodeSelector = "a.episode-link, a[data-episode]",
    )

    /**
     * 4kvm.net 这类站点：详情页即播放页，直链由页面里的 WASM 现算签名。
     *
     * 静态 HTML 里没有 player_aaaa，也没有任何 m3u8 / mp4 —— 实测过。
     * 所以标成 [HtmlRules.MODE_CLIENT_SCRIPT]，让提示语说清「签名在站点脚本里」，
     * 而不是含糊地说「需要登录」（填凭证也没用）或「第三方播放页」（那是另一回事）。
     */
    val k4 = generic.copy(
        itemSelector = "a[href*=/play/]",
        detailPath = "/play/{id}",
        episodeSelector = "a.episode-link",
        resolveMode = HtmlRules.MODE_CLIENT_SCRIPT,
    )

    /**
     * 苹果 CMS 默认模板（如 v.luttt.com）。
     *
     * 列表在 /voddetail/{id}.html，分集在 #hl-plays-list，
     * 真实地址在播放页的 player_aaaa 里 —— 页面公开数据，照读即可。
     */
    val maccms = HtmlRules(
        listPath = "/vodsearch/{q}-------------.html",
        browsePath = "/vodshow/1--------{page}---.html",
        itemSelector = "a.hl-item-thumb",
        detailPath = "/voddetail/{id}.html",
        episodeSelector = "#hl-plays-list a",
        resolveMode = HtmlRules.MODE_MACCMS_PLAYER,
    )

    /** 详情路径形如 /index.php/vod/detail/id/123.html 的苹果 CMS 站（如 xiguazx.cc）。 */
    val indexPhp = HtmlRules(
        listPath = "/index.php/vod/search.html?wd={q}&page={page}",
        browsePath = "/index.php/vod/show/id/1/page/{page}.html",
        itemSelector = "a[href*=/vod/detail/id/]",
        detailPath = "/index.php/vod/detail/id/{id}.html",
        episodeSelector = ".stui-content__playlist a, a[href*=/vod/play/]",
        resolveMode = HtmlRules.MODE_MACCMS_PLAYER,
    )

    /**
     * 苹果 CMS 的**地址规则**配一套 Tailwind 皮（如 www.4kku.com）。
     *
     * 路径和北觅那套完全一样（`/vodsearch/`、`/vodshow/`、`/voddetail/`，直链在播放页的
     * `player_aaaa` 里），不一样的是卡片和分集的类名：没有 `hl-item-thumb`，分集按线路
     * 切成 `#episodes-1..3` 三个容器，锚点上也没有 `data-line`（集号写在地址尾段里）。
     *
     * 这条皮和上面两条的区别只在选择器，所以规则留着可配 —— 用户自己加同类站改这几行就行，
     * 不用等应用发版，也不用为它写一个适配器。
     */
    val tailwind = HtmlRules(
        listPath = "/vodsearch/{q}-------------.html",
        browsePath = "/vodshow/1--------{page}---.html",
        itemSelector = "a[href*=/voddetail/]",
        detailPath = "/voddetail/{id}.html",
        // 分集锚点的三种写法并起来：海链模板的 #hl-plays-list、这套皮的 #episodes-N、
        // 以及两者都带过的 a.episode-link。jsoup 的并集按文档顺序去重，不会重复计同一集
        episodeSelector = "#hl-plays-list a, [id^=episodes-] a, a.episode-link, a[data-episode]",
        resolveMode = HtmlRules.MODE_MACCMS_PLAYER,
    )
}
