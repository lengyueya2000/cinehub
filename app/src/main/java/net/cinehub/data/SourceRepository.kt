package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.SourceConfig

private val Context.dataStore by preferencesDataStore(name = "cinehub")

/**
 * 播放源持久化。
 *
 * 内置了两个用户给的源作为初始数据（4kvm.net / dongpiandi.ai），
 * 用户可以改、可以删、也可以自己加。
 */
class SourceRepository(private val context: Context) {

    private val KEY = stringPreferencesKey("sources_v1")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val sources: Flow<List<SourceConfig>> = context.dataStore.data.map { p ->
        val raw = p[KEY]
        if (raw.isNullOrBlank()) Seed.sources
        else runCatching { json.decodeFromString<List<SourceConfig>>(raw) }
            .getOrElse { Seed.sources }
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
        context.dataStore.edit { it[KEY] = json.encodeToString(sorted) }
    }
}

/** 初始源。 */
object Seed {
    val sources: List<SourceConfig> = listOf(
        SourceConfig(
            id = "k4vm",
            name = "4K影视",
            baseUrl = "https://4kvm.net",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 1,
            tags = listOf("4K HDR", "网页解析"),
            html = net.cinehub.data.source.DefaultRules.k4,
        ),
        SourceConfig(
            id = "luttt",
            name = "北觅影视",
            baseUrl = "https://v.luttt.com",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 2,
            tags = listOf("4K 画质", "直链可播"),
            html = net.cinehub.data.source.DefaultRules.maccms,
        ),
        SourceConfig(
            id = "xiguazx",
            name = "西瓜影院",
            baseUrl = "https://xiguazx.cc",
            kind = net.cinehub.data.model.SourceKind.JsonApi,
            priority = 3,
            tags = listOf("10 万+ 片库"),
            api = net.cinehub.data.model.ApiRules(),
        ),
        SourceConfig(
            id = "dongpiandi",
            name = "懂片帝AI",
            baseUrl = "https://dongpiandi.ai",
            kind = net.cinehub.data.model.SourceKind.Html,
            priority = 4,
            tags = listOf("1080P", "AI 找片"),
            html = net.cinehub.data.source.DefaultRules.generic.copy(
                listPath = "/search?q={q}",
            ),
        ),
        SourceConfig(
            id = "demo_m3u",
            name = "演示源",
            baseUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            kind = net.cinehub.data.model.SourceKind.M3u,
            priority = 5,
            tags = listOf("公开测试流", "可验证播放"),
        ),
    )
}
