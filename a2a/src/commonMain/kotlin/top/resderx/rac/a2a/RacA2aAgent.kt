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

package top.resderx.rac.a2a

import top.resderx.rac.dsl.Llm
import top.resderx.rac.exceptions.RACException
import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.FinishReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Llm → A2A Agent 适配器——将 Llm 的 LLM 调用能力封装为 A2A Agent。
 *
 * - 作用：实现 [top.resderx.rac.a2a.A2aAgentHandler] 接口，将 A2A 的 tasks/send / tasks/sendSubscribe 请求
 *   转换为 Llm 的 [top.resderx.rac.dsl.Llm.chat] 调用，并将 AI 响应映射回 A2A 的 Task / Message 模型
 * - 必要性：使 Llm 能作为 A2A Agent Server 对外提供服务，供任何兼容 A2A 的 Client 调用
 * - 设计思路：
 *   1. getAgentCard 返回配置的 Agent Card 元数据
 *   2. sendMessage 将 A2A Message 转为 Llm chat 请求，返回 Task（含 AI 响应为 Message）
 *   3. sendStreamingMessage 同步调用 Llm chat，通过 context 推送 AgentMessageChunk
 *   4. getTask/listTasks/cancelTask 为简化实现（内存任务存储）
 *   5. 任务状态管理：内存 Map 存储任务，Mutex 保护并发访问
 * - 与 [RacAcpAgent] 的差异：
 *   - ACP 的 sessionPrompt 返回 StopReason，A2A 的 sendMessage 返回 Task/Message
 *   - ACP 的更新为 SessionUpdate（AgentMessageChunk 等），A2A 的更新为 TaskStatusUpdateEvent
 *   - A2A 需维护任务生命周期状态（WORKING/COMPLETED 等），ACP 由 stopReason 隐式表达
 * - 线程安全：tasks Map 由 [tasksMutex] 保护
 * - 边缘：
 *   - 非文本 Part（FilePart/DataPart）被忽略，仅提取 TextPart 拼接为 prompt
 *   - AI 响应仅包含文本内容，映射为 Task 的 history 中的 agent Message
 *   - 任务 ID 由 UUID 生成
 * - 时间复杂度：单次 sendMessage O(n) 序列化 + Llm.chat 执行
 * - 空间复杂度：O(m) tasks Map（m 为历史任务数）
 *
 * @property llm Llm 实例
 * @property agentCard Agent Card 元数据
 * @property systemPrompt 系统提示词，可空
 */
