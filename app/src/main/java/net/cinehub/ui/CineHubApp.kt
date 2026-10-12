package net.cinehub.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.cinehub.ui.screens.DetailScreen
import net.cinehub.ui.screens.HomeScreen
import net.cinehub.ui.screens.PlayerScreen
import net.cinehub.ui.screens.SearchScreen
import net.cinehub.ui.screens.SourceScreen
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

private sealed interface Route {
    data object Home : Route
    data object Search : Route
    data class Detail(val sourceId: String, val mediaId: String) : Route
    data object Player : Route
    data object Sources : Route
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

    fun openDetail(sourceId: String, mediaId: String) {
        vm.openDetail(sourceId, mediaId)
        route = Route.Detail(sourceId, mediaId)
    }

    // 系统返回键逐级回退，而不是直接退出应用
    BackHandler(enabled = route !is Route.Home) {
        route = when (val r = route) {
            is Route.Home -> Route.Home
            is Route.Search -> Route.Home
            is Route.Sources -> Route.Home
            is Route.Detail -> Route.Home
            is Route.Player -> {
                vm.stopPlaying()
                val d = vm.detail.value
                if (d != null) Route.Detail(vm.activeSourceId.value ?: d.media.sourceId, d.media.id)
                else Route.Home
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
                        onPlay = { sourceId, title, ep ->
                            vm.play(sourceId, title, ep.name, ep.index, ep.playUrl, resumeFrom = 0)
                        },
                    )

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

                    is Route.Sources -> SourceScreen(vm = vm, onBack = { route = Route.Home })
                }
            }

            if (route is Route.Home || route is Route.Search) {
                TabBar(active = 0) { idx ->
                    when (idx) {
                        0 -> route = Route.Home
                        3 -> route = Route.Sources
                        else -> route = Route.Home
                    }
                }
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
