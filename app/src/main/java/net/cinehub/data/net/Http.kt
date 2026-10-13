package net.cinehub.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 所有源共用的 HTTP 客户端。
 *
 * 每个源站点都会校验 Referer 与 UA，所以这里不做全局伪装，
 * 由调用方按站点传合适的头。
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 按源配置生成鉴权头。
     *
     * 影枢只转发用户自己填的凭证，不代替用户登录、也不去猜对方的校验算法 ——
     * 能看什么取决于用户在该站自己拥有的权限。
     */
    fun authHeaders(cfg: net.cinehub.data.model.SourceConfig): Map<String, String> {
        val token = cfg.authToken?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return when (cfg.authMode) {
            net.cinehub.data.model.AuthMode.Cookie -> mapOf("Cookie" to token)
            net.cinehub.data.model.AuthMode.Bearer -> mapOf("Authorization" to "Bearer $token")
            net.cinehub.data.model.AuthMode.None -> emptyMap()
        }
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): String = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { b.header("Referer", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
            .newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) error(failureReason(r.code))
                r.body?.string() ?: error("空响应")
            }
    }

    /**
     * 带自定义头地取一次，并把响应里服务端下发的 Cookie 带回给调用方。
     *
     * 有些站（如带签名鉴权的前端应用）要先建匿名会话、之后的请求都得带上
     * 它 Set-Cookie 回来的东西 —— 只拿到正文就拿不到后续数据。
     */
    suspend fun getWithHeaders(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutSec: Long = 20,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        b.header("User-Agent", UA)
        referer?.let { b.header("Referer", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
            .newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) error(failureReason(r.code))
                ResponseBody(
                    body = r.body?.string() ?: error("空响应"),
                    setCookie = r.headers("Set-Cookie").joinToString("; ") {
                        it.substringBefore(';')
                    }.takeIf { it.isNotBlank() },
                )
            }
    }

    /** POST 一段 JSON，同样把 Set-Cookie 带回来。 */
    suspend fun post(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20,
    ): ResponseBody = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val b = Request.Builder().url(url).header("User-Agent", UA).post(json.toRequestBody(mediaType))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
            .newCall(b.build()).execute().use { r ->
                ResponseBody(
                    body = r.body?.string().orEmpty(),
                    status = r.code,
                    setCookie = r.headers("Set-Cookie").joinToString("; ") {
                        it.substringBefore(';')
                    }.takeIf { it.isNotBlank() },
                )
            }
    }

    /** 响应正文 + 服务端下发的会话 Cookie（没有则为 null）。 */
    data class ResponseBody(
        val body: String,
        val status: Int = 200,
        val setCookie: String? = null,
    )

    /** 探测连通性并返回延迟；失败抛出异常。 */
    suspend fun probe(url: String, referer: String? = null): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { b.header("Referer", it) }
        client.newBuilder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
            .newCall(b.build()).execute().use { r: Response ->
                if (!r.isSuccessful) error(failureReason(r.code))
                System.currentTimeMillis() - t0
            }
    }

    /**
     * 把状态码翻译成用户能理解的原因。
     *
     * 567 是腾讯 EdgeOne 的安全拦截码，直接显示「HTTP 567」对用户毫无意义 ——
     * 它真正传达的是「这个站在你所在的网络环境下拒绝访问」。
     */
    private fun failureReason(code: Int): String = when (code) {
        567 -> "安全策略拦截"
        403 -> "拒绝访问"
        404 -> "地址不存在"
        429 -> "请求过于频繁"
        in 500..599 -> "站点异常 $code"
        else -> "HTTP $code"
    }

    /** 解析站点 baseUrl 是否可用，返回最终生效的 baseUrl（可能被重定向）。 */
    suspend fun resolveBase(base: String): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base).header("User-Agent", UA)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.request.url.toString().trimEnd('/')
        }
    }
}
