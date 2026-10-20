package net.cinehub.data.net

import net.cinehub.data.model.SourceConfig
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机 key 代理：只为「站点给的密钥不是规范形态」那一种源存在。
 *
 * ## 它解决的是什么
 *
 * HLS 规范（RFC 8216）写得很清楚：`#EXT-X-KEY` 的 `URI` 指向的文件里存的**就是原始密钥字节**，
 * AES-128 就是 16 字节。播放器照着读、照着 init Cipher，整条链是闭上的。
 *
 * 但不是每个站都守这个规矩。`PpnixAdapter` 那一型给的密钥地址回的不是 16 字节密钥，
 * 而是 **32 个 ASCII 字符**（形如 `ba9bf05693b9fa202d922dd43a08f281`），真正的密钥是它
 * **前 16 个字符**。
 *
 * 播放器不知道这件事。Media3 的 `Aes128DataSource` 把响应原样交给 `SecretKeySpec` ——
 * 32 字节对 AES 是合法长度（那是 AES-256），于是它不报错，安安静静解出一堆乱码，
 * 用户看到的是「解析直链失败」或者一个不动画黑屏。而那个类是包级私有的
 * （`class Aes128DataSource`，没有 `public`），影枢替换不了它。
 *
 * 所以能干净介入的点只剩一个：**在密钥到达播放器之前，把它换成规范的 16 字节**。
 * 这就是本代理存在的全部理由。
 *
 * ## 它做什么、不做什么
 *
 * 做两件事：
 *
 * 1. 转发 m3u8：把 `#EXT-X-KEY` 那行的 `URI` 改写成本机地址，其余一字不改。
 *    分片地址原样留着 —— 那些不走代理（见下）。
 * 2. 回密钥：去向站点要那一串，截成规范的 16 字节回给播放器。
 *
 * 不做的两件事同样重要：
 *
 * - **不碰分片**。分片由播放器直连，一次都不过代理。一部 47 集的剧，
 *   代理总共只搬 47 份 m3u8 和 47 次 16 字节。
 * - **不给别的源用**。只有 `PpnixAdapter` 那一型的 `resolveStream` 会拿到这里的地址，
 *   其余源照旧直连，一行都不改。
 *
 * ## 三处不能松的约束
 *
 * - **只听 `127.0.0.1`**。绑 `0.0.0.0` 等于给同一局域网里每一台设备开一个无鉴权的
 *   解密代理 —— 那串密钥虽然是站点分发给每一个访客的，不等于秘密，但没理由替别人省这一步。
 * - **端口随机取空闲口**。写死端口会和应用商店里随便哪个同类应用撞上，
 *   撞上的表现是这一侧 listen 失败、播放器连一个没人应答的端口，等到超时。
 * - **用完就关**。没有引用它的时候不占端口、不起线程。见 [acquire] / [release]。
 */
object LocalKeyProxy {

    /**
     * 拿到这个源该用的代理根地址；同一源并发要几次都返回同一个。
     *
     * 引用计数：播放器起播时 acquire，停/换源时 release。最后一次 release
     * 才真的关端口 —— 换集、换线路都会重放一次媒体源，那期间不能让端口消失。
     */
    fun acquire(cfg: SourceConfig): String = synchronized(lock) {
        val held = PROXIES[cfg.id]
        if (held != null) {
            held.refs++
            return@synchronized held.baseUrl
        }
        val fresh = openServer(cfg)
        PROXIES[cfg.id] = fresh
        fresh.baseUrl
    }

    /** 放手一次；这是最后一次时关端口、停线程。 */
    fun release(cfg: SourceConfig) = synchronized(lock) {
        val held = PROXIES[cfg.id] ?: return@synchronized
        held.refs--
        if (held.refs > 0) return@synchronized
        close(held)
    }

    /** 源被删掉时用：不管还有几个引用，直接收。 */
    fun forget(id: String) = synchronized(lock) {
        PROXIES.remove(id)?.let { close(it) }
    }

    /** 测试用：直接给一个已起好的端口号，不起真 socket。 */
    internal fun baseUrlForTest(port: Int) = "http://127.0.0.1:$port"

    private fun close(held: Held) {
        held.stopped.set(true)
        runCatching { held.server.close() }
        held.pool.shutdownNow()
    }

    private val lock = Any()
    private val PROXIES = ConcurrentHashMap<String, Held>()

