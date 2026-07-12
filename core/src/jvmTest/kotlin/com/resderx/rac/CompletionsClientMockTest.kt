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
import com.resderx.rac.api.completions.CompletionsClient
import com.resderx.rac.api.completions.CompletionsRequest
import com.resderx.rac.exceptions.RACApiException
import com.resderx.rac.network.RequestExecutor

/**
 * Completions API 客户端的 JVM MockEngine 测试。
 *
 * - 作用：用 MockEngine 模拟 HTTP/SSE 响应，验证 CompletionsClient 的非流式/流式/错误处理逻辑
 * - 必要性：JVM 平台 MockEngine 可靠，覆盖网络层无需真实 API Key
 * - 设计：executorWithMock 辅助函数构建 MockEngine 支撑的 RequestExecutor；每个测试独立注入响应
 */
class CompletionsClientMockTest {

    /** 创建由 MockEngine 支撑的 RequestExecutor，handler 决定响应内容。 */
    private fun executorWithMock(handler: MockRequestHandler): RequestExecutor {
        val client = HttpClient(SseCapableMockEngine(handler)) {
            install(SSE)
            install(HttpTimeout)
        }
        return RequestExecutor(client)
    }

    @Test
    fun completeReturnsResponse() = runTest {
        val json = """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""
        val executor = executorWithMock { _ ->
            respond(json, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = CompletionsClient(executor)
        val request = CompletionsRequest(model = "gpt-4", messages = emptyList())
        val resp = client.complete("http://localhost/v1/chat/completions", emptyMap(), request)
        assertEquals("Hello!", resp.choices[0].message.content)
    }

    @Test
    fun completeThrowsOn401() = runTest {
        val executor = executorWithMock { _ ->
            respond("unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = CompletionsClient(executor)
        assertFailsWith<RACApiException> {
            client.complete(
                "http://localhost/v1/chat/completions",
                emptyMap(),
                CompletionsRequest(model = "x", messages = emptyList()),
            )
        }
    }

    @Test
    fun streamParsesChunksAndStopsAtDone() = runTest {
        // SSE response with 2 chunks + [DONE]
        val chunk1 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}"""
        val chunk2 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}"""
        val sseBody = "data: $chunk1\n\ndata: $chunk2\n\ndata: [DONE]\n\n"
        val executor = executorWithMock { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val client = CompletionsClient(executor)
        val request = CompletionsRequest(model = "gpt-4", messages = emptyList())
        val chunks = client.stream("http://localhost/v1/chat/completions", emptyMap(), request).toList()
        assertEquals(2, chunks.size)
        assertEquals("Hi", chunks[0].choices[0].delta.content)
        assertEquals("!", chunks[1].choices[0].delta.content)
        assertEquals("stop", chunks[1].choices[0].finishReason)
    }
}
