package net.cinehub.data

import net.cinehub.data.model.ApiRules
import net.cinehub.data.model.AuthMode
import net.cinehub.data.model.HtmlRules
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 升级时内置源怎么跟版本走 —— [mergeSeeds] 的规矩。
 *
 * 三件必须同时成立的事，任一件错了用户都会掉源：
 *
 * 1. **用户自己加的一条都不动**（连字段都不许改）—— 覆盖是冲内置源来的。
 * 2. **内置源的内容按新版重写，但启停和排序保留** ——
 *    一起覆盖等于每次升级把用户关掉的线路悄悄打开、把他拖出来的名次抹平。
 * 3. **删掉的内置源自动补回**，并且补到队尾 ——
 *    带着种子写死的名次进来会把用户的第一名（进播放器时的默认源）挤下去。
 *
 * 这条路只有真机跑一次升级才看得见，所以规则本身必须是纯函数、能直接测。
 */
class MergeSeedsTest {

    private val seedNew = SourceConfig(
        id = "luttt",
        name = "北觅影视",
        baseUrl = "https://v.luttt.com",
        kind = net.cinehub.data.model.SourceKind.Html,
        priority = 2,
        tags = listOf("4K 画质"),
    )

    private val seedOther = SourceConfig(
        id = "k4ku",
        name = "4kku",
        baseUrl = "https://www.4kku.com",
        kind = net.cinehub.data.model.SourceKind.Html,
        priority = 3,
        tags = listOf("4K 画质"),
    )

    private val retired = setOf("hqvod")

    /** 用户手里那版：内容还是旧的，但两个开关和位次是他自己的。 */
    private val stored = listOf(
        SourceConfig(
            id = "minoself",
            name = "MINO自建",
            baseUrl = "http://old.example:578",
            kind = net.cinehub.data.model.SourceKind.MinoApi,
            priority = 1,
            authToken = "user-token",
        ),
        SourceConfig(
            id = "luttt",
            name = "北觅影视·旧域名",
            baseUrl = "https://old.luttt.example",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 2,
            enabled = false, // 用户关掉了这一条
            tags = listOf("1080P"),
        ),
        SourceConfig(
            id = "mine1",
            name = "我自己加的源",
            baseUrl = "https://mine.example",
            kind = net.cinehub.data.model.SourceKind.JsonApi,
            priority = 3,
            tags = listOf("自定义"),
        ),
    )

    private val seeds = listOf(
        SourceConfig(
            id = "minoself",
            name = "MINO自建",
            baseUrl = "http://43.248.128.122:578",
            kind = net.cinehub.data.model.SourceKind.MinoApi,
            priority = 1,
        ),
        seedNew,
        seedOther,
    )

    @Test
    fun `用户自己加的源原样留着`() {
        val out = mergeSeeds(stored, seeds, retired)
        val mine = out.sources.single { it.id == "mine1" }
        assertEquals(
            stored.single { it.id == "mine1" },
            mine,
        )
    }

    @Test
    fun `内置源的内容被新版本覆盖`() {
        val out = mergeSeeds(stored, seeds, retired)
        val luttt = out.sources.single { it.id == "luttt" }
        assertEquals("https://v.luttt.com", luttt.baseUrl)      // 地址换新的
        assertEquals("北觅影视", luttt.name)                     // 名字换新的
        assertEquals(listOf("4K 画质"), luttt.tags)               // 标签换新的
        // 报给用户听的条数：这一版 minoself 的地址和 luttt 的内容都变了，是两条
        assertEquals(2, out.updated)
    }

    @Test
    fun `内置源的启停和排序保留`() {
        val out = mergeSeeds(stored, seeds, retired)
        val luttt = out.sources.single { it.id == "luttt" }
        assertFalse("关掉的线路不能被升级偷偷打开", luttt.enabled)
        assertEquals(2, luttt.priority)
    }

    @Test
    fun `内置源没变过就不算改`() {
        // 盘上那份和种子里那份逐字相同 —— 这一趟什么都没动，报告里也不该出现「重写过」。
        // 三个数一起看：只验 updated 会漏掉「悄悄补了一条」那种（added 也会说话）。
        val same = (stored + seedOther.copy(priority = 4)).map { s ->
            val seed = seeds.firstOrNull { it.id == s.id } ?: return@map s
            // 内容取种子，用户那几样留原值 —— 正是 mergeSeeds 自己会写出的形态
            s.copy(
                name = seed.name,
                baseUrl = seed.baseUrl,
                kind = seed.kind,
                tags = seed.tags,
            )
        }
        val out = mergeSeeds(same, seeds, retired)
        assertEquals(0, out.updated)
        assertEquals(0, out.added)
        assertEquals(0, out.removed)
    }

