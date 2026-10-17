package net.cinehub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 间距：4/8dp 栅格 */
object Sp {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 32.dp
}

/** 圆角 */
object R {
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val full = RoundedCornerShape(999.dp)
}

/** 最小可点区域。触摸目标是 44dp —— 别在各处再手写这个字面量。 */
object Touch { val min = 44.dp }

/** 字阶 */
object Fs {
    val hero = 21.sp
    val title = 19.sp
    val navTitle = 17.sp
    val sec = 16.sp
    val body = 14.sp
    val body2 = 13.5f.sp
    val sm = 12.5f.sp
    val xs = 11.5f.sp
    val xxs = 10.5f.sp
    val tiny = 9.5f.sp
}
