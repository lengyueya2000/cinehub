package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.cinehub.data.model.AggregatedMedia
import net.cinehub.data.model.Health
import net.cinehub.data.model.SearchOutcome
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.ConfirmDialog
import net.cinehub.ui.components.Pill
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.StatusDot
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 跨源搜索结果。
 *
 * 一部片只出一条，下面挂各源徽标 —— 不用点进去就能判断哪个源能播、画质如何。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenDetail: (sourceId: String, mediaId: String) -> Unit,
    onBack: () -> Unit,
) {
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val failures by vm.searchFailures.collectAsState()
    val searching by vm.searching.collectAsState()
    val history by vm.history.collectAsState()
    val sources by vm.sources.collectAsState()
    val health by vm.health.collectAsState()

    val enabled = sources.filter { it.enabled }
    val hitSources = results.flatMap { it.availability }.map { it.sourceId }.distinct().size

    // 离开搜索这条线才擦干净。原来挂的是 onDispose —— 可「点进某一部片的详情」也是一次
    // dispose，于是从详情页按返回回来，那一屏刚搜出来的结果没了，搜索框也空了：
    // 用户刚做过的事被返回键替他撤销。现在擦除交给两个真正的出口（返回键、从首页那颗图标进来）。

    // 类型筛选是本页的临时状态：不写进 ViewModel、不落盘，退出搜索就没了。
    // 键在 query 上 —— 换了关键词就是另一批结果，旧筛选不该继续作用在它上面。
    var cat by remember(query) { mutableStateOf<String?>(null) }
    // 「清空」历史是全删，得问一下；删单条不问，重新搜一次就回来了
    var confirmClearHistory by remember { mutableStateOf(false) }
    // 芯片只写结果里真出现过的类型。很多源不给 category，一个可信类型都取不到时
    // 整排直接不渲染 —— 留一排点了没反应的芯片比不排更糟。
    val chips = remember(results) { resultChips(results) }
    val shown = if (cat == null) results else results.filter { it.media.category?.trim() == cat }
    // 没给类型的条目只在「全部」里出现，筛完少了几条得说得出是为什么
    val uncategorized = results.count { it.media.category?.trim().isNullOrEmpty() }

    // 「都没有〈关键词〉」这句话要有依据才能说：有源没答话就是搜过的硬证据，
    // 否则看 VM 里那个「上一次真的发出去搜过的词」。
    // **不能拿搜索历史兜底**（以前就是）：历史跨会话留着，成功搜过「三体」之后，
    // 重进这一屏只在框里再打一遍这三个字、一个请求都没发，也会被当成搜过而写出那句假话。
    // 光在框里打字还没回车时不算搜过，不能声称搜过。
    val searchedTerm by vm.searchedTerm.collectAsState()
    val searched = failures.isNotEmpty() || searchedTerm == query

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Sp.s4, vertical = Sp.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                // 44dp：这是输入框，手指要在里面点定位光标
                Modifier.weight(1f).height(44.dp).clip(R.full).background(T.Surface2)
                    .border(1.dp, T.Play500.copy(alpha = .42f), R.full).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = T.TextDim, modifier = Modifier.size(15.dp))
                Spacer8()
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索影片、演员", color = T.TextFaint, fontSize = Fs.sm)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = vm::onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = T.Text, fontSize = Fs.sm),
                        cursorBrush = SolidColor(T.Play400),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { vm.search() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 清空只在有字时出现：空框里挂一个按了没反应的 × 比不挂更吵
                if (query.isNotEmpty()) {
                    Spacer8()
                    // 看得见的那颗圆保持 22dp，但把它的命中区铺满 44×44：
                    // 原来按那一个 × 要点得很准，而它是这一行唯一的出口
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { vm.clearQuery() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = .10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close, "清空搜索框",
                                tint = T.TextDim, modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
            Spacer12()
            Text(
                "取消",
                color = T.TextDim,
                fontSize = Fs.sm,
                // 44dp：这一颗和输入框并排，行高已经撑到 44 了，它自己那点字却只有 ~18dp 可点
                modifier = Modifier.clip(R.full).heightIn(min = 44.dp)
                    .clickable { vm.clearQuery(); onBack() }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }

        if (results.isNotEmpty() && chips.isNotEmpty()) {
            LazyRow(
                Modifier.padding(bottom = Sp.s3),
                contentPadding = PaddingValues(horizontal = Sp.s4),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item {
                    CatChip("全部 ${results.size}", on = cat == null) { cat = null }
                }
                items(chips, key = { it.first }) { (name, count) ->
                    // 再点一次已选中的芯片 = 取消筛选，回到不过滤的原样
                    CatChip("$name $count", on = cat == name) {
                        cat = if (cat == name) null else name
                    }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Sp.s4, vertical = Sp.s2),
        ) {
            if (results.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(R.md)
                            .background(T.Src500.copy(alpha = .055f))
                            .border(1.dp, T.Src500.copy(alpha = .20f), R.md)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(26.dp).clip(R.sm).background(T.Src500.copy(alpha = .14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("源", color = T.Src500, fontSize = Fs.xxs, fontWeight = FontWeight.Bold)
                        }
                        Spacer8()
                        Text(
                            "已同时检索 ${enabled.size} 个播放源，命中 ${results.size} 条，" +
                                "${hitSources} 个源有结果，重复条目已合并。",
                            color = T.TextDim,
                            fontSize = Fs.xxs,
                            lineHeight = Fs.xxs * 1.5f,
                        )
                    }
                    Spacer12()
                }
                // 筛过就得说明现在是筛过的状态，以及少掉的那些去哪了 ——
                // 不然列表变短会被读成「搜索结果变了」，其实是芯片生效了。
                if (cat != null) {
                    item {
                        Text(
                            "已按类型「$cat」筛出 ${shown.size} 条；这次没回类型信息的 $uncategorized 条只在「全部」里显示。",
                            color = T.TextFaint,
                            fontSize = Fs.xxs,
                            lineHeight = Fs.xxs * 1.5f,
                            modifier = Modifier.padding(bottom = Sp.s3),
                        )
                    }
                }
            }

            if (searching) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("正在并发查询 ${enabled.size} 个源…", color = T.TextDim, fontSize = Fs.sm)
                    }
                }
            }

            // 芯片点了却被筛空：说清是这个筛选条件下没有，不是搜索坏了，并留一个能点的退出口。
            if (!searching && results.isNotEmpty() && shown.isEmpty()) {
                item {
                    FilteredOutBlock(cat, uncategorized) { cat = null }
                }
            }

            if (!searching && results.isEmpty()) {
                item {
                    Column {
                        // 搜过了却一条都没有，界面不能继续沉默 —— 也绝不假装成功。
                        if (query.isNotBlank() && searched) {
                            NoResultBlock(query, enabled.size, failures) { vm.search() }
                            Spacer12()
                        }
                        if (history.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "搜索历史",
                                    color = T.Text,
                                    fontSize = Fs.body2,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "清空",
                                    color = T.TextDim,
                                    fontSize = Fs.xs,
                                    modifier = Modifier.clip(R.full)
                                        .clickable { confirmClearHistory = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Spacer8()
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sp.s2)) {
                                // 键用词本身：长按删掉一条之后，靠位置认项会把邻居的
                                // 组合槽位接过去（删的是第三个，动的却是第四个）。
                                items(history, key = { it }) { h ->
                                    Text(
                                        h,
                                        color = T.TextDim,
                                        fontSize = Fs.xs,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clip(R.full).background(T.Surface2)
                                            // 长按删一条，不弹确认：删错了重新搜一次就回来了
                                            .combinedClickable(
                                                onClick = { vm.search(h) },
                                                onLongClick = { vm.removeSearchHistory(h) },
                                            )
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                            Spacer12()
                        }
                        Spacer8()
                        Text("当前接入的播放源", color = T.Text, fontSize = Fs.body2, fontWeight = FontWeight.Bold)
                        Spacer8()
                        enabled.forEach { cfg ->
                            val h = health[cfg.id]?.health ?: Health.Unknown
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(h)
                                Spacer8()
                                Text(cfg.name, color = T.Text, fontSize = Fs.body2, modifier = Modifier.weight(1f))
                                Text(
                                    when (h) {
                                        // 拿不到毫秒时写「已连通」，不拼出 "-ms" 这种不可能的字。
                                        // washHealth 会给「探根路径没答话、但搜索答上了」的源
                                        // 留下 latencyMs = null —— 那一格是绿的，数字却编不出来。
                                        // 判法同详情页那一栏（见 DetailScreen 的同一段）。
                                        Health.Ok -> if (health[cfg.id]?.latencyMs != null)
                                            "${health[cfg.id]?.latencyMs}ms" else "已连通"
                                        Health.Slow -> "较慢"
                                        Health.Down -> "不可用"
                                        Health.Unknown -> "未检测"
                                    },
                                    // 这一格承载的是「哪个源没答上话」这个事实，不是装饰；
                                    // TextFaint 在纯黑上只有 3.5:1，够不上正文 4.5:1 的门槛
                                    color = T.TextDim,
                                    fontSize = Fs.xxs,
                                )
                            }
                        }
                    }
                }
            }

            // key 用 rowKey 而不是 media.key：同名同年的两条不同条目（类型不同）
            // media.key 完全一样，拿它当 key 会让 LazyColumn 抛重复 key 而闪退
            items(shown, key = { it.rowKey }) { agg ->
                ResultRow(agg) { av -> onOpenDetail(av.sourceId, av.item.id) }
            }

            // 有结果时也不能把没答话的源藏起来：少掉几个源，用户有权知道
            if (shown.isNotEmpty() && failures.isNotEmpty()) {
                item {
                    Text(
                        "另有 ${failures.size} 个源没答上话：" +
                            failures.joinToString("；") { "${it.sourceName} ${it.reason}" },
                        // 这一句承载的是「少了哪几个源、为什么少」这个事实，
                        // 用最小最弱的那一号字（9.5sp + 黑底约 3.5:1）等于不想让人看见
                        color = T.TextDim,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.6f,
                        modifier = Modifier.padding(top = Sp.s3),
                    )
                }
            }
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = "清空搜索历史",
            body = "这 ${history.size} 条搜索词只存在这台设备上，清掉就找不回来了。",
            confirm = "清空",
            onConfirm = { vm.clearSearchHistory(); confirmClearHistory = false },
            onDismiss = { confirmClearHistory = false },
        )
    }
}

