package net.cinehub.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应（fit）的画面区：这一屏「画面区多大」的唯一说法。
 *
 * 有五处读这个数：装画面的 Box、手势层、两块渐变罩、右侧浮层、锁屏图标的纵向偏移。
 * 它算错的时候**不报错、不崩**，只是控件跑到画面外面去，或者手势区比画面大一圈 ——
 * 这正是出过的事：手势层曾自己挂着 aspectRatio（拿 Box 宽去算，窄片时算出的高度超过
 * Box 高），浮层和锁屏图标也各抄了一份 `boxW / ratio`。抽成纯函数，把边界钉在这里。
 *
 * 边界分三类：
 * ① 宽片按宽算高（横向铺满、上下收缩）—— 黑边归顶栏与底部控件，这是这次要修的那件事；
 * ② 窄片按高算宽（纵向铺满、左右收缩）—— 宽度一超 Box 宽就会被父布局裁掉，
 *    所以必须反过来算，`aspectRatio + fillMaxWidth` 在这里是错的；
 * ③ 拉满 / 放大 / 宽高比还没解出来，一律按整块 Box —— 那几种本来就不该收窄。
 *
 * 数值断言带容差（0.01 dp）：800/(16/9) 在 Float 里是 450.00001，不是 450。
 * 真要钉的是「宽高比保持」「不超 Box」「一边贴满」这三条不变量，不是第几位小数。
 */
class FitVideoAreaTest {

    // 横屏模拟器的实测形状：物理 900×1600、旋转 90° → 1600×1000 px，密度 2 → 800×500 dp。
    // 真机上 16:9 内容量出来的画面区是 900 px 高，也就是这里的 450 dp。
    private val boxW = 800.dp
    private val boxH = 500.dp
    private val wide = 16f / 9f

    /** 断言画面区的宽高，单位 dp，带 0.01 的容差。 */
    private fun assertArea(w: Float, h: Float, actual: Pair<Dp, Dp>) {
        assertEquals(w, actual.first.value, 0.01f)
        assertEquals(h, actual.second.value, 0.01f)
    }

    // ---------- ① 宽片：按宽算高，上下收缩 ----------

    @Test
    fun wideContentIsWidthAnchoredAndKeepsFullWidth() {
        // 16:9 画面在 800 dp 宽的视口里高 450 dp，上下各剩 25 dp 归控件 ——
        // 这就是真机量出来的那个 900 px（密度 2）。宽不变，否则等于自己给自己加黑边。
        val (w, h) = fitVideoArea(boxW, boxH, "fit", wide)
        assertEquals(boxW, w)
        assertArea(800f, 450f, w to h)
    }

    @Test
    fun theStripBelowBelongsToTheControls() {
        // 上下那两块各拿到「Box 高 − 画面高」的一半。顶栏与底部控件之所以能名正言顺地
        // 待在画面外面，靠的就是这一条 —— 要是画面区不收窄，它们就得压在黑带上。
        val (w, h) = fitVideoArea(boxW, boxH, "fit", wide)
        assertEquals(boxW, w)
        assertEquals(25f, (boxH.value - h.value) / 2f, 0.01f)
    }

    @Test
    fun cinemaScopeFoldsEvenFurther() {
        // 2:1 的内容：宽还是 800 dp，高只剩 400 dp，剩下 100 dp 全归控件。
        val (w, h) = fitVideoArea(boxW, boxH, "fit", 2f)
        assertEquals(boxW, w)
        assertArea(800f, 400f, w to h)
    }

    // ---------- ② 窄片：按高算宽，左右收缩 ----------

    @Test
    fun narrowContentIsHeightAnchored() {
        // 4:3 老片在 800×500 的 Box 里：按宽算会是 600 dp 高，撑破 Box 50 dp，
        // 超出那截会被父布局裁掉。所以改成按高算宽 —— 高用满，宽 666.67 dp。
        val (w, h) = fitVideoArea(boxW, boxH, "fit", 4f / 3f)
        assertEquals(boxH, h)
        assertArea(666.67f, 500f, w to h)
    }

    @Test
    fun verticalShortDramaAlsoFits() {
        // 竖屏短剧 9:16：宽度只有 Box 高的 9/16 = 281.25 dp，高度用满。
        val (w, h) = fitVideoArea(boxW, boxH, "fit", 9f / 16f)
        assertEquals(boxH, h)
        assertArea(281.25f, 500f, w to h)
    }

    @Test
    fun narrowContentNeverExceedsTheBoxWidth() {
        // 无论多窄，宽度都不许超过 Box 宽 —— 超了就会被父布局裁掉一截，
        // 而手势层/渐变罩/浮层都按这个数摆，一超出就统统跑到画面外面。
        for (ar in listOf(0.1f, 0.5f, 9f / 16f, 0.75f, 1f, 4f / 3f)) {
            val (w, h) = fitVideoArea(boxW, boxH, "fit", ar)
            assertEquals(boxH, h)
            assertTrue("宽 $w 超过了 Box 宽 $boxW（宽高比 $ar）", w <= boxW)
        }
    }

