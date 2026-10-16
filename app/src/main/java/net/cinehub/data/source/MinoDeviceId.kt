package net.cinehub.data.source

import android.content.Context
import java.security.SecureRandom

/**
 * 这个安装的设备 id（16 个 hex 字符）。
 *
 * 后端把它写进每个请求的签名串与握手体里（device_id 那一位），并且服务端按它记会话 ——
 * 每次现造等于一台机器反复以新设备握手，而自建站那一侧的设备风控也会把这种抖动当异常。
 * 所以第一次生成后落盘，之后一直是同一份；它不是账号凭据，只是一台机器的稳定标识。
 *
 * 用 SharedPreferences 而不是 DataStore：这个值要在懒加载路径上**同步**读到
 * （签名头每一次都要它，而取数据的那条协程不在 UI 层，await 一份 DataStore 读
 * 会为了一行字符串把整条链路挂到磁盘流上），并且它由 [D] 在应用启动时接上 ——
 * 和 [net.cinehub.data.net.BrowserEngine] 同一个先例：适配器工厂拿不到 Context，
 * 得由 Application 递进来。
 */
internal object MinoDeviceId {

    private const val PREF = "cinehub_device"
    private const val KEY = "mino_device_id"

    @Volatile
    private var cached: String? = null

    /** 由 [net.cinehub.CineHubApplication] 在启动时接上。 */
    @Volatile
    var appContext: Context? = null

    /** 当前安装的设备 id；没有盘可用（纯 JVM 测试）时退化为本次进程随机一个。 */
    fun get(): String = cached ?: synchronized(this) {
        cached ?: runCatching {
            val ctx = appContext ?: error("没接上 Context")
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val have = prefs.getString(KEY, null)
            if (!have.isNullOrBlank()) {
                have
            } else {
                val fresh = ByteArray(8).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
                prefs.edit().putString(KEY, fresh).apply()
                fresh
            }
        }.getOrElse {
            ByteArray(8).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        }.also { cached = it }
    }
}
