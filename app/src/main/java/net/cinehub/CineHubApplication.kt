package net.cinehub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import net.cinehub.data.net.Http
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 图片加载的全局配置。
 *
 * 豆瓣的封面挂在 `*.doubanio.com` 上，它看请求头放行：不带 Referer 回 418，
 * 带了 Referer 但 UA 是 `okhttp/…` 回 403，换成浏览器 UA 才给图（实测 200）。
 * 列表接口是影枢自己发出去的、本来就带浏览器 UA；图片走 Coil 自带的客户端，
 * 所以在这里统一给这些主机补上两个头 —— 别的源照旧裸取，
 * 放在一处，免得每个用图的界面各写一遍。
 */
class CineHubApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { OkHttpClient.Builder().addInterceptor(DoubanImageHeaders()).build() }
        .build()

    private class DoubanImageHeaders : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (!req.url.host.endsWith("doubanio.com")) return chain.proceed(req)
            return chain.proceed(
                req.newBuilder()
                    .header("Referer", DOUBAN_PAGE)
                    .header("User-Agent", Http.UA)
                    .build(),
            )
        }
    }

    private companion object {
        const val DOUBAN_PAGE = "https://movie.douban.com/"
    }
}
