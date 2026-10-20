package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.searchStore by preferencesDataStore(name = "cinehub_search")

/**
 * 搜索历史 —— 最近搜过的几个词。
 *
 * 曾经它住在 `AppViewModel` 的一个 `MutableStateFlow` 里：纯内存，**重启就没了**。
 * 而搜索页那句确认文案一直写着「这些搜索词只存在这台设备上」—— 用户读到的意思是
 * 「会留着，只是不出这台手机」，实际却是「关掉应用就没了」：那句话得是真的。
 *
 * 单开一份 store、一个仓库，而不是塞进 `WatchRepository`：那份的边界写得很清楚 ——
 * 「按 [net.cinehub.data.model.Media.key] 记的一部片的状态」。搜索词不是片的状态，
 * 混进去以后没人说得清谁有权改它（和 `PlaybackRepository` 单开是同一个理由）。
 */
class SearchHistoryRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY_TERMS = stringPreferencesKey("search_terms_v1")

    private val _terms = MutableStateFlow<List<String>>(emptyList())

    /** 最近搜索，新的在前，最多 [MAX_TERMS] 条。搜索页空态那一排芯片读它。 */
    val terms: StateFlow<List<String>> = _terms.asStateFlow()

    /**
     * 读盘回来了没有。和 `WatchRepository.loaded` 同一道闸：内存这份一开始是空的，
     * 读盘没回来就落一次盘（哪怕只是用户冷启动搜了一个词），写下去的整份
     * 不含盘上那些旧词 —— 旧的那几条当场没了。
     */
    @Volatile
    private var loaded = false

    /**
     * 读盘回来之前用户删掉的那些词。和 [loaded] 同一道闸的理由，反过来说一遍：
     * `add` 是「往内存里加」，读盘回来时两边一合就还在；可 `remove` / `clear` 是
     * 「从内存里拿掉」，而盘上那份还原样躺着 —— 不加这道记性，冷启动头几帧里
     * 长按删掉一条、或点了清空，重启后它又回来了，用户以为删干净了。
     *
     * 只记这一段窗口：加载一过就再没有「盘上说了算」的时刻，删了就是删了。
     */
    private val deletedBeforeLoad = mutableSetOf<String>()

    init {
        scope.launch {
            runCatching {
                context.searchStore.edit { p ->
                    val raw = p[KEY_TERMS]
                    // 解不通就当没有，顺手把那份坏的覆盖掉（见 save 的注释）：
                    // 搜索历史坏得起，收藏坏不起，所以这里不像 WatchRepository 那样另设备份键。
                    val stored = raw?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { json.decodeList(it) }.getOrNull() }
                    if (!loaded) {
                        // 读盘期间用户可能已经搜了一词（冷启动快），新的在前、盘上的补后，
                        // 合并去重再截断 —— 两边都不丢。删掉的那几个词从盘上那份里剔掉，
                        // 理由见 [deletedBeforeLoad]。
                        val merged = _terms.value + (stored ?: emptyList()).filterNot { it in deletedBeforeLoad }
                        _terms.value = normalize(merged)
                    }
                    loaded = true
                    p[KEY_TERMS] = json.encodeList(_terms.value)
                }
            }
        }
    }

    /** 记一次成功的搜索：顶到最前、去重、截断，并落盘。 */
    fun add(term: String) {
        val q = term.trim()
        if (q.isEmpty()) return
        _terms.update { list -> normalize(listOf(q) + list.filterNot { it == q }) }
        save()
    }

    /** 删一条（长按芯片）。不弹确认 —— 删错了重新搜一次就回来了。 */
    fun remove(term: String) {
        if (!loaded) deletedBeforeLoad.add(term)
        _terms.update { list -> list.filterNot { it == term } }
        save()
    }

    /** 全部清空（有确认弹窗）。 */
    fun clear() {
        if (!loaded) deletedBeforeLoad.addAll(_terms.value)
        _terms.value = emptyList()
        save()
    }

    private fun save() {
        // 读盘没回来之前不写（见 [loaded]）：整份覆盖，写早了就是拿半截的内存盖掉盘上的全部。
        if (!loaded) return
        // 内存那份要在 `edit` 的变换里取（和 WatchRepository.snapshot 同一个坑）：
        // 发协程之前抓快照的话，两次写的落地顺序没有保证，后落地的可能正是那一份旧的。
        scope.launch {
            runCatching {
                context.searchStore.edit { it[KEY_TERMS] = json.encodeList(_terms.value) }
            }
        }
    }

    /**
     * 去空格、去重（保前面的那个，即最新的）、截到 [MAX_TERMS]。
     *
     * `add` 和读盘那一趟合并都走它：两处各写一遍去重 + 截断的话，
     * 合并那一趟按「先各自截断再合并」走，就会存出超过 [MAX_TERMS] 条来。
     */
    private fun normalize(list: List<String>): List<String> =
        list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TERMS)

    private fun Json.encodeList(list: List<String>): String = encodeToString(ListSerializer(String.serializer()), list)

    private fun Json.decodeList(raw: String): List<String> = decodeFromString(ListSerializer(String.serializer()), raw)

    private companion object {
        /** 和上一版 `AppViewModel` 里那句 `.take(8)` 同一个数：空态那一排芯片摆得下就好。 */
        const val MAX_TERMS = 8
    }
}
