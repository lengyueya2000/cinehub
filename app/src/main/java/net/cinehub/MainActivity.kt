package net.cinehub

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import net.cinehub.ui.AppViewModel
import net.cinehub.ui.CineHubApp
import net.cinehub.ui.theme.CineHubTheme
import net.cinehub.ui.theme.T
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /**
     * 和界面那侧是同一颗 VM：Compose 里的 `viewModel()` 取的就是这个 activity 作用域的那一份，
     * 这里换 `ViewModelProvider(this)` 拿到的是同一个实例 —— 小窗的话要经过它递。
     */
    private val vm: AppViewModel by lazy { ViewModelProvider(this)[AppViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CineHubTheme {
                Box(Modifier.fillMaxSize().background(T.Bg)) {
                    CineHubApp()
                }
            }
        }
    }

    /**
     * 用户按 Home、打开最近任务、切去别的 app —— 正在放片子就缩成小窗继续放。
     *
     * 只有这一条发起路：`setAutoEnterEnabled` 要 API 31 才用得上，为它多养一套分支换不来什么，
     * 而 8.0 到 14 都认这一句。播放器那一屏没报来比例（还没解出画面、或者那一屏已经不挂着了），
     * 或者这台机器根本没这能力，就是一次普通的退出 —— 和小窗不存在时的表现一模一样。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val ratio = vm.pipRatio.value ?: return
        runCatching { enterPictureInPictureMode(pipParams(ratio)) }
    }

    /**
     * 进小窗那一刻的形态。
     *
     * 只报比例和标题，不报 `sourceRectHint`：那颗只影响缩过去那一下的补间动画，要拿 Compose 的
     * 窗口坐标喂给系统，换来换去都是重排的风险，不值得第一版就背。
     * 比例在小窗期间不跟着换源改 —— 换了另一家的片，画面在这一颗里会有黑边，不会变形。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(ratio: Float): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            // 系统只收 1.0～2.1 这一段。竖屏的片子夹进 1.0 那颗方窗，两边留黑边。
            .setAspectRatio(ratio.coerceIn(1f, 2.1f).toRational())
        // 那颗窗里没地方写「这是哪一部、放的是哪家的片」，而换源是应用自己做的 ——
        // 标题副标题正是系统留给这句话的位置。副标题要 API 27，26 上就只有一行片名。
        vm.playing.value?.let { p ->
            b.setTitle("${p.mediaTitle} · ${p.episodeName}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) b.setSubtitle(p.sourceName)
        }
        return b.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.setInPip(isInPictureInPictureMode)
    }
}

/** 宽高比 → 系统要的那个分数。取到千分位就够小窗用了，别让浮点的尾巴进 Rational。 */
private fun Float.toRational(): Rational = Rational((this * 1000).roundToInt(), 1000)
