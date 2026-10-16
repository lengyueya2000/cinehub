package net.cinehub.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.PlayLine
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.StreamUrls
import net.cinehub.data.model.VideoPlatforms
import net.cinehub.data.net.Http

/**
 * 标准 JSON 接口适配器（苹果 CMS / maccms 的 api.php/provide/vod 系列）。
 *
 * 这类接口在国内影视站里是事实标准，字段名固定为 vod_*，
 * 所以一个实现能覆盖绝大多数「JSON 接口」型的源。
 */
class MaccmsAdapter(override val config: SourceConfig) : SourceAdapter {

    private val rules = config.api ?: net.cinehub.data.model.ApiRules()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun abs(path: String) =
        if (path.startsWith("http")) path
        else config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun JsonObject.str(field: String): String? =
        (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * 取一份接口正文。
     *
     * 三条取数一律走这里，就是为了 [SourceConfig.needsBrowser] 那一格只写一次：
     * 挂在 Cloudflare 后面的站，搜索走通了、详情却还用 OkHttp 去问，界面上就会
     * 出现「列表有片、点进去说这个源要求浏览器验证」。
     *
     * 走浏览器内核的那一路，一次问话给 40 秒而不是默认的 20 秒。这不是拍出来的数：
     * 同一台模拟器上实测「全部重测」量到 21533ms（那一趟题正好过期，引擎现场答了一遍），
     * 而探测那条路本来就是 30 秒 —— 只有取数沿用 20 秒。后果是这个源**每次冷启动都要
     * 失败一次才好用**：暖机就发生在这一次失败的问话里，等题答完了，超时也到了。
     * 界面上看到的是「第一次搜不到、第二次就有了」，那是句假话，不是这个源真的没有。
     */
    private suspend fun fetch(url: String): String = Http.get(
        url,
        referer = config.baseUrl,
        headers = Http.authHeaders(config),
        viaBrowser = config.needsBrowser,
        timeoutSec = if (config.needsBrowser) 40 else 20,
    )

    override suspend fun browse(page: Int): List<Media> =
        parseList(fetch("${abs(rules.listPath)}?ac=videolist&${rules.pageParam}=$page"))

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        return parseList(fetch("${abs(rules.listPath)}?ac=videolist&${rules.searchParam}=$q&${rules.pageParam}=$page"))
    }

    override suspend fun detail(id: String): MediaDetail {
        // id 是从列表页/别的源手里接过来的字符串，原样拼进查询串就等于让它替我们决定
        // 这次请求长什么样（带一个 `&` 就能多出个参数）。填之前编一次。
        val url = abs(rules.detailPath.replace("{id}", java.net.URLEncoder.encode(id, "UTF-8")))
        val root = json.parseToJsonElement(fetch(url)).jsonObject
        val item = firstListItem(root) ?: error("详情接口无数据")

        val media = Media(
            id = item.str(rules.idField) ?: id,
            title = item.str(rules.titleField) ?: id,
            cover = item.str(rules.coverField),
            year = item.str(rules.yearField),
            category = item.str(rules.categoryField),
            remark = item.str(rules.remarkField),
            score = item.str(rules.scoreField),
            area = item.str(rules.areaField),
            actors = item.str(rules.actorsField),
            director = item.str(rules.directorField),
            intro = item.str(rules.introField)?.replace(Regex("<[^>]+>"), "")?.trim(),
            sourceId = config.id,
        )

        // 线路之间是两三个 `$`（苹果 CMS 的文档写 `$$`，实测这批站里是 `$$$`），
        // 而**每一集内部**是「名字$地址」—— 正好一个 `$`。所以分隔按「两个及以上」切：
        // 按 `$$$` 硬切的站一旦给的是 `$$`，整表会被并成一条线路、边界那一集直接吞掉；
        // 按单个 `$` 切则把每集自己劈开。
        val lineSplit = Regex("\\$\\$+")
        val fromNames = item.str(rules.playFromField)?.split(lineSplit) ?: emptyList()
        val urlGroups = item.str(rules.playUrlField)?.split(lineSplit) ?: emptyList()

        val lines = urlGroups.mapIndexed { gi, group ->
            val raw = group.split("#").mapNotNull { seg ->
                val parts = seg.split("$", limit = 2)
                if (parts.size < 2 || parts[1].isBlank()) return@mapNotNull null
                parts[0].trim() to parts[1].trim()
            }
            // 集号优先认名字里那个编号，认不到才按位置数。
            //
            // 按位置数在这里是个真窟窿：只要有一条脏分片被上面那句丢掉，后面每一集的
            // index 就集体前移一格 —— A 线的第 5 集变成 B 线的第 4 集。而「换线路保进度」
            // 与「点第 N 集播第 N 集」全靠 index 对齐，于是播的是另一集，
            // 写进观看记录的也是那个错集号，且一点报错都没有。
            // HtmlAdapter 那边早就防过同一件事（还防了倒序模板把集号排反），这条 JSON 路径漏了。
            PlayLine(
                name = fromNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路 ${gi + 1}",
                episodes = raw.mapIndexed { i, pair ->
                    Episode(
                        name = pair.first,
                        index = episodeNoOf(pair.first) ?: (i + 1),
                        playUrl = pair.second,
                    )
                },
            )
        }.filter { it.episodes.isNotEmpty() }

        return MediaDetail(
            media = media,
            lines = lines.ifEmpty { listOf(PlayLine("默认线路", listOf(Episode("正片", 1, null)))) },
            intro = media.intro,
        )
    }

