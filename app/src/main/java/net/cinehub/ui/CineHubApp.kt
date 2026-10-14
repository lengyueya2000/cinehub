package net.cinehub.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.cinehub.ui.screens.DetailScreen
import net.cinehub.ui.screens.DownloadSettingsScreen
import net.cinehub.ui.screens.DownloadsScreen
import net.cinehub.ui.screens.HomeScreen
import net.cinehub.ui.screens.LibraryScreen
import net.cinehub.ui.screens.MineScreen
import net.cinehub.ui.screens.PlayerScreen
import net.cinehub.ui.screens.SearchScreen
import net.cinehub.ui.screens.SiteBrowserScreen
import net.cinehub.ui.screens.FavoritesScreen
import net.cinehub.ui.screens.HistoryScreen
import net.cinehub.ui.screens.SourceScreen
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

private sealed interface Route {
    data object Home : Route
    data object Search : Route
    data class Detail(val sourceId: String, val mediaId: String) : Route
    data object Player : Route
    data object Library : Route
    data object Sources : Route
    data object SourceManage : Route
    data object Downloads : Route
    data object DownloadSettings : Route
    data object Favorites : Route
    data object History : Route
}

private val TABS = listOf(
    Triple("首页", Icons.Outlined.Home, 0),
    Triple("片库", Icons.Outlined.GridView, 1),
    Triple("下载", Icons.Outlined.Download, 2),
    Triple("我的", Icons.Outlined.Person, 3),
)

