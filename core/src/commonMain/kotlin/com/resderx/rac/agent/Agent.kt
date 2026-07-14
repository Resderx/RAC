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

package com.resderx.rac.agent

import com.resderx.rac.dsl.ChatRequestBuilder
import com.resderx.rac.dsl.Llm
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.AssistantMessage
import com.resderx.rac.messages.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 自动化 Agent——封装 `Llm` + 工具集 + system 提示词，提供自动多轮工具调用循环。
 *
 * - 作用：作为高层 AI 调用入口，让开发者只需声明「参数 data class + 处理 lambda」或「散开参数 param + execute」，
 *   框架自动完成 JSON Schema 生成、参数反序列化、函数调用、多轮循环；
 *   配合 [Session] 管理对话历史，实现「提供完整会话即自动完成」的开发体验
 * - 必要性：底层 [Llm.chatWithTools] 要求调用方手动收集 toolCalls、手动解析 JSON 参数、
 *   手动调用函数、手动回填结果——每个工具都要重复这套样板代码；[Agent] 消除全部样板
 * - 设计思路：
 *   1. 持有 [llm]（复用 HTTP 连接与供应商配置）、[systemPrompt]（可选，由 DSL 的 `prompts()` 设置）、[tools]（工具注册表）、[maxRounds]
 *   2. [run] 接收 [Session] 与用户输入，自行实现多轮循环（不委托 [Llm.chatWithTools]），
 *      以便完整控制 Session 的消息记录与 system 的动态拼接
 *   3. **System 动态拼接**：[systemPrompt] 是 [Agent] 的属性，每次请求时从 agent 读取并拼接到请求头部，
 *      **不存入 Session**——这样不同 agent（不同 system prompt）可以共用同一份 Session，实现对话历史复用
 *   4. **Session 完整记录**：[run] 的多轮循环中，每一轮的中间消息（带 toolCalls 的 AssistantMessage + ToolMessage）
 *      都同步追加到 Session，保持完整对话历史——便于调试、持久化、跨 agent 复用
 *   5. [Session] 职责单一：仅存 user/assistant/tool 消息，不存 system 提示词——[Agent] 是无状态的服务者，
 *      system 是其配置属性，状态由调用方持有的 [Session] 维护
 * - 实现方式：类持有不可变配置，[run] 为 suspend 方法，内部用 [Llm.chatWithBuilder] 自行控制循环
 * - 边缘情况：
 *   - systemPrompt 为 null → 请求不含 system 消息，等价于普通 chat
 *   - 无工具注册 → 循环不执行，等价于普通 chat
 *   - 模型持续请求工具达 [maxRounds] → 返回最后一次响应，不抛异常
 *   - 工具反序列化失败 → [ToolRegistry.execute] 抛 [com.resderx.rac.exceptions.RACException]，
 *     循环自然中断（异常向上传播）
 * - 优点：开发者体验接近「注解驱动」，但无需 KSP/反射，KMP 全平台兼容；
 *   Session 与 Agent 解耦，可跨 agent 复用同一份对话历史
 * - 算法/数据结构：while 循环 + 动态数组（ChatRequestBuilder 内部消息列表）
 * - 时间复杂度：O(maxRounds × 消息长度)，每轮重建请求
 * - 空间复杂度：O(消息历史长度)，session 与 builder 各持一份
 *
 * @property llm 底层 LLM 调用入口，复用其 HttpClient 与供应商配置
 * @property systemPrompt 系统提示词，每次请求时动态拼接到请求头部，不存入 Session；null 表示不拼接
 * @property tools 工具注册表，含全部工具定义与执行逻辑
 * @property maxRounds 最大工具调用循环轮数（不含首轮模型调用），默认 10
 */
