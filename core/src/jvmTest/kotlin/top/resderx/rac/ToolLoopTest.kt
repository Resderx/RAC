/*
 * Copyright 2026 Resderx
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package top.resderx.rac

import top.resderx.rac.dsl.Llm
import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.FinishReason
import top.resderx.rac.messages.ToolCall
import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ProviderRegistry
import top.resderx.rac.providers.SimpleModelProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多轮工具调用循环测试（JVM MockEngine）。
 *
 * - 作用：端到端验证 [Llm.chatWithTools] 的多轮工具调用闭环——
 *   模型返回 toolCalls → 执行工具 → 回写 ToolMessage → 模型返回最终答案
 * - 必要性：工具调用循环是 Agent 流程的核心，需验证对话历史正确累积、工具回执正确注入、
 *   循环终止条件（无 toolCalls 或达 maxRounds）正确触发
 * - 设计：用 [SseCapableMockEngine] 注入 HttpClient，通过 AtomicInteger 计数器在
 *   首次请求返回 tool_calls 响应、第二次返回 stop 响应，验证两轮循环行为
 * - 边缘：baseUrl 指向 localhost，apiKey 为 null（Mock 不需鉴权）；
 *   maxRounds 上限测试验证达到上限时返回最后响应（finishReason=TOOL_CALLS）
 * - 模块拆分：MCP 工具调用循环测试（chatWithMcp）已迁移至 rac-mcp 模块的
 *   top.resderx.rac.ToolLoopTest，本测试仅验证 core 的 chatWithTools
 */
class ToolLoopTest {

    /**
     * 创建由 SseCapableMockEngine 支撑的 Llm 实例，handler 决定 HTTP 响应。
     *
     * @param handler Mock 请求处理器
     * @return 配置好的 Llm 实例（Completions 协议，defaultModel="gpt-4"）
     */
    private fun racWithMock(handler: MockRequestHandler): Llm {
        val client = HttpClient(SseCapableMockEngine(handler)) {
            install(SSE)
            install(HttpTimeout)
        }
        val provider = SimpleModelProvider(
            name = "mock",
            baseUrl = "http://localhost",
            apiKey = null,
            defaultApiType = ApiType.COMPLETIONS,
            models = mapOf("gpt-4" to ModelConfig()),
        )
        val registry = ProviderRegistry().apply { register(provider) }
        return Llm(
            httpClient = client,
            registry = registry,
            defaultProvider = provider,
        )
    }

    /**
     * 构造 Completions API tool_calls 响应 JSON（模型请求调用工具）。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名称
     * @param arguments 工具参数 JSON 字符串
     * @return 完整的 CompletionsResponse JSON
     */
    private fun toolCallsResponseJson(toolCallId: String, toolName: String, arguments: String): String {
        // 转义 arguments 中的双引号以便嵌入 JSON 字符串字段
        val escapedArgs = arguments.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$toolCallId","type":"function","function":{"name":"$toolName","arguments":"$escapedArgs"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":10,"total_tokens":15}}"""
    }

    /**
     * 构造 Completions API stop 响应 JSON（模型返回最终答案）。
     *
     * @param content 最终答案文本
     * @return 完整的 CompletionsResponse JSON
     */
    private fun stopResponseJson(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
    }

    @Test
    fun chatWithToolsExecutesSingleRoundToolCall() = runTest {
        // 计数器：首次请求返回 tool_calls，第二次返回 stop
        val callCount = AtomicInteger(0)
        val rac = racWithMock { _ ->
            val n = callCount.incrementAndGet()
            if (n == 1) {
                // 首轮：模型请求调用 get_weather 工具
                respond(
                    toolCallsResponseJson("call_1", "get_weather", """{"city":"Beijing"}"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            } else {
                // 第二轮：模型基于工具结果返回最终答案
                respond(
                    stopResponseJson("Beijing is sunny"),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }

        // 工具执行器：模拟 get_weather 工具返回天气信息
        val toolExecutor: suspend (ToolCall) -> String = { call ->
            assertEquals("call_1", call.id)
            assertEquals("get_weather", call.name)
            assertEquals("""{"city":"Beijing"}""", call.arguments)
            """{"weather":"sunny","temp":25}"""
        }

        val result: AIMessage = rac.chatWithTools(
            maxRounds = 5,
            toolExecutor = toolExecutor,
        ) {
            user("What's the weather in Beijing?")
            tools {
                tool("get_weather", "Get weather for a city")
            }
        }

        // 验证最终响应：无工具调用，finishReason=STOP，内容为最终答案
        assertEquals("Beijing is sunny", result.content)
        assertTrue(result.toolCalls.isEmpty(), "Final response should have no tool calls")
        assertEquals(FinishReason.STOP, result.finishReason)
        // 验证恰好发生 2 次 HTTP 请求（首轮 tool_calls + 第二轮 stop）
        assertEquals(2, callCount.get(), "Should make exactly 2 HTTP calls (tool call round + final round)")
        rac.httpClient.close()
    }

    @Test
    fun chatWithToolsStopsImmediatelyWhenNoToolCalls() = runTest {
        // 模型首次响应即返回最终答案（无工具调用），循环不应执行
        val callCount = AtomicInteger(0)
        val rac = racWithMock { _ ->
            callCount.incrementAndGet()
            respond(
                stopResponseJson("Direct answer"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val result = rac.chatWithTools(
            maxRounds = 5,
            toolExecutor = { _ -> "should not be called" },
        ) {
            user("Hello")
        }

        assertEquals("Direct answer", result.content)
        assertEquals(FinishReason.STOP, result.finishReason)
        // 仅 1 次 HTTP 请求，工具执行器不应被调用
        assertEquals(1, callCount.get(), "Should make only 1 HTTP call when no tool calls requested")
        rac.httpClient.close()
    }

    @Test
    fun chatWithToolsRespectsMaxRoundsLimit() = runTest {
        // 模型每次都返回 tool_calls，验证达到 maxRounds 时返回最后响应
        val callCount = AtomicInteger(0)
        val rac = racWithMock { _ ->
            callCount.incrementAndGet()
            respond(
                toolCallsResponseJson("call_${callCount.get()}", "loop_tool", "{}"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val toolCallCount = AtomicInteger(0)
        val result = rac.chatWithTools(
            maxRounds = 2,
            toolExecutor = { _ ->
                toolCallCount.incrementAndGet()
                "result"
            },
        ) {
            user("loop")
            tools {
                tool("loop_tool", "A tool that always triggers another call")
            }
        }

        // maxRounds=2：首轮调用 + 2 轮循环 = 3 次 HTTP 请求
        assertEquals(3, callCount.get(), "Should make 3 HTTP calls (1 initial + 2 rounds)")
        // 工具执行器被调用 2 次（每轮 1 个 toolCall）
        assertEquals(2, toolCallCount.get(), "Tool executor should be called 2 times")
        // 达到上限时返回的响应 finishReason 仍为 TOOL_CALLS
        assertEquals(FinishReason.TOOL_CALLS, result.finishReason)
        assertTrue(result.toolCalls.isNotEmpty(), "Should still have pending tool calls at maxRounds")
        rac.httpClient.close()
    }
}
