package net.cinehub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.components.GroupCard
import net.cinehub.ui.components.SectionLabel
import net.cinehub.ui.components.Spacer12
import net.cinehub.ui.components.Spacer4
import net.cinehub.ui.components.Spacer8
import net.cinehub.ui.components.SubHeader
import net.cinehub.ui.theme.Fs
import net.cinehub.ui.theme.R
import net.cinehub.ui.theme.Sp
import net.cinehub.ui.theme.T

/**
 * 播放设置 —— 自动连播、跳过片头/片尾。
 *
 * 和「下载设置」分开一页：那页说的是「接下来怎么下」，这页说的是「拿到之后怎么看」，
 * 两件事凑在一页里，改一个的时候眼睛要在另一堆不相干的选项里找边界。
 *
 * 倍速与画面比例不在这里 —— 那两个是「看着当下调」的东西，播放器里就有芯片和面板，
 * 拉回设置页再调反而绕。这里放的是**不常改、改一次管很久**的偏好。
 */
@Composable
fun PlaybackSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val autoNext by vm.autoPlayNext.collectAsState()
    val skipSec by vm.skipIntroOutroSec.collectAsState()

    Column(
        Modifier.fillMaxSize().background(T.Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        SubHeader("播放设置", "对之后每一集生效", onBack)

        SectionLabel("自动连播")
        GroupCard {
            Column(Modifier.padding(Sp.s4)) {
                Text("播完一集接下一集", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    "开：这一集播到结尾后等 3 秒，自动播下一条，屏幕上会给一句能取消的倒计时。" +
                        "关：播完就停在这一集，下一集自己点。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
                Spacer12()
                Segmented(
                    options = listOf(false, true),
                    selected = autoNext,
                    label = { if (it) "开" else "关" },
                    onSelect = { vm.setAutoPlayNext(it) },
                )
                Spacer8()
                Text(
                    "默认关 —— 自动换片是替你做主的行为。追剧的人开一次，之后一直记着。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
            }
        }

        SectionLabel("跳过片头 / 片尾")
        GroupCard {
            Column(Modifier.padding(Sp.s4)) {
                Text("跳多少秒", color = T.Text, fontSize = Fs.body, fontWeight = FontWeight.Bold)
                Spacer4()
                Text(
                    "一个数管两头：起播后自动跳过开头这一段；播到只剩这一段时自动跳到结尾" +
                        "（于是会接上「播完」那一套：连播开着就倒计时）。",
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
                Spacer12()
                Segmented(
                    // 档位来自仓库那一份（界面上不抄字面量）；0 就是关。
                    options = vm.skipChoices,
                    selected = skipSec,
                    label = { if (it == 0) "关" else "${it} 秒" },
                    onSelect = { vm.setSkipIntroOutro(it) },
                )
                Spacer8()
                Text(
                    when {
                        skipSec == 0 -> "不设跳过：每一集都从头播到结尾。"
                        // 不够三倍是判据里写死的（`durationSec <= skipSec * 3`，含正好三倍），
                        // 这句话得跟它一致 —— 不能一边写着「自动跳过」一边把 45 秒的短片跳没了。
                        else -> "默认 90 秒。整集不足或正好 ${skipSec * 3} 秒的（短片、MV）不跳，" +
                            "免得片头片尾把中间那点内容挤没。" +
                            "手动拖回开头之后不再自动跳 —— 想再看片曲就给你留着。"
                    },
                    color = T.TextDim,
                    fontSize = Fs.xs,
                    lineHeight = Fs.xs * 1.7f,
                )
                // 盘上那份不是预设里的一个（备份恢复、手改过）时，上面那一排没有高亮，
                // 不说一句的话用户会以为「我设的是 30 秒」。
                if (skipSec !in vm.skipChoices) {
                    Spacer8()
                    Text(
                        "当前是 ${skipSec} 秒 —— 不在上面几档里（早先设的值或备份带过来的）。" +
                            "点一档就换成能看见的那一档。",
                        color = T.Warn,
                        fontSize = Fs.xs,
                        lineHeight = Fs.xs * 1.7f,
                    )
                }
            }
        }

        Text(
            "改完当下就生效：正在播的那一集之前怎么跳的还是那样，这一集播完、或换下一集时按新值走。",
            color = T.TextDim,
            fontSize = Fs.xs,
            lineHeight = Fs.xs * 1.7f,
            modifier = Modifier.padding(horizontal = Sp.s4, vertical = Sp.s4),
        )
    }
}

/**
 * 一排互斥选项。和「下载设置」那一排同样式（整块 44dp 高，不是只有那行字能点）。
 *
 * 泛型到 `Opt` 而不是只吃 Int：这里两排一档是布尔（开/关）、一档是秒数，
 * 抄两份同样的盒子，第三页再来一次就是三份。
 * （别写成 `T` —— 那会把主题色对象 [T] 整个遮掉，`T.Play400` 当场编译不过。）
 */
@Composable
private fun <Opt> Segmented(
    options: List<Opt>,
    selected: Opt,
    label: (Opt) -> String,
    onSelect: (Opt) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(R.md)
            .background(Color.White.copy(alpha = .05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { n ->
            val on = n == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .clip(R.sm)
                    .background(
                        if (on) Brush.verticalGradient(listOf(T.Play400, T.Play500))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onSelect(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(n),
                    color = if (on) Color.White else T.TextDim,
                    fontSize = Fs.xs,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
