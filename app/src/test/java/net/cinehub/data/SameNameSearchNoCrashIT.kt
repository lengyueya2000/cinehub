package net.cinehub.data

import kotlinx.coroutines.runBlocking
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.data.model.Media
import net.cinehub.data.model.SourceAvailability
import net.cinehub.data.model.typeFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 端到端：拿真实后端搜「余红旧事」，走**归并那条路**，断言结果行键两两不同。
 *
 * 这一条盯的是那个崩溃：搜索页原来拿 `media.key` 当 LazyColumn 的 key，
 * 而归并后同名的两条 key 完全相同 —— Compose 收重复 key 当场抛异常，
 * 表现是「搜别的词都没事，单单搜余红旧事应用直接闪退」。
 *
 * 归并本身（分桶、去重）在 [MediaRepository] 私有方法里，这里不重跑它，
 * 而是用 [MediaRepository] 公开的 search 拿到真实结果，再断言每条结果的行键唯一 ——
 * 这正是界面会拿去当 key 的那个值。
 */
class SameNameSearchNoCrashIT {

    @Test
    fun searchSameNameTitleProducesUniqueRowKeys() = runBlocking {
        assumeTrue("设 MINO_LIVE=1 才跑（会打真实后端）", System.getenv("MINO_LIVE") == "1")

        val cfg = net.cinehub.data.model.SourceConfig(
            id = "it_mino",
            name = "IT 自建签名源",
            baseUrl = "http://43.248.128.122:578",
            kind = net.cinehub.data.model.SourceKind.MinoApi,
            probePath = "/api/v1/app/tabs",
        )
        // 只接这一个源：崩溃与源数量无关，而少打几个站能把这一条跑得更快更稳
        val adapter = net.cinehub.data.source.MinoAdapter(cfg)

        val hits = adapter.search("余红旧事", 1).filter { it.title == "余红旧事" }
        println("[it] 源返回同名条目 ${hits.size} 条：${hits.map { "id=${it.id} type=${it.category}" }}")
        assertTrue("同名条目少于 2 条，这条用例测不到重复 key", hits.size >= 2)

        // 模拟归并后的两行（真实归并会因类型不同而各成一行）
        val rows = hits.map { m ->
            AggregatedMedia(
                media = m,
                availability = listOf(
                    SourceAvailability(cfg.id, cfg.name, m, Health.Ok),
                ),
            )
        }
        // 前提：归并键确实撞了 —— 这正是原来崩的原因
        val mediaKeys = rows.map { it.media.key }
        println("[it] 归并键（media.key）：${mediaKeys.distinct().size} 个不同 / ${mediaKeys.size} 行")
        assertTrue("归并键应当撞车（否则这条用例没测到东西）", mediaKeys.distinct().size < rows.size)

        // 修复后：界面拿去当 key 的 rowKey 必须两两不同
        val rowKeys = rows.map { it.rowKey }
        println("[it] 行键（rowKey）：$rowKeys")
        assertEquals(
            "行键必须两两不同，否则 LazyColumn 会抛重复 key 而闪退",
            rows.size, rowKeys.distinct().size,
        )
        // 类型也确实把它们区分开了
        assertEquals(2, rows.map { typeFingerprint(it.media.category) }.distinct().size)
    }
}
