package com.resderx.rac.dsl

import com.resderx.rac.api.anthropic.AnthropicRequest
import com.resderx.rac.api.completions.CompletionsRequest
import com.resderx.rac.api.responses.ResponsesRequest
import com.resderx.rac.messages.AssistantMessage
import com.resderx.rac.messages.Message
import com.resderx.rac.messages.SystemMessage
import com.resderx.rac.messages.TextContent
import com.resderx.rac.messages.ToolCall
import com.resderx.rac.messages.ToolDefinition
import com.resderx.rac.messages.ToolMessage
import com.resderx.rac.messages.UserMessage
import com.resderx.rac.providers.ModelProvider

/**
 * Chat 请求的 DSL 构建器，以声明式风格构建对话消息列表与生成参数。
 *
 * - 作用：在 chat { } / chatStream { } 块中逐步构建消息列表、工具定义与采样参数，
 *   最终产出不可变的 CompletionsRequest
 * - 必要性：提供类型安全的 DSL API 替代直接构造 CompletionsRequest，支持条件分支与循环添加消息
 * - 设计思路：内部可变 List<Message> 收集消息，var 字段承载可选参数；build() 时产出不可变请求；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 实现方式：类持有可变状态，每个 DSL 方法返回 Unit（Kotlin DSL 惯例），build() 收集为不可变 CompletionsRequest
 * - 可能的问题：构建器实例非线程安全，多线程并发使用同一实例需调用方同步
 * - 边缘情况：未设置 model 时 build() 回退到 provider.defaultModel；未调用任何 user/assistant 方法时
 *   messages 为空列表（由服务端报错）；tools 为空列表时 build() 产出 null（不发送 tools 字段）
 * - 优点：DSL 风格比构造函数更清晰，支持在 lambda 内条件分支；@RacDslMarker 保证作用域清洁
 * - 数据结构：可变 List<Message> + 可变 List<ToolDefinition> + 扁平可变字段
 * - 时间复杂度：build() 为 O(n+m)，n 为消息数，m 为工具数
 * - 空间复杂度：O(n+m)，n 为消息数，m 为工具数
 *
 * @property temperature 采样温度，null 表示沿用服务端默认
 * @property topP nucleus sampling 参数，null 表示沿用服务端默认
 * @property maxTokens 最大生成 token 数，null 表示沿用服务端默认
 * @property model 模型名，覆盖 provider 默认模型，null 表示沿用
 * @property reasoningEffort 推理强度（如 "low"/"medium"/"high"），仅推理模型支持，null 表示不设置
 */
@RacDslMarker
class ChatRequestBuilder {
    private val _messages: MutableList<Message> = mutableListOf()
    private var _tools: MutableList<ToolDefinition>? = null

    /** 采样温度，null 表示沿用服务端默认。 */
    var temperature: Double? = null

    /** nucleus sampling 参数，null 表示沿用服务端默认。 */
    var topP: Double? = null

    /** 最大生成 token 数，null 表示沿用服务端默认。 */
    var maxTokens: Long? = null

    /** 模型名，覆盖 provider 默认模型，null 表示沿用。 */
    var model: String? = null

    /** 推理强度（如 "low"/"medium"/"high"），仅推理模型支持，null 表示不设置。 */
    var reasoningEffort: String? = null

    /**
     * 添加一条系统消息。
     *
     * @param text 系统指令文本
     */
    fun system(text: String) {
        _messages.add(SystemMessage(text))
    }

    /**
     * 添加一条纯文本用户消息。
     *
     * @param text 用户输入文本
     */
    fun user(text: String) {
        _messages.add(UserMessage(text))
    }

    /**
     * 添加一条用户消息，内容由 UserContentBuilder 构建。
     *
     * @param block 在 UserContentBuilder 作用域内设置内容
     */
    fun user(block: UserContentBuilder.() -> Unit) {
        val builder = UserContentBuilder().apply(block)
        _messages.add(UserMessage(builder.text))
    }

    /**
     * 添加一条助手消息。
     *
     * @param text 助手返回的正文文本
     */
    fun assistant(text: String) {
        _messages.add(AssistantMessage(content = text))
    }

