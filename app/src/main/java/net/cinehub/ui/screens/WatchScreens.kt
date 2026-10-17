package net.cinehub.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import net.cinehub.data.model.Favorite as Fav
import net.cinehub.data.model.HistoryEntry
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.ConfirmDialog
import net.cinehub.ui.components.EmptyState
import net.cinehub.ui.components.GroupCard
import net.cinehub.ui.components.GroupDivider
import net.cinehub.ui.components.HeaderAction
import net.cinehub.ui.components.NumText
import net.cinehub.ui.components.SectionLabel
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.SubHeader
import net.cinehub.ui.components.UnreadableNotice
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 收藏与最近观看两页共用的外壳。
 *
 * 两页都从「我的」进去，用的是同一个顶栏、同一套分组卡 —— 差别只在内容。
 */

// ==================== 收藏 ====================

/** 排序：0 最近加入 / 1 名称 / 2 年份。 */
@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
) {
    val favorites by vm.favorites.collectAsState()
    val history by vm.watchHistory.collectAsState()
    val watchUnreadable by vm.watchUnreadable.collectAsState()
    var sort by remember { mutableIntStateOf(0) }
    // 取消收藏要过一道确认：收藏的片常常是找了好久才定位到某一源的，点错一下就得重来
    var pendingDrop by remember { mutableStateOf<Fav?>(null) }

    val sorted = when (sort) {
        1 -> favorites.sortedBy { it.title }
        // 年份这一档只认四位数年份。原来写的是「把数字全滤出来再转 int」：
        // 站点把 `2024-05-01` 这种整串日期塞进 year 时，它滤成 20240501 —— 一个写法不对的
        // 值于是沉到最前面，把真年份全挤下去。按规矩来：认不出的当没有年份，一律排到最后，
        // 不拿它参与比较。
        2 -> favorites.sortedWith(
            compareBy<Fav> { yearOf(it.year) == null }.thenByDescending { yearOf(it.year) ?: 0 },
        )
        else -> favorites.sortedByDescending { it.addedAt }
    }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        SubHeader("收藏", "${favorites.size} 部", onBack)

        // 「还没有收藏」和「存的那份读不通」在屏幕上长得一样，但只有一句是真话能说：
        // 后者意味着现在这一份是空的、盘上那份还在，而这里任何一次写入都会把空的落回去。
        if (watchUnreadable) UnreadableNotice("收藏和观看记录")

        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "还没有收藏",
                    body = "在详情页点那颗心就能收进来。\n收藏的片会一直留在这里，看完也不会自己消失。",
                )
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s1),
            horizontalArrangement = Arrangement.spacedBy(Sp.s2),
        ) {
            SortChip("最近加入", sort == 0) { sort = 0 }
            SortChip("名称", sort == 1) { sort = 1 }
            SortChip("年份", sort == 2) { sort = 2 }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2),
            horizontalArrangement = Arrangement.spacedBy(Sp.s3),
            verticalArrangement = Arrangement.spacedBy(Sp.s4),
        ) {
            items(sorted, key = { it.key }) { f ->
                FavoriteTile(
                    fav = f,
                    // 收着但已经开看的那一部，要看得出「看到哪了」—— 否则收藏列表和观看记录是两份记忆
                    where = history.firstOrNull { it.key == f.key }?.takeIf { h -> h.positionSec > 0 },
                    onOpen = { onOpenDetail(f.sourceId, f.mediaId) },
                    onDrop = { pendingDrop = f },
                )
            }
        }
    }

    pendingDrop?.let { f ->
        ConfirmDialog(
            title = "取消收藏「${f.title}」？",
            body = "只是从收藏里移掉，观看记录和已缓存的文件都不受影响。想再收回来得回详情页点那颗心。",
            confirm = "移掉",
            onConfirm = { vm.removeFavorite(f.key); pendingDrop = null },
            onDismiss = { pendingDrop = null },
        )
    }
}

