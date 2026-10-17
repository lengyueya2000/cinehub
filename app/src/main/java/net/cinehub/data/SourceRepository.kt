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

    /**
     * 这些内置源的 id **这个安装见过**。
     *
     * 「见过」和「在列表里」是两件事，而补新源必须认前者：认后者的话，用户自己删掉的那几条
     * 会在每次升级时被偷偷加回来一次。
     */
    private val SEEN_KEY = stringPreferencesKey("seen_seed_ids")
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
     *
     * 变换同时拿到「事务里那份列表」和「这批内置源见过没有」，因为 [syncNewSeeds] 要拿后者
     * 判；「见过」也在这同一笔事务里刷新 —— 分开写就有个窗口：上一笔还没落、这一笔按旧的
     * 那份见过判，新源会被补第二次。
     */
    private suspend fun mutate(f: (List<SourceConfig>, Set<String>) -> List<SourceConfig>) {
        context.dataStore.edit { p ->
            val raw = p[KEY]
            val (stored, unreadable) = decode(raw)
            val prev = stored ?: lastGood ?: Seed.sources
            val seen = p[SEEN_KEY]?.split(SEEN_SEP)?.filter { it.isNotBlank() }?.toSet().orEmpty()
            // 排完之后统一重编成 1..n。
            //
            // 属性名是 JSON 键、值是用户看到的顺序，重复的 priority 会让「谁排前面」
            // 取决于排序算法的稳定性， restoreDefaults 补回来的内置源尤其容易和现有条目撞号。
            // 顺手把整列压成连续整数，读写两边就都只有一种解释。
            val next = f(prev.filterNot { it.id in Seed.retiredIds }, seen)
                .sortedBy { it.priority }
                .mapIndexed { i, s -> s.copy(priority = i + 1) }
            // 覆盖前先把读不通的那份存档：这一版解析不了它，就得保证它还在盘上，
            // 而不是被「看起来正常」的新列表就地替换掉。
            if (unreadable && !raw.isNullOrBlank()) p[BACKUP_KEY] = raw
            p[KEY] = json.encodeToString(next)
            p[SEEN_KEY] = Seed.sources.joinToString(SEEN_SEP.toString()) { it.id }
            lastGood = next
        }
    }

    suspend fun upsert(cfg: SourceConfig) = mutate { cur, _ ->
        val i = cur.indexOfFirst { it.id == cfg.id }
        if (i >= 0) cur.toMutableList().also { it[i] = cfg } else cur + cfg
    }

    suspend fun delete(id: String) = mutate { cur, _ -> cur.filterNot { s -> s.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { l, _ ->
        l.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /**
     * 按界面上的新顺序重排。
     *
     * 没出现在 [ids] 里的源要留在末尾，不能顺手删掉：拖拽那一刻正好有另一笔写落了新源
     * （补回内置源、存好一个新源），UI 手里那份顺序就已经少了它 —— 照原样「只保留传进来的」
     * 写回去，用户加的那个源就凭空消失了，而界面上他刚刚只是在挪位置。
     */
    suspend fun reorder(ids: List<String>) = mutate { cur, _ ->
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
        mutate { cur, _ ->
            val have = cur.map { it.id }.toSet()
            val missing = Seed.sources.filter { it.id !in have }
            added = missing.size
            // 缺的补到队尾，不打乱用户已有的排序。
            val tail = cur.maxOfOrNull { it.priority } ?: 0
            cur + missing.mapIndexed { i, s -> s.copy(priority = tail + i + 1) }
        }
        return added
    }

    /**
     * 老安装一次性迁移：已存的 `minoself` 挪到第一位，其余位次保持相对顺序。
     *
     * 背景：这一版之前 minoself 是后补进来的，`syncNewSeeds` 按约定只补到队尾，
     * 老用户（包括 aaa 模拟器上那份）手里它是最后一位；但它现在是默认线路。
     * 只有在新种子顺序已经把它写成第一（`Seed` 里 priority == 1）时才做，
     * 以后种子顺序再变也不会重复挪。
     */
    suspend fun promoteSeedToFront(seedId: String): Boolean {
        var moved = false
        mutate { cur, _ ->
            val idx = cur.indexOfFirst { it.id == seedId }
            if (idx <= 0) return@mutate cur
            val keepOrder = (cur.take(idx) + cur.drop(idx + 1)).sortedBy { it.priority }
            moved = true
            listOf(cur[idx].copy(priority = 0)) + keepOrder
        }
        return moved
    }

    /**
     * 老安装一次性补标：盘里已存的那几个内置源盖上 `hiddenUrl`。
     *
     * 新字段是后加的，老用户 DataStore 里反序列化出来的那四份全是 false ——
     * 而它们正是要藏地址的那几份。只按 id 认种子源，用户自己加的（id 形如 src_xxx）碰不到。
     * 已经标过的再走一遍是空操作，顺手幂等。
     */
    suspend fun markBuiltinHidden(): Boolean {
        var touched = false
        mutate { cur, _ ->
            val hiddenIds = Seed.sources.filter { it.hiddenUrl }.map { it.id }.toSet()
            cur.map {
                if (it.id in hiddenIds && !it.hiddenUrl) {
                    touched = true
                    it.copy(hiddenUrl = true)
                } else it
            }
        }
        return touched
    }

    /**
     * 升级带进来的新内置源，自己长进列表里 —— 不用用户去「播放源管理」点补回。
     *
     * 判据是「这个 id 这个安装见过没有」，不是「它在不在列表里」。这个区别就是全部：
     * 认后者的话，用户自己删掉的每一条都会在下次升级时被偷偷加回来一次。
     *
     * **第一次走到这条的老安装一个都不补**，只把当前这批记成见过。因为那时候还没有
     * 「见过」这份账，列表里缺哪几条无从分辨是他删的还是版本没带 —— 宁可少补一次
     * （他还能点补回），也不能多补一次（那是替他做决定）。
     *
     * 要在应用启动时就调，别排在任何一次用户改动之后：[mutate] 每一笔都会刷新那份「见过」，
     * 先落了别的写，这一版的新源就被记成见过了、反而补不进来。
     */
    suspend fun syncNewSeeds(): List<SourceConfig> {
        var added: List<SourceConfig> = emptyList()
        mutate { cur, seen ->
            val have = cur.map { it.id }.toSet()
            val fresh = if (seen.isEmpty()) emptyList() else
                Seed.sources.filter { it.id !in seen && it.id !in have }
            // 一律补到队尾。原来直接带着 Seed 里写死的那个 priority 进来：mutate 之后是按
            // priority 排序的，于是一条 priority=1 的新源会把用户拖出来的第一名挤下去 ——
            // 而第一名就是进播放器时默认那个源，那是替他做决定。
            val tail = cur.maxOfOrNull { it.priority } ?: 0
            added = fresh.mapIndexed { i, s -> s.copy(priority = tail + i + 1) }
            cur + added
        }
        return added
    }

    private companion object {
        const val SEEN_SEP = '\u0001'
    }
}

/** 初始源。 */
object Seed {
    val sources: List<SourceConfig> = listOf(
        SourceConfig(
            id = "minoself",
            name = "MINO自建",
            // 自有 / 有权限的 Apple-CMS 后端：内容全在 /api/... 下，站点根路径本来就回 404。
            // 于是健康栏改探一个「服务活着」的接口点，而不是注定的 404（否则这一个源
            // 在列表里永远是红字，而它其实三条线路都出得来流）。
            baseUrl = "http://43.248.128.122:578",
            kind = net.cinehub.data.model.SourceKind.MinoApi,
            priority = 1,
            tags = listOf("多条线路", "含 4K"),
            probePath = "/api/v1/app/tabs",
            hiddenUrl = true,
        ),
        SourceConfig(
            id = "luttt",
            name = "北觅影视",
            baseUrl = "https://v.luttt.com",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 2,
            tags = listOf("4K 画质", "直链可播"),
            html = net.cinehub.data.source.DefaultRules.maccms,
            hiddenUrl = true,
        ),
        SourceConfig(
            id = "k4ku",
            name = "4kku",
            baseUrl = "https://www.4kku.com",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 3,
            tags = listOf("4K 画质", "三条线路"),
            html = net.cinehub.data.source.DefaultRules.tailwind,
            hiddenUrl = true,
        ),
        SourceConfig(
            id = "dongpiandi",
            name = "懂片帝AI",
            baseUrl = "https://dongpiandi.ai",
            kind = net.cinehub.data.model.SourceKind.AiMovie,
            priority = 4,
            tags = listOf("1080P", "AI 找片"),
            hiddenUrl = true,
        ),
        SourceConfig(
            // 这个 id 是新的，**不是**当年那个 "xiguazx"：那个已经进了下面的 retiredIds，
            // 而 retiredIds 是在每次 mutate 时按 id 过滤的 —— 复用同一个号会让这一条
            // 每次落盘都被静默删掉，界面上看着「加了又没了」，且一点报错都没有。
            id = "xiguavod",
            name = "西瓜影视",
            baseUrl = "https://xiguazx.cc",
            kind = net.cinehub.data.model.SourceKind.JsonApi,
            priority = 5,
            // 只写实测站得住的两条：片库量是接口自己报的 total（搜「凤凰」55 条、
            // 整站数万条），线路数是同一部片详情里 vod_play_from 切出来的条数。
            // 不写「全线路可播」—— 实测五条线路里 mgtv 与 youku 那两条解析器解不出来。
            tags = listOf("10 万+ 片库", "五条线路"),
            api = net.cinehub.data.model.ApiRules(
                // 接口里存的是各家平台的播放页，要再过一道麒麟解析器才能播
                // （见 QilinResolver）。浏览、搜索、详情仍是标准苹果 CMS，不用改。
                resolveMode = net.cinehub.data.model.ApiRules.MODE_QILIN,
            ),
            hiddenUrl = true,
        ),
    )

    /**
     * 曾经内置、后来撤掉的源。
     *
     * 只从 [sources] 里删掉是不够的：老用户列表里那几条是启动时写进去的，会一直留着。
     * 按 id 清掉它们，用户自己手动加的同类源（id 形如 `src_xxx`）不受影响。
     */
    val retiredIds = setOf("k4vm", "xiguazx", "demo_m3u", "hqvod", "freeokk")
}
