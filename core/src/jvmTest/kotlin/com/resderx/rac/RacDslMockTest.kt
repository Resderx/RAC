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
import com.resderx.rac.api.completions.CompletionsStreamChunk
import com.resderx.rac.dsl.RAC
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.FinishReason
import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ProviderRegistry
import com.resderx.rac.providers.SimpleModelProvider

/**
 * RAC DSL 集成测试脚手架（JVM MockEngine）。
 *
 * - 作用：用 MockEngine 注入 HttpClient 到 RAC 实例，端到端验证 chat { } / chatStream { } 的 DSL 流程
 * - 必要性：验证 RequestExecutor → CompletionsClient → Mappers → AIMessage 的完整链路在 Mock 下正常工作
 * - 设计：构建 Completions 类型供应商，用 MockEngine 返回预设 JSON/SSE，验证统一 AIMessage 输出
 * - 边缘：baseUrl 指向 localhost，apiKey 为 null（Mock 不需要真实鉴权）
 */
class RacDslMockTest {

    /** 创建由 MockEngine 支撑的 RAC 实例，handler 决定 HTTP/SSE 响应。 */
    private fun racWithMock(handler: MockRequestHandler): RAC {
        val client = HttpClient(SseCapableMockEngine(handler)) {
            install(SSE)
            install(HttpTimeout)
        }
        val provider = SimpleModelProvider(
            name = "mock",
            baseUrl = "http://localhost",
            apiKey = null,
            defaultApiType = ApiType.COMPLETIONS,
            defaultModel = "gpt-4",
        )
        val registry = ProviderRegistry().apply { register(provider) }
        return RAC(
            httpClient = client,
            registry = registry,
            defaultProvider = provider,
        )
    }

    @Test
    fun chatReturnsAiMessage() = runTest {
        val json = """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
        val rac = racWithMock { _ ->
            respond(json, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val ai: AIMessage = rac.chat {
            user("ping")
        }
        assertEquals("Hi there", ai.content)
        assertEquals(FinishReason.STOP, ai.finishReason)
        rac.httpClient.close()
    }

    @Test
    fun chatStreamEmitsChunks() = runTest {
        val chunk1 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}"""
        val chunk2 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}"""
        val sseBody = "data: $chunk1\n\ndata: $chunk2\n\ndata: [DONE]\n\n"
        val rac = racWithMock { _ ->
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        val chunks: List<CompletionsStreamChunk> = rac.chatStream {
            user("ping")
        }.toList()
        assertEquals(2, chunks.size)
        assertEquals("Hel", chunks[0].choices[0].delta.content)
        assertEquals("lo", chunks[1].choices[0].delta.content)
        rac.httpClient.close()
    }
}
