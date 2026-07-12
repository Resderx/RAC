package com.resderx.rac.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A2A v1.0 JSON-RPC 方法参数与结果模型。
 *
 * - 作用：定义 A2A JSON-RPC 绑定中各方法（tasks/send、tasks/get、tasks/list 等）的
 *   请求参数与响应结果数据类，供 [A2aClient] 与 [A2aAgentServer] 序列化使用
 * - 必要性：A2A JSON-RPC 绑定规范定义了每个方法的具体参数结构，需精确映射
 * - 设计思路：参数类以 `*Params` 命名，结果类以 `*Result` 命名；多态返回用密封接口
 * - 规范来源：https://a2a-protocol.org/latest/specification/#9-json-rpc-protocol-binding
 *
 * 文件内容：
 * - [SendMessageParams] / [SendMessageResult]：tasks/send 方法
 * - [SendStreamingMessageParams]：tasks/sendSubscribe 方法（流式，结果通过 SSE 事件推送）
 * - [GetTaskParams] / [GetTaskResult]：tasks/get 方法
 * - [ListTasksParams] / [ListTasksResult]：tasks/list 方法
 * - [CancelTaskParams] / [CancelTaskResult]：tasks/cancel 方法
 * - [TaskStatusUpdateEvent] / [TaskArtifactUpdateEvent]：流式更新事件
 * - [A2aError]：A2A 协议错误码常量
 */

/**
 * tasks/send 方法参数。
 *
 * @property id 任务 ID；新任务为 null 由 Server 生成，已有任务则追加消息
 * @property sessionId 会话 ID，可空
 * @property contextId 上下文 ID，可空
 * @property message 要发送的消息
 * @property configuration 发送配置，可空
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class SendMessageParams(
    val id: String? = null,
    val sessionId: String? = null,
    val contextId: String? = null,
    val message: Message,
    val configuration: SendMessageConfiguration? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * 消息发送配置。
 *
 * @property acceptedOutputModes Client 接受的输出 MIME 类型
 * @property blocking 是否阻塞等待任务完成，可空
 * @property historyLength 历史消息最大返回数，可空
 */
@Serializable
data class SendMessageConfiguration(
    val acceptedOutputModes: List<String> = emptyList(),
    val blocking: Boolean? = null,
    val historyLength: Int? = null,
)

/**
 * tasks/send 方法返回结果——密封接口，支持 Task 或 Message 两种返回形式。
 *
 * - Task：Agent 异步处理，Client 可通过 tasks/get 轮询或流式订阅
 * - Message：Agent 直接同步返回，无需任务跟踪
 */
@Serializable
sealed interface SendMessageResult {
    /** 返回的任务对象。 */
    @Serializable
    @SerialName("task")
    data class TaskResult(val task: Task) : SendMessageResult

    /** 直接返回的消息（无任务跟踪）。 */
    @Serializable
    @SerialName("message")
    data class MessageResult(val result: Message) : SendMessageResult
}

/**
 * tasks/sendSubscribe 方法参数（流式版本，与 [SendMessageParams] 结构一致）。
 */
@Serializable
data class SendStreamingMessageParams(
    val id: String? = null,
    val sessionId: String? = null,
    val contextId: String? = null,
    val message: Message,
    val configuration: SendMessageConfiguration? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * tasks/get 方法参数。
 *
 * @property id 任务 ID
 * @property historyLength 历史消息最大返回数，可空
 */
@Serializable
data class GetTaskParams(
    val id: String,
    val historyLength: Int? = null,
)

/**
 * tasks/get 方法返回结果——直接返回 Task。
 */
@Serializable
data class GetTaskResult(
    val task: Task,
)

/**
 * tasks/list 方法参数。
 *
 * @property contextId 上下文 ID 过滤，可空
 * @property state 任务状态过滤，可空
 * @property limit 返回上限，可空
 */
@Serializable
data class ListTasksParams(
    val contextId: String? = null,
    val state: TaskState? = null,
    val limit: Int? = null,
)

/**
 * tasks/list 方法返回结果。
 *
 * @property tasks 任务列表
 */
@Serializable
data class ListTasksResult(
    val tasks: List<Task> = emptyList(),
)

/**
 * tasks/cancel 方法参数。
 *
 * @property id 要取消的任务 ID
 */
@Serializable
data class CancelTaskParams(
    val id: String,
)

/**
 * tasks/cancel 方法返回结果——返回取消后的 Task 状态。
 */
@Serializable
data class CancelTaskResult(
    val task: Task,
)

/**
 * 任务状态更新事件（流式推送）。
 *
 * - 作用：在 tasks/sendSubscribe 流中推送任务状态变化
 * - 边缘：`final` 为 true 表示流结束
 *
 * @property id 任务 ID
 * @property status 新状态快照
 * @property final 是否为流的最后一条事件，默认 false
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class TaskStatusUpdateEvent(
    val id: String,
    val status: TaskStatus,
    val final: Boolean = false,
    val metadata: Map<String, String>? = null,
)

/**
 * 任务产出物更新事件（流式推送）。
 *
 * - 作用：在 tasks/sendSubscribe 流中推送 Agent 生成的产出物块
 * - 边缘：`append` 为 true 表示追加到现有产出物；`lastChunk` 为 true 表示产出物最后一块
 *
 * @property id 任务 ID
 * @property artifact 产出物
 * @property append 是否追加到现有产出物，默认 false
 * @property lastChunk 是否为产出物最后一块，默认 false
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class TaskArtifactUpdateEvent(
    val id: String,
    val artifact: Artifact,
    val append: Boolean = false,
    val lastChunk: Boolean = false,
    val metadata: Map<String, String>? = null,
)

/**
 * A2A 协议 JSON-RPC 错误码常量。
 *
 * - 规范来源：https://a2a-protocol.org/latest/specification/#95-error-handling
 */
object A2aError {
    /** 任务不存在。 */
    const val TASK_NOT_FOUND = -32001

    /** 任务不可操作（已处于终态）。 */
    const val TASK_NOT_OPERATABLE = -32002

    /** 内容类型不支持。 */
    const val CONTENT_TYPE_NOT_SUPPORTED = -32003

    /** 不支持的操作。 */
    const val UNSUPPORTED_OPERATION = -32004

    /** JSON-RPC 标准方法未找到错误。 */
    const val METHOD_NOT_FOUND = -32601

    /** JSON-RPC 标准参数无效错误。 */
    const val INVALID_PARAMS = -32602

    /** JSON-RPC 标准内部错误。 */
    const val INTERNAL_ERROR = -32603
}
