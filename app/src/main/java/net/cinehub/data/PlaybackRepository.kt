package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
 * 播放偏好 —— 目前三件：倍速、自动连播、画面比例模式。
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
    private val keyResize = stringPreferencesKey("resize_mode_v1")

    private val _speed = MutableStateFlow(DEFAULT_SPEED)

    /** 上一次倍速。起播、换线路、换集、换源都读它 —— 用户调过一次就不用再调。 */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _autoNext = MutableStateFlow(DEFAULT_AUTO_NEXT)

    /**
     * 自动连播开关。这一集播完后要不要倒计时接下一集 —— 全局一份，和倍速同一语义：
     * 追剧的人设一次就够了，不该每部片、每一集再问一遍。
     */
    val autoNext: StateFlow<Boolean> = _autoNext.asStateFlow()

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
     */
    val speedChoices: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

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
                    val storedAuto = p[keyAutoNext]
                    if (!loaded && storedAuto != null) _autoNext.value = storedAuto
                    val storedResize = p[keyResize]
                    if (!loaded && storedResize != null) _resizeMode.value = sanitizeResize(storedResize)
                    loaded = true
                    p[keySpeed] = _speed.value
                    p[keyAutoNext] = _autoNext.value
                    p[keyResize] = _resizeMode.value
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

    /** 记下自动连播这一档（开关就设在播放器倍速面板底部）。 */
    fun setAutoNext(value: Boolean) {
        loaded = true
        _autoNext.value = value
        scope.launch { runCatching { context.playbackStore.edit { it[keyAutoNext] = value } } }
    }

    /** 记下画面比例模式；界面上「画面」那一格轮换一圈就是在这几档里走。 */
    fun setResizeMode(value: String) {
        val v = sanitizeResize(value)
        loaded = true
        _resizeMode.value = v
        scope.launch { runCatching { context.playbackStore.edit { it[keyResize] = v } } }
    }

    /** 认不出的（NaN、无穷、被改坏的浮点）一律回默认；其余夹进这一版真有档的那段。 */
    private fun sanitize(v: Float): Float =
        if (!v.isFinite()) DEFAULT_SPEED else v.coerceIn(speedChoices.first(), speedChoices.last())

    /** 画面档认不出的（改坏的字符串、老版本残留）一律回自适应。 */
    private fun sanitizeResize(v: String): String =
        if (v in resizeModes) v else DEFAULT_RESIZE

    private companion object {
        const val DEFAULT_SPEED = 1f

        /** 默认开：连播是「接着看剧」的默认姿势，不想连的人自己关掉，只关一次。 */
        const val DEFAULT_AUTO_NEXT = true

        /** 画面默认自适应：等比、不裁切、不变形。 */
        const val DEFAULT_RESIZE = "fit"

        const val RESIZE_FILL = "fill"
        const val RESIZE_ZOOM = "zoom"
    }
}
