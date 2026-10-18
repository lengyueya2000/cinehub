package net.cinehub.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页末尾那段 script 的解析。
 *
 * 这是这一型能不能看到分集的**唯一**依据 —— 详情页的 DOM 里没有分集锚点
 * （`.playlists` 是空 div，由这段 script 在浏览器里渲染）。所以这里认错，
 * 界面上的表现是「这一部没有分集」，而它其实有 47 集。
 *
 * 三种形状都要钉住：电影（单参数 `1080P`）、电视剧（集号数组）、以及认不出来时
 * 返回 null 而不是抛。
 */
class PpnixScriptTest {

    private val tv =
        "<script>classid=2;classurl='/tv/';infoid=8509;sub='|en|cn|tw|';" +
            "m3u8=['1','2','3','47']</script>"

    private val movie =
        "<script>classid=1;classurl='/movie/';infoid=8582;sub='';m3u8=['1080P']</script>"

    @Test
    fun `电视剧的集号数组`() {
        val v = PpnixScript.read(tv)!!
        assertEquals(2, v.classId)
        assertEquals("8509", v.infoId)
        assertEquals(listOf("1", "2", "3", "47"), v.params)
        assertEquals(PpnixScript.Kind.Tv, v.paramKind)
    }

    @Test
    fun `电影只有一个 1080P 参数`() {
        val v = PpnixScript.read(movie)!!
        assertEquals(1, v.classId)
        assertEquals("8582", v.infoId)
        assertEquals(listOf("1080P"), v.params)
        assertEquals(PpnixScript.Kind.Movie, v.paramKind)
    }

    /** 大小写不敏感：站点哪天写 `1080p` 也该认成电影。 */
    @Test
    fun `电影参数大小写不敏感`() {
        val v = PpnixScript.read("infoid=1;m3u8=['1080p']")!!
        assertEquals(PpnixScript.Kind.Movie, v.paramKind)
    }

    @Test
    fun `没有那段 script 时返回 null`() {
        assertNull(PpnixScript.read("<script>var x = 1;</script>"))
    }

    @Test
    fun `m3u8 数组是空的也算没有分集`() {
        assertNull(PpnixScript.read("infoid=1;m3u8=[]"))
    }

    /**
     * 认不出 infoid 不算失败：分集数组才是能不能播的依据，
     * infoid 只在取链时用（见 PpnixAdapter.resolveStream），那边自己会说「重新打开详情页」。
     */
    @Test
    fun `缺 infoid 时分集照读`() {
        val v = PpnixScript.read("classid=2;m3u8=['1','2']")!!
        assertNull(v.infoId)
        assertEquals(listOf("1", "2"), v.params)
    }

    /** 集号带空格（站点可能写成 `['1', '2']`）也要认得出来。 */
    @Test
    fun `数组元素之间带空格`() {
        val v = PpnixScript.read("infoid=9;m3u8=['1', '2', '10']")!!
        assertEquals(listOf("1", "2", "10"), v.params)
    }

    /** 空的字符串元素不该占一集的位置。 */
    @Test
    fun `数组里的空元素被剔掉`() {
        val v = PpnixScript.read("infoid=9;m3u8=['1','','2']")!!
        assertEquals(listOf("1", "2"), v.params)
    }

    /**
     * 单集电视剧（`['1']`）不是电影。
     *
     * 这一条容易搞反：只有一集的剧参数也是 `['1']`，按「单参数」判会把它当电影，
     * 于是选集里出现一个叫「正片」的第 1 集。判据看的是**参数是不是 1080P**，
     * 不是数组长度。
     */
    @Test
    fun `单集电视剧不是电影`() {
        val v = PpnixScript.read("infoid=9;m3u8=['1']")!!
        assertEquals(PpnixScript.Kind.Tv, v.paramKind)
    }

    /** 集号可能不是纯数字（站点加过「预告」「花絮」这类）。 */
    @Test
    fun `非数字参数也照读`() {
        val v = PpnixScript.read("infoid=9;m3u8=['1','预告','花絮']")!!
        assertEquals(listOf("1", "预告", "花絮"), v.params)
    }

    @Test
    fun `变量顺序不影响`() {
        val v = PpnixScript.read("infoid=5;classid=1;m3u8=['1080P'];sub=''")!!
        assertEquals("5", v.infoId)
        assertEquals(PpnixScript.Kind.Movie, v.paramKind)
    }

    /** 认不出的形状返回 null，调用方照「没有分集」处理，不抛到界面上。 */
    @Test
    fun `完全不像的形状返回 null`() {
        assertNull(PpnixScript.read("classid=1;infoid=2;sub='';"))
    }
}
