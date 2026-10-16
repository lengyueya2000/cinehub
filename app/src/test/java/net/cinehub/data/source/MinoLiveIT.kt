package net.cinehub.data.source

import kotlinx.coroutines.runBlocking
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.SourceKind
import net.cinehub.data.model.StreamResolution
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
