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

/**
 * 通用「网页解析」适配器。
 *
 * 选择器来自 [SourceConfig.html]，所以用户加站点不用改代码。
 * 内置了几个常见站点的默认规则（见 [DefaultRules]），识别不出时用通用兜底。
 */
class HtmlAdapter(override val config: SourceConfig) : SourceAdapter {

    private val rules = config.html ?: DefaultRules.generic
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun abs(path: String): String =
        if (path.startsWith("http")) path
        else config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private suspend fun fetch(path: String, page: Int): Document {
        val url = abs(path.replace("{q}", "").replace("{page}", "$page"))
        return Jsoup.parse(
            Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)),
            url,
        )
    }

    override suspend fun browse(page: Int): List<Media> {
        val path = rules.browsePath.ifBlank { rules.listPath }
        return parseList(fetch(path, page))
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val path = rules.listPath
            .replace("{q}", java.net.URLEncoder.encode(keyword, "UTF-8"))
            .replace("{page}", "$page")
        return parseList(fetch(path, page))
    }

    override suspend fun detail(id: String): MediaDetail {
        val url = abs(rules.detailPath.replace("{id}", id))
        val doc = Jsoup.parse(
            Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)),
            url,
        )

        val title = doc.detailTitle() ?: id
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.coverFromLazy()
        val intro = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".desc,.introduction,.detail-content,.hl-content-text")?.text()

        val media = Media(
            id = id,
            title = title.trim(),
            cover = cover,
            intro = intro?.trim(),
            sourceId = config.id,
        )

        // 分集：同一部剧的多个线路会各有自己的分集锚点
        val epAnchors = doc.select(rules.episodeSelector)
        val byLine = epAnchors.groupBy { it.attr("data-line").ifBlank { "1" } }
        val lines = if (byLine.isEmpty()) {
            listOf(PlayLine("默认线路", listOf(Episode("正片", 1, url))))
        } else {
            byLine.map { (line, anchors) ->
                PlayLine(
                    name = doc.lineName(line),
                    episodes = anchors.mapIndexed { i, a ->
                        Episode(
                            name = a.text().trim().ifBlank { "第 ${i + 1} 集" },
                            index = a.attr("data-episode").toIntOrNull() ?: (i + 1),
                            playUrl = abs(a.attr(rules.episodeUrlAttr).ifBlank { a.attr("href") }),
                        )
                    },
                )
            }
        }
        return MediaDetail(media = media, lines = lines, intro = media.intro)
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
     */
    private fun macPlayerUrl(html: String): String? {
        val raw = Regex("""player_[a-zA-Z0-9]+\s*=\s*(\{.*?\})\s*[;<]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val url = (obj["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        return url.takeIf { it.startsWith("http") }
    }

    private fun parseList(doc: Document): List<Media> {
        val seen = HashSet<String>()
        return doc.select(rules.itemSelector).mapNotNull { a ->
            val href = a.attr("href")
            val id = href.substringAfterLast('/').substringBefore('?').substringBefore('.')
            if (id.isBlank() || !seen.add(id)) return@mapNotNull null

            val img = a.selectFirst("img")
            val title = a.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("[class*=title],h3,h4")?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("data-title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            // 年份与画质都取「整段就是年份 / 就是画质」的小标签，
            // 而不是扫整块文本 —— 简介里出现的年份会把条目年份带偏。
            val badges = a.select("div,span,p,em,li").map { it.text().trim() }.filter { it.length <= 12 }

            Media(
                id = id,
                title = title.trim(),
                cover = img?.let {
                    it.attr("data-src").ifBlank { it.attr("data-original") }.ifBlank { it.attr("src") }
                }?.takeIf { it.isNotBlank() }
                    ?: a.attr("data-original").takeIf { it.isNotBlank() },
                year = badges.firstOrNull { it.matches(YEAR_ONLY) }
                    ?: YEAR.find(title)?.value,
                remark = a.selectFirst("[class*=remarks],[class*=pic-text]")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: badges.firstOrNull { it.matches(QUALITY_ONLY) },
                score = a.selectFirst("[class*=score]")?.text()?.trim()?.takeIf { it.isNotBlank() },
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
        selectFirst(".hl-plays-from a[alt],a[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: "线路 $line"

    private companion object {
        val YEAR = Regex("(19|20)\\d{2}")
        val YEAR_ONLY = Regex("^(19|20)\\d{2}$")
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
}
