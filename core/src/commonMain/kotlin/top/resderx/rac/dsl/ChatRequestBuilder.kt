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

import com.resderx.rac.api.anthropic.AnthropicRequest
import com.resderx.rac.api.anthropic.AnthropicThinking
import com.resderx.rac.api.anthropic.toAnthropicTool
import com.resderx.rac.api.completions.CompletionsRequest
import com.resderx.rac.api.completions.toCompletionsTool
import com.resderx.rac.api.responses.ResponsesRequest
import com.resderx.rac.messages.AssistantMessage
import com.resderx.rac.messages.Message
import com.resderx.rac.messages.SystemMessage
import com.resderx.rac.messages.TextContent
import com.resderx.rac.messages.ToolCall
import com.resderx.rac.messages.ToolDefinition
import com.resderx.rac.messages.ToolMessage
import com.resderx.rac.messages.UserMessage
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider

/**
 * Chat 请求的 DSL 构建器，以声明式风格构建对话消息列表与生成参数。
 *
 * - 作用：在 `chat { }` / `chatStream { }` 块中逐步构建消息列表、工具定义与采样参数，
 *   支持运行时切换 provider 与 model，最终产出不可变的 CompletionsRequest/AnthropicRequest/ResponsesRequest
 * - 必要性：提供类型安全的 DSL API 替代直接构造请求对象，支持条件分支与循环添加消息
 * - 设计思路：
 *   1. 内部可变 List<Message> 收集消息，var 字段承载可选覆盖参数
 *   2. 新增 [providerName]/[modelName] 支持运行时切换——不指定时用 Llm 实例的默认 provider/model
 *   3. build() 时从 provider 的 models 注册表获取 [ModelConfig] 作为默认值，
 *      builder 内显式设置的字段（temperature/topP/maxTokens/reasoningEffort/stop/seed/enableThinking）
 *      覆盖 ModelConfig 默认值
 *   4. tools 块使用结构化 [ToolsBuilder]（param() API），无需手写 JSON Schema
 *   5. ModelConfig 的 systemPrompt 自动作为 SystemMessage 插入消息列表头部（调用方在 chat { } 内
 *      用 system() 设置时覆盖 ModelConfig 的 systemPrompt）
 *   6. enableThinking 在不同 API 上的差异化处理：
 *      - Completions：与 reasoningEffort 互斥（true 且未设 reasoningEffort 时自动设 "medium"；false 强制 null）
 *      - Anthropic：构造 thinking 对象 {type:"enabled", budget_tokens:maxTokens*4/5}（budget 必须 < maxTokens）
 *      - Responses：不支持，静默忽略
 * - 实现方式：类持有可变状态，每个 DSL 方法返回 Unit（Kotlin DSL 惯例），build() 收集为不可变请求
 * - 可能的问题：构建器实例非线程安全
 * - 边缘情况：未设置 model 时回退到 provider.defaultModel；未设置参数时回退到 ModelConfig 默认值，
 *   ModelConfig 也为 null 时回退到服务端默认；tools 为空列表时 build() 产出 null
 * - 优点：ModelConfig 默认值 + builder 覆盖的分层配置，避免每次调用重复设置参数
 *
 * @property temperature 采样温度，覆盖 ModelConfig 默认值，null 表示沿用 ModelConfig 或服务端默认
 * @property topP nucleus sampling 参数，覆盖 ModelConfig 默认值，null 表示沿用
 * @property maxTokens 最大生成 token 数，覆盖 ModelConfig 默认值，null 表示沿用
 * @property reasoningEffort 推理强度，覆盖 ModelConfig 默认值，null 表示沿用
 * @property stop 停止序列，覆盖 ModelConfig 默认值，null 表示沿用
 * @property seed 随机种子，覆盖 ModelConfig 默认值，null 表示沿用
 * @property enableThinking 思考开关，覆盖 ModelConfig 默认值，null 表示沿用
 */
