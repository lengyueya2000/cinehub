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
    val TextFaint = Color(0xFF5B6474)
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
