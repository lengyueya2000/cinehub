package net.cinehub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.Health
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/** 状态圆点。颜色之外一律配文字，不单靠颜色传达状态。 */
@Composable
fun StatusDot(health: Health, size: Int = 6) {
    val c = when (health) {
        Health.Ok -> T.Ok
        Health.Slow -> T.Warn
        Health.Down -> T.Err
        Health.Unknown -> T.TextFaint
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(c))
}

/** 小徽标。cyan 专用于「源 / 渲染」语义。 */
@Composable
fun Pill(
    text: String,
    cyan: Boolean = false,
    ghost: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        cyan -> T.Src500.copy(alpha = .14f)
        ghost -> Color.White.copy(alpha = .07f)
        else -> T.Play500.copy(alpha = .14f)
    }
    val fg = when {
        cyan -> T.Src500
        ghost -> T.TextDim
        else -> T.Play400
    }
    val bd = when {
        cyan -> T.Src500.copy(alpha = .30f)
        ghost -> T.Border
        else -> T.Play500.copy(alpha = .30f)
    }
    Text(
        text = text,
        color = fg,
        fontSize = Fs.xxs,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(R.full)
            .background(bg)
            .border(1.dp, bd, R.full)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Spacer4() = Box(Modifier.size(Sp.s1))
@Composable
fun Spacer8() = Box(Modifier.size(Sp.s2))
@Composable
fun Spacer12() = Box(Modifier.size(Sp.s3))
@Composable
fun Spacer16() = Box(Modifier.size(Sp.s4))

/** 区块标题 + 右侧动作。 */
@Composable
fun SectionHead(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = T.Text, fontSize = Fs.sec, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                // 有 onTrailing 时这一句是**唯一的入口**，那它就不能是最淡的一档色、
                // 也不能是一行刚好套住文字的点：淡到看不见 + 点不准，等于入口不存在。
                color = if (onTrailing != null) T.TextDim else T.TextFaint,
                fontSize = Fs.xs,
                modifier = Modifier
                    .then(
                        if (onTrailing != null)
                            Modifier.heightIn(min = 44.dp).clip(R.sm)
                                .padding(horizontal = Sp.s2)
                        else Modifier
                    )
                    .clickable(enabled = onTrailing != null) { onTrailing?.invoke() },
            )
        }
    }
}

/**
 * 海报卡。
 *
 * [progress] 非空时在底部画进度条（继续观看场景）。
 * [onLongClick] 给了就用长按承载「对这个条目做的破坏性动作」（取消收藏等），
 * 和详情页「长按某一集 = 只缓存它」保持同一套手感。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    cover: String?,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    badge: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier.then(
            if (onLongClick == null) Modifier.clickable(onClick = onClick)
            else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(R.md)
                .background(T.Surface2)
                .border(1.dp, T.Border, R.md),
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .80f)),
                            startY = 200f,
                        )
                    )
            )
            if (rank != null) {
                val top = rank <= 3
                Text(
                    "$rank",
                    color = Color.White,
                    fontSize = Fs.xxs,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (top) Brush.linearGradient(listOf(T.Play400, T.Play600))
                            else Brush.linearGradient(listOf(Color.Black.copy(alpha = .68f), Color.Black.copy(alpha = .68f)))
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (badge != null) {
                Pill(badge, cyan = true, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = .16f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(T.Play500)
                    )
                }
            }
        }
        Spacer4()
        Text(
            title,
            color = T.Text,
            fontSize = Fs.xs,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                // 副标题带的是「哪一集、看到几分、哪一年」这类信息，用 TextFaint 在纯黑上
                // 只有 3.5:1，够不上正文 4.5:1 的门槛 —— 承载信息的文字一律 TextDim
                color = T.TextDim,
                fontSize = Fs.xxs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
