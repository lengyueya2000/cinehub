package net.cinehub.data.net

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 有些站在真正的内容前面摆一道「滑动验证」：任何页面都先回 403，正文只有一张空壳滑块页。
 *
 * 它自己的脚本（`huadong_*.js`）里写着这道题怎么交卷：滑块拖到底之后打一个接口，
 * 参数是脚本里现给的 `key` 和 `value=md5(stringtoHex(脚本里那串))`，
 * 其中 `stringtoHex` 是逐字符取码点加一再拼成十进制串。答对了它才 Set-Cookie 落下放行用的那颗。
 *
 * 实测高清点播（2026-10-16）：**解开的是 cookie，不是 IP** —— 同一个出口 IP，刚答完题再不带
 * 那张 cookie 去要首页，照样 403。所以这一件事只能在这儿做：让影枢自己答这道题、
 * 并把那颗 cookie 带在之后对同一个站的请求上（见 [Http] 里那份按 host 记的 jar）。
 *
 * 有意留的余地：这张题的解锁实测活不了太久（几分钟到两小时，中间还会因为连着打而改回 429），
 * 所以「答过一次」不能当成永久通行证，也不能一次失败就无限量重试 —— 那只会把限流踩得更深。
 * 这里给每个站加一道冷却，且一次请求最多答一遍。
 */
internal object SlideChallenge {

    /** 答完一次题之后，同一个站隔多久才允许再答。太快重答等于自己制造限流。 */
    private const val REATTEMPT_AFTER_MS = 3 * 60_000L

    /** host -> 上一次尝试的时间。 */
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    /** 这一份 403 是不是那道滑块题（不是所有 403 都是它：地区封锁长得也一样）。 */
    fun isSlideGate(status: Int, body: String): Boolean =
        status == 403 && (body.contains("滑动验证") || body.contains("huadong_"))

    /**
     * 照站点自己那条路答一次题，成功就把下发的 cookie收进 [Http] 的 jar。
     *
     * 返回「这一次有没有答过去」，不返回「之后一定放行」—— 那由站点说了算。
     */
    suspend fun solve(pageUrl: String, pageBody: String): Boolean {
        val host = runCatching { URI(pageUrl).host }.getOrNull() ?: return false
        val now = System.currentTimeMillis()
        val previous = lastAttempt.put(host, now)
        if (previous != null && now - previous < REATTEMPT_AFTER_MS) return false

        val scriptSrc = SCRIPT_SRC.find(pageBody)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return false
        val scriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else originOf(pageUrl) + scriptSrc
        val script = Http.getKeepStatus(scriptUrl, referer = pageUrl, timeoutSec = 15).body
        val key = SCRIPT_KEY.find(script)?.groupValues?.get(1) ?: return false
        val value = SCRIPT_VALUE.find(script)?.groupValues?.get(1) ?: return false
        // 页面里有 #bt-info 时走的是「不用滑块、脚本自己隔几秒交卷」那一支，交的是脚本地址本身
        val endpoint = if (pageBody.contains("bt-info")) {
            "$scriptUrl&key=$key&value=${md5(stringToHex(value))}"
        } else {
            val path = ENDPOINT.find(script)?.groupValues?.get(1) ?: return false
            originOf(pageUrl) + path + key + "&value=" + md5(stringToHex(value))
        }
        val reply = Http.getKeepStatus(endpoint, referer = pageUrl, timeoutSec = 15)
        return reply.status in 200..299
    }

    private fun originOf(url: String): String {
        val u = URI(url)
        val port = if (u.port > 0 && u.port != 443 && u.port != 80) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    /** 脚本里那个 stringtoHex：逐字符码点 +1，拼成十进制串。 */
    private fun stringToHex(s: String): String = buildString {
        s.forEach { append(it.code + 1) }
    }

    private fun md5(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val SCRIPT_SRC = Regex("""src="([^"]*huadong_[^"]*\.js[^"]*)"""")
    private val SCRIPT_KEY = Regex("""key\s*=\s*"([0-9a-fA-F]{32})"""")
    private val SCRIPT_VALUE = Regex("""value\s*=\s*"([0-9a-fA-F]{32})"""")

    /** 交卷地址的字面量在脚本里停在 `&key=` 后面，尾巴由这里接上。 */
    private val ENDPOINT = Regex("""\(\s*"(\/[^"]*yanzheng[^"]*?key=)""")
}