@Composable
fun CineHubApp(vm: AppViewModel = viewModel()) {
    var route by remember { mutableStateOf<Route>(Route.Home) }
    val playing by vm.playing.collectAsState()
    val browser by vm.browser.collectAsState()
    val ctx = LocalContext.current

    fun openDetail(sourceId: String, mediaId: String) {
        vm.openDetail(sourceId, mediaId)
        route = Route.Detail(sourceId, mediaId)
    }

    /** 交给系统浏览器 —— 影枢不去替豆瓣渲染页面，那已经是它自己的地盘。 */
    fun openExternal(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(ctx, "手机上没有能打开链接的应用", Toast.LENGTH_SHORT).show() }
    }

    // 系统返回键逐级回退，而不是直接退出应用
    BackHandler(enabled = route !is Route.Home) {
        route = when (val r = route) {
            is Route.Home -> Route.Home
            is Route.Search -> Route.Home
            is Route.Sources -> Route.Home
            is Route.Library -> Route.Home
            is Route.Downloads -> Route.Home
            is Route.Detail -> Route.Home
            // 这几页都是从「我的」进来的，返回就该回到「我的」，不该弹回首页
            is Route.SourceManage -> Route.Sources
            is Route.Favorites -> Route.Sources
            is Route.History -> Route.Sources
            is Route.DownloadSettings -> Route.Sources
            is Route.Player -> {
                // stopPlaying() 会把 playing 清掉，所以「刚才是哪一部」要先拿到手。
                val playedId = vm.playing.value?.mediaId
                vm.stopPlaying()
                val d = vm.detail.value
                // 只有详情页上挂着的就是刚才播的那一部才回得去。从「继续观看」或
                // 下载页直接起播时没人打开过详情页，那时回 Detail 等于把上一部的
                // 页面当成这一部摆给用户看。
                if (d != null && playedId != null && d.media.id == playedId) {
                    Route.Detail(vm.activeSourceId.value ?: d.media.sourceId, d.media.id)
                } else {
                    Route.Home
                }
            }
        }
    }

    // 起播时自动进播放器
    LaunchedEffect(playing) {
        if (playing != null && route !is Route.Player) route = Route.Player
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(T.Bg)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (route) {
                    is Route.Home -> HomeScreen(
                        vm = vm,
                        onOpenDetail = { s, m -> openDetail(s, m) },
                        onOpenSearch = { route = Route.Search },
                        onOpenSources = { route = Route.Sources },
                    )

                    is Route.Search -> SearchScreen(
                        vm = vm,
                        onOpenDetail = { s, m -> openDetail(s, m) },
                        onBack = { route = Route.Home },
                    )

                    is Route.Detail -> DetailScreen(
                        vm = vm,
                        onBack = { route = Route.Home },
                        onPlay = { sourceId, m, ep ->
                            // 不传 resumeFrom —— 让仓库按（这部片，这一集）决定从哪续
                            vm.play(
                                sourceId = sourceId,
                                mediaTitle = m.title,
                                episodeName = ep.name,
                                episodeIndex = ep.index,
                                episodeUrl = ep.playUrl,
                                mediaId = m.id,
                                mediaKey = m.key,
                                mediaYear = m.year,
                                mediaCover = m.cover,
                            )
                        },
                    )

                    is Route.Library -> LibraryScreen(
                        vm = vm,
                        onOpenDetail = { s, m -> openDetail(s, m) },
                        onOpenDoubanPage = { url -> openExternal(url) },
                    )

                    is Route.Downloads -> DownloadsScreen(vm)

                    is Route.Player -> PlayerScreen(
                        vm = vm,
                        onBack = {
                            vm.stopPlaying()
                            val d = vm.detail.value
                            route = if (d != null) {
                                Route.Detail(vm.activeSourceId.value ?: d.media.sourceId, d.media.id)
                            } else Route.Home
                        },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.Sources -> MineScreen(
                        vm = vm,
                        onOpenSourceManage = { route = Route.SourceManage },
                        onOpenFavorites = { route = Route.Favorites },
                        onOpenHistory = { route = Route.History },
                        onOpenDownloads = { route = Route.Downloads },
                        onOpenDownloadSettings = { route = Route.DownloadSettings },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.DownloadSettings -> DownloadSettingsScreen(
                        vm = vm,
                        onBack = { route = Route.Sources },
                    )

                    is Route.SourceManage -> SourceScreen(
                        vm = vm,
                        onBack = { route = Route.Sources },
                    )

                    is Route.Favorites -> FavoritesScreen(
                        vm = vm,
                        onBack = { route = Route.Sources },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.History -> HistoryScreen(
                        vm = vm,
                        onBack = { route = Route.Sources },
                        onResume = { e -> vm.resumeFromHistory(e) },
                    )
                }
            }

            // 底部 TabBar 在这四个平级页面显示；详情/播放器/源管理有自己的顶栏返回
            val tab = when (route) {
                is Route.Home, is Route.Search -> 0
                is Route.Library -> 1
                is Route.Downloads -> 2
                is Route.Sources -> 3
                else -> null
            }
            tab?.let { active ->
                TabBar(active = active) { idx ->
                    when (idx) {
                        0 -> route = Route.Home
                        1 -> route = Route.Library
                        2 -> route = Route.Downloads
                        3 -> route = Route.Sources
                    }
                }
            }
        }

        // 站点页面取链：盖在所有页面之上，因为它既不是一个 Tab 也不该被 Tab 切走
        browser?.let { b ->
            Box(Modifier.fillMaxSize().background(T.Bg)) {
                SiteBrowserScreen(
                    vm = vm,
                    startUrl = b.startUrl,
                    label = b.label,
                    referer = b.referer,
                    onClose = { vm.closeSiteBrowser() },
                )
            }
        }
    }
}

@Composable
private fun TabBar(active: Int, onPick: (Int) -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .72f), Color.Black.copy(alpha = .98f))
                    )
                )
                .padding(top = Sp.s2, bottom = Sp.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEachIndexed { i, (label, icon, idx) ->
                val on = i == active
                Column(
                    Modifier.weight(1f).clickable { onPick(idx) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        icon, null,
                        tint = if (on) T.Play400 else T.TextFaint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        label,
                        color = if (on) T.Text else T.TextFaint,
                        fontSize = Fs.xxs,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .background(Color.Black),
        )
    }
}
