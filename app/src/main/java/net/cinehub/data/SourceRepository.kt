package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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
 * 内置源见 [Seed]：**跟着版本走**，每次启动按 [SourceRepository.syncSeeds] 同步一次。
 * 用户自己添加的源是另一回事，任何一条同步路径都不碰它。
 */
class SourceRepository(private val context: Context) {

    private val KEY = stringPreferencesKey("sources_v1")
    private val BACKUP_KEY = stringPreferencesKey("sources_v1_unreadable")

    // 上一版在这里还有一份 `seen_seed_ids`：老规矩「只补这个安装没见过的内置源」要靠它分辨
    // 缺的那几条是用户删的还是版本没带。这一版内置源直接跟版本走，那份账不再参与判断。
    // 键就此作废、不去 remove 它：对每个老安装删一格等于多一次无谓的写，而留着不影响读取。

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
     * 三步之间没有任何东西挡着：连着拨两个开关、删除撞上拖拽排序、同步内置源撞上存新源，
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
            commit(p, raw, unreadable, f(prev.filterNot { it.id in Seed.retiredIds }))
        }
    }

    /**
     * 把算好的列表写回去：重编号 → 备份读不通的那份 → 落盘 → 刷新内存副本。
     *
     * 重编号的理由：属性名是 JSON 键、值是用户看到的顺序，重复的 priority 会让「谁排前面」
     * 取决于排序算法的稳定性，同步进来的内置源尤其容易和现有条目撞号。
     * 顺手把整列压成连续整数，读写两边就都只有一种解释。
     *
     * 备份的理由：这一版解析不了盘上那份，就得保证它还在盘上，
     * 而不是被「看起来正常」的新列表就地替换掉。
     */
    private fun commit(
        p: MutablePreferences,
        raw: String?,
        unreadable: Boolean,
        next: List<SourceConfig>,
    ) {
        val numbered = next.sortedBy { it.priority }.mapIndexed { i, s -> s.copy(priority = i + 1) }
        if (unreadable && !raw.isNullOrBlank()) p[BACKUP_KEY] = raw
        p[KEY] = json.encodeToString(numbered)
        lastGood = numbered
    }

    suspend fun upsert(cfg: SourceConfig) = mutate { cur ->
        val i = cur.indexOfFirst { it.id == cfg.id }
        if (i >= 0) cur.toMutableList().also { it[i] = cfg } else cur + cfg
    }

    suspend fun delete(id: String) = mutate { cur -> cur.filterNot { s -> s.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { l ->
        l.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /**
     * 按界面上的新顺序重排。
     *
     * 没出现在 [ids] 里的源要留在末尾，不能顺手删掉：拖拽那一刻正好有另一笔写落了新源
     * （同步内置源、存好一个新源），UI 手里那份顺序就已经少了它 —— 照原样「只保留传进来的」
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
     * 内置源跟版本走：升级之后按这一版 [Seed] 同步，**用户自己添加的源一条都不动**。
     *
     * 这一句同时管着过去分开做的三件事，它们在这版并成一条路：
     * - 补进新版本带来的内置源（原 `syncNewSeeds`）；
     * - 用户删掉的内置源按 id 补回（原 `restoreDefaults`，还要他自己去点按钮）；
     * - 给老安装的内置源补上后加的新字段（原 `markBuiltinHidden`）。
     * 理由是内置源不是「用户的一份可改配置」，而是产品自带的线路：版本改了地址、
     * 换了规则、撤了某一条，都该当场生效；靠用户点按钮才回来，或者靠一次专门的迁移，
     * 都是把版本该负责的事推给他。
     *
     * **覆盖只覆盖内容**：地址、接入方式、类型、标签、探点、解析规则、要不要藏地址。
     * **保留的是用户在这一条上做的决定**：启停开关、他拖出来的位次、他自己填的站点凭证、
     * 这条源是哪天加的。
     * 把启停一起覆盖，等于每次升级把用户关掉的线路悄悄打开；把位次一起覆盖，等于
     * 新版本写的第一名自动成为默认线路（priority == 1 就是界面上那颗「默认」芯片，
     * 也是进播放器时挑的那一条），那是替他做决定。
     *
     * 新补进来的内置源排到队尾（按 [Seed.sources] 里的相对顺序），不带着种子里写死的
     * 名次挤进第一格。要在应用启动时调；顺序上排在任何一次用户改动之前。
     *
     * 原来还有一句 `promoteSeedToFront("minoself")`，专给老安装把默认线路挪回第一位。
     * 这一版**不再挪**：位次是用户拖出来的，同步一次都不重排；默认线路只在全新安装时
     * 由种子里的 priority 决定（minoself 本来就是 1）。
     */
    suspend fun syncSeeds(): SeedSync {
        var out = SeedSync(sources = emptyList(), added = 0, updated = 0, removed = 0, fresh = emptyList())
        // 这一笔不走 [mutate]：那个入口会先把撤下的内置源过滤掉，
        // 于是「这次撤下了 N 个」永远数出 0，界面上那句话就成了死代码。
        // 这里要看到**过滤之前**那份，才能如实报出「少了哪几条」。
        context.dataStore.edit { p ->
            val raw = p[KEY]
            val (stored, unreadable) = decode(raw)
            val prev = stored ?: lastGood ?: Seed.sources
            val merged = mergeSeeds(prev, Seed.sources, Seed.retiredIds)
            out = merged
            if (shouldWriteSync(merged, hadStored = stored != null, unreadable = unreadable)) {
                commit(p, raw, unreadable, merged.sources)
            }
        }
        return out
    }
}

/**
 * 这一趟同步要不要写盘。抽成纯函数是为了能测 —— 写多写少都不出声：
 * 写多了会让 `sources` 这条流重新发一遍（列表整屏重组），而带来的信息是零；
 * 写少了更糟，见下面「读不通」那一支。
 *
 * - **读不通时一个字都不写。** 原来这里把「盘上读不通」也算成「要写」，理由是
 *   `commit` 会顺手把原文存进 `BACKUP_KEY`。存档那一步确实对，但 `commit` 还会
 *   把主键换成种子 —— 于是下一次 `sources` 解码成功，`unreadable` 归零，
 *   界面上那段「本机存的源列表这一版读不通，原来那份已另存了一份」的警示**自己消失了**，
 *   而用户自己加的源已经从列表里消失。备份键全仓没有一处读，等于用户拿不回来。
 *   所以读不通就整笔不碰：主键保持原样（警示横幅继续挂着），
 *   备份留到用户下一次真的改动时由 `commit` 照常写入 —— 那条路上 `unreadable` 还在。
 *   `DownloadRepository` 那边同一条规矩：`if (!unreadable && merged != stored) p[KEY] = ...`。
 * - 其余情况只有「真动了」才写；从没存过则一定要落一次，那是初始列表。
 */
internal fun shouldWriteSync(merged: SeedSync, hadStored: Boolean, unreadable: Boolean): Boolean =
    !unreadable && (
        merged.added > 0 || merged.updated > 0 || merged.removed > 0 || !hadStored
        )

/**
 * 一次内置源同步的结果，也是 [mergeSeeds] 的返回值 —— 两处用同一个类型。
 *
 * 界面上那句话只读四个计数；[sources] 是并好的整份列表，[SourceRepository.syncSeeds]
 * 要把它落盘。合成一个类型而不是「规则返回一份、仓储再抄成另一份」：
 * 两套字段名一样的类型摆在一起，早晚会有一边加了字段另一边忘了抄。
 *
 * [added] 这次新补进来的条数（包括被删掉又回来的）；[updated] 内容按新版重写过的条数；
 * [removed] 按 [Seed.retiredIds] 撤掉的条数，[dropped] 就是撤掉的那几条（报名字用）；
 * [fresh] 新补进来的那几条。
 */
data class SeedSync(
    val sources: List<SourceConfig>,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val fresh: List<SourceConfig>,
    val dropped: List<SourceConfig> = emptyList(),
)

/**
 * [SourceRepository.syncSeeds] 的那套并表规则，抽成纯函数是为了能直接测：
 * 「用户自己加的不能被动」和「内置的要跟着版本变」这两件事必须同时成立，
 * 而它们原来只有真机跑一次升级才看得见。
 *
 * id 落在 [seeds] 里的就是内置源，内容整份换成种子那一份（身份那几样除外）；
 * 落在 [retired] 里的整条剔掉；其余条目原样留着，一个字段都不改。
 */
internal fun mergeSeeds(
    stored: List<SourceConfig>,
    seeds: List<SourceConfig>,
    retired: Set<String>,
): SeedSync {
    val seedById = seeds.associateBy { it.id }
    val dropped = stored.filter { it.id in retired }
    val kept = stored.filterNot { it.id in retired }
    val removed = dropped.size

    var updated = 0
    val rewritten = kept.map { s ->
        val seed = seedById[s.id] ?: return@map s
        val next = seed.copy(
            // 用户在这一条上做的决定，一律按盘上那份留着
            enabled = s.enabled,
            priority = s.priority,
            createdAt = s.createdAt,
            // 凭证是他自己的账号，同步**一个字都不碰**：既不该抹掉，也不该由种子塞一份进来。
            // 原来这里写的是 `s.authToken ?: seed.authToken`（他没填过就用种子里那份），
            // 那会让一次升级悄悄把这条从匿名请求变成带凭证请求，而 updated 照样数出 0
            // —— 行为变了、界面上一个字没说。何况 SourceConfig.authToken 的定义就是
            // 「用户自己提供的站点会话凭证」，种子那一份本来就不该存在。
            authToken = s.authToken,
            authMode = s.authMode,
        )
        // 只比内容：上面那四样是从盘上抄来的，本来就该相等；真相等就是这一条没变过。
        if (differentContent(next, s)) updated++
        next
    }

    val have = kept.mapTo(HashSet()) { it.id }
    // 一律补到队尾。原来直接带着 Seed 里写死的那个 priority 进来：mutate 之后是按
    // priority 排序的，于是一条 priority=1 的新源会把用户拖出来的第一名挤下去 ——
    // 而第一名就是进播放器时默认那个源，那是替他做决定。
    val tail = rewritten.maxOfOrNull { it.priority } ?: 0
    val fresh = seeds.filter { it.id !in have && it.id !in retired }
        .mapIndexed { i, s -> s.copy(priority = tail + i + 1) }

    return SeedSync(
        sources = rewritten + fresh,
        added = fresh.size,
        updated = updated,
        removed = removed,
        fresh = fresh,
        dropped = dropped,
    )
}

/**
 * 两条配置的内容是否不同 —— 只看会被版本覆盖的那几样。
 *
 * 不能直接 `next != s`：两边共享的启停 / 位次 / createdAt / 凭证是从盘上抄过去的，
 * 照整份比本来也相等，但「哪些字段算内容」必须写死在这里，因为它和 [mergeSeeds] 里
 * 那份 `copy` 是一对：那边多抄一格、这边少比一格，就会出现「内容被静默覆盖、
 * 界面上却说没动过」。加新的内容字段时，这里和 `MergeSeedsTest` 的清单要一起改。
 */
private fun differentContent(a: SourceConfig, b: SourceConfig): Boolean =
    a.baseUrl != b.baseUrl ||
        a.name != b.name ||
        a.kind != b.kind ||
        a.tags != b.tags ||
        a.html != b.html ||
        a.api != b.api ||
        a.probePath != b.probePath ||
        a.hiddenUrl != b.hiddenUrl ||
        a.needsBrowser != b.needsBrowser ||
        a.playPath != b.playPath

/**
 * 内置源（产品自带的那几条线路）。
 *
 * 这份列表**就是**当前版本的内置源集合：应用每次启动按 [mergeSeeds] 把它同步进用户的列表
 * —— 缺的补回来、内容覆盖成这一份、[retiredIds] 里的清掉。所以改这里等于改「下一版装上去
 * 长什么样」，而不是改某个默认值。
 *
 * 用户自己添加的源不走这条路：它们的 id 形如 `src_xxx`，永远不在 [sources] / [retiredIds] 里。
 */
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