    /**
     * 添加一条工具回执消息。
     *
     * @param id 对应的 ToolCall.id
     * @param content 工具执行结果文本（通常为 JSON 字符串）
     */
    fun tool(id: String, content: String) {
        _messages.add(ToolMessage(toolCallId = id, content = content))
    }

    /**
     * 声明可用工具集，内容由 ToolsBuilder 构建。
     *
     * @param block 在 ToolsBuilder 作用域内添加工具定义
     */
    fun tools(block: ToolsBuilder.() -> Unit) {
        val builder = ToolsBuilder().apply(block)
        val built = builder.build()
        if (_tools == null) {
            _tools = mutableListOf()
        }
        _tools!!.addAll(built)
    }

    /**
     * 追加一组工具定义（用于 MCP 工具自动注入等多轮工具调用场景）。
     *
     * - 作用：将外部已构建好的 [ToolDefinition] 列表批量合并到当前构建器的工具集中，
     *   供 [RAC.chatWithMcp] 扩展函数在调用 MCP 服务器 `listTools()` 后自动注入
     * - 必要性：MCP 工具由服务器端动态发现，无法在 DSL 块内静态声明；需提供一个程序化注入入口
     * - 设计思路：与 [tools] 方法共享同一个 `_tools` 可变列表；首次调用时懒初始化列表
     * - 实现方式：public fun，供 rac-mcp 模块的 chatWithMcp 扩展函数调用
     * - 边缘情况：传入空列表时不修改 `_tools`（保持 null 或原状），避免产生空工具集导致序列化为 `[]`
     * - 优点：与 DSL 风格的 [tools] 方法互补，支持运行时动态工具发现
     *
     * @param tools 要追加的工具定义列表
     */
    fun addTools(tools: List<ToolDefinition>) {
        if (tools.isEmpty()) return
        if (_tools == null) {
            _tools = mutableListOf()
        }
        _tools!!.addAll(tools)
    }

    /**
     * 追加一条带工具调用的助手消息（用于多轮工具调用循环）。
     *
     * - 作用：在模型返回工具调用请求后，将其作为 AssistantMessage 加入对话历史，
     *   以保持上下文连贯性（OpenAI/Anthropic 协议要求 tool_calls 后必须紧跟 tool 角色回执）
     * - 必要性：多轮工具调用循环中，每次模型返回 toolCalls 后需将其回写为 assistant 消息，
     *   再追加 tool 回执，模型才能正确理解对话上下文
     * - 设计思路：content 为 null 时表示纯工具调用（部分供应商允许 content 为空）；
     *   reasoningContent 当前不传递（多轮循环中推理过程对下一轮无意义且增加 token 消耗）
     * - 实现方式：internal fun，供 [RAC.chatWithTools] 内部调用
     * - 边缘情况：content 为空字符串时转为 null（避免发送空 content 字段）；toolCalls 为空时仍追加消息
     *
     * @param content 模型返回的正文文本，纯工具调用时可为 null
     * @param toolCalls 模型请求的工具调用列表
     */
    internal fun appendAssistantWithTools(content: String?, toolCalls: List<ToolCall>) {
        val normalizedContent = content?.takeIf { it.isNotEmpty() }
        _messages.add(AssistantMessage(content = normalizedContent, toolCalls = toolCalls))
    }

    /**
     * 追加一条工具回执消息（用于多轮工具调用循环）。
     *
     * - 作用：将工具执行结果作为 ToolMessage 加入对话历史，供模型在下一轮推理时参考
     * - 必要性：工具调用闭环的必要环节，每个 ToolCall 需对应一条 ToolMessage 回执
     * - 设计思路：直接构造 ToolMessage 并追加到 _messages，与 DSL 的 [tool] 方法逻辑一致，
     *   但以 internal 方法暴露供 RAC 内部循环调用
     * - 实现方式：internal fun，供 [RAC.chatWithTools] 内部调用
     * - 边缘情况：content 为空字符串时仍追加（工具可能返回空结果）
     *
     * @param toolCallId 对应的 ToolCall.id
     * @param content 工具执行结果文本（通常为 JSON 字符串）
     */
    internal fun appendToolResult(toolCallId: String, content: String) {
        _messages.add(ToolMessage(toolCallId = toolCallId, content = content))
    }

