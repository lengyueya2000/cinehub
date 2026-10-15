package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.SourceConfig

private val Context.dataStore by preferencesDataStore(name = "cinehub")

/**
 * 播放源持久化。
 *
 * 内置的初始源见 [Seed]，用户可以改、可以删、也可以自己加。
 */
class SourceRepository(private val context: Context) {

    private val KEY = stringPreferencesKey("sources_v1")
    private val BACKUP_KEY = stringPreferencesKey("sources_v1_unreadable")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 上一次真读通了的列表。读不通时用它兜，见 [sources]。 */
    @Volatile
    private var lastGood: List<SourceConfig>? = null

    /**
     * 落盘那份读不通了（内容在，但这一版解析不了）。
     *
     * 必须和「压根没配过」分开：两者都回落到内置源的话，界面上看着一切正常，
     * 而用户下一次任何保存都会把自己加的源整个冲掉。
     */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /** 盘上那份解出来的列表；第二个成员是「有内容但这一版解不通」。 */
    private fun decode(raw: String?): Pair<List<SourceConfig>?, Boolean> {
        if (raw.isNullOrBlank()) return null to false
        val list = runCatching { json.decodeFromString<List<SourceConfig>>(raw) }.getOrNull()
        return list to (list == null)
    }

    val sources: Flow<List<SourceConfig>> = context.dataStore.data.map { p ->
        val (stored, unreadable) = decode(p[KEY])
        _unreadable.value = unreadable
        // 已经撤掉的内置源不能因为「老用户的列表里还留着这一条」就继续冒出来；
        // 用户自己加的源 id 形如 src_xxx，落不到 retiredIds 里，不受影响。
        val list = (stored ?: lastGood ?: Seed.sources).filterNot { it.id in Seed.retiredIds }
        if (stored != null) lastGood = list
        list
    }

    /** 按优先级排序、且已启用的源。 */
    suspend fun active(): List<SourceConfig> =
        sources.first().filter { it.enabled }.sortedBy { it.priority }

    suspend fun all(): List<SourceConfig> = sources.first().sortedBy { it.priority }

    /**
     * 读—改—写整套放进 DataStore 的事务里。
     *
     * 以前是「先 `sources.first()` 读一份 → 在内存里改 → 整份覆盖写回去」，
     * 三步之间没有任何东西挡着：连着拨两个开关、删除撞上拖拽排序、补回内置源撞上存新源，
     * 后落地的那一笔都会带着它自己那份**旧**快照，把前一笔刚写进去的结果整个盖掉。
     * 界面上就是「我明明删了它还在」「排好的顺序过一会儿自己变回去」，
     * 而盘上确实被旧数据覆盖了 —— 不是显示没刷新。
     *
     * `edit { }` 里的变换由 DataStore 串行执行，读和写看到的是同一份，
     * 所以改的起点是事务里那份，不是调用方手里那份。
     */
    private suspend fun mutate(f: (List<SourceConfig>) -> List<SourceConfig>) {
        context.dataStore.edit { p ->
            val raw = p[KEY]
            val (stored, unreadable) = decode(raw)
            val prev = stored ?: lastGood ?: Seed.sources
            // 排完之后统一重编成 1..n。
            //
            // 属性名是 JSON 键、值是用户看到的顺序，重复的 priority 会让「谁排前面」
            // 取决于排序算法的稳定性， restoreDefaults 补回来的内置源尤其容易和现有条目撞号。
            // 顺手把整列压成连续整数，读写两边就都只有一种解释。
            val next = f(prev.filterNot { it.id in Seed.retiredIds })
                .sortedBy { it.priority }
                .mapIndexed { i, s -> s.copy(priority = i + 1) }
            // 覆盖前先把读不通的那份存档：这一版解析不了它，就得保证它还在盘上，
            // 而不是被「看起来正常」的新列表就地替换掉。
            if (unreadable && !raw.isNullOrBlank()) p[BACKUP_KEY] = raw
            p[KEY] = json.encodeToString(next)
            lastGood = next
        }
    }

    suspend fun upsert(cfg: SourceConfig) = mutate { cur ->
        val i = cur.indexOfFirst { it.id == cfg.id }
        if (i >= 0) cur.toMutableList().also { it[i] = cfg } else cur + cfg
    }

    suspend fun delete(id: String) = mutate { it.filterNot { s -> s.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { l ->
        l.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /**
     * 按界面上的新顺序重排。
     *
     * 没出现在 [ids] 里的源要留在末尾，不能顺手删掉：拖拽那一刻正好有另一笔写落了新源
     * （补回内置源、存好一个新源），UI 手里那份顺序就已经少了它 —— 照原样「只保留传进来的」
     * 写回去，用户加的那个源就凭空消失了，而界面上他刚刚只是在挪位置。
     */
    suspend fun reorder(ids: List<String>) = mutate { cur ->
        val byId = cur.associateBy { it.id }
        val moved = ids.toSet()
        // 新位置要写成新号：mutate 之后按 priority 排，只换列表顺序不换号等于什么都没改。
        (ids.mapNotNull { byId[it] } + cur.filterNot { it.id in moved })
            .mapIndexed { i, s -> s.copy(priority = i + 1) }
    }

    /**
     * 恢复内置源，但保留用户自己加的源。
     *
     * 用户手滑删掉内置源后不该只能重装 —— 这里按 id 补回缺失的内置源，
     * 已经是同一个 id 的条目保持原样（含用户的启停与排序）。
     * 返回补回了几个，交给 UI 说清楚发生了什么。
     */
    suspend fun restoreDefaults(): Int {
        var added = 0
        mutate { cur ->
            val have = cur.map { it.id }.toSet()
            val missing = Seed.sources.filter { it.id !in have }
            added = missing.size
            cur + missing
        }
        return added
    }
}

/** 初始源。 */
object Seed {
    val sources: List<SourceConfig> = listOf(
        SourceConfig(
            id = "luttt",
            name = "北觅影视",
            baseUrl = "https://v.luttt.com",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 1,
            tags = listOf("4K 画质", "直链可播"),
            html = net.cinehub.data.source.DefaultRules.maccms,
        ),
        SourceConfig(
            id = "k4ku",
            name = "4kku",
            baseUrl = "https://www.4kku.com",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 2,
            tags = listOf("4K 画质", "三条线路"),
            html = net.cinehub.data.source.DefaultRules.tailwind,
        ),
        SourceConfig(
            id = "dongpiandi",
            name = "懂片帝AI",
            baseUrl = "https://dongpiandi.ai",
            kind = net.cinehub.data.model.SourceKind.AiMovie,
            priority = 3,
            tags = listOf("1080P", "AI 找片"),
        ),
        SourceConfig(
            id = "demo_m3u",
            name = "演示源",
            baseUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            kind = net.cinehub.data.model.SourceKind.M3u,
            priority = 4,
            tags = listOf("公开测试流", "可验证播放"),
        ),
    )

    /**
     * 曾经内置、后来撤掉的源。
     *
     * 只从 [sources] 里删掉是不够的：老用户列表里那两条是启动时写进去的，会一直留着。
     * 按 id 清掉它们，用户自己手动加的同类源（id 形如 `src_xxx`）不受影响。
     */
    val retiredIds = setOf("k4vm", "xiguazx")
}
