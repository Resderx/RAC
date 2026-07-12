package com.resderx.rac.a2a

import kotlinx.coroutines.flow.Flow

/**
 * A2A Agent 上下文，提供 Agent 向 Client 推送流式更新的能力。
 *
 * - 作用：在 [A2aAgentHandler] 的 sendMessage/sendStreamingMessage 方法中，
 *   Agent 通过此上下文向 Client 推送任务状态变化与产出物块
 * - 必要性：A2A 的流式方法（tasks/sendSubscribe）需要 Agent 主动推送更新；
 *   此接口抽象了 Agent→Client 方向的流式通信，使 handler 实现无需关心底层 SSE 传输
 * - 设计思路：最小接口——sendStatusUpdate 推送状态变化，sendArtifactUpdate 推送产出物块
 * - 实现：由 [A2aAgentServer] 内部实现，通过 SSE 连接推送 JSON-RPC 通知
 * - 边缘：sendStatusUpdate 中 `final=true` 表示流结束；非流式方法（tasks/send）中此上下文不可用
 */
interface A2aAgentContext {

    /**
     * 推送任务状态更新（流式）。
     *
     * - 作用：通知 Client 任务状态变化（WORKING/INPUT_REQUIRED/COMPLETED 等）
     * - 实现：构造 TaskStatusUpdateEvent 并通过 SSE 推送
     *
     * @param event 状态更新事件
     */
    suspend fun sendStatusUpdate(event: TaskStatusUpdateEvent)

    /**
     * 推送任务产出物更新（流式）。
     *
     * - 作用：通知 Client Agent 生成的产出物块（文档、图片、结构化数据等）
     * - 实现：构造 TaskArtifactUpdateEvent 并通过 SSE 推送
     *
     * @param event 产出物更新事件
     */
    suspend fun sendArtifactUpdate(event: TaskArtifactUpdateEvent)
}

/**
 * A2A Agent 处理器接口，定义 Agent 端业务逻辑的统一契约。
 *
 * - 作用：抽象 A2A Agent 的核心操作（消息处理、任务管理），由 [A2aAgentServer] 在收到
 *   Client JSON-RPC 请求时调用
 * - 必要性：将 Agent 的协议处理（JSON-RPC 分发、SSE 流管理）与业务逻辑（AI 调用、任务状态管理）
 *   解耦，使 [RacA2aAgent] 等实现只需关注业务逻辑
 * - 设计思路：
 *   1. getAgentCard 返回 Agent 元数据（无需 context）
 *   2. sendMessage 处理非流式消息，返回 Task 或 Message
 *   3. sendStreamingMessage 处理流式消息，通过 [A2aAgentContext] 推送更新
 *   4. getTask/listTasks/cancelTask 为任务管理操作
 *   5. close 释放 Agent 资源
 * - 实现方式：interface，由 [RacA2aAgent] 提供默认实现（将 RAC 暴露为 A2A Agent）
 * - 规范来源：https://a2a-protocol.org/latest/specification/#9-json-rpc-protocol-binding
 */
interface A2aAgentHandler {

    /**
     * 返回 Agent Card 元数据。
     *
     * @return Agent Card 文档
     */
    fun getAgentCard(): AgentCard

    /**
     * 处理 tasks/send 请求——非流式消息处理。
     *
     * - 作用：接收 Client 消息，执行 Agent 逻辑，返回 Task（异步处理）或 Message（直接响应）
     * - 边缘：若任务已处于终态，应返回错误
     *
     * @param params 发送参数（含消息、任务 ID、配置等）
     * @return 发送结果——Task 或 Message
     */
    suspend fun sendMessage(params: SendMessageParams): SendMessageResult

    /**
     * 处理 tasks/sendSubscribe 请求——流式消息处理。
     *
     * - 作用：接收 Client 消息，执行 Agent 逻辑，通过 [context] 流式推送状态与产出物更新
     * - 边缘：处理完成后应推送 `final=true` 的状态更新以结束流
     *
     * @param params 发送参数
     * @param context Agent 上下文，用于推送流式更新
     * @return 初始结果（流的首条事件，Task 或 Message）
     */
    suspend fun sendStreamingMessage(
        params: SendStreamingMessageParams,
        context: A2aAgentContext,
    ): SendMessageResult

    /**
     * 处理 tasks/get 请求——查询任务状态。
     *
     * @param params 查询参数（含任务 ID 与可选 historyLength）
     * @return 任务当前完整状态
     */
    suspend fun getTask(params: GetTaskParams): GetTaskResult

    /**
     * 处理 tasks/list 请求——列出任务。
     *
     * @param params 过滤参数
     * @return 匹配的任务列表
     */
    suspend fun listTasks(params: ListTasksParams): ListTasksResult

    /**
     * 处理 tasks/cancel 请求——取消任务。
     *
     * @param params 取消参数（含任务 ID）
     * @return 取消后的任务状态
     */
    suspend fun cancelTask(params: CancelTaskParams): CancelTaskResult

    /**
     * 关闭 Agent 并释放资源。
     *
     * - 作用：终止所有活跃任务，释放 AI 客户端等资源
     * - 边缘：幂等操作
     */
    suspend fun close()
}
