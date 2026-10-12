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

    override suspend fun browse(page: Int): List<Media> {
        val url = "${abs(rules.listPath)}?ac=videolist&${rules.pageParam}=$page"
        return parseList(Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)))
    }

    override suspend fun search(keyword: String, page: Int): List<Media> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = "${abs(rules.listPath)}?ac=videolist&${rules.searchParam}=$q&${rules.pageParam}=$page"
        return parseList(Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config)))
    }

    override suspend fun detail(id: String): MediaDetail {
        val url = abs(rules.detailPath.replace("{id}", id))
        val root = json.parseToJsonElement(Http.get(url, referer = config.baseUrl, headers = Http.authHeaders(config))).jsonObject
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

        val fromNames = item.str(rules.playFromField)?.split("$$$") ?: emptyList()
        val urlGroups = item.str(rules.playUrlField)?.split("$$$") ?: emptyList()

        val lines = urlGroups.mapIndexed { gi, group ->
            val eps = group.split("#").mapNotNull { seg ->
                val parts = seg.split("$")
                if (parts.size < 2) return@mapNotNull null
                Episode(name = parts[0].trim(), index = 0, playUrl = parts[1].trim())
            }.mapIndexed { i, e -> e.copy(index = i + 1) }

            PlayLine(
                name = fromNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路 ${gi + 1}",
                episodes = eps,
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

    private fun parseList(body: String): List<Media> {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = (root[rules.listField] as? JsonArray)
            ?: ((root["data"] as? JsonObject)?.get(rules.listField) as? JsonArray)
            ?: JsonArray(emptyList())

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
}

private val JsonElement.contentOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
