package com.resderx.rac.dsl

import com.resderx.rac.api.anthropic.AnthropicClient
import com.resderx.rac.api.anthropic.AnthropicStreamEvent
import com.resderx.rac.api.completions.CompletionsClient
import com.resderx.rac.api.completions.CompletionsStreamChunk
import com.resderx.rac.api.responses.ResponsesClient
import com.resderx.rac.api.responses.ResponsesStreamEvent
import com.resderx.rac.exceptions.RACException
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.ToolCall
import com.resderx.rac.network.RequestExecutor
import com.resderx.rac.network.RetryExecutor
import com.resderx.rac.network.RetryPolicy
import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderRegistry
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * RAC（ResDerX AI Call）顶层入口类，持有所有运行时依赖并提供 chat/respond 系列调用方法。
 *
 * - 作用：作为库的主入口，聚合 HttpClient、三种 API 客户端、供应商注册表与默认供应商，
 *   暴露 chat { } / chatWithTools { } / chatStream { } / anthropicStream { } /
 *   respond { } / respondStream { } 六个核心方法
 * - 必要性：统一管理所有供应商与 API 客户端的生命周期，调用方通过 RAC 实例发起所有 AI 调用；
 *   多轮工具调用需访问内部 API 客户端与对话历史，封装在 RAC 内最合适
 * - 设计思路：构造函数注入所有依赖（HttpClient/registry/defaultProvider + 派生的 executor 与三个客户端），
 *   便于测试时替换 HttpClient（MockEngine）；chat { } 按 defaultProvider.defaultApiType 路由到
 *   Completions/Anthropic/Responses 三种协议，respond { } 显式走 Responses API，
 *   chatStream { } 仅支持 Completions（类型安全），anthropicStream { } 专用于 Anthropic 流式；
 *   chatWithTools { } 在 chat 基础上增加多轮工具调用循环；
 *   映射逻辑委托给 Mappers.kt 的扩展函数保持类体简洁
 * - 模块拆分：MCP（chatWithMcp）/ACP（chatWithAcpAgent、serveAsAcpAgent）/A2A（chatWithA2aAgent、
 *   serveAsA2aAgent）协议集成已拆分至独立模块（rac-mcp/rac-acp/rac-a2a），通过 RAC 扩展函数提供，
 *   避免核心模块承担过多协议耦合；core 仅保留通用 chat/respond 能力
 * - 实现方式：主构造接收 HttpClient/registry/defaultProvider，executor 与三个客户端以默认参数从 HttpClient 派生；
 *   六个调用方法内部构建请求、拼接 URL 与 headers、调用对应客户端、映射响应为 AIMessage 或返回 Flow
 * - 可能的问题：HttpClient 生命周期由 RAC 持有，调用方需在不再使用时调用 httpClient.close()；
 *   defaultProvider 未注册到 registry 时 provider(name) 会抛异常
 * - 边缘情况：apiKey 为 null（如 Ollama）时不添加 Authorization 头；tools 为空时不发送 tools 字段；
 *   choices 为空时 content 为空字符串；ANTHROPIC 供应商调用 chatStream 会抛 RACException 引导使用 anthropicStream；
 *   chatWithTools 达到 maxRounds 时返回 finishReason=TOOL_CALLS 的最后响应
 * - 优点：依赖注入便于测试；六种调用方法覆盖非流式/流式/工具循环 × Completions/Anthropic/Responses；
 *   headers 构建逻辑集中私有，按 ApiType 自动选择 Bearer/x-api-key 鉴权头
 * - 数据结构：持有不可变依赖引用，无额外数据结构
 * - 时间复杂度：chat/respond/anthropicStream 调用为 O(n)（n 为响应中 toolCalls/output/content 项数）；
 *   chatStream/respondStream/anthropicStream 返回冷流 O(1)；
 *   chatWithTools 为 O(R*(T+N))（R 为循环轮数，T 为每轮工具数，N 为响应解析）
 * - 空间复杂度：O(n)（n 为响应中 toolCalls/output/content 项数）；工具循环额外 O(M+R*(T+A))
 *
 * @property httpClient 底层 Ktor HttpClient，由 rac { } 通过 HttpClientFactory 创建，可注入用于测试
 * @property registry 供应商注册表，按名存取 ModelProvider
 * @property defaultProvider 默认供应商，chat { } / respond { } 使用此供应商的 baseUrl/apiKey/defaultModel
 * @property retryPolicy 重试策略，定义网络瞬时错误的自动重试行为
 * @property executor 带重试能力的请求执行器，从 httpClient 与 retryPolicy 派生
 * @property completionsClient Completions API 客户端，从 executor 派生
 * @property anthropicClient Anthropic API 客户端，从 executor 派生
 * @property responsesClient Responses API 客户端，从 executor 派生
 */
