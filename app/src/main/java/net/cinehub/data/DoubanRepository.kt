package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.cinehub.data.model.DoubanChart
import net.cinehub.data.model.DoubanItem
import net.cinehub.data.net.Http

private val Context.doubanStore by preferencesDataStore(name = "cinehub_douban")

/**
 * 豆瓣榜单：只读它公开的几个列表接口。
 *
 * 边界跟别处一致 —— 不登录、不带任何 Cookie 或凭证。
 * 带的就是一个浏览器访客本来就会带的那点东西（UA + Referer），
 * 因为豆瓣对裸 UA 的请求直接拒。哪天它把这些接口收紧了，这里就如实抛错，
 * 界面会写「拉不到 + 原因」，不会装成「这个榜没片」。
 *
 * 榜单结果按「哪个榜」缓存并落盘一份：冷启动先放上次那份，再后台刷新，
 * 用户不至于对着一个转圈的骨架等网络。
 */
class DoubanRepository(private val context: Context) {

    /** 一个榜的一次快照。[at] 是取回的时刻（毫秒）。 */
    @Serializable
    data class Snapshot(val at: Long, val items: List<DoubanItem>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY = stringPreferencesKey("snapshots_v1")

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    init {
        scope.launch { runCatching { load() } }
    }

    /**
     * 落盘那一份也可能带着重复 id（去重这一条是后加的，老用户的盘上正是去重之前存的那份），
     * 而片库那一格拿 id 当列表键 —— 撞键会当场崩。读这一道口上再洗一遍，比在每一处显示
     * 之前各洗一遍便宜，也不会漏。
     */
    fun cached(chartId: String): Snapshot? = snapshots[chartId]?.let { s ->
        val items = s.items.distinctBy { it.id }
        if (items.size == s.items.size) s else s.copy(items = items)
    }

    /** 拉一个榜。失败时抛出的是给用户看的话，不是异常栈。 */
    suspend fun fetch(chart: DoubanChart): List<DoubanItem> {
        val body = Http.get(urlFor(chart), referer = "https://movie.douban.com/")
        val items = runCatching { if (chart.topList) parseTopList(body) else parseSubjects(body) }
            .getOrElse { throw IllegalStateException("豆瓣回的不是列表，多半是收紧了或者改了接口") }
        if (items.isEmpty()) throw IllegalStateException("豆瓣这个榜这次一条都没回")
        // 按 id 去重：豆瓣同一份列表里回两条同样的片子是有的（列表页翻页重叠、榜单本身重复收录）。
        // 片库那一格用 id 当 LazyVerticalGrid 的键，撞键会当场崩在「Key was already used」上；
        // 首页那份走 chunked(3) 的普通行不崩，但同一条片会摆两遍。
        return items.distinctBy { it.id }
    }

    /** 取回成功后存一份，顺带落盘。 */
    fun put(chart: DoubanChart, items: List<DoubanItem>) {
        snapshots[chart.id] = Snapshot(System.currentTimeMillis(), items)
        scope.launch {
            runCatching {
                val all = snapshots.entries.associate { (k, v) -> k to v }
                context.doubanStore.edit {
                    it[KEY] = json.encodeToString(SNAPSHOTS, all)
                }
            }
        }
    }

    private fun urlFor(chart: DoubanChart): String = if (chart.topList) {
        "https://movie.douban.com/j/chart/top_list?type=${if (chart.kind == "tv") 2 else 1}" +
            "&interval_id=100%3A90&page=0"
    } else {
        "https://movie.douban.com/j/search_subjects?type=${chart.kind}" +
            "&tag=${URLEncoder.encode(chart.tag ?: "", "UTF-8")}" +
            "&sort=${chart.sort}&page_limit=30&page_start=0"
    }

    private fun parseSubjects(body: String): List<DoubanItem> =
        json.parseToJsonElement(body).jsonObject["subjects"]?.jsonArray
            ?.mapNotNull { it.jsonObject.toSubjectItem() } ?: emptyList()

    private fun parseTopList(body: String): List<DoubanItem> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonObject.toTopItem() }

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.list(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: emptyList()

    private fun JsonObject.toSubjectItem(): DoubanItem? {
        val id = str("id") ?: return null
        val title = str("title") ?: return null
        return DoubanItem(
            id = id, title = title,
            url = str("url") ?: subjectUrl(id),
            cover = str("cover"), rate = str("rate"),
            year = str("year"),
        )
    }

    private fun JsonObject.toTopItem(): DoubanItem? {
        val id = str("id") ?: return null
        val title = str("title") ?: return null
        return DoubanItem(
            id = id, title = title,
            url = str("url") ?: subjectUrl(id),
            cover = str("cover_url"),
            rate = str("score") ?: this["rating"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            kinds = list("types"), regions = list("regions"),
            year = str("year"),
            rank = runCatching { this["rank"]!!.jsonPrimitive.long.toInt() }.getOrDefault(0),
        )
    }

    private fun subjectUrl(id: String) = "https://movie.douban.com/subject/$id/"

    private suspend fun load() {
        val raw = context.doubanStore.data.first()[KEY] ?: return
        val all = runCatching { json.decodeFromString(SNAPSHOTS, raw) }.getOrNull() ?: return
        all.forEach { (k, v) -> snapshots.putIfAbsent(k, v) }
    }

    private companion object {
        val SNAPSHOTS = MapSerializer(String.serializer(), Snapshot.serializer())
    }
}
