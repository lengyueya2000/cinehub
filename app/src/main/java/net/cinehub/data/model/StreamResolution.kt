package net.cinehub.data.model

/**
 * 直链解析结果。
 *
 * 分成几种而不是一个可空的字符串，是因为「拿不到直链」有好几种完全不同的成因，
 * 对用户意味着完全不同的下一步 —— 提示语混为一谈等于没提示。
 *
 * 由各适配器自己判断属于哪一种（只有它们知道自己那个站的规则），
 * [net.cinehub.data.MediaRepository] 只负责在适配器抛异常时兜底成 [Failed]。
 */
sealed interface StreamResolution {
    /** 拿到了能直接交给播放器的地址。 */
    data class Ready(val url: String) : StreamResolution

    /** 源给的是爱奇艺 / 腾讯 / B 站那类平台的播放页，本机解不了别人的页面。 */
    data class ThirdPartyPage(val pageUrl: String, val platform: String) : StreamResolution

    /** 直链藏在站内登录态后面 —— 填用户自己的凭证有可能走通。 */
    data object NeedsSession : StreamResolution

    /**
     * 地址由源站自己的页面脚本现算出来（常见于用 WASM 做签名的站）。
     *
     * 和 [NeedsSession] 的区别：这里连**请求本身**都构造不出来 —— 参数里带签名，
     * 不执行站点的脚本就得不到。所以填凭证解决不了这一步。
     * 影枢不执行站点脚本、也不去逆它的签名，于是如实说「拿不到」。
     */
    data object NeedsClientScript : StreamResolution

    /** 解析过程本身失败了（规则缺失、网络错误、页面结构变了…），原因如实带回。 */
    data class Failed(val reason: String) : StreamResolution
}

/**
 * 把解析结果翻译成给用户看的一句话；能正常播放时返回 null。
 *
 * 播放和缓存走的是同一条解析路径，所以要说的话也得是同一句 ——
 * 之前它在 ViewModel 里写了一份，下载那边再抄一遍就会慢慢长成两套说法。
 */
fun StreamResolution.userNote(): String? = when (this) {
    is StreamResolution.Ready -> null

    is StreamResolution.ThirdPartyPage ->
        "这个源给的是${platform}的播放页，不是视频文件本身。" +
            "影枢只在本机解码直链，不会去解析、转码别人的页面。"

    is StreamResolution.NeedsSession ->
        "直链只对登录用户开放，影枢不代为登录。" +
            "可以在「播放源管理」里填入你自己的站点凭证，让影枢只做转发。"

    is StreamResolution.NeedsClientScript ->
        "直链地址由源站页面里的脚本现算（带签名），不执行它的脚本就构造不出请求。" +
            "影枢不执行站点脚本、也不逆它的签名，所以这里只能如实说拿不到 —— " +
            "填凭证也解决不了这一步。"

    is StreamResolution.Failed -> "解析直链失败：$reason"
}

/** 已知的第三方视频平台。 */
object VideoPlatforms {    private val HOSTS = listOf(
        "v.qq.com" to "腾讯视频",
        "film.qq.com" to "腾讯视频",
        "youku.com" to "优酷",
        "iqiyi.com" to "爱奇艺",
        "bilibili.com" to "哔哩哔哩",
        "mgtv.com" to "芒果 TV",
        "sohu.com" to "搜狐视频",
        "le.com" to "乐视",
        "pptv.com" to "PP 视频",
        "migu.cn" to "咪咕视频",
        "1905.com" to "电影网",
    )

    /**
     * 命中的平台中文名；不是已知平台返回 null。
     *
     * 用这个来区分「别人的播放页」和「源自己的播放页」——
     * 两者都是 http 地址，只按 `startsWith("http")` 判断会把后者也误报成第三方页面。
     */
    fun platformOf(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        return HOSTS.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") }?.second
    }
}

/** 判断一个地址是不是能直接交给播放器的媒体文件。 */
object StreamUrls {
    private val MEDIA_EXT = listOf(".m3u8", ".mp4", ".mkv", ".flv", ".ts", ".mpd", ".mov")

    fun isMediaUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val path = url.substringBefore('?').lowercase()
        return MEDIA_EXT.any { path.endsWith(it) }
    }
}