@RacDslMarker
class ChatRequestBuilder {
    private val _messages: MutableList<Message> = mutableListOf()
    private var _tools: MutableList<ToolDefinition>? = null

    /** 运行时切换的目标 provider 名称，null 表示用 Llm 实例的默认 provider。 */
    internal var providerName: String? = null

    /** 运行时切换的目标 model 名称，null 表示用 provider 的默认 model。 */
    internal var modelName: String? = null

    /** 采样温度，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var temperature: Double? = null

    /** nucleus sampling 参数，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var topP: Double? = null

    /** 最大生成 token 数，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var maxTokens: Long? = null

    /** 推理强度（如 "low"/"medium"/"high"），覆盖 ModelConfig 默认值，null 表示沿用。 */
    var reasoningEffort: String? = null

    /** 停止序列，模型生成到任一字符串时立即停止，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var stop: List<String>? = null

    /** 随机种子，用于确定性输出，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var seed: Long? = null

    /**
     * 思考开关，覆盖 ModelConfig 默认值，null 表示沿用。
     *
     * - true：启用扩展思考
     *   - Completions：自动设 reasoningEffort="medium"（当未显式设置时）
     *   - Anthropic：构造 thinking={type:"enabled", budget_tokens:maxTokens*4/5}
     *   - Responses：不支持，静默忽略
     * - false：禁用思考
     *   - Completions：强制 reasoningEffort=null
     *   - Anthropic：不发送 thinking 字段
     *   - Responses：不支持，静默忽略
     */
    var enableThinking: Boolean? = null

    /**
     * 切换到指定 provider，后续调用使用该 provider 的连接信息与模型注册表。
     *
     * @param name provider 名称（需已在 `llm { providers { } }` 中注册）
     */
    fun provider(name: String) {
        this.providerName = name
    }

    /**
     * 切换到指定 model，从当前 provider 的 models 注册表查找配置。
     *
     * @param name model 名称（需在 `models { }` 中注册）
     */
    fun model(name: String) {
        this.modelName = name
    }

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
     * 声明可用工具集，内容由结构化 [ToolsBuilder] 构建。
     *
     * @param block 在 [ToolsBuilder] 作用域内添加工具定义
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
     *   供 [Llm.chatWithMcp] 扩展函数在调用 MCP 服务器 `listTools()` 后自动注入
     * - 必要性：MCP 工具由服务器端动态发现，无法在 DSL 块内静态声明；需提供一个程序化注入入口
     * - 设计思路：与 [tools] 方法共享同一个 `_tools` 可变列表；首次调用时懒初始化列表
     * - 实现方式：public fun，供 rac-mcp 模块的 chatWithMcp 扩展函数调用
     * - 边缘情况：传入空列表时不修改 `_tools`（保持 null 或原状），避免产生空工具集导致序列化为 `[]`
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
     * @param toolCallId 对应的 ToolCall.id
     * @param content 工具执行结果文本
     */
    internal fun appendToolResult(toolCallId: String, content: String) {
        _messages.add(ToolMessage(toolCallId = toolCallId, content = content))
    }

    /**
     * 批量注入消息列表（用于 Agent 从 Session 注入完整对话历史）。
     *
     * - 作用：将 [com.resderx.rac.agent.Session] 的 `messages` 快照一次性追加到当前构建器，
     *   供 [com.resderx.rac.agent.Agent.run] 在调用 `chatWithTools` 前注入上下文
     * - 必要性：Agent 需要把多轮对话历史完整传给模型，逐条 `system()`/`user()` 添加繁琐且易错
     * - 可见性：internal，仅供 agent 包内调用，不暴露给外部 DSL 用户
     *
     * @param messages 要追加的消息列表
     */
    internal fun addMessages(messages: List<Message>) {
        _messages.addAll(messages)
    }

