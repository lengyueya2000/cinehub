package net.cinehub.data.source

import net.cinehub.data.model.Media
import net.cinehub.data.model.MediaDetail
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution

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

/** 适配器工厂：按 [SourceConfig.kind] 选择实现。 */
object SourceAdapters {
    fun create(cfg: SourceConfig): SourceAdapter = when (cfg.kind) {
        net.cinehub.data.model.SourceKind.M3u -> M3uAdapter(cfg)
        net.cinehub.data.model.SourceKind.JsonApi -> MaccmsAdapter(cfg)
        net.cinehub.data.model.SourceKind.Html -> HtmlAdapter(cfg)
    }
}
