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
import androidx.lifecycle.Lifecycle
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

    /** 「回到前台」发生过几次。小窗那个 × 与「拖回全屏」靠它区分：前者不会让它变。 */
    private var resumeGen = 0

    /** 离开小窗后待判的那一笔，只留一个。 */
    private var pendingPipCheck: Runnable? = null

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
        // 标题副标题正是系统留给这句话的位置。
        //
        // **这两句要 API 33**（查过 SDK 自带那份 api-versions.xml：setTitle/setSubtitle 都是
        // since 33，不是 26/27）。minSdk 是 24，少一层守卫就会抛 NoSuchMethodError；
        // 更糟的是调用方把它连同 enterPictureInPictureMode 一起包在 runCatching 里，
        // 于是异常被静默吞掉 —— Android 8 到 12 上小窗**永远进不去**，表现恰好是
        // 这个功能本来要治的「按了 Home 什么都没发生」。所以按真实等级守：
        // 26/27 上只有比例，33 起才有标题副标题。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vm.playing.value?.let { p ->
                b.setTitle("${p.mediaTitle} · ${p.episodeName}")
                b.setSubtitle(p.sourceName)
            }
        }
        return b.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.setInPip(isInPictureInPictureMode)
        // 离开小窗有两种走法，这里分开：
        //  - 把小窗拖回全屏 / 点展开 → Activity 马上又可见，接着放；
        //  - 点小窗那个 × → 这是「不看了」，可有的系统并不把 Activity 收掉，
        //    只是把窗口藏起来、进程留着。那条路上播放器那一屏收不到有效的 ON_STOP
        //    （进小窗时那次已经被「在小窗里」豁免掉了），画面没了、声音还在后台响，
        //    而用户找不到任何入口关掉它。
        // 两条路的区别不在回调里 —— 所以记一个「回到前台」的代数，等一会儿看它有没有变。
        if (isInPictureInPictureMode) resumeGen++ else confirmStillAway()
    }

    /**
     * 离开小窗后确认「没有回到前台」。
     *
     * 不能在这一刻直接判：把小窗拖回全屏、点小窗里的展开，走的也是同一个回调，
     * 那时候 Activity 正在回来（`onResume` 还没到）。**也不能只按墙上时钟等固定时长**：
     * 主线程忙的时候（全量重组 + HLS prepare）`onResume` 晚于 300ms 是很平常的事，
     * 那就把一个从没关过小窗的用户的播放停了。
     *
     * 所以两条路各堵一个方向：
     *  - 已经回到前台（`RESUMED`）→ 当场就不报，管它是回调之前还是之后回来的；
     *  - 还没回来 → 再等 [PIP_SETTLE_MS]，这期间 [resumeGen] 一变就说明回来了，也不报。
     * 两条都不成立才是真的「用户关掉了它」。
     *
     * 待判的那一笔只留一个：反复进出小窗时把上一颗撤掉，否则几个 runnable 会各报一次。
     */
    private fun confirmStillAway() {
        val genAtLeave = resumeGen
        pendingPipCheck?.let { window.decorView.removeCallbacks(it) }
        val check = Runnable {
            pendingPipCheck = null
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (isFinishing || isDestroyed || (!resumed && resumeGen == genAtLeave)) {
                vm.notePipDismissed()
            }
        }
        pendingPipCheck = check
        window.decorView.postDelayed(check, PIP_SETTLE_MS)
    }

    override fun onResume() {
        super.onResume()
        // 回来过就是「没关」，代数一变，上面那颗待判的 runnable 自己就不报了
        resumeGen++
    }

    override fun onDestroy() {
        pendingPipCheck?.let { window.decorView.removeCallbacks(it) }
        pendingPipCheck = null
        super.onDestroy()
    }

    private companion object {
        /**
         * 等多久再判「没回到前台」。
         *
         * 拖回全屏那一下从回调到 `onResume` 通常是几十毫秒级；给到 300ms 足够区分，
         * 又不至于让用户点完 × 之后还听上半秒的声音。**它只是一个上限**：
         * 真正的判据是 [resumeGen] 有没有变，所以主线程偶尔忙过头也不会误停。
         */
        const val PIP_SETTLE_MS = 300L
    }
}

/** 宽高比 → 系统要的那个分数。取到千分位就够小窗用了，别让浮点的尾巴进 Rational。 */
private fun Float.toRational(): Rational = Rational((this * 1000).roundToInt(), 1000)
