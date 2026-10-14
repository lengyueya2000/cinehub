package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 「我的」：三行同级入口。
 *
 * 之前这一页就是播放源管理本身，收藏和最近观看被塞在它头顶，看起来像源管理的附属品。
 * 三者其实平级 —— 都是「我自己的东西」，所以这里只放菜单，各页各自成一屏。
 */
@Composable
fun MineScreen(
    vm: AppViewModel,
    onOpenSourceManage: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val history by vm.watchHistory.collectAsState()

    val enabled = sources.count { it.enabled }
    val ok = sources.count { health[it.id]?.health == net.cinehub.data.model.Health.Ok }
    val slow = sources.count { health[it.id]?.health == net.cinehub.data.model.Health.Slow }
    val down = sources.count { health[it.id]?.health == net.cinehub.data.model.Health.Down }

    LazyColumn(
        Modifier.fillMaxSize().background(T.Bg),
        contentPadding = PaddingValues(top = Sp.s2, bottom = Sp.s6),
        verticalArrangement = Arrangement.spacedBy(Sp.s2),
    ) {
        item {
            Column(Modifier.padding(horizontal = Sp.s4, vertical = Sp.s3)) {
                Text("我的", color = T.Text, fontSize = Fs.navTitle, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    "播放源、收藏与观看记录都在这里",
                    color = T.TextFaint,
                    fontSize = Fs.xxs,
                )
            }
        }
        item {
            MenuRow(
                icon = Icons.Filled.Settings,
                tint = T.Src500,
                title = "播放源管理",
                sub = "$enabled 个源已接入 · $ok 正常 / $slow 较慢 / $down 不可用",
                onClick = onOpenSourceManage,
            )
        }
        item {
            MenuRow(
                icon = Icons.Filled.Favorite,
                tint = T.Play400,
                title = "收藏",
                sub = if (favorites.isEmpty()) "还没有收藏，详情页那颗心点一下" else "${favorites.size} 部",
                onClick = onOpenFavorites,
            )
        }
        item {
            MenuRow(
                icon = Icons.Filled.History,
                tint = T.Gold,
                title = "最近观看",
                sub = if (history.isEmpty()) "播过的片会记在这里，下次从那一秒接上"
                else "${history.size} 部 · 最近一部 ${history.first().title}",
                onClick = onOpenHistory,
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s2 / 2)
            .clip(R.lg).background(T.Surface2)
            .border(1.dp, T.Border, R.lg)
            .clickable(onClick = onClick)
            .padding(horizontal = Sp.s4, vertical = Sp.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = .14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.size(Sp.s3))
        Column(Modifier.weight(1f)) {
            Text(title, color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
            Spacer4()
            Text(sub, color = T.TextFaint, fontSize = Fs.xxs, maxLines = 1)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = T.TextFaint, modifier = Modifier.size(20.dp))
    }
}