class RAC(
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
     * @return 对应的 ModelProvider
     * @throws com.resderx.rac.exceptions.RACException 当 name 未注册时
     */
    fun provider(name: String): ModelProvider = registry.get(name)

    /**
     * 非流式 Chat 调用，按默认供应商的 defaultApiType 路由到对应协议客户端。
     *
     * - 作用：统一非流式对话调用入口，按 [ModelProvider.defaultApiType] 分发到
     *   Completions/Anthropic/Responses 三种协议客户端，调用方无需关心协议差异
     * - 必要性：3 家协议供应商（OpenAI 兼容/Anthropic/OpenAI Responses）共享同一 DSL 入口，
     *   避免调用方按供应商类型选择方法
     * - 设计思路：when (defaultApiType) 分发——COMPLETIONS 走 completionsClient.complete()，
     *   ANTHROPIC 走 anthropicClient.complete()（URL 后缀 /messages，构建 AnthropicRequest），
     *   RESPONSES 走 responsesClient.respond()（URL 后缀 /responses，构建 ResponsesRequest）；
     *   三种响应均经 toAIMessage() 映射为统一 AIMessage
     * - 实现方式：suspend 函数，when 表达式按枚举分发，每个分支构建对应请求并调用对应客户端
     * - 边缘情况：apiKey 为 null 时不添加鉴权头（Ollama 等本地供应商）；COMPLETIONS 与 RESPONSES
     *   走 Bearer Token，ANTHROPIC 走 x-api-key（由 buildHeaders 处理）；URL 拼接依赖供应商 baseUrl
     *   不含末尾斜杠
     * - 优点：调用方 API 统一，新增协议类型只需扩展 when 分支与对应 buildXxx 方法
     * - 算法/数据结构：when 表达式 + 委托给 ChatRequestBuilder.build/buildAnthropic/buildResponses
     * - 时间复杂度：O(n)（n 为响应中 toolCalls/output/content 项数）
     * - 空间复杂度：O(n)（n 为响应中 toolCalls/output/content 项数）
     *
     * @param block 在 ChatRequestBuilder 作用域内构建消息与参数
     * @return 统一的 AIMessage
     */
    suspend fun chat(block: ChatRequestBuilder.() -> Unit): AIMessage {
        val builder = ChatRequestBuilder().apply(block)
        return chatWithBuilder(builder)
    }

    /**
     * 使用已构建好的 [ChatRequestBuilder] 执行一次非流式 Chat 调用。
     *
     * - 作用：将 [chat] 的核心逻辑提取为可复用的私有方法，供 [chatWithTools] 多轮循环复用——
     *   每轮循环都需基于同一 builder（含累积的对话历史）发起调用，避免重复构建
     * - 必要性：多轮工具调用循环中，每轮都需在 builder 追加 assistant+tool 消息后重新调用，
     *   若不提取公共方法会导致 [chat] 与 [chatWithTools] 逻辑重复
     * - 设计思路：按 [ModelProvider.defaultApiType] 路由到对应协议客户端，三种响应均经
     *   [toAIMessage] 映射为统一 [AIMessage]
     * - 实现方式：private suspend fun，按枚举分发到 completionsClient/anthropicClient/responsesClient
     *
     * @param builder 已填充消息与参数的构建器
     * @return 统一的 AIMessage
     */
    private suspend fun chatWithBuilder(builder: ChatRequestBuilder): AIMessage {
        val provider = defaultProvider
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
     * 带工具调用循环的非流式 Chat 调用。
     *
     * - 作用：在普通 [chat] 基础上自动处理工具调用闭环——模型返回 toolCalls 时，调用调用方提供的
     *   [toolExecutor] 执行每个工具，将结果作为 ToolMessage 回写对话历史，再次调用模型，
     *   循环直至模型不再请求工具调用或达到 [maxRounds] 上限
     * - 必要性：Agent 流程的核心能力。工具调用闭环涉及多轮对话管理（assistant+tool_calls →
     *   tool 回执 → 下一轮推理），手动实现繁琐且易错；封装为统一方法使调用方只需声明工具集与执行器
     * - 设计思路：
     *   1. 构建 ChatRequestBuilder（含调用方声明的 tools 与 messages）
     *   2. 调用 [chatWithBuilder] 获取首轮响应
     *   3. 若响应的 toolCalls 为空，直接返回（无工具调用或已结束）
     *   4. 否则，将 assistant 消息（含 toolCalls）追加到 builder 的对话历史
     *   5. 对每个 ToolCall 调用 [toolExecutor] 获取结果，追加为 ToolMessage
     *   6. 回到步骤 2，循环最多 [maxRounds] 次
     *   7. 达到上限时返回最后一次响应（此时模型可能仍在请求工具，调用方需判断 finishReason）
     * - 实现方式：suspend fun，while 循环 + 计数器；builder 在循环中被持续追加，
     *   保持单实例以累积完整对话历史
     * - 可能的问题：[toolExecutor] 抛出的异常会终止循环并向上传播；达到 maxRounds 时返回的
     *   AIMessage 的 finishReason 仍为 TOOL_CALLS，调用方需据此判断是否未完成
     * - 边缘情况：首轮响应无 toolCalls 时立即返回（不进入循环）；toolCalls 为空列表但
     *   finishReason 为 TOOL_CALLS 时仍直接返回（异常情况，尊重模型输出）；
     *   content 为空字符串的纯工具调用响应，追加 AssistantMessage 时 content 转为 null
     * - 优点：调用方无需关心多轮对话状态管理；toolExecutor 为 suspend lambda，支持异步工具；
     *   maxRounds 可配置，防止无限循环
     * - 算法/数据结构：while 循环 + 单一可变 builder（累积消息列表）
     * - 时间复杂度：O(R * (T + N))，R 为实际轮数（≤ maxRounds+1），T 为每轮工具数，N 为响应解析
     * - 空间复杂度：O(M + R * (T + A))，M 为初始消息数，A 为每轮 assistant+tool 消息增量
     *
     * @param maxRounds 最大工具调用循环轮数（不含首轮模型调用），默认 10；达到上限时返回最后一次响应
     * @param toolExecutor 工具执行器，接收 [ToolCall] 返回执行结果字符串；抛出的异常会终止循环
     * @param block 在 ChatRequestBuilder 作用域内构建初始消息与工具定义
     * @return 最终的 AIMessage（无工具调用或达到 maxRounds 上限）
     * @throws IllegalArgumentException 当 maxRounds <= 0
     * @throws RACException 当 toolExecutor 抛出异常或模型调用失败时向上传播
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
        // 循环条件：模型请求了工具调用 且 未达到轮数上限
        while (response.toolCalls.isNotEmpty() && round < maxRounds) {
            // 将模型本轮的 assistant 消息（含 toolCalls）回写到对话历史，保持上下文连贯
            builder.appendAssistantWithTools(
                content = response.content,
                toolCalls = response.toolCalls,
            )
            // 依次执行每个工具调用，将结果作为 ToolMessage 追加到历史
            for (toolCall in response.toolCalls) {
                val result = toolExecutor(toolCall)
                builder.appendToolResult(toolCallId = toolCall.id, content = result)
            }
            // 下一轮模型调用（模型此时能看到工具回执，决定继续调用工具或返回最终答案）
            response = chatWithBuilder(builder)
            round++
        }
        return response
    }

    /**
     * 流式 Chat 调用（仅 Completions API）。
     *
     * - 作用：为 Completions 协议供应商提供流式调用入口，返回 CompletionsStreamChunk 冷流；
     *   Anthropic 供应商需改用 [anthropicStream]，Responses 供应商需改用 [respondStream]
     * - 必要性：流式响应类型与协议强耦合（CompletionsStreamChunk/AnthropicStreamEvent/ResponsesStreamEvent
     *   互不兼容），无法用统一返回类型表达；为类型安全起见，chatStream 仅服务 Completions 协议
     * - 设计思路：调用前校验 defaultApiType == COMPLETIONS，非 Completions 时抛 RACException 引导调用方
     *   使用正确的流式方法；通过校验后构建 CompletionsRequest（stream=true 由 client 设置）并返回冷流
     * - 实现方式：fun 函数返回 Flow<CompletionsStreamChunk>，先校验后委托 completionsClient.stream()
     * - 边缘情况：默认供应商为 Anthropic 时抛 RACException("chatStream requires a Completions API
     *   provider; use anthropicStream for Anthropic")；为 Responses 时同样抛异常引导使用 respondStream
     * - 优点：编译期类型安全，调用方明确知道流元素类型；运行期快速失败并给出修复建议
     * - 算法/数据结构：校验 + 委托客户端 stream()
     * - 时间复杂度：返回冷流 O(1)
     * - 空间复杂度：O(1)
     *
     * @param block 在 ChatRequestBuilder 作用域内构建消息与参数
     * @return 冷流，每个元素为一个 CompletionsStreamChunk
     * @throws RACException 当默认供应商的 defaultApiType 不是 COMPLETIONS 时
     */
    fun chatStream(block: ChatRequestBuilder.() -> Unit): Flow<CompletionsStreamChunk> {
        val builder = ChatRequestBuilder().apply(block)
        val provider = defaultProvider
        if (provider.defaultApiType != ApiType.COMPLETIONS) {
            throw RACException("chatStream requires a Completions API provider; use anthropicStream for Anthropic")
        }
        val request = builder.build(provider)
        val url = "${provider.baseUrl}/chat/completions"
        val headers = buildHeaders(provider)
        return completionsClient.stream(url, headers, request)
    }

    /**
     * 流式 Chat 调用（仅 Anthropic API）。
     *
     * - 作用：为 Anthropic 协议供应商提供流式调用入口，返回 AnthropicStreamEvent 冷流；
     *   Completions 供应商需改用 [chatStream]，Responses 供应商需改用 [respondStream]
     * - 必要性：Anthropic 流式协议事件类型（message_start/content_block_delta 等）与 Completions/
     *   Responses 互不兼容，需独立流式入口保证类型安全
     * - 设计思路：调用前校验 defaultApiType == ANTHROPIC，非 Anthropic 时抛 RACException 引导调用方
     *   使用正确的流式方法；通过校验后构建 AnthropicRequest（stream=true 由 client 设置）并返回冷流
     * - 实现方式：fun 函数返回 Flow<AnthropicStreamEvent>，先校验后委托 anthropicClient.stream()
     * - 边缘情况：默认供应商为 Completions 时抛 RACException("anthropicStream requires an Anthropic
     *   API provider; use chatStream for Completions")；为 Responses 时同样抛异常引导使用 respondStream
     * - 优点：编译期类型安全，调用方明确知道流元素类型；运行期快速失败并给出修复建议
     * - 算法/数据结构：校验 + 委托客户端 stream()
     * - 时间复杂度：返回冷流 O(1)
     * - 空间复杂度：O(1)
     *
     * @param block 在 ChatRequestBuilder 作用域内构建消息与参数
     * @return 冷流，每个元素为一个 AnthropicStreamEvent
     * @throws RACException 当默认供应商的 defaultApiType 不是 ANTHROPIC 时
     */
    fun anthropicStream(block: ChatRequestBuilder.() -> Unit): Flow<AnthropicStreamEvent> {
        val builder = ChatRequestBuilder().apply(block)
        val provider = defaultProvider
        if (provider.defaultApiType != ApiType.ANTHROPIC) {
            throw RACException("anthropicStream requires an Anthropic API provider; use chatStream for Completions")
        }
        val request = builder.buildAnthropic(provider)
        val url = "${provider.baseUrl}/messages"
        val headers = buildHeaders(provider)
        return anthropicClient.stream(url, headers, request)
    }

    /**
     * 非流式 Respond 调用（Responses API）。
     *
     * - 使用默认供应商，构建 ResponsesRequest 并调用 responsesClient.respond()
     * - URL = `${provider.baseUrl}/responses`
     * - headers = provider.defaultHeaders + Authorization（apiKey 非 null 时）+ Content-Type
     * - 响应经 toAIMessage() 映射为统一 AIMessage
     *
     * @param block 在 RespondRequestBuilder 作用域内构建输入与参数
     * @return 统一的 AIMessage
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
     * - 与 respond { } 相同的请求构建逻辑，但调用 responsesClient.stream() 返回冷流
     * - 每个元素为 ResponsesStreamEvent，调用方按需消费
     *
     * @param block 在 RespondRequestBuilder 作用域内构建输入与参数
     * @return 冷流，每个元素为一个 ResponsesStreamEvent
     */
    fun respondStream(block: RespondRequestBuilder.() -> Unit): Flow<ResponsesStreamEvent> {
        val builder = RespondRequestBuilder().apply(block)
        val provider = defaultProvider
        val request = builder.build(provider)
        val url = "${provider.baseUrl}/responses"
        val headers = buildHeaders(provider)
        return responsesClient.stream(url, headers, request)
    }

    /**
     * 构建请求头：供应商默认头 + 鉴权头（按 ApiType 选择 Bearer/x-api-key）+ Content-Type。
     *
     * - 作用：集中拼接 HTTP 请求头，按供应商协议类型选择鉴权方式——
     *   ANTHROPIC 用 `x-api-key: <apiKey>`，Completions/Responses 用 `Authorization: Bearer <apiKey>`
     * - 必要性：Anthropic 与 OpenAI 鉴权头不同，需按 ApiType 分支处理；将头构建逻辑集中在 RAC 内
     *   避免供应商工厂硬编码鉴权头（apiKey 在配置时未知）
     * - 设计思路：先复制 defaultHeaders（含 anthropic-version 等协议必填头），再按 ApiType 注入鉴权头，
     *   最后统一追加 Content-Type；apiKey 为 null 时跳过鉴权头（支持 Ollama 等无鉴权供应商）
     * - 实现方式：private fun，返回不可变 Map（toMutableMap 操作副本，不影响 provider.defaultHeaders）
     * - 边缘情况：apiKey 为 null 时不添加任何鉴权头；defaultApiType 为 ANTHROPIC 但 apiKey 为 null 时
     *   不添加 x-api-key（生产调用会失败）；defaultHeaders 中的 Authorization/x-api-key 会被覆盖
     * - 优点：鉴权策略集中管理，新增协议类型只需扩展 if 分支
     * - 算法/数据结构：MutableMap 复制 + 条件写入
     * - 时间复杂度：O(h)，h 为 defaultHeaders 大小
     * - 空间复杂度：O(h)
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
