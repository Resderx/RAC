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

package top.resderx.rac.dsl

import com.resderx.rac.api.anthropic.AnthropicClient
import com.resderx.rac.api.completions.CompletionsClient
import com.resderx.rac.api.responses.ResponsesClient
import com.resderx.rac.exceptions.RACException
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.StreamEvent
import com.resderx.rac.messages.ToolCall
import com.resderx.rac.messages.toAnthropicStreamEvents
import com.resderx.rac.messages.toCompletionsStreamEvents
import com.resderx.rac.messages.toResponsesStreamEvents
import com.resderx.rac.network.RequestExecutor
import com.resderx.rac.network.RetryExecutor
import com.resderx.rac.network.RetryPolicy
import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderRegistry
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * LLM 顶层入口类，持有所有运行时依赖并提供 chat/respond 系列调用方法。
 *
 * - 作用：作为库的主入口，聚合 HttpClient、三种 API 客户端、供应商注册表与默认供应商，
 *   暴露 `chat { }` / `chatWithTools { }` / `chatStream { }` / `anthropicStream { }` /
 *   `respond { }` / `respondStream { }` 六个核心方法
 * - 必要性：统一管理所有供应商与 API 客户端的生命周期，调用方通过 [Llm] 实例发起所有 AI 调用
 * - 设计思路：
 *   1. 构造函数注入所有依赖（HttpClient/registry/defaultProvider + 派生的 executor 与三个客户端）
 *   2. `chat { }` 按 provider.defaultApiType 路由到 Completions/Anthropic/Responses 三种协议
 *   3. `chat { }` 内可通过 `provider("xxx")` / `model("xxx")` 运行时切换供应商与模型
 *   4. `chatWithTools { }` 在 chat 基础上增加多轮工具调用循环
 *   5. 映射逻辑委托给 [Mappers.kt] 的扩展函数保持类体简洁
 * - 模块拆分：MCP（chatWithMcp）/ACP（chatWithAcpAgent、serveAsAcpAgent）/A2A（chatWithA2aAgent、
 *   serveAsA2aAgent）协议集成已拆分至独立模块（rac-mcp/rac-acp/rac-a2a），通过 [Llm] 扩展函数提供
 * - 实现方式：主构造接收 HttpClient/registry/defaultProvider，executor 与三个客户端以默认参数从 HttpClient 派生
 * - 边缘情况：apiKey 为 null（如 Ollama）时不添加 Authorization 头；tools 为空时不发送 tools 字段；
 *   ANTHROPIC 供应商调用 chatStream 会抛 [RACException] 引导使用 anthropicStream
 *
 * @property httpClient 底层 Ktor HttpClient，由 `llm { }` 通过 HttpClientFactory 创建，可注入用于测试
 * @property registry 供应商注册表，按名存取 [ModelProvider]
 * @property defaultProvider 默认供应商，`chat { }` / `respond { }` 未指定 provider 时使用
 * @property retryPolicy 重试策略，定义网络瞬时错误的自动重试行为
 * @property executor 带重试能力的请求执行器，从 httpClient 与 retryPolicy 派生
 * @property completionsClient Completions API 客户端，从 executor 派生
 * @property anthropicClient Anthropic API 客户端，从 executor 派生
 * @property responsesClient Responses API 客户端，从 executor 派生
 */
