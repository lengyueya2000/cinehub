package net.cinehub.data.net

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * host -> 这个站上一次**动它**的时刻（外加「这次动它是不是因为被限流」）。
     *
     * 原来这张表只记「尝试解题的时刻」，而 429 压根不走滑块那套（那套只认
     * 403 + 滑块字样）—— 于是站点一回 429，下一轮扇出**没有任何冷却**地接着打，
     * 那正是把限流踩深的行为。现在两种情况共用这一张表：429 也落一次戳，
     * 3 分钟内不再碰这个站。
     */
    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * 一个站当前的状态。
     *
     * [ConcurrentHashMap.compute] 保证「检查 + 落戳」对同一个 key 是**一步**完成的，
     * 所以两个并发请求不会同时通过检查。
     *
     * 原来这里是「先读时间戳、隔几行再写回去」：那中间隔着可挂起点，
     * 两边都能拿到「可以解」的结论，于是**同时各解一次题**（各 3 个请求）——
     * 而「不要连着打」正是这道题存在的原因。改成 compute 之后，
     * 同一 host 同一时刻只有一个调用方能拿到占位。
     *
     * 时刻与「是否因限流而记的」放在同一个单元里，是为了让这两件事
     * 能够在**同一次 compute** 里一起判定 —— 分成两张表就会出现
     * 「读到 A 表说没冷却、写入 B 表才发现刚被限流」的缝。
     */
    private class Slot {
        /** 真正动手的时刻。占位时就写下：占位与「开始动手」必须是同一时刻。 */
        @Volatile var at: Long = 0L

        /** 这一次落戳是不是因为对方回了 429（而不是答了滑块题）。 */
        @Volatile var rateLimited: Boolean = false
    }

    /**
     * 这个 host 现在能不能动手解那道题。
     *
     * 用 compute 做**原子的**「检查 + 占位」：
     *  - 冷却期没过 → 不给位；
     *  - 冷却期过了 → 给位，并把时刻记成「现在」。
     *
     * 只有拿到位的那一个调用方会去解那道题。
     */
    private fun tryClaim(host: String): Boolean {
        val now = System.currentTimeMillis()
        val claimed = AtomicBoolean(false)
        slots.compute(host) { _, old ->
            val s = old ?: Slot()
            if (old != null && now - s.at < REATTEMPT_AFTER_MS) {
                // 冷却期没过：时刻保持不动，什么都不改
                s
            } else {
                s.at = now
                s.rateLimited = false
                claimed.set(true)
                s
            }
        }
        return claimed.get()
    }

    /**
     * 这个站刚刚因为限流被拒了 —— 记一笔，让它进入同一套冷却。
     *
     * 429 之前不走滑块那套（那套只认 403 + 滑块字样），于是限流发生时
     * 下一轮扇出**没有任何冷却**地接着打。这里把它并进同一张表：
     * 站点被限流之后，3 分钟内不再对它解题、也不重试（见 [Http] 里的出站闸）。
     */
    fun noteRateLimited(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        slots.compute(host) { _, old ->
            val s = old ?: Slot()
            s.at = System.currentTimeMillis()
            s.rateLimited = true
            s
        }
    }

    /**
     * 这个站现在是不是正处在「被限流」的冷却期里。
     *
     * [Http] 在**真正发请求之前**问这一句：冷却期内的站直接不再打扰它，
     * 立刻回一句 429 —— 与真被拒时界面上看到的那句话一模一样（[Http.failureReason]），
     * 但服务器那边一次都不会被打。
     *
     * 只看「因限流而记的」那一种戳：答完滑块题之后的冷却**不**用来挡请求 ——
     * 答完题就是为了接着取内容，挡掉就自相矛盾了。
     */
    fun isRateLimited(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val s = slots[host] ?: return false
        if (!s.rateLimited) return false
        return System.currentTimeMillis() - s.at < REATTEMPT_AFTER_MS
    }

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

        // 先问一句冷却期（只读，便宜）：省得白解析一遍页面。
        // 这只是优化 —— 真正说了算的是下面那一步的**原子占位**。
        if (inCooldown(host)) return false

        val scriptSrc = SCRIPT_SRC.find(pageBody)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return false
        // 到这一步才落下这颗时间戳。原来它在解析之前就写了：于是「这一页里根本没有那道题的
        // 脚本」也算一次尝试，把后面 3 分钟的窗口一起烧掉，而界面上那句是「影枢照它自己那条
        // 路答过一次还是没过」—— 那一次压根没答过。
        //
        // 而且落戳必须是**原子的**：原来是「先读、隔几行再写」，两路并发都能读到
        // 「还没答过」，于是同时各解一次题（各 3 个请求）—— 那正是这道题要防的连着打。
        if (!tryClaim(host)) return false
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

    /** 这个站是不是还在冷却期里（只读，不占位）。 */
    private fun inCooldown(host: String): Boolean {
        val s = slots[host] ?: return false
        return System.currentTimeMillis() - s.at < REATTEMPT_AFTER_MS
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
