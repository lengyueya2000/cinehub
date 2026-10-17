package net.cinehub.data.source

import android.util.Base64
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.net.Http

/**
 * 「苹果 CMS 把地址交给解析站」这一套取链方式（实测 www.hqvod.com 的 13 条线路全是这个形态）。
 *
 * 站点自己是怎么把一集变成一条流的，五步，每一步都是从它自己的代码里读出来的：
 *
 * 1. 播放页里的 `player_aaaa` 给三个字段：`url`（一串凭据，不是地址）、`link_next`（下一集的
 *    站内路径）、`from`（线路名）。
 * 2. 它的 `/static/js/player.js` 看到 `player_list[from].ps == "1"` 就把 `PlayFrom` 改写成
 *    `parse` —— 于是站点接下来加载的是 `/static/player/parse.js`。`PA.js`、`PK.js` 这些
 *    根本不存在（实测 404：因为它们从来不被请求）。
 * 3. `parse.js` 明写着那张 iframe：`?url=<凭据>&next=//<document.domain><link_next>&title=<页面标题第一段>`。
 * 4. iframe 里那颗 344 KB、jsjiami.com.v7 混淆过的脚本，把**自己 query string 去掉开头 `url=`
 *    之后的整串**当作签名原文（所以 `&next=`、`&title=` 都在原文里），据此算出 key/vkey/ckey，
 *    POST 给同站的 `player/api.php`。
 * 5. 响应的 `url` 字段还是密文：AES-128-CBC 的 key 与 iv 由**同一次响应**下发
 *    （`aes_key`/`aes_iv`，各自再套一层 RC4）。解出来才是 m3u8（平台页那类线路给的是 mp4）。
 *
 * 三处最容易抄错的地方，都是拿 node 探针按「中间某集 / 最后一集 / 只有一集的电影」三种页面对出来的：
 * - 签名原文不是 `url` 字段本身，而是拼完 `&next=`、`&title=` 的那一整串；
 * - 脚本会把原文里第一个 `.html` 之后全砍掉。剧集因此切在下一集的路径上，而**最后一集和电影**
 *   的 `link_next` 是空的，砍不到东西，`&title=<片名>` 就留在原文里参与签名 ——
 *   少了这一段，恰恰只在最后一集和电影上签错；
 * - AES 的 key/iv 不写死在这份代码里，跟着每次响应走。
 *
 * 只用 JDK 自带的 MD5 与 AES，RC4 那二十行自己写：为一个站加一个密码学依赖不值当。
 */
internal object MaccmsParseResolver {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** AES-128-CBC 的钥匙与 IV 都必须是 16 字节（分组长度）。 */
    private const val AES_BLOCK_BYTES = 16

    /** 站点加载的那段 iframe 模板；解析站地址就写在自己的 `src` 里。 */
    private val IFRAME_SRC = Regex("""src="(https?://[^"']+?)/player/\?url=""")