class Agent(
    val llm: Llm,
    val systemPrompt: String?,
    val tools: ToolRegistry,
    val maxRounds: Int = 10,
) {
    /**
     * 执行一轮 Agent 调用——动态拼接 system、注入历史、多轮工具调用、完整记录到 Session。
     *
     * - 作用：Agent 的核心入口，调用方传入 [session] 与 [input]，框架自动完成全流程
     * - 流程：
     *   1. `session.addUser(input)` —— 追加用户输入到 session
     *   2. 构造 [ChatRequestBuilder]：
     *      a. 若 `systemPrompt != null`，调 `builder.system(systemPrompt)` 拼接到请求头部（**不存 session**）
     *      b. `builder.addMessages(session.messages)` 注入完整对话历史
     *      c. `builder.addTools(tools.definitions())` 注入工具定义
     *   3. 调 `llm.chatWithBuilder(builder)` 获取首轮响应
     *   4. 若响应含 toolCalls 且未达 [maxRounds]：
     *      a. 构造 [AssistantMessage]（含 content/toolCalls/reasoningContent），追加到 **session** 与 **builder**
     *      b. 对每个 toolCall：`tools.execute(toolCall)` → 追加 ToolMessage 到 **session** 与 **builder**
     *      c. 回到步骤 3，继续下一轮
     *   5. 最终结果（无 toolCalls 或达 maxRounds）追加到 session，返回
     * - 关键设计：system 不存 session——每次请求都从 agent 属性动态拼接，不同 agent 可共用同一 session
     * - 关键设计：中间消息（AssistantMessage + ToolMessage）同步到 session，保持完整对话历史
     *
     * @param session 对话历史容器，跨多轮调用保持完整上下文（不含 system）
     * @param input 本轮用户输入文本
     * @return 最终的 [AIMessage]（无工具调用或达 maxRounds 上限）
     */
    suspend fun run(session: Session, input: String): AIMessage {
        // 1. 追加用户输入到 session
        session.addUser(input)

        // 2. 构造 builder——system 动态拼接（不存 session），注入完整历史 + 工具定义
        val builder = ChatRequestBuilder().apply {
            if (systemPrompt != null) {
                system(systemPrompt)
            }
            addMessages(session.messages)
            addTools(tools.definitions())
        }

        // 3. 首次调用
        var response = llm.chatWithBuilder(builder)
        var round = 0

        // 4. 多轮工具调用循环——中间消息同步到 session 与 builder
        while (response.toolCalls.isNotEmpty() && round < maxRounds) {
            // 4a. 构造中间 AssistantMessage（含工具调用请求），同步到 session 与 builder
            val assistantMessage = AssistantMessage(
                content = response.content.takeIf { it.isNotEmpty() },
                toolCalls = response.toolCalls,
                reasoningContent = response.reasoningContent,
            )
            session.addAssistantMessage(assistantMessage)
            builder.appendAssistantWithTools(
                content = response.content.takeIf { it.isNotEmpty() },
                toolCalls = response.toolCalls,
            )

            // 4b. 执行工具并记录结果，同步到 session 与 builder
            for (toolCall in response.toolCalls) {
                val result = tools.execute(toolCall)
                session.addToolResult(toolCall.id, result)
                builder.appendToolResult(toolCall.id, result)
            }

            // 4c. 下一轮调用
            response = llm.chatWithBuilder(builder)
            round++
        }

        // 5. 最终结果追加到 session（供下一轮对话引用）
        session.addAssistant(response)

        return response
    }

    /**
     * 流式执行 Agent 调用——与 [run] 语义一致，但以 SSE 流式方式返回事件流。
     *
     * - 作用：Agent 的流式入口，调用方传入 [session] 与 [input]，框架自动完成全流程并实时推送事件
     * - 返回：[StreamEvent] 冷流，包含 TextDelta/ReasoningDelta/ToolCallDelta/Done 四种事件
     * - 流程（与 [run] 一致，但每轮用 [Llm.chatStreamWithBuilder] 获取流式事件）：
     *   1. `session.addUser(input)` —— 追加用户输入到 session
     *   2. 构造 [ChatRequestBuilder]：动态拼接 system（不存 session）+ 注入完整历史 + 注入工具定义
     *   3. 调 `llm.chatStreamWithBuilder(builder)` 获取首轮流式事件
     *   4. collect 每轮事件：
     *      - delta 事件（TextDelta/ReasoningDelta/ToolCallDelta）：**始终转发**给外层流，
     *        调用方可实时看到模型输出（含中间轮的工具调用参数增量）
     *      - Done 事件：从中提取 AIMessage，判断是否含 toolCalls
     *        - 含 toolCalls 且未达 [maxRounds]：**不转发** Done，执行工具、追加中间消息到 session 与 builder，继续下一轮
     *        - 无 toolCalls 或达 [maxRounds]：**转发** Done（最终结果），退出循环
     *   5. 最终结果追加到 session
     * - 事件语义：
     *   - 中间轮的 delta 事件会与最终轮的 delta 事件连续流出，调用方无需区分轮次边界
     *   - 只有最终轮会 emit Done——调用方收到 Done 即表示整个 Agent 流程结束
     *   - 中间轮的 ToolCallDelta 会被转发，调用方可据此知道模型正在请求工具调用
     * - 关键设计：system 动态拼接不存 session；中间消息同步到 session，保持完整对话历史
     *
     * @param session 对话历史容器，跨多轮调用保持完整上下文（不含 system）
     * @param input 本轮用户输入文本
     * @return 统一语义化事件流，最终事件为 Done（含完整 AIMessage 信息）
     */
    fun runStream(session: Session, input: String): Flow<StreamEvent> = flow {
        // 1. 追加用户输入到 session
        session.addUser(input)

        // 2. 构造 builder——system 动态拼接（不存 session），注入完整历史 + 工具定义
        val builder = ChatRequestBuilder().apply {
            if (systemPrompt != null) {
                system(systemPrompt)
            }
            addMessages(session.messages)
            addTools(tools.definitions())
        }

        var round = 0
        var isFinal = false

        // 3-4. 多轮流式循环
        while (!isFinal) {
            var response: AIMessage? = null

            // collect 本轮的流式事件
            llm.chatStreamWithBuilder(builder).collect { event ->
                when (event) {
                    is StreamEvent.Done -> {
                        // 从 Done 提取 AIMessage，判断是否为最终轮
                        val aiMessage = AIMessage(
                            content = event.content,
                            reasoningContent = event.reasoningContent,
                            toolCalls = event.toolCalls,
                            usage = event.usage,
                            finishReason = event.finishReason,
                            rawResponse = event.rawResponse,
                        )
                        response = aiMessage
                        isFinal = aiMessage.toolCalls.isEmpty() || round >= maxRounds
                        if (isFinal) {
                            // 最终轮：转发 Done（调用方据此知道流程结束）
                            emit(event)
                        }
                        // 中间轮：不转发 Done，collect 自然结束后执行工具
                    }
                    else -> {
                        // delta 事件（TextDelta/ReasoningDelta/ToolCallDelta）：始终转发
                        emit(event)
                    }
                }
            }

            val resp = response ?: break

            if (!isFinal) {
                // 中间轮：执行工具，同步到 session 与 builder
                val assistantMessage = AssistantMessage(
                    content = resp.content.takeIf { it.isNotEmpty() },
                    toolCalls = resp.toolCalls,
                    reasoningContent = resp.reasoningContent,
                )
                session.addAssistantMessage(assistantMessage)
                builder.appendAssistantWithTools(
                    content = resp.content.takeIf { it.isNotEmpty() },
                    toolCalls = resp.toolCalls,
                )
                for (toolCall in resp.toolCalls) {
                    val result = tools.execute(toolCall)
                    session.addToolResult(toolCall.id, result)
                    builder.appendToolResult(toolCall.id, result)
                }
                round++
            } else {
                // 最终轮：追加到 session（供下一轮对话引用）
                session.addAssistant(resp)
            }
        }
    }
}
