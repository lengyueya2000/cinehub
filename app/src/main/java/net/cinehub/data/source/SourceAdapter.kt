package net.cinehub.data.source

import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个播放源的读写接口。
 *
 * 实现类只需关心「怎么把这个站点的内容变成 [Media] / [MediaDetail]」，
 * 上层（搜索聚合、详情页、播放器）完全不感知站点差异。
 */
interface SourceAdapter {

    val config: SourceConfig

    /** 首页 / 分类浏览。失败时抛异常，由上层转成源健康状态。 */
    suspend fun browse(page: Int = 1): List<Media>

    /** 搜索。 */
    suspend fun search(keyword: String, page: Int = 1): List<Media>

    /** 详情，含各线路与分集。 */
    suspend fun detail(id: String): MediaDetail

    /**
     * 解析某一集的真实播放地址。
     *
     * 只有适配器自己知道「拿不到直链」是哪一种成因（登录态？第三方播放页？
     * 地址要靠站点脚本现算？），所以这里直接返回 [StreamResolution]，
     * 上层只负责把它翻译成给用户看的话，不再靠猜。
     */
    suspend fun resolveStream(episodeUrl: String): StreamResolution
}

/** 适配器工厂。 */
object SourceAdapters {

    /**
     * 同一份连接配置取到的是**同一个实例**。
     *
     * 以前每次调用现造一个，于是挂在实例上的东西每走一步都清零 ——
     * 站点给本机签发的匿名会话 Cookie（懂片帝AI 于是每一次搜索、每一次取线路
     * 都得重新建一遍会话），以及任何想按实例存的缓存。
     *
     * 认的是「怎么跟这个站说话」那几项：地址、接入方式、解析规则、凭证、播放页模板。
     * 名字 / 位次 / 启停改了不影响说话方式，不该把会话一起丢掉；
     * 反过来用户改了地址或填了凭证，那必须换新实例，不能拿着旧会话去打新站。
     */
    fun create(cfg: SourceConfig): SourceAdapter {
        val key = connectionKey(cfg)
        val held = CACHE.compute(cfg.id) { _, have ->
            if (have != null && have.key == key) have else Held(key, newOf(cfg))
        }
        return checkNotNull(held).adapter
    }

    /** 源被删掉时把它那份实例连同会话一起丢掉。 */
    fun forget(id: String) {
        CACHE.remove(id)
    }

    private fun connectionKey(cfg: SourceConfig) = listOf(
        cfg.baseUrl, cfg.kind.name, cfg.authToken.orEmpty(), cfg.authMode.name,
        cfg.playPath.orEmpty(), cfg.needsBrowser.toString(), cfg.probePath.orEmpty(),
        cfg.html?.toString().orEmpty(), cfg.api?.toString().orEmpty(),
    ).joinToString("\n")

    private fun newOf(cfg: SourceConfig): SourceAdapter = when (cfg.kind) {
        net.cinehub.data.model.SourceKind.M3u -> M3uAdapter(cfg)
        net.cinehub.data.model.SourceKind.JsonApi -> MaccmsAdapter(cfg)
        net.cinehub.data.model.SourceKind.Html -> HtmlAdapter(cfg)
        net.cinehub.data.model.SourceKind.AiMovie -> AiMovieAdapter(cfg)
        net.cinehub.data.model.SourceKind.MinoApi -> MinoAdapter(cfg)
        net.cinehub.data.model.SourceKind.PpnixApi -> PpnixAdapter(cfg)
    }

    private class Held(val key: String, val adapter: SourceAdapter)

    private val CACHE = ConcurrentHashMap<String, Held>()
}