    /**
     * 构建不可变的 CompletionsRequest。
     *
     * @param provider 提供默认模型名的供应商
     * @return 构建完成的 CompletionsRequest（stream=false）
     */
    internal fun build(provider: ModelProvider): CompletionsRequest = CompletionsRequest(
        model = model ?: provider.defaultModel,
        messages = _messages.toList(),
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        stream = false,
        tools = _tools?.takeIf { it.isNotEmpty() },
        reasoningEffort = reasoningEffort,
    )

    /**
     * 构建不可变的 AnthropicRequest（Anthropic Messages API）。
     *
     * - 作用：将 ChatRequestBuilder 收集的消息/参数映射为 Anthropic 协议请求体，
     *   供 RAC.chat { } 在 ANTHROPIC 分支调用 anthropicClient.complete()
     * - 必要性：Anthropic 协议与 OpenAI Completions 不同——system 消息需从 messages 列表
     *   抽取为顶层 `system` 字段，max_tokens 为必填字段，无 reasoning_effort 字段
     * - 设计思路：用 filterIsInstance<SystemMessage> 抽取系统消息并以换行拼接为 system 字符串；
     *   非 system 消息保留原顺序作为 messages；maxTokens 为 null 时默认 4096（Anthropic 必填）；
     *   temperature/topP/tools 直接透传；stream 固定 false（流式由 anthropicStream 走 stream=true）
     * - 实现方式：纯函数，构造 AnthropicRequest data class 实例
     * - 边缘情况：无 SystemMessage 时 system 字段为 null；maxTokens 为 null 时默认 4096；
     *   _tools 为 null 或空时 tools 字段为 null（不发送 tools 字段）；reasoningEffort 被忽略
     *   （Anthropic 协议无此字段）；UserMessage 的多模态内容原样透传（Anthropic 支持 image content block）
     * - 优点：与 build() 对称，集中处理 Anthropic 协议差异，RAC 类保持简洁
     * - 算法/数据结构：线性遍历 _messages 两次（filterIsInstance + filter）
     * - 时间复杂度：O(n)，n 为消息数
     * - 空间复杂度：O(n+m)，n 为消息数，m 为工具数
     *
     * @param provider 提供默认模型名的供应商
     * @return 构建完成的 AnthropicRequest（stream=false）
     */
    internal fun buildAnthropic(provider: ModelProvider): AnthropicRequest {
        val systemText = _messages
            .filterIsInstance<SystemMessage>()
            .joinToString("\n") { it.content }
            .takeIf { it.isNotEmpty() }
        val nonSystemMessages = _messages.filter { it !is SystemMessage }
        return AnthropicRequest(
            model = model ?: provider.defaultModel,
            messages = nonSystemMessages,
            system = systemText,
            maxTokens = maxTokens ?: 4096L,
            temperature = temperature,
            topP = topP,
            tools = _tools?.takeIf { it.isNotEmpty() },
            stream = false,
        )
    }

