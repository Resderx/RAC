package com.resderx.rac.acp

/**
 * ACP Agent 上下文，提供 Agent 向 Client 发送消息的能力。
 *
 * - 作用：在 [AcpAgentHandler] 的 sessionPrompt/sessionLoad 方法中，
 *   Agent 通过此上下文向 Client 推送更新（session/update 通知）与请求权限（session/request_permission 请求）
 * - 必要性：ACP 是双向 JSON-RPC 协议，Agent 不仅接收 Client 请求，也需主动向 Client 发送通知与请求；
 *   此接口抽象了 Agent→Client 方向的通信，使 handler 实现无需关心底层传输
 * - 设计思路：最小接口——sendUpdate 发送通知（无响应），requestPermission 发送请求（等待响应）
 * - 实现：由 [AcpAgentServer] 内部实现，通过底层 [AcpConnection] 发送 JSON-RPC 消息
 * - 边缘：sendUpdate 为 fire-and-forget（通知无响应）；requestPermission 挂起直到 Client 响应
 */
interface AcpAgentContext {

    /**
     * 向 Client 推送会话更新通知（session/update）。
     *
     * - 作用：流式推送 Agent 的进度更新——计划、消息块、工具调用、用量等
     * - 实现：构造 session/update JSON-RPC 通知并通过连接发送
     *
     * @param update 会话更新内容（PlanUpdate/AgentMessageChunk/ToolCallUpdate/UsageUpdate 等）
     */
    suspend fun sendUpdate(update: SessionUpdate)

    /**
     * 向 Client 请求权限（session/request_permission）。
     *
     * - 作用：Agent 执行敏感操作前请求用户授权（如执行命令、写入文件）
     * - 实现：构造 session/request_permission JSON-RPC 请求发送给 Client，等待响应
     *
     * @param permission 权限请求内容（类型、标题、选项）
     * @return 用户选择的权限结果（allow/deny/cancelled）
     */
    suspend fun requestPermission(permission: PermissionRequest): PermissionOutcome
}

/**
 * ACP Agent 处理器接口，定义 Agent 端业务逻辑的统一契约。
 *
 * - 作用：抽象 ACP Agent 的核心操作（初始化、会话管理、提示处理），
 *   由 [AcpAgentServer] 在收到 Client 请求时调用
 * - 必要性：将 Agent 的协议处理（JSON-RPC 分发）与业务逻辑（AI 调用、会话管理）解耦，
 *   使 [RacAcpAgent] 等实现只需关注业务逻辑
 * - 设计思路：
 *   1. initialize/sessionNew 无需 context（不涉及 Agent→Client 通信）
 *   2. sessionLoad/sessionPrompt 接收 [AcpAgentContext]，用于推送更新与请求权限
 *   3. sessionCancel 为通知（无响应），仅传递 sessionId
 *   4. close 释放 Agent 资源
 * - 实现方式：interface，由 [RacAcpAgent] 提供默认实现（将 RAC 暴露为 ACP Agent）
 * - 边缘：sessionPrompt 应在处理过程中通过 context.sendUpdate 推送进度；
 *   sessionCancel 应取消当前进行中的轮次（如有的话）
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/
 */
interface AcpAgentHandler {

    /**
     * 处理 initialize 请求，返回 Agent 信息与能力。
     *
     * @param params Client 的初始化参数（协议版本、能力、客户端信息）
     * @return Agent 的初始化响应（协议版本、能力、信息、认证方法）
     */
    suspend fun initialize(params: InitializeParams): InitializeResult

    /**
     * 处理 session/new 请求，创建新会话。
     *
     * @param params 会话创建参数（工作目录、MCP 服务器配置、额外目录）
     * @return 会话创建结果（含 sessionId）
     */
    suspend fun sessionNew(params: SessionNewParams): SessionNewResult

    /**
     * 处理 session/load 请求，加载已有会话并重放历史。
     *
     * - 作用：恢复之前创建的会话，通过 [context].sendUpdate 重放历史消息
     *
     * @param params 会话加载参数（sessionId、工作目录、MCP 配置）
     * @param context Agent 上下文，用于推送重放的 session/update 通知
     * @return 会话加载结果
     */
    suspend fun sessionLoad(params: SessionLoadParams, context: AcpAgentContext): SessionLoadResult

    /**
     * 处理 session/prompt 请求，执行一轮 Agent 对话。
     *
     * - 作用：接收用户提示，执行 AI 推理与工具调用，通过 [context].sendUpdate 流式推送进度，
     *   最终返回停止原因
     * - 实现：将 ACP 内容块转为 AI 调用 → 执行推理 → 推送 AgentMessageChunk/ToolCallUpdate 等更新
     *
     * @param params 提示参数（sessionId、prompt 内容块列表）
     * @param context Agent 上下文，用于推送 session/update 通知与请求权限
     * @return 提示结果（stopReason）
     */
    suspend fun sessionPrompt(params: SessionPromptParams, context: AcpAgentContext): SessionPromptResult

    /**
     * 处理 session/cancel 通知，取消当前进行中的轮次。
     *
     * - 作用：通知 Agent 取消正在执行的 session/prompt 轮次
     * - 边缘：如果当前无活跃轮次，应静默忽略
     *
     * @param sessionId 会话 ID
     */
    suspend fun sessionCancel(sessionId: String)

    /**
     * 关闭 Agent 并释放资源。
     *
     * - 作用：终止所有活跃会话，释放 AI 客户端等资源
     * - 边缘：幂等操作
     */
    suspend fun close()
}
