package com.resderx.rac.a2a

import com.resderx.rac.dsl.RAC
import com.resderx.rac.exceptions.RACException
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.FinishReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * RAC → A2A Agent 适配器——将 RAC 的 LLM 调用能力封装为 A2A Agent。
 *
 * - 作用：实现 [A2aAgentHandler] 接口，将 A2A 的 tasks/send / tasks/sendSubscribe 请求
 *   转换为 RAC 的 [RAC.chat] 调用，并将 AI 响应映射回 A2A 的 Task / Message 模型
 * - 必要性：使 RAC 能作为 A2A Agent Server 对外提供服务，供任何兼容 A2A 的 Client 调用
 * - 设计思路：
 *   1. getAgentCard 返回配置的 Agent Card 元数据
 *   2. sendMessage 将 A2A Message 转为 RAC chat 请求，返回 Task（含 AI 响应为 Message）
 *   3. sendStreamingMessage 同步调用 RAC chat，通过 context 推送 AgentMessageChunk
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
 * - 时间复杂度：单次 sendMessage O(n) 序列化 + RAC.chat 执行
 * - 空间复杂度：O(m) tasks Map（m 为历史任务数）
 *
 * @property rac RAC 实例
 * @property agentCard Agent Card 元数据
 * @property systemPrompt 系统提示词，可空
 */
class RacA2aAgent(
    private val rac: RAC,
    private val agentCard: AgentCard,
    private val systemPrompt: String? = null,
) : A2aAgentHandler {

    /** 内存任务存储：taskId → Task。由 [tasksMutex] 保护。 */
    private val tasks = mutableMapOf<String, Task>()

    /** tasks 访问互斥锁。 */
    private val tasksMutex = Mutex()

    override fun getAgentCard(): AgentCard = agentCard

    /**
     * 从 A2A Message 提取文本内容。
     *
     * - 作用：将 Message 的 parts 中的 TextPart 拼接为单个字符串
     * - 边缘：非 TextPart 类型被忽略
     *
     * @param message A2A 消息
     * @return 拼接后的文本
     */
    private fun extractText(message: Message): String =
        message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }

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
    private fun finishReasonToTaskState(finishReason: FinishReason): TaskState = when (finishReason) {
        FinishReason.STOP -> TaskState.COMPLETED
        FinishReason.LENGTH -> TaskState.COMPLETED
        FinishReason.TOOL_CALLS -> TaskState.INPUT_REQUIRED
        FinishReason.CONTENT_FILTER -> TaskState.FAILED
        FinishReason.UNKNOWN -> TaskState.FAILED
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
     * 执行 RAC chat 调用并构造 Task。
     *
     * - 作用：将 A2A 消息转为 RAC chat 请求，执行推理，构造包含 AI 响应的 Task
     * - 实现：提取文本 → 调用 RAC.chat → 构造 agent Message → 组装 Task
     *
     * @param params 发送参数
     * @param isStreaming 是否为流式调用（影响 Task 初始状态）
     * @return 包含 AI 响应的 Task
     */
    private suspend fun executeChat(params: SendMessageParams): Task {
        val promptText = extractText(params.message)
        val aiMessage: AIMessage = rac.chat {
            systemPrompt?.let { system(it) }
            user(promptText)
        }

        val taskState = finishReasonToTaskState(aiMessage.finishReason)
        val agentMessage = Message(
            role = Role.AGENT,
            parts = listOf(TextPart(text = aiMessage.content)),
        )

        val taskId = params.id ?: generateTaskId()
        val task = Task(
            id = taskId,
            sessionId = params.sessionId,
            contextId = params.contextId,
            status = TaskStatus(state = taskState),
            history = listOf(params.message, agentMessage),
            artifacts = null,
        )

        tasksMutex.withLock { tasks[taskId] = task }
        return task
    }

    override suspend fun sendMessage(params: SendMessageParams): SendMessageResult {
        return SendMessageResult.TaskResult(task = executeChat(params))
    }

    override suspend fun sendStreamingMessage(
        params: SendStreamingMessageParams,
        context: A2aAgentContext,
    ): SendMessageResult {
        // 推送 WORKING 状态
        val taskId = params.id ?: generateTaskId()
        context.sendStatusUpdate(TaskStatusUpdateEvent(
            id = taskId,
            status = TaskStatus(state = TaskState.WORKING),
        ))

        // 执行 chat——将 SendStreamingMessageParams 转为 SendMessageParams（结构一致）
        val task = executeChat(SendMessageParams(
            id = params.id,
            sessionId = params.sessionId,
            contextId = params.contextId,
            message = params.message,
            configuration = params.configuration,
            metadata = params.metadata,
        ))

        // 推送 agent 消息作为 artifact
        val agentMessage = task.history?.lastOrNull { it.role == Role.AGENT }
        if (agentMessage != null) {
            val textPart = agentMessage.parts.filterIsInstance<TextPart>().firstOrNull()
            if (textPart != null) {
                context.sendArtifactUpdate(TaskArtifactUpdateEvent(
                    id = taskId,
                    artifact = Artifact(
                        artifactId = "message-1",
                        parts = listOf(textPart),
                        lastChunk = true,
                    ),
                    lastChunk = true,
                ))
            }
        }

        // 推送最终状态
        context.sendStatusUpdate(TaskStatusUpdateEvent(
            id = taskId,
            status = task.status,
            final = true,
        ))

        return SendMessageResult.TaskResult(task = task)
    }

    override suspend fun getTask(params: GetTaskParams): GetTaskResult {
        val task = tasksMutex.withLock { tasks[params.id] }
            ?: throw RACException("Task not found: ${params.id}")
        return GetTaskResult(task = task)
    }

    override suspend fun listTasks(params: ListTasksParams): ListTasksResult {
        val allTasks = tasksMutex.withLock { tasks.values.toList() }
        val filtered = allTasks
            .filter { params.contextId == null || it.contextId == params.contextId }
            .filter { params.state == null || it.status.state == params.state }
            .let { list -> params.limit?.let { list.take(it) } ?: list }
        return ListTasksResult(tasks = filtered)
    }

    override suspend fun cancelTask(params: CancelTaskParams): CancelTaskResult {
        val task = tasksMutex.withLock {
            tasks[params.id]?.copy(status = TaskStatus(state = TaskState.CANCELED))
                ?.also { tasks[params.id] = it }
        } ?: throw RACException("Task not found: ${params.id}")
        return CancelTaskResult(task = task)
    }

    override suspend fun close() {
        tasksMutex.withLock { tasks.clear() }
    }
}
