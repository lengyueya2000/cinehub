package net.cinehub.data.model

import kotlinx.serialization.Serializable

/**
 * 播放源配置。
 *
 * 设计上刻意做成「用户可配置的通用类型」而不是给每个站写死一套代码：
 * 站点会换域名、会改结构，用户应该能自己加一个源、自己改选择器，
 * 而不是等应用发版。
 */
@Serializable
data class SourceConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val kind: SourceKind,
    val enabled: Boolean = true,
    /** 数字越小越优先。 */
    val priority: Int = 1,
    /** 该源被识别/探测到的能力，用于界面上的标签展示。 */
    val tags: List<String> = emptyList(),
    /** HTML 解析规则；kind == Html 时生效。 */
    val html: HtmlRules? = null,
    /** JSON 接口参数；kind == JsonApi 时生效。 */
    val api: ApiRules? = null,
    /**
     * 用户自己提供的站点会话凭证（Cookie / token）。
     */
    val authToken: String? = null,
    /** 凭证的传递方式。 */
    val authMode: AuthMode = AuthMode.None,
    /**
     * 源站自己播放页的路径模板，占位符 `{id}` `{sid}` `{nid}`（线路号 / 第几集）。
     *
     * 只有接口型源需要：苹果 CMS 的 `vod_play_url` 里给的可能直接是
     * 但源站自己的播放页能播 —— 它自己的播放器会去把平台页地址换成可播流。
     * 于是影枢打开这个页面，在旁边接住页面已经请求出来的那条流 —— 换流是源站自己的播放器做的。
     */
    val playPath: String? = null,
    /**
     * 这个源的取数要不要走浏览器内核（[net.cinehub.data.net.BrowserEngine]）。
     *
     * 给的是「站点在前面挂了一道要执行它的脚本才答得出的题」那种源（Cloudflare 的
     * managed challenge 是典型）：纯 HTTP 客户端无论把请求头拼得多像浏览器都拿不到正文，
     * 差的不是头，是有没有真的跑对方那段脚本。标上这一项，这个源的取数就改由一颗后台
     * WebView 同源问出去，题由引擎自己答。
     *
     * 只对 JSON 接口型源生效（[net.cinehub.data.source.MaccmsAdapter] 认它）：
     * 网页解析那类源要的是 DOM，不是这份正文，换过去得另搭一层。
     *
     * 两件事要说在前面：这一条路上 [authToken] **发不出去** —— 浏览器不许脚本给 fetch 设
     * Cookie / Authorization 头，所以「要凭证」和「要过浏览器题」是两套东西，别同时填；
     * 而这一格目前只有内置源用得上，「添加播放源」那张表单里没有它 ——
     * 一个过不了题的站，用户自己加进来就是列表里一行永远的红字。
     *
     * 代价是这一源的取数慢 —— 第一次过题十几秒，而且全应用排队走一颗引擎。
     */
    val needsBrowser: Boolean = false,
    /**
     * 健康栏探测用的路径（拼在 [baseUrl] 后面）。
     *
     * 默认探 [baseUrl] 根路径 —— 对绝大多数站成立，它们根路径就是一个能打开的页面。
     * 但有些后端根路径直接回 404（如自建 Apple-CMS 的接口型源：内容全在 `/api/...` 下，
     * 站点根本没有首页 HTML）。那一栏探的是「这个源现在通不通」，而它的结论会决定
     * 这一源在列表里排哪儿、要不要被自动换源跳过 —— 拿一个注定的 404 去量，
     * 一个完全能取到内容的源就会永远挂着红字「连接失败」，那是假话。
     *
     * 于是这一类源可以指名一个**真的代表「服务活着」**的探点。留空即沿用根路径。
     */
    val probePath: String? = null,
    /**
     * 地址是否在界面上隐藏显示（内置源）。
     *
     * 只是防「顺手看到」：编辑表单不回显、列表行不显示完整地址。
     * 反编译或抓包依然能拿到——真正的隐藏做不到，这里只做展示层遮挡。
     * 用户自己加的源一律 false，照常完整显示、正常编辑。
     */
    val hiddenUrl: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 健康栏该探哪一个绝对地址。 */
    fun probeUrl(): String =
        baseUrl.trimEnd('/') + (probePath?.takeIf { it.isNotBlank() } ?: "")
    /**
     * 拼出源站自己的播放页地址。
     *
     * 没有配置、或缺能填的 id 就返回 null —— 宁可不给这个入口，
     * 也不要拿一个必然打不开的平台页去骗用户点。
     */
    fun playPageFor(mediaId: String?, nid: Int, sid: Int = 1): String? {
        val tpl = playPath?.takeIf { it.isNotBlank() } ?: return null
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        return baseUrl.trimEnd('/') + "/" +
            tpl.trimStart('/').replace("{id}", id).replace("{sid}", "$sid").replace("{nid}", "$nid")
    }
}

@Serializable
enum class AuthMode {
    /** 不带凭证。 */
    None,

    /** 作为 Cookie 头发送。 */
    Cookie,

    /** 作为 Authorization 头发送。 */
    Bearer,
}

enum class SourceKind {
    /** m3u / m3u8 播放列表。最通用，任何 HTTP 直链列表都能接。 */
    M3u,