    /**
     * 接口返回的未必都是直链。
     *
     * 苹果 CMS 的 `vod_play_url` 里混着两类地址：真正的媒体文件，
     * 以及爱奇艺 / 腾讯 / B 站那种「播放页」。后者交给播放器只会报错，
     * 所以这里按域名如实归类，让上层说清楚是哪个平台。
     */
    override suspend fun resolveStream(episodeUrl: String): StreamResolution = when {
        StreamUrls.isMediaUrl(episodeUrl) -> StreamResolution.Ready(episodeUrl)
        else -> VideoPlatforms.platformOf(episodeUrl)
            ?.let { StreamResolution.ThirdPartyPage(episodeUrl, it) }
            ?: StreamResolution.Failed("接口给的地址既不是媒体文件，也不是已知平台的播放页")
    }

    private fun firstListItem(root: JsonObject): JsonObject? {
        (root[rules.listField] as? JsonArray)?.firstOrNull()?.let { return it.jsonObject }
        // 有些接口直接把数据放在 data 下
        (root["data"] as? JsonObject)?.let { d ->
            (d[rules.listField] as? JsonArray)?.firstOrNull()?.let { return it.jsonObject }
            if (d.containsKey(rules.titleField)) return d
        }
        if (root.containsKey(rules.titleField)) return root
        return null
    }

    /**
     * 把列表接口读成条目。
     *
     * 「这个源没有这部片」和「这一版规则读不懂这个源」是两句话，以前混成了一句：
     * 字段找不到就返回空列表，界面照着说「没有」。可苹果 CMS 真的没搜到时给的是
     * `{"list":[]}` —— 那个空数组是答案；而**整个 list 字段都不存在**不是答案，
     * 是接口改了、或者这一路根本不是一个列表接口。那种情况必须报出来，
     * 否则用户会拿着一句假话去换关键词、换源，而真正该改的是这个源的规则。
     */
    private fun parseList(body: String): List<Media> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            error("这个源给回来的不是 JSON，接口形态多半已经改了")
        }
        val arr = (root[rules.listField] as? JsonArray)
            ?: ((root["data"] as? JsonObject)?.get(rules.listField) as? JsonArray)
        if (arr == null) {
            val msg = (root["msg"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            error(
                "这个源的接口里没有「${rules.listField}」这一项，这一版规则读不动它" +
                    (msg?.let { "（接口自己说：$it）" } ?: ""),
            )
        }

        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str(rules.idField) ?: return@mapNotNull null
            Media(
                id = id,
                title = o.str(rules.titleField) ?: return@mapNotNull null,
                cover = o.str(rules.coverField),
                year = o.str(rules.yearField),
                category = o.str(rules.categoryField),
                remark = o.str(rules.remarkField),
                score = o.str(rules.scoreField),
                area = o.str(rules.areaField),
                actors = o.str(rules.actorsField),
                director = o.str(rules.directorField),
                intro = o.str(rules.introField)?.replace(Regex("<[^>]+>"), "")?.trim(),
                sourceId = config.id,
            )
        }
    }

    /**
     * 从分集名字里认那个编号：`第12集`、`12期`、`HD 7 4K` 里那个 7 都算。
     * 认不出来返回 null，由调用方退回按位置数。
     */
    private fun episodeNoOf(name: String): Int? {
        EP_NO.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        EP_LEADING.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }
}

private val EP_NO = Regex("第\\s*(\\d{1,4})\\s*[集期话]")
private val EP_LEADING = Regex("^\\s*(\\d{1,3})\\b")

private val JsonElement.contentOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
