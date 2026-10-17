package net.cinehub.ui.screens

import net.cinehub.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「下一集到底是清单里哪一条」的唯一权威查法。
 *
 * 手动上下集按钮和自动连播倒计时共用它（见 [nextEpisodeOf]）：两处各写一套
 * 「往后挪一格」的话，迟早有一处在缺集时把用户扔到第一集 —— 那正是当年
 * `-1 加一挡落到清单第一集` 的翻版。
 */
class NextEpisodeOfTest {

    private fun eps(vararg indexes: Int) =
        indexes.map { Episode(name = "第${it}集", index = it, playUrl = "u$it") }

    @Test
    fun stepsToTheFollowingEntryInLineOrder() {
        // 集号按清单顺序走，不按 index 算术：线路里出现 1,2,4（缺 3）时，
        // 第 2 集的下一集是第 4 集，不是不存在的第 3 集
        val list = eps(1, 2, 4)
        assertEquals(4, nextEpisodeOf(list, currentIndex = 2, delta = 1)?.index)
    }

    @Test
    fun stepsBackwardsToThePreviousEntry() {
        val list = eps(1, 2, 4)
        assertEquals(2, nextEpisodeOf(list, currentIndex = 4, delta = -1)?.index)
    }

    @Test
    fun unknownCurrentIndexMovesNowhere() {
        // 当前这一集在本条线路的清单里找不到（线路之间集号对不上）：
        // 返回 null，调用方不动 —— 绝不落到清单第一集
        assertNull(nextEpisodeOf(eps(1, 2, 3), currentIndex = 9, delta = 1))
    }

    @Test
    fun pastTheLastEntryMovesNowhere() {
        assertNull(nextEpisodeOf(eps(1, 2, 3), currentIndex = 3, delta = 1))
    }

    @Test
    fun beforeTheFirstEntryMovesNowhere() {
        assertNull(nextEpisodeOf(eps(1, 2, 3), currentIndex = 1, delta = -1))
    }

    @Test
    fun emptyListMovesNowhere() {
        assertNull(nextEpisodeOf(emptyList(), currentIndex = 1, delta = 1))
    }
}
