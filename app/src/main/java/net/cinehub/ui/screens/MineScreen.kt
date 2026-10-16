package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.Health
import net.cinehub.data.model.HealthReport
import net.cinehub.data.model.SourceConfig
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.GroupCard
import net.cinehub.ui.components.GroupDivider
import net.cinehub.ui.components.PosterCard
import net.cinehub.ui.components.SectionHead
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 「我的」：三条海报轨 + 一组设置入口。
 *
 * 这一页的主角是「我自己的东西」，所以收藏 / 最近观看 / 离线缓存各占一条横轨，
 * 用和首页一样的海报语言 —— 全是首页没有的：首页那条「继续观看」只列没看完的，
 * 这里列的是完整历史（含已看完的）；收藏与缓存首页根本不出现。
 *
 * 源的健康状况仍要在这一页说话（它是全应用唯一需要盯的东西），但不抢主角：
 * 收在设置组第一行的圆点 + 文字里，点进去才是完整的管理页。
 */
@Composable
fun MineScreen(
    vm: AppViewModel,
    onOpenSourceManage: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDownloadSettings: () -> Unit,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
) {
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val history by vm.watchHistory.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val segParallel by vm.segParallel.collectAsState()

    val cached = downloads.filter { it.status == DownloadStatus.Done }
    val enabled = sources.filter { it.enabled }
    val down = enabled.count { health[it.id]?.health == Health.Down }
    val slow = enabled.count { health[it.id]?.health == Health.Slow }
    val ok = enabled.count { health[it.id]?.health == Health.Ok }

    val ctx = LocalContext.current
    // 版本号从包里取，不写死 —— 写死的那份迟早开始说谎
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    }

    LazyColumn(
        Modifier.fillMaxSize().background(T.Bg),
        // 这一页不再摆标题：底部导航那颗「我的」已经说明人在哪，顶部再来一遍只是占一屏
        contentPadding = PaddingValues(top = Sp.s5, bottom = Sp.s7),
    ) {
        // ---------- 收藏 ----------
        item {
            SectionHead(
                "收藏",
                trailing = if (favorites.isEmpty()) null else "全部 ›",
                onTrailing = if (favorites.isEmpty()) null else onOpenFavorites,
                modifier = Modifier.padding(start = Sp.s4, end = Sp.s4, bottom = Sp.s2),
            )
        }
        if (favorites.isEmpty()) {
            // 空的时候不留一条空轨 —— 但这一栏是这页的开场，给一句下一步该干什么
            item {
                Text(
                    "还没有收藏。详情页封面下面那颗心点一下，这里就会出现。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s2),
                )
            }
        } else {
            item {
                PosterRail {
                    items(favorites.take(RAIL_MAX), key = { "fav_" + it.key }) { f ->
                        PosterCard(
                            title = f.title,
                            subtitle = listOfNotNull(f.year, f.category).joinToString(" · ")
                                .ifBlank { "已收藏" },
                            cover = f.cover,
                            modifier = Modifier.width(RAIL_W),
                            onClick = { onOpenDetail(f.sourceId, f.mediaId) },
                        )
                    }
                }
            }
        }

        // ---------- 最近观看 ----------
        // 整段只在有内容时出现：一条记录都没有时，这里没话可说
        if (history.isNotEmpty()) {
            item {
                SectionHead(
                    "最近观看",
                    trailing = "全部 ›",
                    onTrailing = onOpenHistory,
                    modifier = Modifier.padding(start = Sp.s4, end = Sp.s4, top = Sp.s5, bottom = Sp.s2),
                )
            }
            item {
                PosterRail {
                    items(history.take(RAIL_MAX), key = { "his_" + it.key }) { e ->
                        PosterCard(
                            title = e.title,
                            subtitle = if (e.finished) "已看完 · ${e.episodeName}" else e.where,
                            cover = e.cover,
                            modifier = Modifier.width(RAIL_W),
                            // 看完的那一部进度条不再画 —— 封底留白就是「不用再接」
                            progress = if (e.resumable && e.durationSec > 0)
                                (e.positionSec.toFloat() / e.durationSec).coerceIn(0f, 1f) else null,
                            onClick = { vm.resumeFromHistory(e) },
                        )
                    }
                }
            }
        }

        // ---------- 离线缓存 ----------
        if (cached.isNotEmpty()) {
            item {
                // 这一栏不再挂「管理 ›」：下面设置组里那行「离线缓存」去的就是同一页，
                // 而底部导航那颗「下载」也去那一页 —— 同一件事的三个入口留一个就够，
                // 留在这页里的是带数字的那一行（它还能说出几集可播、几集在下）。
                SectionHead(
                    "离线缓存",
                    modifier = Modifier.padding(start = Sp.s4, end = Sp.s4, top = Sp.s5, bottom = Sp.s2),
                )
            }
            item {
                PosterRail {
                    items(cached.take(RAIL_MAX), key = { "dl_" + it.id }) { t ->
                        PosterCard(
                            title = t.mediaTitle,
                            subtitle = t.episodeName,
                            cover = t.mediaCover,
                            badge = "可离线",
                            modifier = Modifier.width(RAIL_W),
                            onClick = { vm.playCached(t) },
                        )
                    }
                }
            }
        }

        // ---------- 设置 ----------
        item {
            SectionHead(
                "设置",
                modifier = Modifier.padding(start = Sp.s4, end = Sp.s4, top = Sp.s6, bottom = Sp.s2),
            )
        }
        item {
            GroupCard {
                SettingRow(
                    icon = Icons.Filled.Settings,
                    tint = T.Src500,
                    title = "播放源管理",
                    // 健康不只靠颜色：结论先写成字，圆点只是让它更快扫到
                    extra = { SourceHealthStrip(enabled, health, ok, slow, down) },
                    onClick = onOpenSourceManage,
                )
                GroupDivider(rowTextStart)
                SettingRow(
                    icon = Icons.Outlined.Download,
                    tint = T.Ok,
                    title = "离线缓存",
                    sub = if (cached.isEmpty()) "还没有缓存好的集"
                    else "${cached.size} 集可离线播放 · ${
                        downloads.count { it.status == DownloadStatus.Running }
                    } 集下载中",
                    onClick = onOpenDownloads,
                )
                GroupDivider(rowTextStart)
                SettingRow(
                    icon = Icons.Outlined.Tune,
                    tint = T.Gold,
                    title = "下载设置",
                    sub = "缓存一集时同时开 $segParallel 条连接取分片",
                    onClick = onOpenDownloadSettings,
                )
            }
        }

        item {
            Text(
                buildString {
                    if (version != null) append("影枢 v$version")
                    append(" · 收藏、进度与凭证只存这台设备")
                },
                color = T.TextDim,
                fontSize = Fs.xs,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = Sp.s4, end = Sp.s4, top = Sp.s6),
            )
        }
    }
}

