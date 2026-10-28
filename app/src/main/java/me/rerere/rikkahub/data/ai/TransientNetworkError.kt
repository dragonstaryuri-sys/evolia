package me.rerere.rikkahub.data.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 判断生成过程中的异常是否为「网络层瞬态中断」——连接在传输途中被系统网络栈、
 * VPN/代理或 NAT 网关掐断（errno 103 ECONNABORTED，即 "Software caused connection abort"）、
 * 连接被重置、管道破裂、DNS 解析失败或建连超时。这类错误对同一请求重试一次是安全的。
 *
 * 刻意排除的情况：
 * - 读超时（"timeout" / "Read timed out"）：等待已远超预期，重试只会成倍拉长等待，
 *   交给应用层 chatGenerationTimeoutMinutes 看门狗统一取消；
 * - 取消 / HTTP 业务错误：不属于网络瞬态，重试无意义。
 *
 * 纯 JVM 函数（不依赖 Android API），供 GenerationHandler 重试判定与
 * ChatService.translateError 友好文案映射共用，行为有单元测试固化。
 */
fun isTransientNetworkError(t: Throwable): Boolean {
    if (t is UnknownHostException) return true
    if (t !is IOException) return false
    val message = t.message?.lowercase() ?: return false
    if (t is SocketTimeoutException) {
        // 建连超时可重试；读超时（"Read timed out" / "timeout"）不可
        return "connect" in message && "timed out" in message
    }
    val transientMarkers = listOf(
        "connection abort", // ECONNABORTED，含 "Software caused connection abort"
        "econnaborted",
        "connection reset", // ECONNRESET，含 "Connection reset by peer"
        "econnreset",
        "broken pipe",      // EPIPE
    )
    return transientMarkers.any(message::contains)
}
