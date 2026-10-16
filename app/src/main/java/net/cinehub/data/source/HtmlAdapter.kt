package net.cinehub.data.source

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
        // 没配浏览路径、而搜索模板里带 {q} —— 这一条不能照发。fetch() 会把 {q} 换成空串，
        // 于是打出去的是 `/search?q=&page=1`，一发任何浏览器都不会发的请求。站点爱回什么回什么，
        // 但界面把它翻成「站点异常 500」就是假话：明明是这条请求不该发（实测 hqvod 就是这么判死的）。
        // 内置三条规则都显式配了 browsePath，只有认不出模板时用的 generic 会走到这条退化路径 ——
        // 所以伤到的正好只有「用户手动添加陌生站」那一路。
        if (rules.browsePath.isBlank() && rules.listPath.contains("{q}")) error(
            "这个源没配浏览路径 —— 默认规则会拿它的搜索页当列表页，空着查询词打过去没有意义。" +
                "在「浏览路径」里给它填一条分类页（形如 /vodshow/1--------{page}---.html）再试",
        )
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
        val tabs = doc.lineTabs()
        val lines = if (byLine.isEmpty()) {
            listOf(PlayLine("默认线路", listOf(Episode("正片", 1, url))))
        } else {
            byLine.entries.mapIndexed { ordinal, (line, anchors) ->
                // 第 ordinal 组分集 ↔ 第 ordinal 个线路标签（这套皮就按文档顺序排）。
                // 标签上写着这一组有几集，所以数量对不上就当没读到：宁可叫「线路 7」，
                // 也不要拿别人的名字安在这一组头上 —— 那是句看起来更可信的假话。
                val tab = tabs.getOrNull(ordinal)?.takeIf { it.episodes == null || it.episodes == anchors.size }
                PlayLine(
                    name = doc.lineName(line, tab, ordinal),
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
        // 站点排在第一的那条是替它自己的播放器挑的，影枢放得出来的却不是它（高清点播 的「推荐」
        // 实测就是：清单开头是不带地址的 #EXTINF，接着是几条图片 CDN 的 .image）。
        // 按名字直接舍掉 —— 只是挪到最后它还在候选里，自动换源照样会走到它，白失败一次。
        // 一条不剩时整条规则不生效：规则写错了，也不该让用户看到一份没有线路的详情。
        val kept = lines.filterNot { it.name in rules.lineDrop }.takeIf { it.isNotEmpty() } ?: lines
        return MediaDetail(media = media, lines = kept, intro = media.intro)
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
     * 每条分支各对应一种真实成因，按「先看是不是别人的页面，再看本站怎么取」的顺序判断：
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
            return MacPlayerJson.read(html)?.field("url")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?.let { StreamResolution.Ready(it) }
                ?: StreamResolution.Failed(
                    "播放页里没有 player_aaaa 数据，这个模板可能改版了",
                )
        }

        // 3. 苹果 CMS 的另一形态：player_aaaa 里那串不是地址，要拿去问解析站（见 MaccmsParseResolver）
        if (rules.resolveMode == HtmlRules.MODE_MACCMS_PARSE) {
            return MaccmsParseResolver.resolve(episodeUrl, config)
        }

        // 4. 地址由站点脚本现算（如 4kvm.net 的 WASM 签名）
        if (rules.resolveMode == HtmlRules.MODE_CLIENT_SCRIPT) {
            return StreamResolution.NeedsClientScript
        }

        // 5. 直链只对登录用户开放 —— 填用户自己的凭证可能走通
        if (rules.resolveMode == HtmlRules.MODE_SESSION) {
            return StreamResolution.NeedsSession
        }

        // 6. 没配规则 —— 如实说规则不足，而不是猜一个原因
        return StreamResolution.Failed(
            "这个源还没有配置直链解析规则，影枢只能拿到它的播放页地址",
        )
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

    /**
     * 线路名：先看锚点自己标了没有，再看按位置对上的标签，都取不到才退回「线路 N」。
     *
     * 站点给线路起的名字是「②蓝光」「推荐K」「稳定③」这种，跟地址尾段那个线路号
     * 没有任何可推的对应关系 —— 只能从页面的标签栏读，或者干脆不猜。
     *
     * 最后那一路 `a[alt]`（北觅这类皮）必须**按 ordinal 配对**：原来写的是全篇
     * `selectFirst`，于是同一个文档里每一条线路都取到同一个 alt —— 实测那份详情里唯一的
     * `a[alt]` 就是「北觅影视」，结果是两条线路同名。同名的代价不只是「兄弟该平级却有两条
     * 一模一样」：换线路靠 `index == cur` 挑地址、[HtmlRules.lineDrop] 按名字舍掉已知放不出
     * 的那条，同名一次就同时命中全部，用户点线路二落到的可能还是线路一。
     */
    private fun Document.lineName(line: String, tab: LineTab?, ordinal: Int): String =
        // 这套皮的线路名写在 #source-tabs 的按钮上（data-source="3" → 「TT线路」），
        // 用 ownText 是为了不把图标元素那些空文本一起带进来
        selectFirst("[data-source=$line],[data-sid=$line]")?.ownText()?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 12 }
            ?: tab?.name
            ?: select(".hl-plays-from a[alt]").getOrNull(ordinal)?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: "线路 $line"

    /**
     * 分集区上方那排线路标签：名字 + 标签上写明的集数（没有集数则为 null）。
     *
     * 「/fenlei + /bofang」这套皮（如 www.hqvod.com）把 13 条线路的名字只写在这里，
     * 分集锚点上不带任何线路属性；标签与下面的分集块按文档顺序一一对应。
     */
    private fun Document.lineTabs(): List<LineTab> =
        select(".anthology-tab a")
            .mapNotNull { a ->
                val name = a.ownText().replace('\u00A0', ' ').trim()
                if (name.isBlank() || name.length > 12) return@mapNotNull null
                LineTab(name, a.selectFirst(".badge")?.text()?.trim()?.toIntOrNull())
            }

    /** 一条线路标签：显示名，和它自己声明的集数（用来核对位置对不对得上）。 */
    private class LineTab(val name: String, val episodes: Int?)

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
     * 「/fenlei + /xiangqing + /bofang」这一套苹果 CMS 皮（如 www.hqvod.com）。
     *
     * 路径规则和上面几套都不一样：分类 /fenlei/{类型}-{页}.html、详情 /xiangqing/{id}.html、
     * 分集 /bofang/{片id}-{线路}-{集}.html —— 尾段那对数字正是线路号与集号，
     * [Element.playLine] 按它把 13 条线路拆开（这套皮不给锚点标任何线路属性）。
     * 搜索页 /sousuo/{词}-------------，末尾那一串横线是模板写死的空条件位。
     *
     * 直链走 [HtmlRules.MODE_MACCMS_PARSE] 的 [MaccmsParseResolver]。这一套皮里
     * `player_aaaa.url` 那串**不是地址**：一部片 13 条线路没有一条给地址，给的全是一串
     * base64url 样子的凭据（`from` 是 PK / PJ / PD / lzm3u8 这类线路名，或 qq / youku 那种
     * 平台页），照读出来塞给播放器只会得到一个看不懂的错。站点自己怎么用它、影枢又怎么跟着走，
     * 都写在 [MaccmsParseResolver] 的注释里（那条链路 2026-10-15 用 node 探针在三种页面上逐环验过：
     * 中间某集、最后一集、只有一集的电影）。
     *
     * `lineDrop` 舍掉的是它排在最前那条「推荐」：同一集同一线路，官网的播放器放得出来，
     * 影枢拿到的却是一份「开头几条 #EXTINF 后面不带地址、接着是几条图片 CDN 的 .image」的清单，
     * ExoPlayer 报 Source error。为什么两边不一样**还没定案**（要同一集两边各抓一份清单对比），
     * 先按名字舍掉，免得每次起播都白等一次失败 —— 这一条不再出现在线路选单里，
     * 自动换源也不会再走到它。名字是**正好等于**才舍，「推荐L」「推荐K」留在原位。
     */
    val hq = HtmlRules(
        listPath = "/sousuo/{q}-------------.html",
        browsePath = "/fenlei/1-{page}.html",
        itemSelector = "a.public-list-exp",
        detailPath = "/xiangqing/{id}.html",
        episodeSelector = "a[href*=/bofang/]",
        resolveMode = HtmlRules.MODE_MACCMS_PARSE,
        lineDrop = listOf("推荐"),
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