    @Test
    fun `自己填的站点凭证不被版本抹掉`() {
        val out = mergeSeeds(stored, seeds, retired)
        val mino = out.sources.single { it.id == "minoself" }
        assertEquals("user-token", mino.authToken)
        // 地址还是被新版覆盖了：凭证保留 ≠ 整条不更新
        assertEquals("http://43.248.128.122:578", mino.baseUrl)
    }

    @Test
    fun `删掉的内置源自动补回并排到队尾`() {
        // 盘上没有 luttt（用户删过），新版里有 —— 补回来，但不许挤进第一格。
        // k4ku 同样不在盘上（这份测试数据里本来就没有），所以补回来的是两条。
        val out = mergeSeeds(stored.filterNot { it.id == "luttt" }, seeds, retired)
        val luttt = out.sources.single { it.id == "luttt" }
        assertEquals(4, luttt.priority) // 三条已有的最大位次 3 之后
        assertEquals(2, out.added)
        assertEquals(listOf("luttt", "k4ku"), out.fresh.map { it.id })
        // 用户自己那条仍在原位
        assertEquals(3, out.sources.single { it.id == "mine1" }.priority)
    }

    @Test
    fun `撤掉的内置源被剔掉`() {
        val withRetired = stored + SourceConfig(
            id = "hqvod",
            name = "看好了吗",
            baseUrl = "https://hqvod.example",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 9,
        )
        val out = mergeSeeds(withRetired, seeds, retired)
        assertFalse(out.sources.any { it.id == "hqvod" })
        assertEquals(1, out.removed)
        // 撤掉的那条要能报出名字：只说「撤下 1 个」的话，用户没法判断少的是哪条
        assertEquals(listOf("看好了吗"), out.dropped.map { it.name })
        // 自己加的那条不受牵连
        assertTrue(out.sources.any { it.id == "mine1" })
    }

    @Test
    fun `补进来的按种子里的相对顺序排`() {
        val out = mergeSeeds(stored.filterNot { it.id in setOf("luttt", "k4ku") }, seeds, retired)
        val luttt = out.sources.single { it.id == "luttt" }
        val k4ku = out.sources.single { it.id == "k4ku" }
        assertEquals(listOf("luttt", "k4ku"), out.fresh.map { it.id })
        assertTrue(luttt.priority < k4ku.priority)
    }

    @Test
    fun `每次同步都从空列表补出完整内置源集合`() {
        // 全新的安装 / 或者用户把内置源全删了：同步完必须是一套完整的内置源
        val out = mergeSeeds(emptyList(), seeds, retired)
        assertEquals(
            setOf("minoself", "luttt", "k4ku"),
            out.sources.map { it.id }.toSet(),
        )
        assertEquals(3, out.added)
    }

    @Test
    fun `同步是幂等的`() {
        // 这条路上每次冷启动都会跑一遍。第二趟必须什么都不报、什么都不改 ——
        // 否则每次开机都会弹出「补进来 N 个 / 重写了 N 个」，用户会以为天天在更新。
        val first = mergeSeeds(stored, seeds, retired)
        val second = mergeSeeds(first.sources, seeds, retired)
        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(0, second.removed)
        // 列表本身也不许动：内容、启停、位次逐条相同
        assertEquals(
            first.sources.sortedBy { it.id },
            second.sources.sortedBy { it.id },
        )
    }

    // ---------- 哪些字段算「内容」：逐格验一遍 ----------

    /**
     * 内容与 [seedNew] 逐字相同、但「用户那几样」全是他自己的盘上记录。
     *
     * 拿它当基准，一次只改一格，就能把 [differentContent] 的清单逐格验穿。
     * 这张表存在的理由：[differentContent] 与 [mergeSeeds] 里那份 `copy` 是一对，
     * 而它们错了都不出声 —— 那边多抄一格、这边少比一格，就是「内容被静默覆盖、
     * 界面上还说没动过」。将来给 [SourceConfig] 加内容字段，这里会先红。
     */
    private val baseline = seedNew.copy(
        priority = 7,                      // 用户拖出来的位次
        enabled = false,                   // 用户把这条关掉了
        createdAt = 1_700_000_000_000L,    // 哪天加进来的
        authToken = "user-token",          // 用户自己的凭证
        authMode = AuthMode.Bearer,
    )

    private fun updatedWhenStoredBecomes(change: (SourceConfig) -> SourceConfig): Int =
        mergeSeeds(listOf(change(baseline)), listOf(seedNew), retired).updated