class Llm(
    val httpClient: HttpClient,
    val registry: ProviderRegistry,
    val defaultProvider: ModelProvider,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val executor: RetryExecutor = RetryExecutor(RequestExecutor(httpClient), retryPolicy),
    val completionsClient: CompletionsClient = CompletionsClient(executor),
    val anthropicClient: AnthropicClient = AnthropicClient(executor),
    val responsesClient: ResponsesClient = ResponsesClient(executor),
) {
    /**
     * 按名称获取已注册的供应商。
     *
     * @param name 供应商名称
     * @return 对应的 [ModelProvider]
     * @throws com.resderx.rac.exceptions.RACException 当 name 未注册时
     */
    fun provider(name: String): ModelProvider = registry.get(name)

    /**
     * 解析 chat { } / respond { } 内的 provider 切换——builder.providerName 非 null 时从注册表查找，
     * 否则用默认供应商。
     *
     * @param builder chat 请求构建器（含 providerName）
     * @return 最终使用的供应商
     */
    private fun resolveProvider(builder: ChatRequestBuilder): ModelProvider {
        return builder.providerName?.let { registry.get(it) } ?: defaultProvider
    }

    /**
     * 非流式 Chat 调用，按 provider 的 defaultApiType 路由到对应协议客户端。
     *
     * - 作用：统一非流式对话调用入口，按 [ModelProvider.defaultApiType] 分发到
     *   Completions/Anthropic/Responses 三种协议客户端
     * - 支持 `chat { provider("openai"); model("gpt-4o"); ... }` 运行时切换
     *
     * @param block 在 [ChatRequestBuilder] 作用域内构建消息与参数
     * @return 统一的 [AIMessage]
     */
    suspend fun chat(block: ChatRequestBuilder.() -> Unit): AIMessage {
        val builder = ChatRequestBuilder().apply(block)
        return chatWithBuilder(builder)
    }

    /**
     * 使用已构建好的 [ChatRequestBuilder] 执行一次非流式 Chat 调用。
     *
     * - 作用：将 [chat] 的核心逻辑提取为可复用的方法，供 [chatWithTools] 多轮循环与 [Agent] 自行控制循环复用
     * - 可见性：`internal`——同模块内（如 [com.resderx.rac.agent.Agent]）可访问，支持 Agent 自行管理多轮循环
     *   并在每轮把中间消息同步到 Session；外部模块不可直接调用
     * - 按 [resolveProvider] 解析最终使用的 provider，按 [ModelProvider.defaultApiType] 路由
     *
     * @param builder 已填充消息与参数的构建器
     * @return 统一的 [AIMessage]
     */
    internal suspend fun chatWithBuilder(builder: ChatRequestBuilder): AIMessage {
        val provider = resolveProvider(builder)
        val headers = buildHeaders(provider)
        return when (provider.defaultApiType) {
            ApiType.COMPLETIONS -> {
                val request = builder.build(provider)
                val url = "${provider.baseUrl}/chat/completions"
                completionsClient.complete(url, headers, request).toAIMessage()
            }
            ApiType.ANTHROPIC -> {
                val request = builder.buildAnthropic(provider)
                val url = "${provider.baseUrl}/messages"
                anthropicClient.complete(url, headers, request).toAIMessage()
            }
            ApiType.RESPONSES -> {
                val request = builder.buildResponses(provider)
                val url = "${provider.baseUrl}/responses"
                responsesClient.respond(url, headers, request).toAIMessage()
            }
        }
    }

    /**
     * 使用已构建好的 [ChatRequestBuilder] 执行一次流式 Chat 调用——返回统一语义化事件流。
     *
     * - 作用：将 [chatStream] / [anthropicStream] 的核心逻辑提取为可复用的方法，
     *   供 [com.resderx.rac.agent.Agent.runStream] 在多轮循环中复用——每轮调用本方法获取该轮的流式事件
     * - 可见性：`internal`——同模块内（Agent）可访问，支持 Agent 自行管理多轮流式循环；
     *   外部模块不可直接调用
     * - 设计：按 [resolveProvider] 解析 provider，按 [ModelProvider.defaultApiType] 路由到
     *   Completions 或 Anthropic 的流式 API，经 [toCompletionsStreamEvents]/[toAnthropicStreamEvents] 聚合为统一事件流
     * - 边缘：Responses API 暂不支持 Agent 流式（需 RespondRequestBuilder，与 ChatRequestBuilder 不兼容），
     *   遇到时抛 [RACException]
     *
     * @param builder 已填充消息与参数的构建器（调用方可跨轮复用，追加消息后再次调用本方法）
     * @return 统一语义化事件流（TextDelta/ReasoningDelta/ToolCallDelta/Done）
     */
    internal fun chatStreamWithBuilder(builder: ChatRequestBuilder): Flow<StreamEvent> {
        val provider = resolveProvider(builder)
        val headers = buildHeaders(provider)
        return when (provider.defaultApiType) {
            ApiType.COMPLETIONS -> {
                val request = builder.build(provider)
                val url = "${provider.baseUrl}/chat/completions"
                completionsClient.stream(url, headers, request).toCompletionsStreamEvents()
            }
            ApiType.ANTHROPIC -> {
                val request = builder.buildAnthropic(provider)
                val url = "${provider.baseUrl}/messages"
                anthropicClient.stream(url, headers, request).toAnthropicStreamEvents()
            }
            ApiType.RESPONSES -> {
                throw RACException("Agent streaming does not support Responses API; use Completions or Anthropic provider")
            }
        }
    }

    /**
     * 带工具调用循环的非流式 Chat 调用。
     *
     * - 作用：在普通 [chat] 基础上自动处理工具调用闭环——模型返回 toolCalls 时，调用调用方提供的
     *   [toolExecutor] 执行每个工具，将结果作为 ToolMessage 回写对话历史，再次调用模型，
     *   循环直至模型不再请求工具调用或达到 [maxRounds] 上限
     *
     * @param maxRounds 最大工具调用循环轮数（不含首轮模型调用），默认 10；达到上限时返回最后一次响应
     * @param toolExecutor 工具执行器，接收 [ToolCall] 返回执行结果字符串
     * @param block 在 [ChatRequestBuilder] 作用域内构建初始消息与工具定义
     * @return 最终的 [AIMessage]（无工具调用或达到 maxRounds 上限）
     * @throws IllegalArgumentException 当 maxRounds <= 0
     */
    suspend fun chatWithTools(
        maxRounds: Int = 10,
        toolExecutor: suspend (ToolCall) -> String,
        block: ChatRequestBuilder.() -> Unit,
    ): AIMessage {
        require(maxRounds > 0) { "maxRounds must be positive, but was $maxRounds" }
        val builder = ChatRequestBuilder().apply(block)
        var response = chatWithBuilder(builder)
        var round = 0
        while (response.toolCalls.isNotEmpty() && round < maxRounds) {
            builder.appendAssistantWithTools(
                content = response.content,
                toolCalls = response.toolCalls,
            )
            for (toolCall in response.toolCalls) {
                val result = toolExecutor(toolCall)
                builder.appendToolResult(toolCallId = toolCall.id, content = result)
            }
            response = chatWithBuilder(builder)
            round++
        }
        return response
    }

    /**
     * 流式 Chat 调用（仅 Completions API）。
     *
     * - 作用：以 SSE 流式方式调用 Completions API，返回统一语义化事件流
     * - 返回：[StreamEvent] 冷流，包含 TextDelta/ReasoningDelta/ToolCallDelta/Done 四种事件，
     *   每个事件携带增量(delta)与累积值(accumulated)，结束时 Done 含完整 AIMessage
     * - 设计：内部调用 [CompletionsClient.stream] 获取原始 chunk，经 [toCompletionsStreamEvents] 聚合为统一事件
     *
     * @param block 在 [ChatRequestBuilder] 作用域内构建消息与参数
     * @return 统一语义化事件流
     * @throws RACException 当供应商的 defaultApiType 不是 COMPLETIONS 时
     */
    fun chatStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent> {
        val builder = ChatRequestBuilder().apply(block)
        val provider = resolveProvider(builder)
        if (provider.defaultApiType != ApiType.COMPLETIONS) {
            throw RACException("chatStream requires a Completions API provider; use anthropicStream for Anthropic")
        }
        val request = builder.build(provider)
        val url = "${provider.baseUrl}/chat/completions"
        val headers = buildHeaders(provider)
        return completionsClient.stream(url, headers, request).toCompletionsStreamEvents()
    }

    /**
     * 流式 Chat 调用（仅 Anthropic API）。
     *
     * - 作用：以 SSE 流式方式调用 Anthropic Messages API，返回统一语义化事件流
     * - 返回：[StreamEvent] 冷流，格式与 chatStream 完全一致
     * - 设计：内部调用 [AnthropicClient.stream] 获取原始事件，经 [toAnthropicStreamEvents] 聚合为统一事件
     *
     * @param block 在 [ChatRequestBuilder] 作用域内构建消息与参数
     * @return 统一语义化事件流
     * @throws RACException 当供应商的 defaultApiType 不是 ANTHROPIC 时
     */
    fun anthropicStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent> {
        val builder = ChatRequestBuilder().apply(block)
        val provider = resolveProvider(builder)
        if (provider.defaultApiType != ApiType.ANTHROPIC) {
            throw RACException("anthropicStream requires an Anthropic API provider; use chatStream for Completions")
        }
        val request = builder.buildAnthropic(provider)
        val url = "${provider.baseUrl}/messages"
        val headers = buildHeaders(provider)
        return anthropicClient.stream(url, headers, request).toAnthropicStreamEvents()
    }

    /**
     * 非流式 Respond 调用（Responses API）。
     *
     * @param block 在 [RespondRequestBuilder] 作用域内构建输入与参数
     * @return 统一的 [AIMessage]
     */
    suspend fun respond(block: RespondRequestBuilder.() -> Unit): AIMessage {
        val builder = RespondRequestBuilder().apply(block)
        val provider = defaultProvider
        val request = builder.build(provider)
        val url = "${provider.baseUrl}/responses"
        val headers = buildHeaders(provider)
        val response = responsesClient.respond(url, headers, request)
        return response.toAIMessage()
    }

    /**
     * 流式 Respond 调用（Responses API）。
     *
     * - 作用：以 SSE 流式方式调用 Responses API，返回统一语义化事件流
     * - 返回：[StreamEvent] 冷流，格式与 chatStream 完全一致
     * - 设计：内部调用 [ResponsesClient.stream] 获取原始事件，经 [toResponsesStreamEvents] 聚合为统一事件
     *
     * @param block 在 [RespondRequestBuilder] 作用域内构建输入与参数
     * @return 统一语义化事件流
     */
    fun respondStream(block: RespondRequestBuilder.() -> Unit): Flow<StreamEvent> {
        val builder = RespondRequestBuilder().apply(block)
        val provider = defaultProvider
        val request = builder.build(provider)
        val url = "${provider.baseUrl}/responses"
        val headers = buildHeaders(provider)
        return responsesClient.stream(url, headers, request).toResponsesStreamEvents()
    }

    /**
     * 构建请求头：供应商默认头 + 鉴权头（按 ApiType 选择 Bearer/x-api-key）+ Content-Type。
     *
     * @param provider 供应商
     * @return 合并后的请求头 Map
     */
    private fun buildHeaders(provider: ModelProvider): Map<String, String> {
        val headers = provider.defaultHeaders.toMutableMap()
        val key = provider.apiKey
        if (key != null) {
            if (provider.defaultApiType == ApiType.ANTHROPIC) {
                headers["x-api-key"] = key
            } else {
                headers["Authorization"] = "Bearer $key"
            }
        }
        headers["Content-Type"] = "application/json"
        return headers
    }
}