    /** 一次取链：播放页 + parse.js + api.php，三次请求。 */
    suspend fun resolve(episodeUrl: String, cfg: SourceConfig): StreamResolution {
        val site = cfg.baseUrl.trimEnd('/')
        val auth = Http.authHeaders(cfg)
        // 凭证只往本站的地址上带。解析站是另一个域 —— 把用户在源配置里填的 cookie 发过去，
        // 等于替他把凭证抄送给第三方，所以这两条请求一律不带 auth。
        val page = playPage(episodeUrl, site, auth)
        val player = MacPlayerJson.read(page) ?: error(
            "播放页里没有 player_aaaa —— 站点连播放数据都不发了，多半是改版"
        )
        val credential = player.field("url")?.takeIf { it.isNotBlank() } ?: error(
            "播放页的 player_aaaa 里 url 字段是空的 —— 这一集站点不给取链"
        )
        val parser = parserBase(site, episodeUrl, auth)
        val signed = signedText(
            credential = credential,
            linkNext = player.field("link_next").orEmpty(),
            title = MacPlayerJson.title(page),
            host = URI(site).host,
        )
        // RC4 的 key 由原文算出来，签名与解密共用同一把
        val rc4Key = signKey(signed)
        val reply = Http.postForm(
            "$parser/player/api.php",
            mapOf(
                "url" to signed,
                "key" to enplay(rc4Key, md5Hex(signed + "stray")),
                // 秒级时间戳拼在自己的 md5 前面：一份签名只用一次（实测同一份重放第二次就回非 JSON）
                "vkey" to enplay(
                    rc4Key,
                    "${System.currentTimeMillis() / 1000}" + md5Hex(signKey(signed) + "stray"),
                ),
                "ckey" to enplay(rc4Key, md5Hex(URI(parser).host + "stray")),
            ),
            headers = mapOf(
                "Referer" to "$parser/player/",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            // 平台页那类线路（from 是 qq / youku 那种）要解析站自己去上游现取，实测最慢一次 151 秒。
            // 界面不能陪着等两分钟：到点报「没在 30 秒内答话」，交给自动重试去换线路。
            timeoutSec = 30,
        )
        val obj = runCatching { json.parseToJsonElement(reply.body) as? JsonObject }.getOrNull()
            ?: error("解析站回的不是能读的那份数据（HTTP ${reply.status}）—— 换一条线路试试")
        val cipher = obj.field("url")?.takeIf { it.isNotBlank() }
        if (obj.field("code")?.toIntOrNull() != 200 || cipher == null) {
            error("解析站没给出这一集的地址（回的是 code=${obj.field("code") ?: "?"}）—— 换一条线路试试")
        }
        val key = obj.field("aes_key")
        val iv = obj.field("aes_iv")
        if (key == null || iv == null) error("解析站没下发解密的钥匙")
        // 空串也是「下发了」，但解出来是零长的 ByteArray：RC4 会撞上 key[i % 0]（报 / by zero），
        // 长度不对的则会在 Cipher 里变成 InvalidKeyException。两句都不是用户能读懂的话，
        // 也不是这条线路的真实成因。AES-128-CBC 只认 16 字节的钥匙与 16 字节的 IV，
        // 所以按这个卡死，不合适的都归到同一句人话上。
        val aesKey = deplay(rc4Key, key)
        val aesIv = deplay(rc4Key, iv)
        if (aesKey.size != AES_BLOCK_BYTES || aesIv.size != AES_BLOCK_BYTES) {
            error("解析站没下发可用的解密钥匙 —— 换一条线路试试")
        }
        val stream = aesDecrypt(cipher, aesKey, aesIv)
        if (!stream.startsWith("http")) error("解析站解出来的不是一个地址")
        return StreamResolution.Ready(stream)
    }

    /**
     * 播放页正文。
     *
     * 这一站会随机先发一张 403 的挑战页（132 字节，一句 `window.location.href = 同一个地址`）
     * 并同时下发会话 cookie；浏览器遇到它会重走一次、带上那颗 cookie，第二次起就一直是 200
     * （实测：同一集不带 cookie 连要两次都是 403，带着第一次那颗 cookie 再要就一直 200）。
     * 这里跟着做同一个动作，一次。
     */
    private suspend fun playPage(pageUrl: String, site: String, auth: Map<String, String>): String {
        val first = Http.getKeepStatus(pageUrl, auth, referer = "$site/")
        if (MacPlayerJson.read(first.body) != null) return first.body
        val second = first.setCookie?.let {
            Http.getKeepStatus(pageUrl, auth + mapOf("Cookie" to it), referer = "$site/")
        }
        val body = second?.body ?: first.body
        if (MacPlayerJson.read(body) != null) return body
        error(
            "打不开这一集的播放页：站点回 HTTP ${second?.status ?: first.status}，" +
                "正文里没有播放数据（带着它下发的那颗会话再要过一次，还是不给）" +
                if (second?.status == 429 || first.status == 429) "—— 同一台设备连着要会被它限流" else ""
        )
    }

    /**
     * 解析站地址：读站点自己的 `/static/player/parse.js`。
     *
     * 不在 APK 里写死那个域名 —— 它是个 punycode 中文域名（xn--2p1a.bfapi.cyou 这一类），
     * 这类站换域名是常事；换域名之后 parse.js 自己会跟着换，写死的则会一路报「打不开」。
     *
     * 读一次记下来：它是张静态文件，而自动重试会把一个源的十几条线路连着试完，
     * 每次都重新要一遍就是每次都多打一个请求 —— 这个源正是被连着打会回 429 的那种。
     */
    private suspend fun parserBase(site: String, pageUrl: String, auth: Map<String, String>): String =
        parserBases[site] ?: run {
            val script = Http.get("$site/static/player/parse.js", referer = pageUrl, headers = auth)
            val base = IFRAME_SRC.find(script)?.groupValues?.get(1)?.trimEnd('/') ?: error(
                "站点的 /static/player/parse.js 里读不到那张 iframe —— 它换了取链的写法"
            )
            parserBases[site] = base
            base
        }

    private val parserBases = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 拼出签名原文 —— 与脚本看到的 iframe query string 一字不差。
     *
     * iframe 的 src 是站点 parse.js 拼的，脚本再把这个 query string 里 `url=` 之后的整串取回来
     * （也就是连 `&next=`、`&title=` 一起），然后按下面几步修剪。修剪顺序照脚本，不重排。
     */
    private fun signedText(credential: String, linkNext: String, title: String, host: String): String {
        var text = credential + "&next=//" +
            (if (linkNext.isBlank()) "" else host + linkNext) +
            "&title=" + title.substringBefore('-')
        // 页面是 https，平台页线路的 url 字段常还给 http://
        text = text.replaceFirst("http://", "https://")
        val html = text.indexOf(".html")
        if (html >= 0) text = text.substring(0, html + ".html".length)
        val shtml = text.indexOf(".shtml")
        if (shtml >= 0) text = text.substring(0, shtml + ".shtml".length)
        if (text.contains("bilibili.com")) {
            // 平台页线路带的那串追踪参数，脚本按原样砍
            text = text.substringBefore("?spm_id_from=").substringBefore("?from_spmid=").substringBefore("?theme=")
        }
        return text
    }

    // ---------- 站点脚本里那三个函数 ----------

    /** 脚本里的 calculatee：md5 的十六进制串。 */
    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 脚本里的 calculate：md5 之后再拼一个「空格 + P」，取末尾 22 个字符。 */
    private fun signKey(text: String): String = (md5Hex(text) + " P").takeLast(22)

    /**
     * RC4（KSA 摆表 + PRGA 出字节）。脚本里这个函数叫 `aesplay` —— 名字是它自己叫错的。
     *
     * JS 那边 key/data 走的是 charCodeAt 的字符码；这几处的输入全是 ASCII，按字节走与它一字不差。
     *
     * 空 key 直接拒绝：`key[i % key.size]` 会抛 `ArithmeticException: / by zero`，
     * 那是一条会显示给用户看的内部错误。空 key 只可能来自一份没下发的钥匙，
     * 调用方（见 [resolve]）本来就该按「没下发可用的解密钥匙」去说。
     */
    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "解析站没下发可用的解密钥匙 —— 换一条线路试试" }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val t = s[i]
            s[i] = s[j]
            s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (i in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]
            s[a] = s[b]
            s[b] = t
            out[i] = (s[(s[a] + s[b]) and 0xFF] xor (data[i].toInt() and 0xFF)).toByte()
        }
        return out
    }

    /** 脚本里的 enplay：base64(RC4(calculate(原文), 明文))。 */
    private fun enplay(signKey: String, data: String): String =
        Base64.encodeToString(
            rc4(signKey.toByteArray(Charsets.UTF_8), data.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )

    /** 脚本里的 deplay：同一把 RC4 再跑一遍（流密码，加解密同一段代码）；下发的钥匙就这样还原。 */
    private fun deplay(signKey: String, encoded: String): ByteArray =
        rc4(signKey.toByteArray(Charsets.UTF_8), Base64.decode(encoded, Base64.NO_WRAP))

    /**
     * 响应的 `url`：base64 的 AES-128-CBC/Pkcs7。
     *
     * JCE 的 PKCS5Padding 对 16 字节分组来说就是 PKCS7，跟脚本里 CryptoJS 那一份同一回事。
     */
    private fun aesDecrypt(encoded: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
