package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.Favorite
import net.cinehub.data.model.HistoryEntry
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/** 两页共用的顶栏：返回 + 标题 + 右侧一个可选动作。 */
@Composable
private fun WatchHeader(title: String, sub: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = T.Text, modifier = Modifier.size(19.dp))
        }
        Spacer8()
        Column(Modifier.weight(1f)) {
            Text(title, color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
            Text(sub, color = T.TextFaint, fontSize = Fs.xxs)
        }
        action?.invoke()
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = T.TextFaint, fontSize = Fs.sm)
    }
}

/**
 * 收藏列表。
 *
 * 点卡片进详情页（那里那颗心就是取消收藏的开关），长按直接移掉 ——
 * 和详情页「长按某一集 = 只缓存它」是同一套手感。
 */
@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
) {
    val favorites by vm.favorites.collectAsState()

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        WatchHeader("收藏", "${favorites.size} 部", onBack)
        if (favorites.isEmpty()) {
            Empty("还没有收藏。在详情页点那颗心就能收进来。")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2),
            horizontalArrangement = Arrangement.spacedBy(Sp.s3),
            verticalArrangement = Arrangement.spacedBy(Sp.s4),
        ) {
            items(favorites, key = { it.key }) { f ->
                FavoriteCell(f, onOpen = { onOpenDetail(f.sourceId, f.mediaId) }, onDrop = {
                    vm.removeFavorite(f.key)
                })
            }
        }
        Spacer4()
        Text(
            "长按某一张 = 取消收藏",
            color = T.TextFaint,
            fontSize = Fs.tiny,
            modifier = Modifier.padding(horizontal = Sp.s4),
        )
    }
}

@Composable
private fun FavoriteCell(f: Favorite, onOpen: () -> Unit, onDrop: () -> Unit) {
    PosterCard(
        title = f.title,
        subtitle = listOfNotNull(f.year?.take(4), f.category).joinToString(" · ").ifBlank { null },
        cover = f.cover,
        onClick = onOpen,
        onLongClick = onDrop,
    )
}

/**
 * 最近观看。
 *
 * 点一行直接接上上次那一集；每条右侧一个删除，顶部一个清空。
 * 这里不提供「编辑」—— 记录是播放器写出来的，能改的只有「不要了」。
 */
@Composable
fun HistoryScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onResume: (HistoryEntry) -> Unit,
) {
    val history by vm.watchHistory.collectAsState()

    Column(Modifier.fillMaxSize().background(T.Bg)) {
        WatchHeader("最近观看", "${history.size} 部", onBack, action = {
            if (history.isNotEmpty()) {
                Text(
                    "清空",
                    color = T.Src500,
                    fontSize = Fs.sm,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.clearHistory() },
                )
            }
        })
        if (history.isEmpty()) {
            Empty("还没有观看记录。播一部片就会出现在这里。")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2)) {
            items(history, key = { it.key }) { e ->
                HistoryRow(e, onResume = { onResume(e) }, onDrop = { vm.removeHistory(e.key) })
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onResume: () -> Unit, onDrop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Sp.s2)
            .clip(R.md).background(T.Surface2)
            .border(1.dp, T.Border, R.md)
            .clickable(onClick = onResume)
            .padding(Sp.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(52.dp).height(72.dp).clip(R.sm).background(T.Surface3),
        ) {
            if (!e.cover.isNullOrBlank()) {
                AsyncImage(
                    model = e.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer12()
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                color = T.Text,
                fontSize = Fs.body2,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer4()
            Text(e.where, color = T.TextDim, fontSize = Fs.xs)
            Spacer4()
            Text(
                when {
                    e.finished -> "已看完"
                    else -> "上次看到 · ${ago(e.updatedAt)}"
                },
                color = T.TextFaint,
                fontSize = Fs.tiny,
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = "删除这条记录",
            tint = T.TextFaint,
            modifier = Modifier.size(18.dp).clickable(onClick = onDrop),
        )
    }
}

/** 相对时间：记录列表要的是「多近」，不是精确时间戳。 */
private fun ago(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    val m = d / 60_000
    val h = m / 60
    val day = h / 24
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "${m} 分钟前"
        h < 24 -> "${h} 小时前"
        day < 7 -> "${day} 天前"
        else -> "${day / 7} 周前"
    }
}
