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
    /*
     * 返回该回「刚才来的那一页」，不是永远回首页。
     *
     * 以前每一页的 onBack 都写死成 Route.Home：从搜索结果点进一部片，看完详情按返回，
     * 人掉回首页，刚才那一屏结果没了 —— 想接着挑就得重新搜一遍。从「我的」进收藏、
     * 进播放源管理也一样，返回键把人弹回首页，那扇门旁边的一切都得重走。
     * 底下这份栈只管这一层：切 Tab 是换一条线，栈清空。
     */
    val backStack = remember { mutableListOf<Route>() }

    fun go(next: Route) {
        backStack.add(route)
        route = next
    }

    fun backOr(fallback: Route) {
        route = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else fallback
    }

    /** 换一条线（Tab）：不是「回去」，是把栈丢掉。 */
    fun switchTab(next: Route) {
        backStack.clear()
        route = next
    }
    val playing by vm.playing.collectAsState()
    val browser by vm.browser.collectAsState()
    val notice by vm.notice.collectAsState()
    val ctx = LocalContext.current

    // 走不通的分支得说出一句话。没有这一条，那些 `?: return` 的入口就是「按下去没反应」，
    // 而用户只能靠反复点、反复重装去猜到底哪儿出了问题。
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.consumeNotice()
        }
    }

    fun openDetail(sourceId: String, mediaId: String) {
        vm.openDetail(sourceId, mediaId)
        go(Route.Detail(sourceId, mediaId))
    }

    /** 交给系统浏览器 —— 影枢不去替豆瓣渲染页面，那已经是它自己的地盘。 */
    fun openExternal(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(ctx, "手机上没有能打开链接的应用", Toast.LENGTH_SHORT).show() }
    }

    // 系统返回键和屏上那颗返回走的是同一份栈，不再各写一套去处
    BackHandler(enabled = route !is Route.Home) {
        route = when (route) {
            is Route.Player -> {
                // stopPlaying() 会把 playing 清掉，所以「刚才是哪一部」要先拿到手。
                val playedId = vm.playing.value?.mediaId
                vm.stopPlaying()
                val d = vm.detail.value
                // 只有详情页上挂着的就是刚才播的那一部才回得去。从「继续观看」或下载页
                // 直接起播时没人打开过详情页，那时回 Detail 等于把上一部的页面当成这一部摆出来。
                if (d != null && playedId != null && d.media.id == playedId) {
                    Route.Detail(vm.activeSourceId.value ?: d.media.sourceId, d.media.id)
                } else {
                    backStack.removeLastOrNull() ?: Route.Home
                }
            }

            else -> backStack.removeLastOrNull() ?: Route.Home
        }
    }

    // 起播时自动进播放器 —— 但只认「从没有到有了」这一次跳变。
    //
    // 原来盯的是 playing 这个对象本身：它每变一次（换源、拿到直链、自动换源那一轮改 note）
    // 这里就跑一次，把人从详情页、从下载页硬拽回播放器。用户明明已经退出来了，
    // 屏上却又弹出播放中 —— 那是把「状态有更新」当成了「用户想播放」。
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(playing != null) {
        val now = playing != null
        if (now && !wasPlaying && route !is Route.Player) route = Route.Player
        wasPlaying = now
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
                        // 从首页那颗搜索图标进来是「新开一趟」，先擦干净；
                        // 从详情页按返回回到搜索页则不擦 —— 那一屏结果本来就是用户搜出来的。
                        onOpenSearch = { vm.resetSearch(); go(Route.Search) },
                        onOpenSources = { go(Route.Sources) },
                    )

                    is Route.Search -> SearchScreen(
                        vm = vm,
                        onOpenDetail = { s, m -> openDetail(s, m) },
                        onBack = { vm.resetSearch(); backOr(Route.Home) },
                    )

                    is Route.Detail -> DetailScreen(
                        vm = vm,
                        onBack = { backOr(Route.Home) },
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
                            // 和上面那条返回路径同一个规矩：先记下刚播的是哪一部再 stopPlaying，
                            // 而且只有详情页挂着的就是这一部才回得去。从「继续观看」或下载页
                            // 直接起播时没人打开过详情页，那时回 Detail 等于把上一部的页面
                            // 当成这一部摆出来 —— 选集、收藏那颗心全是别的片的。
                            val playedId = vm.playing.value?.mediaId
                            vm.stopPlaying()
                            val d = vm.detail.value
                            route = if (d != null && playedId != null && d.media.id == playedId) {
                                Route.Detail(vm.activeSourceId.value ?: d.media.sourceId, d.media.id)
                            } else Route.Home
                        },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.Sources -> MineScreen(
                        vm = vm,
                        onOpenSourceManage = { go(Route.SourceManage) },
                        onOpenFavorites = { go(Route.Favorites) },
                        onOpenHistory = { go(Route.History) },
                        onOpenDownloads = { go(Route.Downloads) },
                        onOpenDownloadSettings = { go(Route.DownloadSettings) },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.DownloadSettings -> DownloadSettingsScreen(
                        vm = vm,
                        onBack = { backOr(Route.Sources) },
                    )

                    is Route.SourceManage -> SourceScreen(
                        vm = vm,
                        onBack = { backOr(Route.Sources) },
                    )

                    is Route.Favorites -> FavoritesScreen(
                        vm = vm,
                        onBack = { backOr(Route.Sources) },
                        onOpenDetail = { s, m -> openDetail(s, m) },
                    )

                    is Route.History -> HistoryScreen(
                        vm = vm,
                        onBack = { backOr(Route.Sources) },
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
                        0 -> switchTab(Route.Home)
                        1 -> switchTab(Route.Library)
                        2 -> switchTab(Route.Downloads)
                        3 -> switchTab(Route.Sources)
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