    /** 标准 JSON 接口（苹果 CMS / maccms 的 api.php/provide/vod 系列）。 */
    JsonApi,

    /** 网页解析：抓列表页与详情页，按 CSS 选择器取字段。 */
    Html,

    /**
     * 带签名鉴权的前端应用（如 dongpiandi.ai）。
     *
     * 静态页里没有内容，必须按站点自己的接口协议取数据 —— 请求要签名、
     * 取播放地址要靠站点下发的匿名会话。规则不是 CSS 选择器，而是接口路径。
     */
    AiMovie,

    /**
     * 自建 Apple-CMS 后端的签名接口（MINO 型协议）。
     *
     * 与 [AiMovie] 同属「请求要签名」那一类，但握手不一样：这一型要先做一次
     * ECDH（临时 secp256r1 密钥对 + 服务端公钥 → HKDF 派生签名钥与响应体钥），
     * 之后每个请求带 HMAC-SHA256 签名头，响应正文还可能是 AES-256-GCM 加密的信封。
     * 播放地址也不在接口里：分集给的是加密票证 `tk:`，要换 play_ticket、
     * 再拿票据里下发的解析接口现算（见 [net.cinehub.data.source.MinoAdapter]）。
     */
    MinoApi,
}

@Serializable
data class HtmlRules(
    /** 搜索页路径模板，{q} 为搜索词、{page} 为页码。 */
    val listPath: String = "/search?q={q}&page={page}",
    /**
     * 分类浏览路径模板，{page} 为页码。
     *
     * 留空时退化成「把 listPath 的 {q} 去掉」，只对搜索即列表的站点成立；
     * 苹果 CMS 模板站需要单独给一条（如 /vodshow/1--------{page}---.html）。
     */
    val browsePath: String = "",
    val itemSelector: String = "a[href*=/play/]",
    val detailPath: String = "/play/{id}",
    val episodeSelector: String = "a.episode-link",
    val episodeUrlAttr: String = "href",
    /**
     * 名字正好等于其中某一项的线路，**根本不列出来**。
     *
     * 站点把哪条排在第一是替它自己的播放器挑的，影枢不一定放得出来那一条。挪到最后是一种处理，
     * 但挪着挪着它还在候选里、还会被自动换源走到，白失败一次。这一格是直接舍掉：
     * 名字**正好等于**才舍（「推荐L」「推荐K」留下），一条不剩时整条规则不生效 ——
     * 规则写错了也不该让用户看到一份没有线路的详情。
     */
    val lineDrop: List<String> = emptyList(),
    /**
     * 直链获取方式。
     *
     * 苹果 CMS 模板站会把真实地址写进播放页的 `player_aaaa` 配置里，
     * 地址要靠站点脚本现算的标 [MODE_CLIENT_SCRIPT]，
     * 直链只对登录用户开放的标 [MODE_SESSION]，
     * 其余留 [MODE_NONE]，由 [net.cinehub.data.source.HtmlAdapter.resolveStream] 如实说明。
     */
    val resolveMode: String = MODE_NONE,
) {
    companion object {
        const val MODE_NONE = "none"

        /** 从播放页的 player_aaaa 里取 url 字段。 */
        const val MODE_MACCMS_PLAYER = "maccmsPlayer"

        /**
         * 苹果 CMS 的另一形态：player_aaaa 里的 url 是一串凭据，要拿去问站点自己的解析站。
         *
         * 见 [net.cinehub.data.source.MaccmsParseResolver] —— 签名原文、三个签名值、
         * 响应里下发的 AES 钥匙，都按站点自己那段脚本的样子复现。
         */
        const val MODE_MACCMS_PARSE = "maccmsParse"

        /**
         * 直链由站点自己的页面脚本现算（WASM 签名 / JS 拼地址）。
         */
        const val MODE_CLIENT_SCRIPT = "clientScript"

        /**
         * 直链只对登录用户开放。
         *
         * 界面上提示用户可以在源配置里填**自己的**站点凭证（`authToken` + `authMode`），
         * 请求时带着它走。
         */
        const val MODE_SESSION = "session"
    }
}

@Serializable
data class ApiRules(
    /** 形如 https://host/api.php/provide/vod 或 https://host/v1/... */
    val listPath: String = "/api.php/provide/vod",
    val searchParam: String = "wd",
    val pageParam: String = "pg",
    /** JSON 里列表所在的字段路径，如 "list"。 */
    val listField: String = "list",
    val idField: String = "vod_id",
    val titleField: String = "vod_name",
    val coverField: String = "vod_pic",
    val yearField: String = "vod_year",
    val categoryField: String = "vod_class",
    val remarkField: String = "vod_remarks",
    val scoreField: String = "vod_score",
    val areaField: String = "vod_area",
    val actorsField: String = "vod_actor",
    val directorField: String = "vod_director",
    val introField: String = "vod_content",
    /** 播放地址字段，形如 "第1集$url#第2集$url"。 */
    val playUrlField: String = "vod_play_url",
    /** 线路名字段，形如 "线路1$$$线路2"。 */
    val playFromField: String = "vod_play_from",
    /** 详情接口路径，{id} 为影片 id。 */
    val detailPath: String = "/api.php/provide/vod/at/json/?ac=detail&ids={id}",
)
