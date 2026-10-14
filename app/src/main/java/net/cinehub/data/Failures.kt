package net.cinehub.data

import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 取一次，失败退回空 —— 但协程被取消时照原样抛出去。
 *
 * `runCatching` 会把 CancellationException 也一起吞掉：父作用域都停了，这里却当成
 * 「这个源没返回」继续往下走，取消就变得不可靠（一次换源要并发打七个源，
 * 用户退回去之后那七个还在跑就是这么来的）。
 */
internal inline fun <T> quietly(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * 把网络异常的原文翻成一句能显示的短原因。
 *
 * OkHttp 抛的是 `connection closed`、`failed to connect to …` 这类英文 ——
 * 直接显示等于把日志甩到界面上；但「哪个源没查动、为什么」必须留着，
 * 否则界面只能说「没结果」，用户分不清是站点没有还是影枢没连上。
 */
internal fun Throwable.shortReason(): String = when (this) {
    is UnknownHostException -> "域名没解析出来"
    is SocketTimeoutException -> "连它超时"
    is javax.net.ssl.SSLException -> "加密连接没建起来"
    is java.io.IOException -> "连到一半被切断"
    else -> message?.take(60) ?: "没给出原因"
}
