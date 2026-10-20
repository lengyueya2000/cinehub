package net.cinehub.ui.screens

import net.cinehub.data.ByteFormat
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 详情页那几个小工具：缓存按钮上的字、字节数读数、简介清冼。
 *
 * 都是「同一个量在两处写成两个数」的老毛病盯防对象 —— ByteFormat 的注释里写着
 * 有人把截断「修」成四舍五入，另一屏就差 0.1。这里把两件事钉住：
 * 读数只有一份（[fmtBytesLocal] 不许自己算），按钮上的字只有一份（[cacheLabel]）。
 */
class DetailLabelsTest {

    // ---------- 字节数：只有 ByteFormat 一份 ----------

    @Test
    fun byteReadoutDelegatesToByteFormat() {
        // 曾经详情页自己算：GB 用 %.1f（四舍五入）、KB 带小数。
        // 于是「已经下好的 2.0 GB 会一起没掉」这个弹窗报的数，和缓存页、播放器
        // 报的同一条任务不一样 —— 而那个弹窗的全部作用就是告诉用户要失去什么。
        listOf(0L, 512L, 2047L, 2048L, 1_999_999L, 2_097_151L, 2_097_152L, 1_073_741_823L, 5_368_709_120L)
            .forEach { b ->
                assertEquals("b=$b", ByteFormat.bytes(b), fmtBytesLocal(b))
            }
    }

    @Test
    fun byteReadoutTruncatesRatherThanRounds() {
        // 1.99 MB：截断写 1.9，四舍五入会写成 2.0 —— 这就是那条注释里说的 0.1
        assertEquals("1.9 MB", fmtBytesLocal((1.99 * 1024 * 1024).toLong()))
        // GB 档同理：1.999 GB 写 1.9
        assertEquals("1.9 GB", fmtBytesLocal((1.999 * 1024 * 1024 * 1024).toLong()))
        // KB 档是整数
        assertEquals("512 KB", fmtBytesLocal(512L * 1024))
    }

    // ---------- 缓存按钮上的字 ----------

    private fun task(
        status: DownloadStatus,
        done: Long = 0,
        total: Long = 0,
        segs: Int = 0,
        segTotal: Int = 0,
    ) = DownloadTask(
        id = "t", mediaId = "m", mediaTitle = "x", sourceId = "s", sourceName = "n",
        episodeName = "第 1 集", episodeIndex = 1, episodeUrl = "http://e",
        status = status, bytesDone = done, bytesTotal = total,
        segmentsDone = segs, segmentsTotal = segTotal,
    )

    @Test
    fun cacheLabelCoversEveryStatus() {
        assertEquals("缓存", cacheLabel(null))
        assertEquals("已缓存", cacheLabel(task(DownloadStatus.Done, done = 100, total = 100)))
        assertEquals("排队中", cacheLabel(task(DownloadStatus.Queued)))
        assertEquals("继续缓存", cacheLabel(task(DownloadStatus.Paused, done = 50, total = 100)))
        assertEquals("重试缓存", cacheLabel(task(DownloadStatus.Failed)))
    }

    @Test
    fun cacheLabelRunningNeedsThePercentOrSaysNothing() {
        // 有总量才报百分比，否则「0%」是假话（单文件那条路对方不给 Content-Length）
        assertEquals("缓存中 50%", cacheLabel(task(DownloadStatus.Running, done = 50, total = 100)))
        assertEquals("缓存中", cacheLabel(task(DownloadStatus.Running, done = 0, total = 0)))
        assertEquals("缓存中", cacheLabel(task(DownloadStatus.Running, segs = 0, segTotal = 0)))
    }

    // ---------- 简介清冼 ----------

    @Test
    fun cleanIntroUnescapesEntitiesOnce() {
        // &amp; 要最后单独解：混在表里一起解会把 &amp;amp; 二次解码成 &
        assertEquals("A & B", cleanIntro("A &amp; B"))
        assertEquals("A &amp; B", cleanIntro("A &amp;amp; B"))
        assertEquals("&laquo;a&raquo;", cleanIntro("&laquo;a&raquo;"))
        assertEquals("5\u00b0C", cleanIntro("5&deg;C"))
    }

    @Test
    fun cleanIntroDecodesNumericEntitiesAndGuardsBadOnes() {
        assertEquals("A", cleanIntro("&#65;"))
        assertEquals("A", cleanIntro("&#x41;"))
        // 越界的码点不许进 Character.toChars（那是直接崩的那一类）
        assertEquals(null, cleanIntro("&#0;"))
        assertEquals(null, cleanIntro("&#1114112;"))
        assertEquals(null, cleanIntro("&#xFFFFFFFF;"))
    }

    @Test
    fun cleanIntroTurnsBrIntoNewlinesAndDropsTags() {
        assertEquals("上\n下", cleanIntro("上<br>下"))
        assertEquals("上\n下", cleanIntro("上<BR/>下"))
        assertEquals("上\n下", cleanIntro("上<br />下"))
        assertEquals("正文", cleanIntro("<p>正文</p>"))
        // 行尾空格不该留下（原来它留着，简介末尾一坨看不见的空白）
        assertEquals("上\n下", cleanIntro("上   \n下"))
    }

    @Test
    fun cleanIntroReturnsNullForNothing() {
        assertEquals(null, cleanIntro(null))
        assertEquals(null, cleanIntro(""))
        assertEquals(null, cleanIntro("   "))
        assertEquals(null, cleanIntro("<br>"))
    }
}