    @Test
    fun `用户那几样不同不算内容被改`() {
        // 基准本身就是「内容一样、用户那几样不一样」，这一趟该报 0
        assertEquals(0, updatedWhenStoredBecomes { it })
    }

    @Test
    fun `每一格内容字段单独变都会报一条更新`() {
        // 逐格：漏一格 = 那一格被静默覆盖而计数为 0
        assertEquals(1, updatedWhenStoredBecomes { it.copy(name = "改过的名字") })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(baseUrl = "https://other.example") })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(kind = SourceKind.JsonApi) })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(tags = listOf("别的标签")) })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(html = HtmlRules()) })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(api = ApiRules()) })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(playPath = "/play/{id}/{sid}/{nid}") })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(needsBrowser = true) })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(probePath = "/api/ping") })
        assertEquals(1, updatedWhenStoredBecomes { it.copy(hiddenUrl = true) })
    }

    @Test
    fun `用户那几样单独变都不算更新`() {
        // 启停、位次、凭证、加进来的日期：同步一律保留，也就一律不该进「重写了 N 个」
        assertEquals(0, updatedWhenStoredBecomes { it.copy(enabled = true) })
        assertEquals(0, updatedWhenStoredBecomes { it.copy(priority = 9) })
        assertEquals(0, updatedWhenStoredBecomes { it.copy(createdAt = 1L) })
        assertEquals(0, updatedWhenStoredBecomes { it.copy(authToken = "another") })
        assertEquals(0, updatedWhenStoredBecomes { it.copy(authMode = AuthMode.Cookie) })
    }

    @Test
    fun `种子里的凭证不会被塞给用户`() {
        // SourceConfig.authToken 的定义是「用户自己提供的站点会话凭证」。
        // 种子那一份要是被继承下来，一次升级就会把这条从匿名请求悄悄换成带凭证请求，
        // 而 updated 数出来是 0 —— 行为变了、界面上一个字没说。
        val seeded = seedNew.copy(authToken = "seed-token", authMode = AuthMode.Bearer)
        val mine = baseline.copy(authToken = null, authMode = AuthMode.None)
        val out = mergeSeeds(listOf(mine), listOf(seeded), retired)
        val luttt = out.sources.single { it.id == "luttt" }
        assertNull(luttt.authToken)
        assertEquals(AuthMode.None, luttt.authMode)
        assertEquals(0, out.updated)
    }

    @Test
    fun `补回来的内置源是启用的`() {
        // 补回的是「版本自带的那一条」，用户没在这条上做过任何决定，所以按种子的原样进来：
        // 启用、不带凭证。带个 false 进来等于用户刚装上就有一条灰的。
        val out = mergeSeeds(emptyList(), seeds, retired)
        assertEquals(3, out.fresh.size)
        assertTrue(out.fresh.all { it.enabled })
        assertTrue(out.fresh.all { it.authToken == null })
    }

    // ---------- 这一趟该不该写盘 ----------

    @Test
    fun `读不通的存档一个字都不写`() {
        // 回归保护。原来「盘上读不通」也算「要写」，理由是 commit 会顺手把原文存进备份键 ——
        // 存档那一步没错，但 commit 还会把主键换成种子：下一次解码就成功了，
        // 「本机存的源列表读不通，原来那份已另存」那段警示自己消失，
        // 而用户自己加的源已经从列表里没了（备份键全仓没有一处读，等于拿不回来）。
        val merged = mergeSeeds(emptyList(), seeds, retired)
        assertFalse(shouldWriteSync(merged, hadStored = false, unreadable = true))
        assertFalse(shouldWriteSync(merged, hadStored = true, unreadable = true))
    }

    @Test
    fun `没动过就不写盘`() {
        // 每次冷启动都会走一遍同步；写一次就让 sources 这条流重发一遍（列表整屏重组），
        // 而它带来的信息是零。
        val once = mergeSeeds(stored, seeds, retired)
        val quiet = mergeSeeds(once.sources, seeds, retired)
        assertFalse(shouldWriteSync(quiet, hadStored = true, unreadable = false))
    }

    @Test
    fun `真动了就写盘`() {
        val merged = mergeSeeds(stored, seeds, retired)
        assertTrue(shouldWriteSync(merged, hadStored = true, unreadable = false))
    }

    @Test
    fun `从没存过就写一次初始列表`() {
        val fresh = mergeSeeds(emptyList(), seeds, retired)
        assertTrue(shouldWriteSync(fresh, hadStored = false, unreadable = false))
    }
}