    /**
     * 解析最终使用的 model 名称与 [ModelConfig] 默认值。
     *
     * - 作用：根据 [modelName] 与 provider 的 models 注册表，确定最终使用的 model 名与对应配置
     * - 实现方式：优先用 [modelName]，否则用 provider.defaultModel；从 provider.models 查找 ModelConfig
     * - 边缘情况：model 未在注册表中找到时 ModelConfig 为 null（全部参数沿用服务端默认）
     *
     * @param provider 目标供应商
     * @return (model 名, ModelConfig?) 二元组
     */
    private fun resolveModel(provider: ModelProvider): Pair<String, ModelConfig?> {
        val model = modelName ?: provider.defaultModel
        val config = provider.models[model]
        return model to config
    }

    /**
     * 收集最终消息列表，在头部插入 ModelConfig.systemPrompt（若调用方未显式 system()）。
     *
     * - 作用：当 model 注册时声明了 systemPrompt 且调用方未在 chat { } 内用 system() 覆盖时，
     *   自动将 systemPrompt 作为 SystemMessage 插入消息列表头部
     * - 设计思路：检查 _messages 首项是否为 SystemMessage，若否且 ModelConfig.systemPrompt 非空则插入
     *
     * @param config ModelConfig，可能含 systemPrompt
     * @return 最终消息列表（可能含头部 SystemMessage）
     */
    private fun resolveMessages(config: ModelConfig?): List<Message> {
        val hasSystem = _messages.firstOrNull() is SystemMessage
        if (!hasSystem && config?.systemPrompt != null) {
            return listOf(SystemMessage(config.systemPrompt)) + _messages
        }
        return _messages.toList()
    }

    /**
     * 解析 effective enableThinking——builder 显式值优先，否则取 ModelConfig 默认值。
     *
     * @param config 模型配置，可能含 enableThinking 默认值
     * @return effective enableThinking，null 表示未设置（沿用默认行为）
     */
    private fun resolveEnableThinking(config: ModelConfig?): Boolean? =
        enableThinking ?: config?.enableThinking

    /**
     * 解析 effective reasoningEffort，并应用 enableThinking 的互斥规则（Completions 专用）。
     *
     * - 互斥规则：
     *   - enableThinking=true 且 reasoningEffort 未显式设置 → 自动设为 "medium"
     *   - enableThinking=false → 强制 null（禁用推理）
     *   - enableThinking=null → 不干预 reasoningEffort
     * - 分层回退：builder.reasoningEffort → config.reasoningEffort → null
     *
     * @param config 模型配置，可能含 reasoningEffort 默认值
     * @return effective reasoningEffort（已应用 enableThinking 互斥规则）
     */
    private fun resolveReasoningEffortForCompletions(config: ModelConfig?): String? {
        val effThinking = resolveEnableThinking(config)
        val effReasoning = reasoningEffort ?: config?.reasoningEffort
        return when (effThinking) {
            true -> effReasoning ?: "medium"
            false -> null
            null -> effReasoning
        }
    }

    /**
     * 构建不可变的 CompletionsRequest。
     *
     * - 透传定制化参数：stop/seed 直接透传（分层回退）；enableThinking 通过 [resolveReasoningEffortForCompletions]
     *   间接体现为 reasoningEffort 的自动设置/强制清除
     *
     * @param provider 提供连接信息与 models 注册表的供应商
     * @return 构建完成的 CompletionsRequest（stream=false）
     */
    internal fun build(provider: ModelProvider): CompletionsRequest {
        val (model, config) = resolveModel(provider)
        return CompletionsRequest(
            model = model,
            messages = resolveMessages(config),
            temperature = temperature ?: config?.temperature,
            topP = topP ?: config?.topP,
            maxTokens = maxTokens ?: config?.maxTokens,
            stream = false,
            tools = _tools?.takeIf { it.isNotEmpty() }?.map { it.toCompletionsTool() },
            reasoningEffort = resolveReasoningEffortForCompletions(config),
            stop = stop ?: config?.stop,
            seed = seed ?: config?.seed,
        )
    }

