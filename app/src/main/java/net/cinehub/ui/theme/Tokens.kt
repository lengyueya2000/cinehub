package net.cinehub.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计令牌：取自设计稿 design/ui-concept.html。
 * 命名与概念稿保持一致，便于对照。
 */
object T {
    // 底层
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0C0C0D)
    val Surface2 = Color(0xFF16161A)
    val Surface3 = Color(0xFF1F1F24)
    val Surface4 = Color(0xFF2A2A31)

    // 文字
    val Text = Color(0xFFF8FAFC)
    val TextDim = Color(0xFF94A3B8)

    /**
     * 最弱的那一档文字色。
     *
     * 原来是 `#5B6474`。算一遍对比度（WCAG 相对亮度 `L = 0.2126R + 0.7152G + 0.0722B`，
     * R/G/B 先做 sRGB→线性变换；对比度 `(L1+0.05)/(L2+0.05)`）：
     *
     * - 对 `Bg`(#000000)：**3.52 : 1**
     * - 对 `Surface2`(#16161A)：**3.02 : 1**
     *
     * 两档都不足 4.5:1，而这一档被用在 **55 处**信息性文字上 —— 错误原因、空状态、
     * 占位符、状态说明（`SearchScreen` 的「错误原因」、`PlayerScreen` 的空状态、
     * `SourceScreen` 的三处占位符与状态说明）。它们不是装饰，是「这一行字要说的事」。
     *
     * 改成 `#8A93A3` 之后（同一套公式实测算出）：
     *
     * - 对 `Bg`(#000000)：**6.78 : 1** ✅
     * - 对 `Surface`(#0C0C0D)：**6.32 : 1** ✅
     * - 对 `Surface2`(#16161A)：**5.83 : 1** ✅
     * - 对 `Surface3`(#1F1F24)：**5.30 : 1** ✅
     *
     * 四档底色上全部过 4.5:1（WCAG AA 正文线）。
     * 它在视觉上仍然是「比 [TextDim] 弱一档」的那一档，
     * 层级关系没变，只是不再弱到读不清。
     */
    val TextFaint = Color(0xFF8A93A3)
    val TextOnArt = Color(0xFFCBD5E1)

    // 播放红
    val Play400 = Color(0xFFFB7185)
    val Play500 = Color(0xFFE11D48)
    val Play600 = Color(0xFFBE123C)

    // 源 / 渲染青 —— 专属于「播放源」语义
    val Src400 = Color(0xFF67E8F9)
    val Src500 = Color(0xFF22D3EE)
    val Src600 = Color(0xFF0891B2)

    // 状态
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Err = Color(0xFFF87171)
    val Gold = Color(0xFFF5C451)

    // 描边
    val Border = Color(0x14FFFFFF)      // 8%
    val BorderStrong = Color(0x26FFFFFF) // 15%
    val BorderFaint = Color(0x0DFFFFFF)
}
