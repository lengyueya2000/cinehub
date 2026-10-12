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
     *
     * 有些站点要求登录后才给直链。影枢不去绕过对方的校验，
     * 而是让用户填自己的凭证 —— 能不能看取决于用户在该站的账号。
     */
    val authToken: String? = null,
    /** 凭证的传递方式。 */
    val authMode: AuthMode = AuthMode.None,
    val createdAt: Long = System.currentTimeMillis(),
)

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
    val titleSelector: String = "img@alt",
    val coverSelector: String = "img@src",
    val detailPath: String = "/play/{id}",
    val episodeSelector: String = "a.episode-link",
    val episodeNameAttr: String = "text",
    val episodeUrlAttr: String = "href",
    /**
     * 直链获取方式。
     *
     * 苹果 CMS 模板站会把真实地址写进播放页的 `player_aaaa` 配置里，
     * 这是公开的页面数据（不是绕过登录），所以可以照读；
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
         * 直链由站点自己的页面脚本现算（WASM 签名 / JS 拼地址）。
         *
         * 不执行对方的脚本就构造不出请求，所以影枢不解析、也不去逆它的签名 ——
         * 标这个模式只是为了让提示语说实话。
         */
        const val MODE_CLIENT_SCRIPT = "clientScript"

        /**
         * 直链只对登录用户开放。
         *
         * 影枢不代为登录，但会提示用户可以在源配置里填**自己的**站点凭证
         * （`authToken` + `authMode`），凭证只做转发。
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
