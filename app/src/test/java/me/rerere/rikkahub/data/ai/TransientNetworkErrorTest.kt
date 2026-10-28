package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 网络瞬态错误分类器（isTransientNetworkError）的行为验证：
 * 连接被掐断/重置/DNS 失败/建连超时可重试；读超时、取消、HTTP 业务错误不可重试。
 */
class TransientNetworkErrorTest {

    @Test
    fun `connection abort errno 103 is transient`() {
        // Android 系统掐断 socket 的典型报错
        assertTrue(isTransientNetworkError(SocketException("Software caused connection abort")))
        assertTrue(isTransientNetworkError(IOException("Connection aborted")))
    }

    @Test
    fun `connection reset and broken pipe are transient`() {
        assertTrue(isTransientNetworkError(SocketException("Connection reset by peer")))
        assertTrue(isTransientNetworkError(IOException("Broken pipe")))
    }

    @Test
    fun `dns failure is transient`() {
        assertTrue(
            isTransientNetworkError(
                UnknownHostException("Unable to resolve host \"api.example.com\": No address associated with hostname")
            )
        )
    }

    @Test
    fun `connect timeout is transient but read timeout is not`() {
        assertTrue(isTransientNetworkError(SocketTimeoutException("connect timed out")))
        assertFalse(isTransientNetworkError(SocketTimeoutException("Read timed out")))
        assertFalse(isTransientNetworkError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `cancellation and http errors are not transient`() {
        assertFalse(isTransientNetworkError(CancellationException("canceled")))
        assertFalse(isTransientNetworkError(IOException("canceled")))
        assertFalse(isTransientNetworkError(IllegalStateException("HTTP 502: Bad Gateway")))
        assertFalse(isTransientNetworkError(IOException("HTTP 401: Unauthorized")))
    }

    @Test
    fun `socket closed and null message are not transient`() {
        // 本地主动关闭（如取消生成）不应触发重试
        assertFalse(isTransientNetworkError(IOException("Socket closed")))
        assertFalse(isTransientNetworkError(IOException(null as String?)))
    }
}
