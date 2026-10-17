package net.cinehub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.DoubanItem
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 二级页的公共外壳。
 *
 * 之前收藏、最近观看、播放源管理、下载、下载设置各有各的顶栏和卡片写法：
 * 三种返回键尺寸、四种圆角、分隔线一会儿 8% 白一会儿 5% 白。这里把这几样定下来，
 * 五页共用一套 —— 页面之间的差别应该留在内容上，不该留在手感上。
 */

/** 二级页顶栏：返回（44dp 触摸区）+ 标题 + 副标题 + 右侧最多一个动作。 */
@Composable
fun SubHeader(
    title: String,
    sub: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Sp.s2, end = Sp.s4, top = Sp.s3, bottom = Sp.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // 视觉上是颗 28 的圆，但可点区域给到 44 —— 手指不必对准图标
            Box(
                Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, T.Border, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowBack, "返回",
                        tint = T.Text, modifier = Modifier.size(17.dp),
                    )
                }
            }
        } else {
            Spacer16()
        }
        Spacer8()
        Column(Modifier.weight(1f)) {
            Text(title, color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Text(
                    sub,
                    color = T.TextDim,
                    fontSize = Fs.xxs,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        action?.invoke()
    }
}

/** 顶栏右侧的动作。整块高度 ≥44dp，不是只有那行字能点。 */
@Composable
fun HeaderAction(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (danger) T.Err else T.Src500
    Box(
        // 44dp 高 + 12dp 圆角：用全圆角时两个字的动作会被撑成正圆，看着像颗按钮徽标
        Modifier.heightIn(min = 44.dp)
            .clip(R.md)
            .background(fg.copy(alpha = .10f))
            .border(1.dp, fg.copy(alpha = .28f), R.md)
            .clickable(onClick = onClick)
            .padding(horizontal = Sp.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = Fs.xs, fontWeight = FontWeight.SemiBold)
    }
}

/** 分组标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = T.TextDim,
        fontSize = Fs.xs,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = Sp.s4, top = Sp.s5, bottom = Sp.s2),
    )
}

/** 一组行共用的那张卡。 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Sp.s4)
            .clip(R.lg).background(T.Surface2).border(1.dp, T.Border, R.lg),
        content = content,
    )
}

/**
 * 组内分隔线：默认从文字起笔处开始，不穿过左侧图标。
 *
 * 起点按各行自己的内边距给（默认那套行是 66dp）—— 别在页面里再写一条同样粗细的线，
 * 两条线迟早会分出两种颜色。
 */
@Composable
fun GroupDivider(indent: Dp = 66.dp) {
    Box(
        Modifier.fillMaxWidth().padding(start = indent)
            .height(1.dp).background(T.BorderFaint)
    )
}

/** 行内的文字动作。44dp 是下限，标签再短也把可点区域撑够。 */
@Composable
fun TextAction(
    label: String,
    color: Color = T.TextDim,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = 44.dp).widthIn(min = 52.dp)
            .clip(R.sm)
            .background(if (filled) T.Play500.copy(alpha = .16f) else Color.Transparent)
            .border(1.dp, if (filled) color.copy(alpha = .4f) else T.Border, R.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Sp.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = Fs.xs, fontWeight = FontWeight.SemiBold)
    }
}

/** 等宽数字。状态刷新时数值变长变短，旁边的字不该跟着左右跳。 */
@Composable
fun NumText(
    text: String,
    color: Color = T.Text,
    fontSize: TextUnit = Fs.body,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        modifier = modifier,
    )
}

/**
 * 破坏性动作的二次确认。
 *
 * 之前清空观看记录、删除单条记录、删除源、删除缓存任务都是点一下就没了 ——
 * 这四样恰好都是「用户自己没法重建」的东西（源要重新填凭证，收藏要重新找片）。
 * 文案必须说清会没掉什么，而不是「确定吗」。
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = T.Surface2,
        shape = R.lg,
        titleContentColor = T.Text,
        textContentColor = T.TextDim,
        title = { Text(title, fontSize = Fs.body, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = Fs.sm, lineHeight = Fs.sm * 1.65f) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = T.Err, fontWeight = FontWeight.Bold, fontSize = Fs.body)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("先不", color = T.TextDim, fontSize = Fs.body)
            }
        },
    )
}

/** 空态：一句标题、一句「为什么是空的 / 下一步做什么」。 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Sp.s5, vertical = Sp.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
        Spacer8()
        Text(
            body,
            color = T.TextDim,
            fontSize = Fs.xs,
            lineHeight = Fs.xs * 1.7f,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 「屏上这份不是全部」的告示。
 *
 * 三份本机记录（播放源、收藏与观看、缓存任务）都可能因为版本换了格式而读不通。
 * 那时候界面显示的是回落出来的空列表，而盘上那份原文还在存档里 —— 不说清楚的话，
 * 「还没有缓存任何内容」会被读成「我自己删光了」，而用户下一次任何操作就把屏幕上
 * 这份空的写回盘上，那份旧的才真的没了。
 */
@Composable
fun UnreadableNotice(what: String) {
    Text(
        "注意：本机存的${what}这一版读不通（多半是刚换过格式）。现在显示的是回落出来的空列表，" +
            "原来那份已另存了一份没动 —— 先别在这里增删，等升级到能读的版本。",
        color = T.Warn,
        fontSize = Fs.xxs,
        lineHeight = Fs.xxs * 1.6f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s1),
    )
}

/**
 * 「豆瓣有这一部、手上的源对不上」那条贴在底部的说明。片库和首页共用。
 *
 * [why] 必须是 ViewModel 里 openFromDouban 现算出来的那一句 —— 「源里真没有」
 * 「连上的源里没同名」「同名有但对不上年份」是三种不同的事，写成一句「找不到」
 * 就等于拿应用的失败替源作保。这里只负责摆出来，并把去豆瓣那条路递过去。
 */
@Composable
fun DoubanMissBanner(
    item: DoubanItem,
    why: String,
    enabledSources: Int,
    onOpenDoubanPage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(Sp.s4).clip(R.lg).background(T.Surface2)
            .border(1.dp, T.Border, R.lg).padding(Sp.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "《${item.title}》${item.year?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""} 豆瓣有，$why",
                color = T.Text,
                fontSize = Fs.sm,
            )
            Text(
                "在 $enabledSources 个启用的源里搜过 —— 可能是源里真没有，也可能只是片名写法不一样",
                color = T.TextFaint,
                fontSize = Fs.xxs,
            )
        }
        Spacer8()
        Row(
            Modifier.height(44.dp).clip(R.full).background(T.Surface).border(1.dp, T.Border, R.full)
                .clickable { onOpenDoubanPage(item.url) }.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text("去豆瓣看", color = T.Text, fontSize = Fs.xs) }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = T.TextDim, fontSize = Fs.body) }
    }
}

