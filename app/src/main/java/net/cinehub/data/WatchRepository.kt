package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.Favorite
import net.cinehub.data.model.HistoryEntry
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * 这一份仓库退休了没有。
     *
     * [scope] 的根是 [SupervisorJob] —— 它**永远不会自己结束**，所以不显式取消的话，
     * 这一颗作用域和它身上的每一个子任务都会一直活到进程结束。VM 是 activity 作用域的
     * （`AppViewModel` 由 `MainActivity` 的 `ViewModelProvider` 拿），Activity 重建一次
     * 就多一套仓库、多一套 `SupervisorJob`、多一份「还在写盘」的协程。
     *
     * 用 [AtomicBoolean] 而不是 `@Volatile var`：关的动作要和「起一个落盘协程」并发发生
     * （主线程关仓库、IO 线程正好在写盘），单纯一个 volatile 标志读-改-写不是原子的。
     */
    private val closed = AtomicBoolean(false)

    /**
     * 这一份仓库退休：把还在跑的协程全停掉。
     *
     * 幂等：调几次都是同一个结果 —— `scope.cancel()` 对一个已取消的作用域再调一次没有副作用，
     * [closed] 也只让第一次真正生效。
     *
     * **关之前先把待写的落盘做掉**。这个仓库的每一次改动（收藏、观看记录）都是
     * 「改内存 + 起一个协程写盘」两条腿，而写盘那一条是异步的：用户在写盘落地之前
     * 退出应用，这一笔就跟着那个被取消的协程一起没了 —— 用户明明刚点过收藏，
     * 下次进来它不在。
     *
     * 所以这里先同步落一次盘（见 [flushNow]），再取消作用域。取消只停**还没开始**的活，
     * 已经在写的那一笔由 DataStore 自己的事务保证要么写完要么不写，不会写一半。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        flushNow()
        pendingSave?.cancel()
        pendingSave = null
        scope.cancel()
    }

    private val KEY_FAV = stringPreferencesKey("favorites_v1")
    private val KEY_HIS = stringPreferencesKey("history_v1")
    /** 收藏页那一排 SortChip 当前选中哪一档（0 最近加入 / 1 名称 / 2 年份）。 */
    private val KEY_FAV_SORT = intPreferencesKey("favorite_sort_v1")
    /** 解不通时那份原文的去处，见 [snapshot]。和 [SourceRepository] 的备份键同一套路。 */
    private val KEY_FAV_BAK = stringPreferencesKey("favorites_v1_unreadable")
    private val KEY_HIS_BAK = stringPreferencesKey("history_v1_unreadable")

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /**
     * 盘上那份有内容、但这一版解析不了。
     *
     * 必须和「压根没存过」分开：两者都当空列表处理的话，用户看到的是「我收藏的东西没了」，
     * 而真相是版本对不上 —— 更糟的是下一次写入会把那份还救得回来的原文彻底冲掉。
     */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    private val _favoriteSort = MutableStateFlow(DEFAULT_FAV_SORT)

    /**
     * 收藏页的排序选择。存下来 —— 上一版是 Compose 里的 `remember`，退出那一屏就回 0，
     * 每次进来都要重挑一次。倍速、连播、画面比例（`PlaybackRepository`）早就是持久的，
     * 同一种「用户挑好的偏好」不该有两种待遇。档位含义见 [setFavoriteSort]。
     */
    val favoriteSort: StateFlow<Int> = _favoriteSort.asStateFlow()

    /**
     * 排序这一档读盘回来了没有。
     *
     * 和 [loaded] 同一道闸的单独一份：`setFavoriteSort` 是直接往这一个键里写的
     * （不整份覆盖，不需要等 [loaded]），但 [load] 回来时仍可能拿盘上那份旧的
     * 把用户刚点的盖掉 —— 所以先点过就别再读。和 `PlaybackRepository.loaded` 同一个套路。
     */
    @Volatile
    private var sortLoaded = false

    /**
     * 记下收藏页选的那一档：0 最近加入 / 1 名称 / 2 年份。
     *
     * 认不出的值一律回 0（最近加入）：盘上那份是手改过的、或将来档位重排后残留的序号，
     * 拿去 `when` 只会落进 else 分支显示最近加入 —— 那正好是默认，写回一个干净的 0 更诚实。
     */
    fun setFavoriteSort(value: Int) {
        val v = if (value in 0..2) value else DEFAULT_FAV_SORT
        sortLoaded = true
        _favoriteSort.value = v
        // 仓库已经退休就不再起协程了（起了也不会执行，见 [close]）——
        // 这一档的落盘由 close 里那次 [flushNow] 带走。
        if (closed.get()) return
        scope.launch { runCatching { context.watchStore.edit { it[KEY_FAV_SORT] = v } } }
    }

    private var lastSaveAt = 0L
    private var pendingSave: Job? = null

    /**
     * 盘上那一份读回来了没有。
     *
     * 落盘是整份覆盖写（见 [snapshot]），而内存这份一开始是**空的** —— 读盘还没回来
     * 就让用户的一次点收藏落了盘，写下去的就是「一条收藏都没有」，他攒了很久的
     * 收藏和观看记录当场没了；[load] 随后合并的是盘上那份（已经被冲掉的那份），救不回来。
     * 读回来之前一律不落盘。`PlaybackRepository` 一直是这么做的，这里补上。
     */
    @Volatile
    private var loaded = false

    init {
        scope.launch { load() }
    }

    // ---------- 收藏 ----------

    fun isFavorite(key: String): Boolean = _favorites.value.any { it.key == key }

    /** 收藏/取消，返回切换后是不是「已收藏」。 */
    fun toggleFavorite(fav: Favorite): Boolean {
        // 开关和「原来有没有」要在**同一次原子更新**里算出来。
        // 原来先读 `isFavorite` 再单独 update：两次点击贴着来的时候，
        // 两笔都读到「还没有」，于是都去加一条 —— 收藏没被切掉，反而重复了一遍。
        var had = false
        _favorites.update { list ->
            had = list.any { it.key == fav.key }
            if (had) list.filterNot { it.key == fav.key } else list + fav
        }
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
        // 读盘没回来之前不写（见 [loaded]）：内存这份还是空的，写下去就是清空用户的收藏。
        if (!loaded) return
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
        // 仓库已经退休：这一笔延后的写等不到它了（作用域已被取消）。
        // 立刻同步写一次，别让用户刚续的那一集留在内存里就散了 —— 见 [flushNow]。
        if (closed.get()) {
            flushNow()
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
        // 仓库已经退休就别再起新的写盘协程了：那个作用域已被取消，
        // 起出来的活一件也不会执行（见 [close]）。真正要保住的那一份由 [flushNow] 写。
        if (closed.get()) return
        scope.launch {
            runCatching {
                context.watchStore.edit {
                    // 覆盖之前先验一眼盘上那份：解不通就先挪进备份键，再写新的。
                    // 这一版既然读不懂它，直接盖就等于把用户的东西删了。
                    it[KEY_FAV]?.takeIf { raw -> raw.isNotBlank() && !readable<Favorite>(raw) }
                        ?.let { raw -> it[KEY_FAV_BAK] = raw }
                    it[KEY_HIS]?.takeIf { raw -> raw.isNotBlank() && !readable<HistoryEntry>(raw) }
                        ?.let { raw -> it[KEY_HIS_BAK] = raw }
                    it[KEY_FAV] = json.encodeToString(_favorites.value)
                    it[KEY_HIS] = json.encodeToString(_history.value)
                }
            }
        }
    }

    /**
     * 立刻把内存里那一份落盘，**等它真的落完才回来**。
     *
     * 这是 [close] 用的最后一道：关仓库的时候可能正有一笔写盘协程在飞
     * （用户刚点完收藏、落盘那一趟还没落地），把它取消掉就等于把这一笔丢了。
     * 所以这里同步地写一次，写完再让作用域停下来。
     *
     * 用 `runBlocking` 而不是 `scope.launch`：后者是异步的，close 紧接着就把作用域取消了，
     * 那一笔多半还没开始跑就被取消掉 —— 等于没写。`runBlocking` 等的是 DataStore 那一个文件的写，
     * DataStore 内部自己走 Dispatchers.IO，不靠调用方的线程，所以不会死锁。
     *
     * 代价：`runBlocking` 会**占住调用线程**直到这一笔落完（几十毫秒量级，一个小文件）。
     * `close` 是在 Activity 拆除时调的（`AppViewModel.onCleared`），主线程被占住一小会儿
     * 是可以接受的 —— 换来的是「用户刚点的那一下收藏」不会因为拆除而丢。
     * 真正不能接受的是不落盘：丢的是用户看得见的东西。
     *
     * 读盘还没回来之前**不写**（见 [loaded]）：内存这份还是空的，写下去就是清空用户的收藏。
     * 这种情况下 close 没什么可保的 —— 盘上那份还在，没有改动会丢。
     */
    private fun flushNow() {
        if (!loaded) return
        runCatching {
            runBlocking {
                context.watchStore.edit {
                    // 和 [snapshot] 同一道保险：盘上那份解不通就先挪进备份键，再写新的。
                    // 这一份是「关仓库时最后写的」—— 它要是把一份解不通的旧 blob 直接盖掉，
                    // 用户那份原文就彻底没了（备份键是它唯一的去处）。
                    it[KEY_FAV]?.takeIf { raw -> raw.isNotBlank() && !readable<Favorite>(raw) }
                        ?.let { raw -> it[KEY_FAV_BAK] = raw }
                    it[KEY_HIS]?.takeIf { raw -> raw.isNotBlank() && !readable<HistoryEntry>(raw) }
                        ?.let { raw -> it[KEY_HIS_BAK] = raw }
                    it[KEY_FAV] = json.encodeToString(_favorites.value)
                    it[KEY_HIS] = json.encodeToString(_history.value)
                    it[KEY_FAV_SORT] = _favoriteSort.value
                }
            }
        }
    }

    /**
     * 启动那一次读盘，整套放在 `edit` 的变换里。
     *
     * 原来是 `data.first()` 读完再在内存里拼 —— 中间那几十毫秒没有任何东西挡着：
     * 用户冷启动就点收藏、或者进「最近观看」直接清空，改的都在内存里，
     * 而 load 手里还是读盘那一刻的旧 blob，拼完写回就把他刚做的操作复活成原样。
     * DataStore 的变换是串行的，读—合—写落在同一个事务里就不会有这件事。
     *
     * 解不通的那一份**不回写**：留着原文（并挪一份进备份键），内存里保持空，
     * 界面上用 [unreadable] 说清楚，而不是让下一次保存坐实成「用户自己删光的」。
     */
    private suspend fun load() = runCatching {
        context.watchStore.edit { p ->
            val favRaw = p[KEY_FAV]
            val hisRaw = p[KEY_HIS]
            // 排序选择是个独立的偏好，和上面那份「读不通就整份作废」的清单无关：
            // 收藏 blob 坏了也不该把用户挑好的排序丢掉。读盘期间用户已点过一档时，
            // 新的那一档说了算（见 [sortLoaded]）。
            if (!sortLoaded) {
                _favoriteSort.value = p[KEY_FAV_SORT]?.takeIf { it in 0..2 } ?: DEFAULT_FAV_SORT
            }
            p[KEY_FAV_SORT] = _favoriteSort.value
            sortLoaded = true
            val favs = favRaw?.takeIf { it.isNotBlank() }?.let { decodeIt<Favorite>(it) }
            val his = hisRaw?.takeIf { it.isNotBlank() }?.let { decodeIt<HistoryEntry>(it) }
            val favBad = !favRaw.isNullOrBlank() && favs == null
            val hisBad = !hisRaw.isNullOrBlank() && his == null
            _unreadable.value = favBad || hisBad
            if (favBad) p[KEY_FAV_BAK] = favRaw.orEmpty()
            if (hisBad) p[KEY_HIS_BAK] = hisRaw.orEmpty()

            val nextFavs = (_favorites.value + (favs ?: emptyList())).distinctBy { it.key }
            val nextHis = (_history.value + (his ?: emptyList()))
                .distinctBy { it.key }
                .sortedByDescending { it.updatedAt }
                .take(MAX_HISTORY)
            _favorites.value = nextFavs
            _history.value = nextHis
            if (!favBad) p[KEY_FAV] = json.encodeToString(nextFavs)
            if (!hisBad) p[KEY_HIS] = json.encodeToString(nextHis)
        }
        // 读完了才允许落盘（见 [loaded]）。这一趟本身已经把合并结果写进事务了，
        // 所以这里不必再补一次 —— 但用户可能在读盘途中点过收藏，那一笔卡在内存里，
        // 得靠一次落盘补上（那时 loaded 已为真，save() 才会真的写）。
        loaded = true
        if (_favorites.value.isNotEmpty() || _history.value.isNotEmpty()) save()
    }

    private inline fun <reified T> decodeIt(raw: String): List<T>? =
        runCatching { json.decodeFromString<List<T>>(raw) }.getOrNull()

    private inline fun <reified T> readable(raw: String): Boolean = decodeIt<T>(raw) != null

    private companion object {
        const val MAX_HISTORY = 100

        /** 排序默认档：最近加入。和收藏页第一颗 SortChip 同一个顺序。 */
        const val DEFAULT_FAV_SORT = 0
    }
}