    private class Held(val server: ServerSocket, val cfg: SourceConfig) : Closeable {
        var refs: Int = 0
        val stopped = AtomicBoolean(false)
        lateinit var baseUrl: String
        val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cinehub-key-proxy").apply { isDaemon = true }
        }

        override fun close() {
            stopped.set(true)
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }

    /**
     * 起一颗只监听回环的服务器，并开始接连接。
     *
     * 端口给 0 让系统挑一个真空闲口。`SO_REUSEADDR` 由 ServerSocket 默认开，
     * 但上一次的端口刚关、TIME_WAIT 还没过时仍可能 `BindException` —— 所以重试几次。
     *
     * 接受循环放在调用方的线程池里跑：播放器那边是「要一份 m3u8」「要一次密钥」，
     * 一次只办一件事，单线程够用。真要多线程的那天，先把并发场景写出来再说 ——
     * 现在多起几个线程只是多几个没人用的栈。
     */
    private fun openServer(cfg: SourceConfig): Held {
        var last: Throwable? = null
        repeat(5) {
            val server = runCatching { ServerSocket(0, 8, InetAddress.getByName(LOOPBACK)) }
                .onFailure { last = it }
                .getOrNull()
            if (server != null) {
                val held = Held(server, cfg)
                held.baseUrl = "http://$LOOPBACK:${server.localPort}"
                held.pool.execute { acceptLoop(server, held) }
                return held
            }
        }
        throw IOException("本机 key 代理起不来：连试 5 次都没拿到空闲端口", last)
    }

    /**
     * 接连接直到 [Held.stopped]。
     *
     * `accept()` 抛 `SocketException` 是**关端口时的正常形态**（[close] 会关 server），
     * 所以这一路不报警、也不重试 —— 重试一个已经关了的 server 只会空转。
     */
    private fun acceptLoop(server: ServerSocket, held: Held) {
        while (!held.stopped.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            // 单线程池：这一条办完才接下一跳。播放器对同一份 m3u8 的请求本来就少
            // （一份清单 + 一把密钥），串起来办不会成为瓶颈。
            runCatching { serveOne(socket, held.cfg, held.baseUrl) }
        }
    }

    /**
     * 处理一条连接：读请求行 → 按路径分派 → 回响应 → 关。
     *
     * 用最朴素的 socket 读写而不是引一个 HTTP 服务器库：这里只需要支持播放器
     * 发来的那一种请求（GET、无 body、不保活）。引库意味着多几个传递依赖要 R8 处理，
     * 而它们的其余功能一辈子用不上。
     */
    private fun serveOne(socket: Socket, cfg: SourceConfig, baseUrl: String) {
        socket.use { s ->
            // 读超时：播放器不来读响应的话，这一颗线程不能一直挂着。
            s.soTimeout = 20_000
            val reader = s.getInputStream().bufferedReader()
            val out = s.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val target = parts[1]

            // 头要读完再回：留着不读，客户端可能因为写不完而挂在 write 上。
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }

            when {
                target.startsWith(KEY_PATH) -> {
                    // 密钥地址由适配器在改写 m3u8 时一并写进 URI 的查询串里
                    // —— 站点那份 m3u8 只有一个相对的 `../key`，它相对的是
                    // 站点自己的地址，不是本代理的。所以真地址得由适配器给出。
                    val siteUrl = KeyTrimmer.siteUrlFrom(target)
                    if (siteUrl == null) {
                        write(out, 400, "text/plain; charset=utf-8", "缺站点地址".toByteArray())
                        return
                    }
                    val trimmed = runBlockingKey(siteUrl, cfg)
                    if (trimmed == null) {
                        write(out, 502, "text/plain; charset=utf-8", "取不到密钥".toByteArray())
                        return
                    }
                    write(out, 200, "application/octet-stream", trimmed)
                }

                target.startsWith(M3U8_PATH) -> {
                    val siteUrl = KeyTrimmer.siteUrlFrom(target)
                    if (siteUrl == null) {
                        write(out, 400, "text/plain; charset=utf-8", "缺站点地址".toByteArray())
                        return
                    }
                    val body = runCatching { blockingGet(siteUrl, cfg) }.getOrNull()
                    if (body == null) {
                        write(out, 502, "text/plain; charset=utf-8", "取不到播放清单".toByteArray())
                        return
                    }
                    // 每把 key 的 URI 都按清单自己的地址解析成绝对地址再编进查询串。
                    // 曾经直接把清单地址塞进去，取「密钥」时抓回来的就是整份 m3u8，
                    // 截 16 字节当密钥解出一堆乱码 —— 播放器报错、自动换源，PPnix 永远播不了。
                    val rewritten = KeyTrimmer.rewritePlaylist(body) { rawKeyUri ->
                        KeyTrimmer.localKeyUriWithSite(
                            baseUrl,
                            KeyTrimmer.resolveKeyUrl(siteUrl, rawKeyUri),
                        )
                    }
                    write(out, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray())
                }

                else -> write(out, 404, "text/plain; charset=utf-8", "没有这一条".toByteArray())
            }
        }
    }

    /**
     * 在**没有协程作用域**的 socket 线程里等一次挂起函数。
     *
     * 这颗线程是代理自己的（[acceptLoop] 里那条），`runBlocking` 它不会堵住主线程，
     * 也不会堵住播放器 —— 播放器那边本来就是发起请求后干等。
     */
    private fun runBlockingKey(siteUrl: String, cfg: SourceConfig): ByteArray? =
        kotlinx.coroutines.runBlocking { KeyTrimmer.fetchTrimmed(siteUrl, cfg) }

    private fun blockingGet(url: String, cfg: SourceConfig): String =
        kotlinx.coroutines.runBlocking {
            Http.get(url, referer = cfg.baseUrl)
        }

    private fun write(out: java.io.OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Bad Gateway"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            // 不保活：一次连接只办一件事，办完就关。少了这一行，播放器会以为
            // 下一条请求能复用这条连接，等到超时才发现对面关了。
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    const val LOOPBACK = "127.0.0.1"
    const val KEY_PATH = "/key"

    /**
     * 清单路径带 `.m3u8` 后缀不是装饰：播放器按 [StreamUrls.isHls]（后缀 / 末段含
     * m3u8 / 路径含 /m3u8/）决定走 HLS 还是把地址当单个视频文件抽流。曾经叫
     * `/playlist`，三条全不中 —— 整份 m3u8 文本被 ProgressiveMediaSource 当媒体
     * 探测，所有 extractor 认不出，Source error 换源，密钥请求一次都没发出来。
     */
    const val M3U8_PATH = "/playlist.m3u8"
}