    // ---------- ③ 不收窄的三种 ----------

    @Test
    fun exactMatchChangesNothing() {
        // 宽高比刚好等于 Box 的（800/500）：画面铺满、一个像素的边都不留。
        // 容差是因为 1.6f 在二进制里不精确，800/1.6f 除出来是 499.99999。
        val ar = boxW.value / boxH.value
        val (w, h) = fitVideoArea(boxW, boxH, "fit", ar)
        assertArea(800f, 500f, w to h)
    }

    @Test
    fun unknownAspectFillsTheBox() {
        // 画面还没解出来（videoSize 是 0）：按整块 Box 算，等它自己报再重排。
        // 提前收窄会让控件在起播那一瞬闪一个错误的位置。
        assertEquals(boxW to boxH, fitVideoArea(boxW, boxH, "fit", null))
        // 非正数同理 —— 那和「不知道」是一回事，更不该拿去当除数。
        assertEquals(boxW to boxH, fitVideoArea(boxW, boxH, "fit", 0f))
        assertEquals(boxW to boxH, fitVideoArea(boxW, boxH, "fit", -2f))
    }

    @Test
    fun fillAndZoomNeverShrink() {
        // 那两种本来就是让 PlayerView 自己去裁去拉，画面区收窄了反而和它对着干。
        for (ar in listOf(0.5f, 4f / 3f, wide, 2f)) {
            assertEquals(boxW to boxH, fitVideoArea(boxW, boxH, "fill", ar))
            assertEquals(boxW to boxH, fitVideoArea(boxW, boxH, "zoom", ar))
        }
    }

    // ---------- 不变量：任何一种宽高比都不许破 ----------

    @Test
    fun theAreaAlwaysStaysInsideTheBox() {
        // 不管什么内容，画面区都不许超出 Box —— 超出去的部分会被父布局裁掉。
        for (ar in listOf(0.1f, 0.5625f, 0.75f, 1f, 4f / 3f, 1.6f, wide, 2f, 3.5f)) {
            val (w, h) = fitVideoArea(boxW, boxH, "fit", ar)
            assertTrue("宽 $w 超出 Box（宽高比 $ar）", w <= boxW)
            assertTrue("高 $h 超出 Box（宽高比 $ar）", h <= boxH)
        }
    }

    @Test
    fun oneSideAlwaysTouchesTheEdge() {
        // 「一边贴满、另一边按比例」：永远有一边顶到边。
        // 两边都不贴 = 画面比能放的还小，白费一块屏幕；
        // 两边都贴而比例又不对 = 画面被拉变形了。
        for (ar in listOf(0.1f, 0.5625f, 0.75f, 1f, 4f / 3f, 1.6f, wide, 2f, 3.5f)) {
            val (w, h) = fitVideoArea(boxW, boxH, "fit", ar)
            assertTrue("宽高比 $ar 时两边都没贴满", w == boxW || h == boxH)
        }
    }

    @Test
    fun theAreaKeepsTheContentAspectRatio() {
        // 画面区自己的宽高比要等于内容的宽高比：这样 PlayerView 的 FIT 在这一块里
        // 一个像素的黑边都不会有 —— 黑边全在画面区外面，归控件。
        // 这条是这次改动的**目的**本身，比任何具体数值都值得钉。
        for (ar in listOf(0.5625f, 4f / 3f, wide, 2f)) {
            val (w, h) = fitVideoArea(boxW, boxH, "fit", ar)
            assertEquals(ar, w.value / h.value, 0.001f)
        }
    }

    @Test
    fun theBoxShapeOnlyMovesTheLine() {
        // 换一块 Box 只是挪那条线的位置，判据本身不变：同样是 16:9 内容，
        // 在 4:3 的 Box 里按宽算高得 450 dp；换成竖 Box（500×800）内容相对更「宽」了，
        // 于是仍然按宽算高、得 281.25 dp。都是同一条判据，不用为屏幕形状改代码。
        val flat = fitVideoArea(800.dp, 600.dp, "fit", wide)   // 4:3 的 Box
        assertArea(800f, 450f, flat)

        val tall = fitVideoArea(500.dp, 800.dp, "fit", wide)   // 竖着的 Box
        // 竖 Box 高 800 dp，16:9 内容还是比 Box「宽」（1.778 × 800 ≫ 500），
        // 所以仍然按宽算高：宽用满 500 dp、高 281.25 dp，剩下的全归控件。
        assertArea(500f, 281.25f, tall)
    }
}