class RacA2aAgent(
    private val llm: top.resderx.rac.dsl.Llm,
    private val agentCard: top.resderx.rac.a2a.AgentCard,
    private val systemPrompt: String? = null,
) : top.resderx.rac.a2a.A2aAgentHandler {

    /** 内存任务存储：taskId → Task。由 [tasksMutex] 保护。 */
    private val tasks = mutableMapOf<String, top.resderx.rac.a2a.Task>()

    /** tasks 访问互斥锁。 */
    private val tasksMutex = Mutex()

    override fun getAgentCard(): top.resderx.rac.a2a.AgentCard = agentCard

    /**
     * 从 A2A Message 提取文本内容。
     *
     * - 作用：将 Message 的 parts 中的 TextPart 拼接为单个字符串
     * - 边缘：非 TextPart 类型被忽略
     *
     * @param message A2A 消息
     * @return 拼接后的文本
     */
    private fun extractText(message: top.resderx.rac.a2a.Message): String =
        message.parts.filterIsInstance<top.resderx.rac.a2a.TextPart>().joinToString("") { it.text }

    /**
     * 将 RAC AIMessage 的 FinishReason 映射为 A2A TaskState。
     *
     * - STOP → COMPLETED
     * - LENGTH → COMPLETED（达到 token 上限但仍完成）
     * - TOOL_CALLS → INPUT_REQUIRED（需用户提供工具结果）
     * - CONTENT_FILTER → FAILED
     * - UNKNOWN → FAILED
     *
     * @param finishReason RAC 的完成原因
     * @return A2A 任务状态
     */
    private fun finishReasonToTaskState(finishReason: top.resderx.rac.messages.FinishReason): top.resderx.rac.a2a.TaskState = when (finishReason) {
        top.resderx.rac.messages.FinishReason.STOP -> top.resderx.rac.a2a.TaskState.COMPLETED
        top.resderx.rac.messages.FinishReason.LENGTH -> top.resderx.rac.a2a.TaskState.COMPLETED
        top.resderx.rac.messages.FinishReason.TOOL_CALLS -> top.resderx.rac.a2a.TaskState.INPUT_REQUIRED
        top.resderx.rac.messages.FinishReason.CONTENT_FILTER -> top.resderx.rac.a2a.TaskState.FAILED
        top.resderx.rac.messages.FinishReason.UNKNOWN -> top.resderx.rac.a2a.TaskState.FAILED
    }

    /**
     * 生成唯一任务 ID——随机长整型十六进制字符串。
     *
     * - KMP 兼容：不依赖 java.util.UUID 或 System.currentTimeMillis()，
     *   仅使用 kotlin.random.Random（KMP 标准库）
     * - 碰撞概率：64 位随机数碰撞概率极低（2^64 空间）
     *
     * @return 唯一 ID 字符串
     */
    private fun generateTaskId(): String =
        Random.nextLong(1, Long.MAX_VALUE).toString(16)

    /**
     * 执行 Llm chat 调用并构造 Task。
     *
     * - 作用：将 A2A 消息转为 Llm chat 请求，执行推理，构造包含 AI 响应的 Task
     * - 实现：提取文本 → 调用 Llm.chat → 构造 agent Message → 组装 Task
     *
     * @param params 发送参数
     * @param isStreaming 是否为流式调用（影响 Task 初始状态）
     * @return 包含 AI 响应的 Task
     */
    private suspend fun executeChat(params: SendMessageParams): top.resderx.rac.a2a.Task {
        val promptText = extractText(params.message)
        val aiMessage: top.resderx.rac.messages.AIMessage = llm.chat {
            systemPrompt?.let { system(it) }
            user(promptText)
        }

        val taskState = finishReasonToTaskState(aiMessage.finishReason)
        val agentMessage = top.resderx.rac.a2a.Message(
            role = top.resderx.rac.a2a.Role.AGENT,
            parts = listOf(top.resderx.rac.a2a.TextPart(text = aiMessage.content)),
        )

        val taskId = params.id ?: generateTaskId()
        val task = top.resderx.rac.a2a.Task(
            id = taskId,
            sessionId = params.sessionId,
            contextId = params.contextId,
            status = top.resderx.rac.a2a.TaskStatus(state = taskState),
            history = listOf(params.message, agentMessage),
            artifacts = null,
        )

        tasksMutex.withLock { tasks[taskId] = task }
        return task
    }

    override suspend fun sendMessage(params: top.resderx.rac.a2a.SendMessageParams): top.resderx.rac.a2a.SendMessageResult {
        return top.resderx.rac.a2a.SendMessageResult.TaskResult(task = executeChat(params))
    }

    override suspend fun sendStreamingMessage(
        params: top.resderx.rac.a2a.SendStreamingMessageParams,
        context: top.resderx.rac.a2a.A2aAgentContext,
    ): top.resderx.rac.a2a.SendMessageResult {
        // 推送 WORKING 状态
        val taskId = params.id ?: generateTaskId()
        context.sendStatusUpdate(
            top.resderx.rac.a2a.TaskStatusUpdateEvent(
                id = taskId,
                status = top.resderx.rac.a2a.TaskStatus(state = top.resderx.rac.a2a.TaskState.WORKING),
            )
        )

        // 执行 chat——将 SendStreamingMessageParams 转为 SendMessageParams（结构一致）
        val task = executeChat(
            top.resderx.rac.a2a.SendMessageParams(
                id = params.id,
                sessionId = params.sessionId,
                contextId = params.contextId,
                message = params.message,
                configuration = params.configuration,
                metadata = params.metadata,
            )
        )

        // 推送 agent 消息作为 artifact
        val agentMessage = task.history?.lastOrNull { it.role == top.resderx.rac.a2a.Role.AGENT }
        if (agentMessage != null) {
            val textPart = agentMessage.parts.filterIsInstance<top.resderx.rac.a2a.TextPart>().firstOrNull()
            if (textPart != null) {
                context.sendArtifactUpdate(
                    top.resderx.rac.a2a.TaskArtifactUpdateEvent(
                        id = taskId,
                        artifact = top.resderx.rac.a2a.Artifact(
                            artifactId = "message-1",
                            parts = listOf(textPart),
                            lastChunk = true,
                        ),
                        lastChunk = true,
                    )
                )
            }
        }

        // 推送最终状态
        context.sendStatusUpdate(
            top.resderx.rac.a2a.TaskStatusUpdateEvent(
                id = taskId,
                status = task.status,
                final = true,
            )
        )

        return top.resderx.rac.a2a.SendMessageResult.TaskResult(task = task)
    }

    override suspend fun getTask(params: top.resderx.rac.a2a.GetTaskParams): top.resderx.rac.a2a.GetTaskResult {
        val task = tasksMutex.withLock { tasks[params.id] }
            ?: throw top.resderx.rac.exceptions.RACException("Task not found: ${params.id}")
        return top.resderx.rac.a2a.GetTaskResult(task = task)
    }

    override suspend fun listTasks(params: top.resderx.rac.a2a.ListTasksParams): top.resderx.rac.a2a.ListTasksResult {
        val allTasks = tasksMutex.withLock { tasks.values.toList() }
        val filtered = allTasks
            .filter { params.contextId == null || it.contextId == params.contextId }
            .filter { params.state == null || it.status.state == params.state }
            .let { list -> params.limit?.let { list.take(it) } ?: list }
        return top.resderx.rac.a2a.ListTasksResult(tasks = filtered)
    }

    override suspend fun cancelTask(params: top.resderx.rac.a2a.CancelTaskParams): top.resderx.rac.a2a.CancelTaskResult {
        val task = tasksMutex.withLock {
            tasks[params.id]?.copy(status = top.resderx.rac.a2a.TaskStatus(state = top.resderx.rac.a2a.TaskState.CANCELED))
                ?.also { tasks[params.id] = it }
        } ?: throw top.resderx.rac.exceptions.RACException("Task not found: ${params.id}")
        return top.resderx.rac.a2a.CancelTaskResult(task = task)
    }

    override suspend fun close() {
        tasksMutex.withLock { tasks.clear() }
    }
}