/**
 * 把「非规范密钥」那一型站点给的响应收拾成规范形态。
 *
 * 纯函数为主（[fetchTrimmed] 要发一次请求），单测见 `PpnixKeyTrimmerTest`。
 * 这里每一行都对应一种实测到的形状，不为「将来可能」写防御。
 */
internal object KeyTrimmer {

    /** 规范的 AES-128 密钥长度。 */
    const val KEY_LEN = 16

    /**
     * 把站点回的密钥收拾成 16 字节。
     *
     * 实测 PPnix 给的是 32 个 ASCII 字符，真密钥是前 16 个字符。也就是说：**截断**，
     * 不是 hex 解码 —— hex 解码出来的是同一串字符的另一种 16 字节表示，那个解不开
     * （拿 node 按两种都试过，只有截断出得了 TS 包头 `47 40 11 …`）。
     *
     * 恰好 16 字节时原样返回：有些站是守规矩的，不必为它改代码路径。
     */
    fun trim(raw: ByteArray): ByteArray = when {
        raw.size == KEY_LEN -> raw
        raw.size > KEY_LEN -> raw.copyOf(KEY_LEN)
        else -> raw
    }

    /**
     * 把 m3u8 里的 key `URI` 换成本代理的密钥地址。
     *
     * 只动 `#EXT-X-KEY` 那一行、只动它的 URI —— `METHOD`、`IV`、`KEYFORMAT`
     * 一概不碰：那些是站点说了算的事，影枢改它就是在替站点做决定。
     *
     * IV 留着站点那份（PPnix 实测是 `0x00…00`）。这一点值得单独说：HLS 规范说 IV
     * 缺省时用分片序号，而站点**显式写了全零**，那就是它真的用全零加密
     * （按分片序号解不开，实测过）。所以照原样转达，不替它「补规范」。
     */
    fun rewritePlaylist(playlist: String, localKeyUri: String): String =
        rewritePlaylist(playlist) { localKeyUri }