    /**
     * 构建不可变的 ResponsesRequest（OpenAI Responses API）。
     *
     * - 作用：将 ChatRequestBuilder 收集的消息/参数映射为 Responses API 请求体，
     *   供 RAC.chat { } 在 RESPONSES 分支调用 responsesClient.respond()
     * - 必要性：Responses API 的 input 为字符串而非消息列表，需将 ChatRequestBuilder 的
     *   消息列表压缩为单个 input 字符串；max_tokens 字段名变为 max_output_tokens
     * - 设计思路：优先取最后一条 UserMessage 的文本内容作为 input（Responses API 的典型用法）；
     *   若无 UserMessage 则将所有消息按角色拼接为 input 字符串作为容错；maxTokens 映射到 maxOutputTokens；
     *   temperature/tools 直接透传；stream 固定 false（流式由 respondStream 走 stream=true）
     * - 实现方式：纯函数，构造 ResponsesRequest data class 实例
     * - 边缘情况：无任何消息时 input 为空字符串；UserMessage 的多模态内容仅取 TextContent 拼接
     *   （图片/音频在 Responses API 的字符串 input 模式下无法表达，会被忽略）；
     *   SystemMessage 在 input 兜底拼接时作为文本片段包含；reasoningEffort 被忽略（Responses API
     *   单独走 reasoning 字段，不在本映射处理）；maxTokens 为 null 时 maxOutputTokens 为 null
     * - 优点：与 build()/buildAnthropic() 对称，集中处理 Responses API 字段映射
     * - 算法/数据结构：线性遍历 _messages 提取 UserMessage 文本
     * - 时间复杂度：O(n)，n 为消息数
     * - 空间复杂度：O(n+m)，n 为消息数，m 为工具数
     *
     * @param provider 提供默认模型名的供应商
     * @return 构建完成的 ResponsesRequest（stream=false）
     */
    internal fun buildResponses(provider: ModelProvider): ResponsesRequest {
        val lastUserText = _messages
            .filterIsInstance<UserMessage>()
            .lastOrNull()
            ?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("") { it.text }
        val input = lastUserText ?: _messages.joinToString("\n") { msg ->
            when (msg) {
                is SystemMessage -> msg.content
                is UserMessage -> msg.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                is AssistantMessage -> msg.content ?: ""
                is ToolMessage -> msg.content
            }
        }
        return ResponsesRequest(
            model = model ?: provider.defaultModel,
            input = input,
            temperature = temperature,
            maxOutputTokens = maxTokens,
            stream = false,
            tools = _tools?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * 用户消息内容构建器（简化版，当前仅支持纯文本）。
 *
 * - 作用：在 user { } 块内以 DSL 风格设置用户消息内容
 * - 必要性：为未来扩展多模态内容预留入口，当前保持最简实现
 * - 设计思路：标注 @RacDslMarker 防止作用域污染；当前仅暴露 text 属性
 * - 实现方式：可变 var text 属性，由 ChatRequestBuilder.user(block) 读取后构造 UserMessage
 * - 可能的问题：当前不支持图片/音频，多模态场景需后续扩展
 * - 边缘情况：text 未设置时为空字符串
 * - 优点：接口简单，扩展时不破坏现有调用方
 * - 数据结构：单一字符串字段
 * - 时间复杂度：O(1)
 * - 空间复杂度：O(text 长度)
 */
@RacDslMarker
class UserContentBuilder {
    /** 用户消息文本内容，默认空字符串。 */
    var text: String = ""
}

/**
 * 工具定义列表构建器。
 *
 * - 作用：在 tools { } 块内以 DSL 风格逐个添加工具定义
 * - 必要性：提供声明式 API 声明可用工具集，比直接构造 List<ToolDefinition> 更清晰
 * - 设计思路：标注 @RacDslMarker 防止作用域污染；内部可变 List，build() 产出不可变列表
 * - 实现方式：类持有 MutableList<ToolDefinition>，tool() 方法追加，build() 转为不可变 List
 * - 可能的问题：构建器实例非线程安全
 * - 边缘情况：未调用 tool() 时 build() 返回空列表
 * - 优点：DSL 风格简洁，参数 schema 以字符串传入避免与 kotlinx-schema 类型耦合
 * - 数据结构：MutableList<ToolDefinition>
 * - 时间复杂度：tool() O(1) 均摊；build() O(m)，m 为工具数
 * - 空间复杂度：O(m)，m 为工具数
 */
@RacDslMarker
class ToolsBuilder {
    private val _tools: MutableList<ToolDefinition> = mutableListOf()

    /**
     * 添加一个工具定义。
     *
     * @param name 工具（函数）名称，全局唯一
     * @param description 工具功能描述，供模型判断何时调用
     * @param parameters 工具参数的 JSON Schema 字符串，默认 "{}" 表示无参数
     */
    fun tool(name: String, description: String, parameters: String = "{}") {
        _tools.add(ToolDefinition(name = name, description = description, parameters = parameters))
    }

    /**
     * 构建不可变的工具定义列表。
     *
     * @return 工具定义列表（不可变）
     */
    internal fun build(): List<ToolDefinition> = _tools.toList()
}
