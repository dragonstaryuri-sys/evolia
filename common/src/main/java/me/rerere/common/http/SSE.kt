package me.rerere.common.http

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * 代表 SSE 连接中的各种事件
 */
sealed class SseEvent {
    data object Open : SseEvent()
    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()
    data object Closed : SseEvent()
    data class Failure(val throwable: Throwable?, val response: Response?, val errorBody: String? = null) : SseEvent()
}

/**
 * 为 OkHttpClient 创建 SSE 连接的扩展函数
 */
fun OkHttpClient.sseFlow(request: Request): Flow<SseEvent> {
    return callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(SseEvent.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(SseEvent.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(SseEvent.Closed)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 强制抓取响应体原文，用于诊断业务错误
                val errorBody = response?.let { resp ->
                    try {
                        // 使用 peekBody 镜像一份响应体，不消耗原始流
                        resp.peekBody(1024 * 128).string()
                    } catch (e: Exception) {
                        null
                    }
                }

                if (!errorBody.isNullOrBlank()) {
                    Log.e("SSE", "连接失败响应详情: $errorBody")
                }

                trySend(SseEvent.Failure(t, response, errorBody))
                channel.close()
            }
        }

        val factory = EventSources.createFactory(this@sseFlow)
        val eventSource = factory.newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