    /**
     * 构建不可变的 AnthropicRequest（Anthropic Messages API）。
     *
     * - 透传定制化参数：
     *   - stopSequences：分层回退（builder.stop → config.stop）
     *   - thinking：根据 effective enableThinking + effective maxTokens 构造 [AnthropicThinking]；
     *     budget_tokens 取 maxTokens 的 4/5（确保 < maxTokens 满足 API 约束，同时留 20% 给正文输出）
     *   - seed：Anthropic 不支持，静默忽略
     *
     * @param provider 提供连接信息与 models 注册表的供应商
     * @return 构建完成的 AnthropicRequest（stream=false）
     */
    internal fun buildAnthropic(provider: ModelProvider): AnthropicRequest {
        val (model, config) = resolveModel(provider)
        val messages = resolveMessages(config)
        val systemText = messages
            .filterIsInstance<SystemMessage>()
            .joinToString("\n") { it.content }
            .takeIf { it.isNotEmpty() }
        val nonSystemMessages = messages.filter { it !is SystemMessage }

        // 解析 effective maxTokens（Anthropic 必填，有 4096 兜底）
        val effMaxTokens = maxTokens ?: config?.maxTokens ?: 4096L

        // 构造 thinking 对象——仅当 enableThinking=true 且 maxTokens > 1 时（budget 必须 < maxTokens）
        val effThinking = resolveEnableThinking(config)
        val thinking: AnthropicThinking? = if (effThinking == true && effMaxTokens > 1) {
            // budget 取 maxTokens 的 4/5，确保 1 <= budget < maxTokens
            val budget = (effMaxTokens * 4 / 5).coerceIn(1L, effMaxTokens - 1)
            AnthropicThinking(type = "enabled", budgetTokens = budget)
        } else {
            // effThinking == false 或 null：不发送 thinking 字段（false 时等价于禁用）
            null
        }

        return AnthropicRequest(
            model = model,
            messages = nonSystemMessages,
            system = systemText,
            maxTokens = effMaxTokens,
            temperature = temperature ?: config?.temperature,
            topP = topP ?: config?.topP,
            tools = _tools?.takeIf { it.isNotEmpty() }?.map { it.toAnthropicTool() },
            stream = false,
            stopSequences = stop ?: config?.stop,
            thinking = thinking,
        )
    }

    /**
     * 构建不可变的 ResponsesRequest（OpenAI Responses API）。
     *
     * - 透传定制化参数：
     *   - seed：分层回退（builder.seed → config.seed）
     *   - stop：Responses 不支持，静默忽略
     *   - enableThinking：Responses 不支持，静默忽略
     *
     * @param provider 提供连接信息与 models 注册表的供应商
     * @return 构建完成的 ResponsesRequest（stream=false）
     */
    internal fun buildResponses(provider: ModelProvider): ResponsesRequest {
        val (model, config) = resolveModel(provider)
        val messages = resolveMessages(config)
        val lastUserText = messages
            .filterIsInstance<UserMessage>()
            .lastOrNull()
            ?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("") { it.text }
        val input = lastUserText ?: messages.joinToString("\n") { msg ->
            when (msg) {
                is SystemMessage -> msg.content
                is UserMessage -> msg.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                is AssistantMessage -> msg.content ?: ""
                is ToolMessage -> msg.content
            }
        }
        return ResponsesRequest(
            model = model,
            input = input,
            temperature = temperature ?: config?.temperature,
            maxOutputTokens = maxTokens ?: config?.maxTokens,
            stream = false,
            tools = _tools?.takeIf { it.isNotEmpty() }?.map { it.toCompletionsTool() },
            seed = seed ?: config?.seed,
        )
    }
}

/**
 * 用户消息内容构建器（简化版，当前仅支持纯文本）。
 *
 * - 作用：在 `user { }` 块内以 DSL 风格设置用户消息内容
 * - 必要性：为未来扩展多模态内容预留入口，当前保持最简实现
 * - 设计思路：标注 @RacDslMarker 防止作用域污染；当前仅暴露 text 属性
 */
@RacDslMarker
class UserContentBuilder {
    /** 用户消息文本内容，默认空字符串。 */
    var text: String = ""
}
