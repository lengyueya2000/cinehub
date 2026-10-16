package net.cinehub.data.source

import kotlinx.coroutines.runBlocking
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.typeFingerprint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 全链路实测：握手 → 浏览/搜索 → 详情（多条线路）→ 换票 → 解析出最终可播地址。
 *
 * 默认**跳过**（构建机上不该打真实站点，也不该让 CI 的绿取决于一个外部后端）。
 * 本地验证时带上环境变量 `MINO_LIVE=1` 才真的跑：
 * `gradlew :app:testReleaseUnitTest --tests net.cinehub.data.source.MinoLiveIT -Dorg.gradle.project.MINO_LIVE=1`
 * 或先 `$env:MINO_LIVE='1'` 再跑本模块测试。
 *
 * 这一条不是走过场：单测钉的是「我们的算法和参考实现一致」，这一条钉的是
 * 「后端今天还认我们这套签名」。两边都绿，才有资格说整合完成。
 */
class MinoLiveIT {

    @Test
    fun fullChainResolvesPlayableUrl() = runBlocking {
        assumeTrue("设 MINO_LIVE=1 才跑（会打真实后端）", System.getenv("MINO_LIVE") == "1")
        val cfg = SourceConfig(
            id = "it_mino",
            name = "IT 自建签名源",
            baseUrl = "http://43.248.128.122:578",
            kind = SourceKind.MinoApi,
            probePath = "/api/v1/app/tabs",
        )
        val adapter = MinoAdapter(cfg)

        // 1) 浏览：首页该给出一页片子
        val cards = adapter.browse(1)
        println("[live] browse -> ${cards.size} 条，第一条：${cards.firstOrNull()?.title}")
        assertTrue("browse 返回 0 条", cards.isNotEmpty())

        // 2) 搜索：认得「庆余年」
        val hits = adapter.search("庆余年", 1)
        println("[live] search 庆余年 -> ${hits.size} 条：${hits.take(3).map { it.title }}")
        assertTrue("search 返回 0 条", hits.isNotEmpty())

        // 2b) 同名条目：后端里「余红旧事」2026 有两条，靠类型才分得开。
        //     这一条钉的是「官方那 3 条 4K 线路能被单独拿到」，也就是这次的 bug。
        val same = adapter.search("余红旧事", 1).filter { it.title == "余红旧事" }
        println("[live] search 余红旧事 -> ${same.size} 条同名条目：" +
            same.joinToString { "id=${it.id} type=${it.category}" })
        assertTrue("同名条目少于 2 条，类型校验无从谈起（后端数据可能变了）", same.size >= 2)
        val types = same.map { typeFingerprint(it.category) }
        assertTrue(
            "两条同名条目的类型归一值没能区分开：$types（应含漫剧与连续剧）",
            types.distinct().size >= 2,
        )
        // 分别打开两条，确认线路真的不一样 —— 这正是用户看到的「1080P vs 4K」
        for (m in same.take(2)) {
            val d = adapter.detail(m.id)
            println("[live]   id=${m.id} type=${m.category} -> ${d.lines.size} 条线路：" +
                d.lines.joinToString { it.name })
        }

        // 3) 详情：爱情公寓3（报告里的验证样本，vod_id=1535960）该有多条线路
        val detail = adapter.detail("1535960")
        println("[live] detail -> 《${detail.media.title}》 ${detail.lines.size} 条线路，" +
            detail.lines.joinToString { "${it.name}(${it.episodes.size}集)" })
        assertTrue("detail 没有线路", detail.lines.isNotEmpty())

        // 4) 每条线路第一集都换一次票、解析到最终地址
        var ok = 0
        for (line in detail.lines) {
            val ep = line.episodes.first()
            when (val r = adapter.resolveStream(ep.playUrl ?: "")) {
                is StreamResolution.Ready -> {
                    ok++
                    println("[live] 线路「${line.name}」→ ${r.url.take(110)}")
                }
                is StreamResolution.Failed ->
                    println("[live] 线路「${line.name}」失败：${r.reason}")
                else -> println("[live] 线路「${line.name}」：${r}")
            }
        }
        assertTrue("三条线路一条都没解析出地址", ok > 0)
        println("[live] 可播线路 $ok/${detail.lines.size}")
    }
}
