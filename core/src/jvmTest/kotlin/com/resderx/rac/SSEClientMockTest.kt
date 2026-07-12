package com.resderx.rac

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.resderx.rac.exceptions.RACApiException
import com.resderx.rac.network.SSEClient

/**
 * SSEClient 的 JVM MockEngine 测试，替代原 SSETest.kt 占位。
 *
 * - 作用：验证 SSEClient 解析 SSE data 事件、过滤 [DONE] 与空值、以及非 2xx 异常映射
 * - 必要性：流式 API 均依赖 SSEClient，需在 JVM 上用 MockEngine 覆盖核心路径
 * - 设计：每个测试构建独立的 MockEngine 响应，验证 SSEClient.stream 返回的 Flow 内容
 */
class SSEClientMockTest {

    private fun sseClientWithMock(handler: MockRequestHandler): SSEClient {
        val client = HttpClient(SseCapableMockEngine(handler)) {
            install(SSE)
            install(HttpTimeout)
        }
        return SSEClient(client)
    }

    @Test
    fun streamEmitsDataEvents() = runTest {
        val sseBody = "data: alpha\n\ndata: beta\n\n"
        val sseClient = sseClientWithMock { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val events = sseClient.stream("http://localhost/sse", emptyMap(), "{}").toList()
        assertEquals(2, events.size)
        assertEquals("alpha", events[0])
        assertEquals("beta", events[1])
    }

    @Test
    fun streamFiltersDoneMarker() = runTest {
        val sseBody = "data: hello\n\ndata: [DONE]\n\n"
        val sseClient = sseClientWithMock { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val events = sseClient.stream("http://localhost/sse", emptyMap(), "{}").toList()
        assertEquals(1, events.size)
        assertEquals("hello", events[0])
    }

    @Test
    fun streamThrowsOnNon2xx() = runTest {
        val sseClient = sseClientWithMock { _ ->
            respond("server error", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<RACApiException> {
            sseClient.stream("http://localhost/sse", emptyMap(), "{}").toList()
        }
    }

    @Test
    fun streamHandlesMultiLineDataField() = runTest {
        // SSE spec: multiple data: lines in one event are joined with \n
        val sseBody = "data: line1\ndata: line2\n\n"
        val sseClient = sseClientWithMock { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val events = sseClient.stream("http://localhost/sse", emptyMap(), "{}").toList()
        assertEquals(1, events.size)
        assertTrue(events[0].isNotEmpty())
    }
}
