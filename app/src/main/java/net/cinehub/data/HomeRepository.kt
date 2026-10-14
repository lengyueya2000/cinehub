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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.Media

private val Context.homeStore by preferencesDataStore(name = "cinehub_home")

/**
 * 首页那一页内容的快照。
 *
 * 首页取的是「优先级最高的、真给了内容的那个源」的那一页，一次拉取要打对方站点，
 * 而用户切回首页的频率远高于内容变化的频率 —— 每次进来都重拉，既慢又是在反复敲别人的门。
 * 所以这里落一份盘：冷启动先上屏上次那份，只有过期了、或者源的顺序/启停变了、
 * 或者用户自己点刷新，才再去取。
 *
 * [fingerprint] 存的是启用源的 `id:priority` 串（不哈希，出问题时要一眼看得出是哪一步变了）。
 * 它一变就说明「首页该由哪个源来填」这件事变了，那时不等 12 小时，立刻重取。
 */
class HomeRepository(private val context: Context) {

    /** 一份首页快照。[at] 是取回的时刻（毫秒）。 */
    @Serializable
    data class Snapshot(
        val at: Long,
        val sourceId: String,
        val fingerprint: String,
        val items: List<Entry>,
    ) {
        /**
         * 落盘用的条目。
         *
         * 不复用 [Media]：那是领域模型，不该为了存一次快照去挂序列化注解；
         * 这里只留首页真正会显示的几样。
         */
        @Serializable
        data class Entry(
            val id: String,
            val title: String,
            val cover: String? = null,
            val year: String? = null,
            val category: String? = null,
            val remark: String? = null,
            val score: String? = null,
            val area: String? = null,
            val sourceId: String,
        ) {
            /** 快照里的条目 → 领域模型。首页显示的就是它。 */
            fun toMedia(): Media = Media(
                id = id,
                title = title,
                cover = cover,
                year = year,
                category = category,
                remark = remark,
                score = score,
                area = area,
                sourceId = sourceId,
            )
        }
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY = stringPreferencesKey("home_v1")

    @Volatile
    private var cached: Snapshot? = null

    private val _ready = MutableStateFlow(false)

    /**
     * 落盘那份读完了没有。
     *
     * 冷启动时 VM 得等它读完再决定要不要联网 —— 不等就会把「还没读到」当成「没有快照」，
     * 白拉一次，而这一条存在的意义正是省掉那一次。
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 上次那份快照；还没有（首次安装，或盘上那份读不通）时 null。 */
    fun snapshot(): Snapshot? = cached

    /** 该不该重新去取。源的顺序/启停变了，或已超过 [maxAgeMs]，或压根还没有快照。 */
    fun staleFor(fingerprint: String, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        val s = cached ?: return true
        return s.fingerprint != fingerprint || System.currentTimeMillis() - s.at > maxAgeMs
    }

    init {
        scope.launch { load() }
    }

    suspend fun save(items: List<Media>, sourceId: String, fingerprint: String) {
        val snap = Snapshot(
            at = System.currentTimeMillis(),
            sourceId = sourceId,
            fingerprint = fingerprint,
            items = items.map { entryOf(it) },
        )
        cached = snap
        _ready.value = true
        val text = json.encodeToString(snap)
        runCatching { context.homeStore.edit { it[KEY] = text } }
    }

    /**
     * 读盘上那份。
     *
     * 整条路都允许失败（DataStore 读不动、那份是半个 JSON、版本对不上），
     * 但 [_ready] 必须在 finally 里翻转：调用方是「等读完再决定要不要联网」，
     * 而读失败时「没有快照」正是它要的那个答案 —— 闩锁卡住，首页就永远停在空白，
     * 连一个「点一下重试」的入口都出不来。
     */
    private suspend fun load() {
        try {
            val raw = runCatching { context.homeStore.data.map { it[KEY] }.first() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                cached = runCatching { json.decodeFromString<Snapshot>(raw) }.getOrNull()
            }
        } finally {
            _ready.value = true
        }
    }

    companion object {
        /** 用户要的那个「12 小时」。 */
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}

private fun entryOf(m: Media) = HomeRepository.Snapshot.Entry(
    id = m.id,
    title = m.title,
    cover = m.cover,
    year = m.year,
    category = m.category,
    remark = m.remark,
    score = m.score,
    area = m.area,
    sourceId = m.sourceId,
)