@Composable
private fun SortChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(R.full)
            .background(if (active) T.Play500.copy(alpha = .14f) else Color.Transparent)
            .border(1.dp, if (active) T.Play500.copy(alpha = .32f) else T.Border, R.full)
            .clickable(onClick = onClick)
            // 44dp：这一排是这一页唯一的排序门，视觉上 ~30dp 的手指按不准
            .heightIn(min = 44.dp)
            .padding(horizontal = Sp.s3, vertical = Sp.s2),
    ) {
        Text(
            text,
            color = if (active) T.Play400 else T.TextDim,
            fontSize = Fs.xs,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * 收藏的一格。
 *
 * 取消收藏给的是封面右上角那颗 ♥，不是「长按」—— 手势能省版面，但没人告诉用户这里藏着
 * 一个动作。长按仍然保留，和详情页「长按某一集 = 只缓存它」是同一套手感。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(fav: Fav, where: HistoryEntry?, onOpen: () -> Unit, onDrop: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(R.md).background(T.Surface2).border(1.dp, T.Border, R.md)
                .combinedClickable(onClick = onOpen, onLongClick = onDrop),
        ) {
            if (!fav.cover.isNullOrBlank()) {
                AsyncImage(
                    model = fav.cover, contentDescription = fav.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)), startY = 320f)
                ),
            )
            // 视觉是颗 20 的圆，可点区域 44 —— 这里不再垫 padding：
            // 原来写的 `size(44.dp)…padding(2.dp)…clickable(...)` 把内边距算在 size 里面，
            // 可点节点实际只有 40×40，够不着 44 这条下限。内圈那 20 的圆本来就居中，不需要垫。
            Box(
                Modifier.size(44.dp).align(Alignment.TopEnd)
                    .clip(CircleShape).clickable(onClick = onDrop),
                contentAlignment = Alignment.Center,
            ) {
                // 视觉只留 20dp：角标压在封面上，太大就把海报挡掉一角
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .38f))
                        .border(1.dp, T.Play400.copy(alpha = .50f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Favorite, "取消收藏", tint = T.Play400, modifier = Modifier.size(11.dp))
                }
            }
            where?.let {
                Text(
                    "看到 ${if (it.finished) "已看完" else "第${it.episodeIndex}集"}",
                    color = T.TextOnArt,
                    fontSize = Fs.tiny,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
        Spacer4()
        Text(
            fav.title, color = T.Text, fontSize = Fs.xs, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(fav.year?.take(4), fav.category).joinToString(" · ").ifBlank { "已收藏" },
            color = T.TextDim, fontSize = Fs.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}


// ==================== 最近观看 ====================

/**
 * 最近观看。
 *
 * 一行一条紧凑记录，按「今天 / 本周 / 更早」分组 —— 之前每条一张 200dp 高的大卡，
 * 十部就要翻三屏，而这里真正要比的只有「哪一部、看到哪、多久之前」。
 * 删除改成长按 + 确认：常驻的垃圾桶既容易误点，又让每一行都多一列噪音。
 */
@Composable
fun HistoryScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onResume: (HistoryEntry) -> Unit,
) {
    val history by vm.watchHistory.collectAsState()
    val watchUnreadable by vm.watchUnreadable.collectAsState()
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        SubHeader("最近观看", "${history.size} 部 · 只存这台设备", onBack, action = {
            if (history.isNotEmpty()) HeaderAction("清空", danger = true) { confirmClear = true }
        })

        if (watchUnreadable) UnreadableNotice("收藏和观看记录")

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "还没有观看记录",
                    body = "播一部片就会出现在这里。\n下次进来会从上次那一集、那一秒接上。",
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = Sp.s7)) {
            val today = mutableListOf<HistoryEntry>()
            val week = mutableListOf<HistoryEntry>()
            val older = mutableListOf<HistoryEntry>()
            history.forEach { e ->
                when (bucket(e.updatedAt)) {
                    0 -> today += e
                    1 -> week += e
                    else -> older += e
                }
            }
            listOf("今天" to today, "本周更早" to week, "更早" to older).forEach { (name, list) ->
                if (list.isEmpty()) return@forEach
                item(key = "h_$name") { SectionLabel(name) }
                item(key = "hg_$name") {
                    GroupCard {
                        list.forEachIndexed { i, e ->
                            if (i > 0) GroupDivider()
                            HistoryRow(
                                e = e,
                                onResume = { onResume(e) },
                                onDrop = { pendingDelete = e },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "长按一行 = 从记录里移掉",
                    color = T.TextDim,
                    fontSize = Fs.tiny,
                    modifier = Modifier.fillMaxWidth().padding(top = Sp.s4),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    pendingDelete?.let { e ->
        ConfirmDialog(
            title = "删掉「${e.title}」的记录？",
            body = "续播位置会一起没掉 —— 下次再播这一部，会从第 1 集开头开始。收藏和已缓存的文件不受影响。",
            confirm = "删掉",
            onConfirm = { vm.removeHistory(e.key); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空全部观看记录？",
            body = "${history.size} 部的续播位置都会没掉。收藏和已缓存的文件不受影响。",
            confirm = "全部清空",
            onConfirm = { vm.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(e: HistoryEntry, onResume: () -> Unit, onDrop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onResume, onLongClick = onDrop)
            .padding(Sp.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(40.dp).height(56.dp).clip(R.sm).background(T.Surface3),
        ) {
            if (!e.cover.isNullOrBlank()) {
                AsyncImage(
                    model = e.cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer8()
        Column(Modifier.weight(1f)) {
            Text(
                e.title, color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${e.where} · ${ago(e.updatedAt)}",
                color = T.TextDim, fontSize = Fs.xs,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (e.resumable && e.durationSec > 0) {
                Spacer4()
                Box(
                    Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = .12f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth((e.positionSec.toFloat() / e.durationSec).coerceIn(0f, 1f))
                            .height(2.dp).clip(RoundedCornerShape(2.dp)).background(T.Play500),
                    )
                }
            }
        }
        Spacer8()
        if (e.finished) {
            Text(
                "已看完",
                color = T.Ok,
                fontSize = Fs.tiny,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(R.full)
                    .background(T.Ok.copy(alpha = .10f))
                    .border(1.dp, T.Ok.copy(alpha = .30f), R.full)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        } else {
            NumText(
                if (e.durationSec > 0) "${(e.positionSec * 100 / e.durationSec)}%" else "—",
                color = T.TextDim,
                fontSize = Fs.xs,
                modifier = Modifier.width(38.dp),
            )
        }
    }
}

/** 本地历法「今天 0 点」那一毫秒。[bucket] 与 [ago] 共用这把尺，别说成两种话。 */
private fun todayStartLocal(): Long {
    val dayMs = 86_400_000L
    val now = System.currentTimeMillis()
    return (now + TimeZoneOffsetMs) / dayMs * dayMs - TimeZoneOffsetMs
}

/** 分组：0 今天 / 1 本周更早 / 2 更早。按日历天算，不按「24 小时内」。 */
private fun bucket(ts: Long): Int {
    val dayMs = 86_400_000L
    val todayStart = todayStartLocal()
    return when {
        ts >= todayStart -> 0
        ts >= todayStart - 6 * dayMs -> 1
        else -> 2
    }
}

private val TimeZoneOffsetMs: Long
    get() = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

/**
 * 相对时间：记录列表要的是「多近」，不是精确时间戳。
 *
 * 「天」这一档和 [bucket] 用同一条历法日界线。原来它按 24 小时的倍数算，
 * 于是昨天 23:50 看的那一部在同一行里被写成「本周更早 · 40 分钟前」——
 * 分组说「上周之前那些天」，右边说「一小时内」，两句话指的是同一条记录。
 */
private fun ago(ts: Long): String {
    val dayMs = 86_400_000L
    val minutes = ((System.currentTimeMillis() - ts) / 60_000L).coerceAtLeast(0L)
    val d0 = todayStartLocal()
    val days = if (ts >= d0) 0L else (d0 - ts + dayMs - 1) / dayMs
    return when {
        days == 0L && minutes < 1 -> "刚刚"
        days == 0L && minutes < 60 -> "$minutes 分钟前"
        days == 0L -> "${minutes / 60} 小时前"
        days == 1L -> "昨天"
        days < 7 -> "$days 天前"
        else -> "${days / 7} 周前"
    }
}

/** 从站点给的 year 里认那个四位数字。认不出返回 null（不拿坏值参与排序）。 */
private val FourDigitYear = Regex("\\d{4}")

private fun yearOf(raw: String?): Int? = raw?.let { FourDigitYear.find(it)?.value?.toIntOrNull() }
