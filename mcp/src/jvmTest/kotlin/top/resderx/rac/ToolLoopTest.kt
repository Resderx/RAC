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
import top.resderx.rac.mcp.McpClient
import top.resderx.rac.mcp.McpResource
import top.resderx.rac.mcp.chatWithMcp
import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.FinishReason
import top.resderx.rac.messages.ToolDefinition
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
 * MCP 工具调用循环测试（JVM MockEngine）。
 *
 * - 作用：端到端验证 [Llm.chatWithMcp] 扩展函数的多轮工具调用闭环——
 *   MCP 工具发现 → 模型返回 toolCalls → 委托 MCP 客户端 callTool → 回写 ToolMessage → 模型返回最终答案
 * - 必要性：chatWithMcp 是 rac-mcp 模块的核心扩展，需验证 MCP 工具自动注入、
 *   工具名路由、空工具列表退化等行为
 * - 模块拆分：本测试位于 rac-mcp 模块，与 core 的 ToolLoopTest 分离，
 *   因 chatWithMcp 扩展函数已从 core 迁移至 rac-mcp
 * - 设计：用 [SseCapableMockEngine] 注入 HttpClient，[FakeMcpClient] 模拟 MCP 服务器，
 *   无需真实 MCP 服务器与 AI 供应商
 * - 边缘：MCP 服务器无工具时退化为普通 chat；工具名匹配由模型决定
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
    fun chatWithMcpAutoInjectsToolsAndExecutesLoop() = runTest {
        // HTTP 层：首轮返回 tool_calls，第二轮返回 stop
        val callCount = AtomicInteger(0)
        val rac = racWithMock { _ ->
            val n = callCount.incrementAndGet()
            if (n == 1) {
                respond(
                    toolCallsResponseJson("mcp_call_1", "search", """{"query":"kotlin"}"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            } else {
                respond(
                    stopResponseJson("Kotlin is a modern programming language"),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }

        // 模拟 MCP 客户端：listTools 返回一个工具，callTool 返回搜索结果
        val mcpClient = FakeMcpClient(
            tools = listOf(
                ToolDefinition(
                    name = "search",
                    description = "Search the web",
                    parameters = """{"type":"object","properties":{"query":{"type":"string"}}}""",
                ),
            ),
            toolResults = mapOf("search" to """{"results":["Kotlin is a modern programming language"]}"""),
        )

        val result: AIMessage = rac.chatWithMcp(
            mcpClient = mcpClient,
            maxRounds = 5,
        ) {
            user("What is Kotlin?")
        }

        // 验证最终响应
        assertEquals("Kotlin is a modern programming language", result.content)
        assertEquals(FinishReason.STOP, result.finishReason)
        assertTrue(result.toolCalls.isEmpty())
        // 验证 MCP 客户端被正确调用
        assertTrue(mcpClient.listToolsCalled, "listTools should be called once")
        assertEquals(1, mcpClient.callToolCount.get(), "callTool should be called once")
        assertEquals(2, callCount.get(), "Should make 2 HTTP calls")
        rac.httpClient.close()
    }

    @Test
    fun chatWithMcpWorksWithEmptyToolList() = runTest {
        // MCP 服务器无工具时，应退化为普通 chat 调用
        val callCount = AtomicInteger(0)
        val rac = racWithMock { _ ->
            callCount.incrementAndGet()
            respond(
                stopResponseJson("No tools needed"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val mcpClient = FakeMcpClient(tools = emptyList(), toolResults = emptyMap())

        val result = rac.chatWithMcp(
            mcpClient = mcpClient,
            maxRounds = 5,
        ) {
            user("Hi")
        }

        assertEquals("No tools needed", result.content)
        assertEquals(FinishReason.STOP, result.finishReason)
        assertTrue(mcpClient.listToolsCalled, "listTools should still be called to discover tools")
        assertEquals(0, mcpClient.callToolCount.get(), "callTool should not be called when no tools available")
        assertEquals(1, callCount.get(), "Should make only 1 HTTP call")
        rac.httpClient.close()
    }
}

/**
 * MCP 客户端的测试替身（fake），用于 [ToolLoopTest] 中模拟工具发现与调用。
 *
 * - 作用：在不启动真实 MCP 服务器的前提下，提供 [McpClient] 接口的可控实现，
 *   使测试可预设工具列表与工具调用结果
 * - 设计：tools 字段为 listTools() 的返回值；toolResults 映射工具名→结果字符串；
 *   listToolsCalled 与 callToolCount 用于断言验证调用次数
 * - 边缘：callTool 传入未预设的工具名时返回空字符串；listResources/readResource 未使用，返回空
 */
private class FakeMcpClient(
    /** listTools() 返回的预设工具列表。 */
    private val tools: List<ToolDefinition>,
    /** 工具名 → 调用结果的映射；未预设的工具名返回空字符串。 */
    private val toolResults: Map<String, String>,
) : McpClient {
    /** listTools() 是否被调用过（测试断言用）。 */
    var listToolsCalled: Boolean = false
        private set

    /** callTool() 被调用的次数（测试断言用）。 */
    val callToolCount = AtomicInteger(0)

    override suspend fun listTools(): List<ToolDefinition> {
        listToolsCalled = true
        return tools
    }

    override suspend fun callTool(name: String, arguments: String): String {
        callToolCount.incrementAndGet()
        return toolResults[name] ?: ""
    }

    override suspend fun listResources(): List<McpResource> = emptyList()

    override suspend fun readResource(uri: String): String = ""

    override suspend fun close() { /* no-op */ }
}