/**
 * 搜完了、一条都没有。
 *
 * 「几个源压根没答话」和「源都答了、确实没有这部片」是两件事，
 * 合成一句「无结果」用户只能猜 —— 所以分开说，没答话的要点名到具体哪个源、为什么。
 * 这里不写「搜索完成」之类的话：结果确实空着，说什么都不能把它说成成功。
 */
@Composable
private fun NoResultBlock(
    query: String,
    searchedSources: Int,
    failures: List<SearchOutcome.Failure>,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(R.md)
            .background(if (failures.isEmpty()) T.Surface2 else T.Warn.copy(alpha = .07f))
            .border(1.dp, if (failures.isEmpty()) T.Border else T.Warn.copy(alpha = .28f), R.md)
            .padding(13.dp),
    ) {
        if (failures.isEmpty()) {
            Text(
                "检索了 $searchedSources 个播放源，都没有「$query」",
                color = T.Text,
                fontSize = Fs.body2,
                fontWeight = FontWeight.Bold,
            )
            Spacer4()
            Text(
                "这些源是各自去站里按片名找词的，写法差一点就对不上。换个更短、更接近片名的关键词再试一次。",
                color = T.TextDim,
                fontSize = Fs.xs,
                lineHeight = Fs.xs * 1.6f,
            )
        } else {
            Text(
                "${failures.size} 个源没答上话，其余源里也没有「$query」",
                color = T.Warn,
                fontSize = Fs.body2,
                fontWeight = FontWeight.Bold,
            )
            Spacer8()
            // reason 已经是仓库给好的短中文，直接列出来，不再自己编一套说法
            failures.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        f.sourceName,
                        color = T.Text,
                        fontSize = Fs.xs,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer8()
                    Text(f.reason, color = T.TextFaint, fontSize = Fs.xs)
                }
            }
            Spacer8()
            Text(
                "这几个源这次没返回任何内容。换个关键词再搜一次，" +
                    "或到下面「当前接入的播放源」和底部「我的 → 播放源管理」里看它们现在的状态。",
                color = T.TextDim,
                fontSize = Fs.xs,
                lineHeight = Fs.xs * 1.6f,
            )
        }
        Spacer12()
        Text(
            "重新搜索「$query」",
            color = T.Play400,
            fontSize = Fs.sm,
            fontWeight = FontWeight.SemiBold,
            // 44dp：这是这一屏唯一的出口，点不中就等于没有出口
            modifier = Modifier.clip(R.sm).heightIn(min = 44.dp)
                .clickable(onClick = onRetry)
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}

