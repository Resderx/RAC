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

package top.resderx.rac.agent

import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.AssistantMessage
import top.resderx.rac.messages.Message
import top.resderx.rac.messages.ToolMessage
import top.resderx.rac.messages.UserMessage

/**
 * 对话历史容器——仅存储消息列表，不存储 system 提示词、工具定义或任何其他状态。
 *
 * - 作用：作为 [Agent.run] 的对话上下文载体，累积 user/assistant/tool 三类消息，
 *   跨多轮调用保持完整对话历史（含中间工具调用过程），使模型能理解上下文指代（如"呢"、"那个"）
 * - 必要性：Agent 本身是无状态的，每次 [Agent.run] 调用后不保留上下文；
 *   需由调用方持有 [Session] 实例并反复传入，实现多轮对话记忆
 * - 设计思路：
 *   1. 职责单一：仅存储 `List<Message>`，不存储 system 提示词——system 是 [Agent] 的属性，
 *      在发送请求时由 [Agent.run] 动态拼接到请求头部，不存入 session。
 *      这样不同 agent（不同 system prompt）可以共用同一份 session，实现对话历史复用
 *   2. 完整记录：session 记录完整对话历史，包括中间的工具调用过程（AssistantMessage + ToolMessage），
 *      而非只记录最终结果——便于调试、持久化、跨 agent 复用
 *   3. 可变内部 + 不可变对外：内部用 `MutableList<Message>` 高效追加，
 *      对外通过 [messages] 返回快照（`toList()`），外部修改不影响内部状态
 *   4. [Agent] 自动管理内容：调用方一般只读 [messages] 或调 [clear]，
 *      追加消息（addUser/addAssistant/addAssistantMessage/addToolResult）主要由 [Agent.run] 自动完成
 * - 实现方式：类持有 `MutableList<Message>`，每个 add 方法委托到对应 `Message` 子类构造
 * - 边缘情况：
 *   - [addAssistant] 接收 [AIMessage]（Agent 的返回类型），映射为 [AssistantMessage]：
 *     content 为空字符串时转为 null（与 [AssistantMessage] 的可空语义一致，
 *     避免序列化出 `"content": ""` 干扰模型理解纯工具调用场景）
 *   - [messages] 每次返回新快照，频繁调用有 O(n) 开销，但保证不可变性
 *   - session 不含 SystemMessage——system 由 agent 在请求时动态拼接，不在 session 中持久化
 * - 优点：职责清晰，可独立序列化/持久化整个对话历史；不含 system，可跨 agent 复用
 * - 算法/数据结构：基于 `ArrayList` 的动态数组
 * - 时间复杂度：追加 O(1) 均摊；[messages] 快照 O(n)
 * - 空间复杂度：O(n)，n 为消息数量
 */
class Session {

    /** 内部可变消息列表，承载完整对话历史（user/assistant/tool，不含 system）。 */
    private val _messages: MutableList<Message> = mutableListOf()

    /**
     * 当前对话历史的只读快照。
     *
     * - 每次调用返回新列表（`toList()`），外部修改不影响内部状态
     * - 供 [Agent.run] 读取历史注入请求，或供调用方调试/持久化
     * - 不含 SystemMessage——system 由 agent 在请求时动态拼接
     */
    val messages: List<Message> get() = _messages.toList()

    /**
     * 对话历史是否为空。
     *
     * - 作用：供调用方判断 session 是否有内容；[Agent.run] 不再据此判断是否补 system
     *   （system 始终由 agent 在请求时动态拼接，与 session 是否为空无关）
     */
    fun isEmpty(): Boolean = _messages.isEmpty()

    /**
     * 追加一条纯文本用户消息。
     *
     * - 作用：记录用户输入，通常由 [Agent.run] 在调用前自动追加
     *
     * @param text 用户输入文本
     */
    fun addUser(text: String) {
        _messages.add(UserMessage(text))
    }

    /**
     * 追加一条助手消息——从 [AIMessage]（Agent 返回类型）映射。
     *
     * - 作用：记录模型最终响应（正文 + 工具调用 + 推理），由 [Agent.run] 在调用完成后自动追加
     * - 映射规则：
     *   - content 为空字符串 → 转为 null（[AssistantMessage.content] 为可空类型，
     *     空字符串转 null 保持与流式聚合器一致的可空语义，避免序列化 `"content": ""`）
     *   - toolCalls/reasoningContent 直接透传
     *
     * @param aiMessage 模型返回的统一响应
     */
    fun addAssistant(aiMessage: AIMessage) {
        val normalizedContent = aiMessage.content.takeIf { it.isNotEmpty() }
        _messages.add(
            AssistantMessage(
                content = normalizedContent,
                toolCalls = aiMessage.toolCalls,
                reasoningContent = aiMessage.reasoningContent,
            )
        )
    }

    /**
     * 追加一条助手消息——直接接收 [AssistantMessage]（用于记录中间工具调用消息）。
     *
     * - 作用：在 [Agent.run] 的多轮工具调用循环中，每一轮模型返回的带 toolCalls 的 AssistantMessage
     *   都需要记录到 session，保持完整对话历史——这样 session 可跨 agent 复用、可持久化、可调试
     * - 必要性：[addAssistant] 接收 [AIMessage]（最终响应类型），但中间轮的模型响应也是 AIMessage，
     *   语义上「中间轮」与「最终轮」不同——本方法直接接收 [AssistantMessage] 明确语义，
     *   供 [Agent.run] 在循环中记录中间消息
     *
     * @param assistantMessage 要追加的助手消息（含 content/toolCalls/reasoningContent）
     */
    fun addAssistantMessage(assistantMessage: AssistantMessage) {
        _messages.add(assistantMessage)
    }

    /**
     * 追加一条工具回执消息。
     *
     * - 作用：记录工具执行结果，供模型在下一轮推理时引用
     * - 必要性：工具调用闭环的必要环节；[Agent.run] 的多轮循环中自动追加
     *
     * @param toolCallId 对应的 [top.resderx.rac.messages.ToolCall.id]
     * @param content 工具执行结果文本（通常为 JSON 字符串）
     */
    fun addToolResult(toolCallId: String, content: String) {
        _messages.add(ToolMessage(toolCallId = toolCallId, content = content))
    }

    /**
     * 清空对话历史。
     *
     * - 作用：重置 [Session] 到初始状态，开始新对话
     * - 边缘：清空后 [isEmpty] 返回 true；下一次 [Agent.run] 会重新动态拼接 system（与 session 无关）
     */
    fun clear() {
        _messages.clear()
    }
}
