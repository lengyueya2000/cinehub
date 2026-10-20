package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Context.playbackStore by preferencesDataStore(name = "cinehub_playback")

/**
 * 播放偏好 —— 目前几件：倍速、自动连播、画面比例模式、跳过片头/片尾的秒数。
 *
 * 单开一份 store 而不是塞进源列表或缓存任务那两份：这个仓库里每份 store 都归一个仓库所有，
 * 倍速既不属于源也不属于下载，混进去以后没人说得清「谁有权改它」。
 *
 * 全局一份，不分作品：所有片、所有源、离线缓存的播放，都按这一份值起播。
 */
class PlaybackRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keySpeed = floatPreferencesKey("speed_v1")
    private val keyAutoNext = booleanPreferencesKey("auto_next_v1")
    private val keyAutoNextChosen = booleanPreferencesKey("auto_next_chosen_v1")
    private val keyResize = stringPreferencesKey("resize_mode_v1")
    private val keySkip = intPreferencesKey("skip_intro_outro_v1")

    private val _speed = MutableStateFlow(DEFAULT_SPEED)

    /** 上一次倍速。起播、换线路、换集、换源都读它 —— 用户调过一次就不用再调。 */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _autoNext = MutableStateFlow(DEFAULT_AUTO_NEXT)

    /**
     * 自动连播开关。这一集播完后要不要倒计时接下一集 —— 全局一份，和倍速同一语义：
     * 追剧的人设一次就够了，不该每部片、每一集再问一遍。
     *
     * 默认关：自动跳到下一集是「替用户做主」的行为，没打招呼就换片比多看一眼按钮更打扰人。
     * 想要的人进播放器点一下「连播」芯片，或在这里开一次，之后一直记着。
     */
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

    private val _skipSec = MutableStateFlow(DEFAULT_SKIP_SEC)

    /**
     * 跳过片头 / 片尾的秒数，一个数管两头（多数片子片头片尾时长接近，两个框是负担）。
     *
     * 0 = 关。起播后位置越过这个数就再不动这一集（不重复跳）；片尾同理，剩不到这个数
     * 就直接跳到结尾。数值在「播放设置」里调，默认 90 秒 —— 国产剧片头普遍 60~100 秒，
     * 90 是个不疼不痒的中位。存秒数而不是「开/关」：既然要能自定义，开关就是多余的一层。
     */
    val skipSec: StateFlow<Int> = _skipSec.asStateFlow()

    private val _resizeMode = MutableStateFlow(DEFAULT_RESIZE)

    /**
     * 画面比例模式（`"fit"` / `"fill"` / `"zoom"`）。
     *
     * 自适应是默认：等比、留黑边，不会把画面弄变形。`fill` 是给宽高比元数据
     * 写错的源准备的（拉伸铺平反而正常），`zoom` 是等比裁切、不要黑边。
     * 存字符串而不是枚举序号：DataStore 里落的是人读得懂的值，将来加第四档
     * 或调档位顺序都不会把老用户的存档错位到另一档上。
     */
    val resizeMode: StateFlow<String> = _resizeMode.asStateFlow()

    /** 这一版真认得的三档。界面上轮换的顺序就是这一份的顺序。 */
    val resizeModes: List<String> = listOf("fit", RESIZE_FILL, RESIZE_ZOOM)

    /**
     * 这一版可选的几档。面板照着这一份摆，界面上不再抄一遍字面量：
     * 两处各列各的，早晚会有一处摆出对方 clamp 不掉的档。
     *
     * 只是「值得摆成芯片」的那几档，不是能停的全部 —— 整段见 [speedRange]。
     */
    val speedChoices: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /**
     * 倍速能停的整段（0.5~3）。和 [speedChoices] 分开报：那一份是「哪几档值得摆成芯片」，
     * 这一份是「自定义滑杆能拖到的头尾，也是 sanitize 夹的范围」——
     * 想加 2.5x 这种没人要的档当芯片，只改 speedChoices；想整体放开上限，只改这一段。
     */
    val speedRange: ClosedFloatingPointRange<Float> = MIN_SPEED..MAX_SPEED

    /**
     * 跳过片头/片尾能选的那几档秒数（0 = 关）。设置页照这一份摆，
     * 界面上不另列一遍 —— 和 [speedChoices] 同一个理由。
     */
    val skipChoices: List<Int> = listOf(0, 30, 60, 90, 120, 180)

    @Volatile
    private var loaded = false

    init {
        scope.launch {
            runCatching {
                context.playbackStore.edit { p ->
                    // 读盘没回来之前用户已经点了一档，那新的这一档说了算，不能拿盘上那份旧的盖回去。
                    // 倍速和连播共用这一道闸门：它们在同一次 edit 事务里先后落地，
                    // 分开两个 loaded 只会多一个「一半新一半旧」的窗口。
                    val stored = p[keySpeed]
                    if (!loaded && stored != null) _speed.value = sanitize(stored)
                    val storedResize = p[keyResize]
                    if (!loaded && storedResize != null) _resizeMode.value = sanitizeResize(storedResize)
                    val storedSkip = p[keySkip]
                    if (!loaded && storedSkip != null) _skipSec.value = sanitizeSkip(storedSkip)
                    val storedAuto = p[keyAutoNext]
                    if (!loaded) {
                        // 自动连播默认从「开」改成了「关」。盘上那份值不带「这是用户自己选的」
                        // 这个意思 —— 它多半是 1.4.10 及以前默认开的时候写下去的，不是谁点过。
                        // 于是老用户也得跟着改，只认 [keyAutoNextChosen] 写过的：没写过就一律
                        // 回到新默认，否则「默认关」只对新装的人是关的，老人照旧被换片。
                        _autoNext.value = if (p[keyAutoNextChosen] == true) storedAuto == true
                        else DEFAULT_AUTO_NEXT
                    }
                    loaded = true
                    p[keySpeed] = _speed.value
                    p[keyAutoNext] = _autoNext.value
                    p[keyAutoNextChosen] = true
                    p[keyResize] = _resizeMode.value
                    p[keySkip] = _skipSec.value
                }
            }
        }
    }

    /** 记下这一次倍速；界面上「倍速 x」那一格和起播读的是同一个值。 */
    fun setSpeed(value: Float) {
        val v = sanitize(value)
        loaded = true
        _speed.value = v
        scope.launch { runCatching { context.playbackStore.edit { it[keySpeed] = v } } }
    }

    /** 记下自动连播这一档（开关是播放器底部那颗「连播 开/关」芯片）。 */
    fun setAutoNext(value: Boolean) {
        loaded = true
        _autoNext.value = value
        scope.launch {
            runCatching {
                context.playbackStore.edit {
                    it[keyAutoNext] = value
                    // 标一句「这是用户自己选的」：下次启动的迁移就不该再动它
                    // （见 init 里 auto_next 那一支）。
                    it[keyAutoNextChosen] = true
                }
            }
        }
    }

    /** 记下画面比例模式；界面上「画面」那一格走的就是这几档。 */
    fun setResizeMode(value: String) {
        val v = sanitizeResize(value)
        loaded = true
        _resizeMode.value = v
        scope.launch { runCatching { context.playbackStore.edit { it[keyResize] = v } } }
    }

    /**
     * 记下跳过片头/片尾的秒数。
     *
     * 收任意 0~600 的整数而不是只认那几档预设：设置页现在给的是预设芯片，
     * 可盘上那份不一定是预设里的一个（备份恢复、手改过 preferences），
     * 于是 sanitize 走的是「夹」而不是「拒」—— 夹到边界上用户看得见，
     * 拒了就得弹一句「输入不合法」，而这一格根本没有输入框可以拒。0 就是关。
     */
    fun setSkipSec(value: Int) {
        val v = sanitizeSkip(value)
        loaded = true
        _skipSec.value = v
        scope.launch { runCatching { context.playbackStore.edit { it[keySkip] = v } } }
    }

    /** 认不出的（NaN、无穷、被改坏的浮点）一律回默认；其余夹进这一版真能停的那段。 */
    private fun sanitize(v: Float): Float =
        if (!v.isFinite()) DEFAULT_SPEED else v.coerceIn(MIN_SPEED, MAX_SPEED)

    /** 画面档认不出的（改坏的字符串、老版本残留）一律回自适应。 */
    private fun sanitizeResize(v: String): String =
        if (v in resizeModes) v else DEFAULT_RESIZE

    /** 负数当 0（关）；上限 600 秒：再长就不是「片头」而是「这一集别播了」。 */
    private fun sanitizeSkip(v: Int): Int = v.coerceIn(0, MAX_SKIP_SEC)

    private companion object {
        const val DEFAULT_SPEED = 1f

        /** 倍速能停的整段：下限 0.5、上限 3。芯片只摆其中几档（见 [speedChoices]）。 */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f

        /**
         * 默认关：自动接下一集是替用户做主的行为 —— 没打招呼就换一部片，
         * 比多看一眼「连播」按钮更打扰人。想要的人开一次，之后记着。
         */
        const val DEFAULT_AUTO_NEXT = false

        /** 画面默认自适应：等比、不裁切、不变形。 */
        const val DEFAULT_RESIZE = "fit"

        /** 片头/片尾默认跳 90 秒：国产剧片头普遍 60~100 秒，取个中位。 */
        const val DEFAULT_SKIP_SEC = 90

        const val MAX_SKIP_SEC = 600

        const val RESIZE_FILL = "fill"
        const val RESIZE_ZOOM = "zoom"
    }
}
