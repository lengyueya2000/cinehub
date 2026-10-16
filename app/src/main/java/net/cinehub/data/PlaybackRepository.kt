package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
 * 播放偏好 —— 目前只有一件：倍速。
 *
 * 单开一份 store 而不是塞进源列表或缓存任务那两份：这个仓库里每份 store 都归一个仓库所有，
 * 倍速既不属于源也不属于下载，混进去以后没人说得清「谁有权改它」。
 *
 * 全局一份，不分作品：所有片、所有源、离线缓存的播放，都按这一个值起播。
 */
class PlaybackRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keySpeed = floatPreferencesKey("speed_v1")

    private val _speed = MutableStateFlow(DEFAULT_SPEED)

    /** 上一次倍速。起播、换线路、换集、换源都读它 —— 用户调过一次就不用再调。 */
    val speed: StateFlow<Float> = _speed.asStateFlow()

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
                    val stored = p[keySpeed]
                    if (!loaded && stored != null) _speed.value = sanitize(stored)
                    loaded = true
                    p[keySpeed] = _speed.value
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

    /** 认不出的（NaN、无穷、被改坏的浮点）一律回默认；其余夹进这一版真有档的那段。 */
    private fun sanitize(v: Float): Float =
        if (!v.isFinite()) DEFAULT_SPEED else v.coerceIn(speedChoices.first(), speedChoices.last())

    private companion object {
        const val DEFAULT_SPEED = 1f
    }
}