/** 横轨的外壳：左右留白与首页对齐，卡片之间 12dp。 */
@Composable
private fun PosterRail(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Sp.s4),
        horizontalArrangement = Arrangement.spacedBy(Sp.s3),
        content = content,
    )
}

/**
 * 设置那一组里，标题文字离卡片左边的距离：行的内边距 + 图标 + 图标与文字的间距。
 *
 * 分隔线要正好起笔在这里，所以这个数得和 [SettingRow] 的内边距一同维护。
 */
private val rowTextStart = Sp.s4 + 30.dp + Sp.s3

@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    sub: String? = null,
    extra: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column {
        Row(
            // 整行至少 52dp：这一页最小的可点项也要够拇指。
            // heightIn 必须写在 padding 外面，否则它约束的是内容而不是整行，
            // 单行标题那一条会被撑到 76dp，看着像坏了。
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .heightIn(min = 52.dp)
                // 下面还要接一段状态条时，行的下内边距收紧，否则那一段看着像隔了一屏
                .padding(start = Sp.s4, end = Sp.s4, top = Sp.s3, bottom = if (extra == null) Sp.s3 else Sp.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(R.sm).background(tint.copy(alpha = .13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(Sp.s3))
            Column(Modifier.weight(1f)) {
                Text(title, color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.SemiBold)
                // 副标题紧贴标题：它俩是一句话，中间断开就会读成两件事
                if (sub != null) {
                    Text(
                        sub,
                        color = T.TextDim,
                        fontSize = Fs.xxs,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, null, tint = T.TextFaint, modifier = Modifier.size(18.dp))
        }
        extra?.invoke()
    }
}

/**
 * 源健康：一句结论 + 一排圆点，缩进到和标题文字同一根起笔线上。
 *
 * 结论必须是字，圆点只是让它更快扫到 —— 单靠颜色传达状态是不合格的。
 * 有源不可用时要点名：否则用户是到了详情页播放失败才发现的。
 */
@Composable
private fun SourceHealthStrip(
    enabled: List<SourceConfig>,
    health: Map<String, HealthReport>,
    ok: Int,
    slow: Int,
    down: Int,
) {
    if (enabled.isEmpty()) return
    val indent = Sp.s4 + 30.dp + Sp.s3
    val shown = enabled.take(6)
    val worst = enabled.firstOrNull { health[it.id]?.health == Health.Down }
        ?: enabled.firstOrNull { health[it.id]?.health == Health.Slow }
    val unknown = enabled.count {
        val h = health[it.id]?.health
        h == null || h == Health.Unknown
    }
    Column(Modifier.padding(start = indent, end = Sp.s4, bottom = Sp.s3)) {
        Text(
            when {
                down > 0 && worst != null -> "$down 个不可用（${worst.name}）· $slow 个较慢"
                slow > 0 -> "$slow 个较慢 · $ok 个正常"
                ok == enabled.size -> "全部 $ok 个源正常"
                // 「5 个里测出 3 个、还有 2 个没测」不能整句写成「还没检测过」——
                // 测过就是测过，而这两句话给的下一步根本不是同一件事
                unknown == enabled.size -> "还没检测过，点进去测一次"
                else -> "$ok 个正常 · 另有 $unknown 个还没测过"
            },
            color = when {
                down > 0 -> T.Err
                slow > 0 -> T.Warn
                else -> T.TextDim
            },
            fontSize = Fs.xxs,
        )
        Row(Modifier.padding(top = Sp.s2)) {
            shown.forEachIndexed { i, s ->
                StatusDot(health[s.id]?.health ?: Health.Unknown, size = 6)
                if (i < shown.lastIndex) Spacer(Modifier.width(Sp.s2))
            }
        }
    }
}

private val RAIL_W = 104.dp
private const val RAIL_MAX = 12
