package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.Favorite
import net.cinehub.data.model.HistoryEntry

private val Context.watchStore by preferencesDataStore(name = "cinehub_watch")

/**
 * 观看状态：收藏 + 播放记录（含续播位置）。
 *
 * 两者合成一个仓库、一份 DataStore：都是「按 [net.cinehub.data.model.Media.key]
 * 记的一部片的状态」，走的是同一套 Flow + JSON 读写管道。分成两份只是把管道抄两遍。
 *
 * 界面读的是内存里的 StateFlow（启动时一次性 load），写的时候顺带落盘 ——
 * 播放器每 10 秒报一次位置，不能每次都去 await 一次 DataStore 读。
 */
class WatchRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY_FAV = stringPreferencesKey("favorites_v1")
    private val KEY_HIS = stringPreferencesKey("history_v1")

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private var lastSaveAt = 0L
    private var pendingSave: Job? = null

    init {
        scope.launch { load() }
    }

    // ---------- 收藏 ----------

    fun isFavorite(key: String): Boolean = _favorites.value.any { it.key == key }

    /** 收藏/取消，返回切换后是不是「已收藏」。 */
    fun toggleFavorite(fav: Favorite): Boolean {
        val had = isFavorite(fav.key)
        _favorites.update { list -> if (had) list.filterNot { it.key == fav.key } else list + fav }
        save()
        return !had
    }

    fun removeFavorite(key: String) {
        _favorites.update { list -> list.filterNot { it.key == key } }
        save()
    }

    // ---------- 播放记录 ----------

    /** 这一部最近看到的那一集。 */
    fun historyOf(key: String): HistoryEntry? = _history.value.firstOrNull { it.key == key }

    /**
     * 某一集该从哪一秒开始。
     *
     * 只认「记录里正好是这一集、而且没看完」的情况 —— 用户明确点第 5 集时，
     * 拿第 3 集的位置去续播是错的。
     */
    fun resumeAt(key: String, episodeIndex: Int): Long =
        historyOf(key)?.takeIf { it.episodeIndex == episodeIndex && it.resumable }?.positionSec ?: 0L

    /** 按 key upsert 并提到最前；只留最近 [MAX_HISTORY] 部。 */
    fun record(e: HistoryEntry) {
        _history.update { list ->
            (list.filterNot { it.key == e.key } + e).sortedByDescending { it.updatedAt }
                .take(MAX_HISTORY)
        }
        saveSoon()
    }

    fun removeHistory(key: String) {
        _history.update { list -> list.filterNot { it.key == key } }
        save()
    }

    fun clearHistory() {
        _history.value = emptyList()
        save()
    }

    // ---------- 落盘 ----------

    private fun save() {
        lastSaveAt = System.currentTimeMillis()
        snapshot()
    }

    /**
     * 位置上报走这条：1.5 秒内的连续写入合并成一次。
     *
     * 合并不能靠「丢掉后面那次」—— 退出播放时补写的那一笔正好落在窗口里，
     * 丢掉就等于用户下次进来从十几秒前开始。所以窗口内的写改成延后补一次。
     */
    private fun saveSoon() {
        val wait = 1_500L - (System.currentTimeMillis() - lastSaveAt)
        if (wait <= 0L) {
            save()
            return
        }
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(wait)
            save()
        }
    }

    /**
     * 落一次盘。
     *
     * 和 [DownloadRepository.saveNow] 同一个坑：内存那份要在 `edit` 的变换里取。
     * 启动写之前抓快照，两次写的落地顺序没有保证，后落地的可能正是那一份**旧的** ——
     * 用户刚点的收藏、刚续的那一集就被盖回去了。
     */
    private fun snapshot() {
        scope.launch {
            runCatching {
                context.watchStore.edit {
                    it[KEY_FAV] = json.encodeToString(_favorites.value)
                    it[KEY_HIS] = json.encodeToString(_history.value)
                }
            }
        }
    }

    private suspend fun load() {
        val p = context.watchStore.data.first()
        // 同上：读盘这段时间里用户点的收藏、播的那一集，不能被整份覆盖掉。
        _favorites.value =
            (_favorites.value + p[KEY_FAV].decode<Favorite>()).distinctBy { it.key }
        _history.value = (_history.value + p[KEY_HIS].decode<HistoryEntry>())
            .distinctBy { it.key }
            .sortedByDescending { it.updatedAt }
            .take(MAX_HISTORY)
    }

    private inline fun <reified T> String?.decode(): List<T> =
        if (isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<T>>(this) }.getOrNull() ?: emptyList()

    private companion object {
        const val MAX_HISTORY = 100
    }
}
