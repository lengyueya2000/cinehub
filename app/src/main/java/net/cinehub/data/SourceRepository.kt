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

    val sources: Flow<List<SourceConfig>> = context.dataStore.data.map { p ->
        val raw = p[KEY]
        val stored = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<List<SourceConfig>>(it) }.getOrNull()
        }
        _unreadable.value = raw.isNullOrBlank().not() && stored == null
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

    suspend fun upsert(cfg: SourceConfig) {
        val cur = sources.first().toMutableList()
        val i = cur.indexOfFirst { it.id == cfg.id }
        if (i >= 0) cur[i] = cfg else cur += cfg
        write(cur)
    }

    suspend fun delete(id: String) {
        write(sources.first().filterNot { it.id == id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        write(sources.first().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun reorder(ids: List<String>) {
        val cur = sources.first().associateBy { it.id }
        write(ids.mapIndexedNotNull { i, id -> cur[id]?.copy(priority = i + 1) })
    }

    /**
     * 恢复内置源，但保留用户自己加的源。
     *
     * 用户手滑删掉内置源后不该只能重装 —— 这里按 id 补回缺失的内置源，
     * 已经是同一个 id 的条目保持原样（含用户的启停与排序）。
     * 返回补回了几个，交给 UI 说清楚发生了什么。
     */
    suspend fun restoreDefaults(): Int {
        val cur = sources.first().toMutableList()
        val have = cur.map { it.id }.toSet()
        val missing = Seed.sources.filter { it.id !in have }
        write(cur + missing)
        return missing.size
    }

    private suspend fun write(list: List<SourceConfig>) {
        val sorted = list.sortedBy { it.priority }
        val encoded = json.encodeToString(sorted)
        context.dataStore.edit { p ->
            val prev = p[KEY]
            // 覆盖前先把读不通的那份存档：这一版解析不了它，就得保证它还在盘上，
            // 而不是被「看起来正常」的新列表就地替换掉。
            if (!prev.isNullOrBlank() && runCatching { json.decodeFromString<List<SourceConfig>>(prev) }
                    .isFailure
            ) p[BACKUP_KEY] = prev
            p[KEY] = encoded
        }
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