/** 筛选筛空了的一屏：说的是「这个条件下没有」，并给一个立刻能点的出口。 */
@Composable
private fun FilteredOutBlock(cat: String?, hidden: Int, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(R.md).background(T.Surface2).border(1.dp, T.Border, R.md)
            .padding(13.dp),
    ) {
        Text(
            "这个筛选条件下没有内容",
            color = T.Text,
            fontSize = Fs.body2,
            fontWeight = FontWeight.Bold,
        )
        Spacer4()
        Text(
            "有些播放源不给条目返回类型信息（这次有 $hidden 条就没给），它们不会出现在任何类型芯片下。",
            color = T.TextDim,
            fontSize = Fs.xs,
            lineHeight = Fs.xs * 1.6f,
        )
        Spacer12()
        Text(
            if (cat != null) "清除筛选「$cat」" else "清除筛选",
            color = T.Play400,
            fontSize = Fs.sm,
            fontWeight = FontWeight.SemiBold,
            // 同上：这一屏唯一的出口
            modifier = Modifier.clip(R.sm).heightIn(min = 44.dp)
                .clickable(onClick = onClear)
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}

/** 只列这次结果里真出现过的类型（类型名 + 命中条数），芯片上写的数字就是点了能剩下多少。 */
private fun resultChips(items: List<AggregatedMedia>): List<Pair<String, Int>> =
    items.mapNotNull { it.media.category?.trim()?.takeIf { c -> c.isNotEmpty() } }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

/** 可点筛选芯片（选中态用底色 + 文字双重表达，不单靠颜色）。 */
@Composable
private fun CatChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.White else T.TextDim,
        fontSize = Fs.sm,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(R.full)
            .background(if (on) T.Play500.copy(alpha = .16f) else T.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}


@Composable
private fun ResultRow(agg: AggregatedMedia, onClick: (net.cinehub.data.model.SourceAvailability) -> Unit) {
    val best = agg.availability.firstOrNull()
    Row(
        Modifier.fillMaxWidth()
            .clickable { best?.let(onClick) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(58.dp, 82.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                .background(T.Surface2)
                .border(1.dp, T.Border, androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
        ) {
            if (!agg.media.cover.isNullOrBlank()) {
                AsyncImage(
                    model = agg.media.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer12()
        Column(Modifier.weight(1f)) {
            Text(
                agg.media.title,
                color = T.Text,
                fontSize = Fs.body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer4()
            Text(
                listOfNotNull(
                    agg.media.year,
                    agg.media.category ?: agg.media.remark,
                    agg.media.area,
                ).joinToString(" · ").ifBlank { "—" },
                color = T.TextFaint,
                fontSize = Fs.xxs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer8()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(agg.availability, key = { it.sourceId }) { av ->
                    val dim = av.health == Health.Down
                    Row(
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(
                                if (dim) Color.White.copy(alpha = .04f)
                                else T.Src500.copy(alpha = .09f)
                            )
                            .border(
                                1.dp,
                                if (dim) T.Border else T.Src500.copy(alpha = .30f),
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            )
                            // 这一排芯片是这一行**唯一**说出「哪个源能播」的地方
                            // （上面那句注释就写着「不用点进去就能判断」），点它却什么都不做 ——
                            // 而整行是能点的、点下去进的是 firstOrNull() 那个源。
                            // 用户点「源B 1080P」却被送进源A，且没有任何交代。所以这里接上。
                            .clickable { onClick(av) }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(av.health, 5)
                        Spacer4()
                        Text(
                            buildString {
                                append(av.sourceName)
                                av.quality?.let { append(" ").append(it) }
                                if (dim) append(" 不可用")
                            },
                            color = if (dim) T.TextFaint else T.Src500,
                            fontSize = Fs.tiny,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Spacer8()
        Column(horizontalAlignment = Alignment.End) {
            agg.availability.firstNotNullOfOrNull { it.quality }?.let { Pill(it, cyan = true) }
            Spacer8()
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(T.Surface2)
                    .border(1.dp, T.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = T.TextDim,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .055f)))
}