    /**
     * 把 m3u8 里的 key `URI` 换成本代理的密钥地址。
     *
     * 回调拿到的是 `#EXT-X-KEY` 里写的**原文**（PPnix 是相对的 `../key`），
     * 由调用方按清单地址解析、按这把 key 自己的地址拼本机地址 ——
     * key rotation 时每一把各走各的，不会串。
     */
    fun rewritePlaylist(playlist: String, localKeyUriFor: (String) -> String): String {
        if (!playlist.contains("#EXT-X-KEY", ignoreCase = true)) return playlist
        // lineSequence() 对「以换行结尾」的正文会在末尾多产出一个空串，
        // 照原样 append 下去就多出一个空行。所以只处理非空行，
        // 结尾有没有换行由原文决定。
        val lines = playlist.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return playlist
        val out = StringBuilder(playlist.length + 64)
        val last = lines.size - 1
        lines.forEachIndexed { i, line ->
            out.append(
                if (line.trimStart().startsWith("#EXT-X-KEY", ignoreCase = true)) {
                    rewriteKeyLine(line, localKeyUriFor)
                } else {
                    line
                },
            )
            if (i < last || playlist.endsWith("\n")) out.append('\n')
        }
        return out.toString()
    }

    private val KEY_URI = Regex("""URI\s*=\s*(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)

    private fun rewriteKeyLine(line: String, localKeyUriFor: (String) -> String): String {
        val m = KEY_URI.find(line) ?: return line
        // 带引号时地址在第一组；不带引号时第一组是空串，地址在第二组。
        val raw = m.groupValues[2].ifEmpty { m.groupValues[1] }
        return line.replaceRange(m.range, "URI=\"${localKeyUriFor(raw)}\"")
    }

    /**
     * 清单里的 key 地址是**相对清单地址**写的（实测 `../key`），要解析成绝对地址，
     * 代理才知道去哪儿取。`URI("…/8509/1.m3u8").resolve("../key")`
     * 正是 RFC 3986 的相对引用解析 → `…/info/m3u8/key`（实测站点的真密钥地址）。
     *
     * 解析不出时退回「同目录拼一下」，至少地址形状还对。
     */
    fun resolveKeyUrl(playlistUrl: String, keyUri: String): String =
        runCatching { java.net.URI(playlistUrl).resolve(keyUri).toString() }
            .getOrElse {
                playlistUrl.substringBeforeLast('/') + "/" + keyUri.substringAfterLast('/')
            }

    /** 拼出本代理的密钥地址。 */
    fun localKeyUri(baseUrl: String): String = baseUrl.trimEnd('/') + LocalKeyProxy.KEY_PATH

    /**
     * 带站点地址的密钥地址。
     *
     * 站点那份 m3u8 里写的是相对的 `../key` —— 它相对的是**站点自己**的 m3u8 地址。
     * 经过本代理之后，播放器看到的本代理地址下并没有那个 `../key`，
     * 所以真地址得由影枢在改写时一并写进查询串，代理才拿得到。
     */
    fun localKeyUriWithSite(baseUrl: String, siteUrl: String): String =
        baseUrl.trimEnd('/') + LocalKeyProxy.KEY_PATH + "?u=" + URLEncoder.encode(siteUrl, "UTF-8")

    /** m3u8 的取法：把站点那份地址包一层，让内容经过本代理。 */
    fun proxiedPlaylistUrl(baseUrl: String, siteUrl: String): String =
        baseUrl.trimEnd('/') + LocalKeyProxy.M3U8_PATH + "?u=" + URLEncoder.encode(siteUrl, "UTF-8")

    /** 从 `?u=` 里取出站点那份地址。 */
    fun siteUrlFrom(target: String): String? {
        val q = target.substringAfter('?', "")
        return q.split('&').firstNotNullOfOrNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) return@firstNotNullOfOrNull null
            if (kv.substring(0, i) != "u") return@firstNotNullOfOrNull null
            runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrNull()
        }
    }

    /**
     * 去向站点要密钥并截成规范长度。拿不到返回 null，由调用方决定怎么说话。
     *
     * 走 [Http] 而不是裸 OkHttp：站点按 host 记会话（[Http] 的 jar），
     * 而播放器那边拉分片是另一条独立的连接，两边不需要共享会话 ——
     * 但 m3u8 与密钥最好共用，站点有可能给密钥也挂一道会话校验。
     */
    suspend fun fetchTrimmed(keyUrl: String, cfg: SourceConfig): ByteArray? =
        runCatching { Http.getBytes(keyUrl, referer = cfg.baseUrl) }.getOrNull()?.let { trim(it) }
}
