package com.resderx.rac.acp

import com.resderx.rac.dsl.RAC
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.FinishReason

/**
 * 以 ACP Client 身份调用远程 ACP Agent，执行一轮对话（RAC 扩展函数）。
 *
 * - 作用：将 RAC 作为 ACP Client，通过已配置的 [AcpClient] 与远程 ACP Agent（如 Claude Code、
 *   Codex CLI 等）交互——初始化握手 → 创建会话 → 发送提示 → 收集 Agent 的流式更新 → 返回统一 [AIMessage]
 * - 必要性：ACP 是 Zed + JetBrains 联合推出的标准化 AI 编码助手通信协议，RAC 需原生支持 Client 角色，
 *   使调用方无需关心 ACP 协议细节（JSON-RPC、session 生命周期、update 通知路由）即可调用任何 ACP Agent
 * - 模块拆分：本扩展函数位于 rac-acp 模块，避免 core 模块依赖 ACP 协议包；
 *   调用方需在依赖中同时引入 rac-core 与 rac-acp
 * - 设计思路：
 *   1. 调用 [AcpClient.initialize]（幂等，内部 double-check 确保仅握手一次）
 *   2. 调用 [AcpClient.sessionNew] 创建新会话（每次调用创建新会话，不复用）
 *   3. 调用 [AcpClient.sessionPrompt] 发送提示，在 [onUpdate] 回调中累积 [AgentMessageChunk] 的文本
 *   4. 将 ACP [StopReason] 映射为 RAC [FinishReason]，构造统一 [AIMessage] 返回
 * - 实现方式：suspend 扩展函数，委托 [AcpClient] 的生命周期方法，内部 StringBuilder 累积响应文本
 * - 可能的问题：[AcpClient] 生命周期由调用方管理，本方法不关闭 client；远程 Agent 抛错时向上传播为 [com.resderx.rac.exceptions.RACException]
 * - 边缘情况：prompt 为空字符串时仍发送（由 Agent 决定如何处理）；Agent 未推送任何 AgentMessageChunk 时
 *   返回的 AIMessage.content 为空字符串；Agent 推送的非文本内容块（image/audio/resource）被忽略
 * - 优点：调用方无需关心 ACP session 管理与 JSON-RPC 细节；与 RAC.chat/RAC.chatWithTools 返回类型一致
 * - 算法/数据结构：StringBuilder 累积文本 + StopReason → FinishReason 映射
 * - 时间复杂度：O(N)，N 为 Agent 推送的消息块数
 * - 空间复杂度：O(T)，T 为累积的响应文本长度
 *
 * @receiver RAC 实例（仅作为命名空间锚点，不直接使用其内部能力）
 * @param client 已配置传输的 ACP 客户端（调用方管理生命周期）
 * @param prompt 用户提示文本
 * @param cwd 工作目录，传递给 Agent 作为执行上下文，默认空字符串
 * @param onUpdate 会话更新回调，接收 Agent 推送的流式更新（计划/消息块/工具调用/用量）；默认空回调
 * @return 统一的 AIMessage（content 为累积的 Agent 响应文本，finishReason 由 StopReason 映射）
 * @throws com.resderx.rac.exceptions.RACException 当 ACP 握手/会话创建/提示失败时向上传播
 */
suspend fun RAC.chatWithAcpAgent(
    client: AcpClient,
    prompt: String,
    cwd: String = "",
    onUpdate: suspend (SessionUpdate) -> Unit = {},
): AIMessage {
    // 1. ACP 握手（幂等：DefaultAcpClient 内部 double-check 保证仅执行一次）
    client.initialize()
    // 2. 创建新会话（每次调用创建新会话，不复用旧会话）
    val session = client.sessionNew(cwd = cwd)
    // 3. 累积 Agent 响应文本
    val contentBuilder = StringBuilder()
    // 4. 发送提示并收集流式更新
    val stopReason = client.sessionPrompt(
        sessionId = session.sessionId,
        prompt = listOf(AcpTextBlock(text = prompt)),
    ) { update ->
        // 从 AgentMessageChunk 中提取文本并累积
        if (update is AgentMessageChunk) {
            (update.content as? AcpTextBlock)?.text?.let { contentBuilder.append(it) }
        }
        // 转发给调用方回调
        onUpdate(update)
    }
    // 5. 映射停止原因并返回统一 AIMessage
    return AIMessage(
        content = contentBuilder.toString(),
        finishReason = stopReason.toFinishReason(),
    )
}

/**
 * 将 RAC 作为 ACP Agent 启动，通过 stdio 与 ACP Client 通信（RAC 扩展函数）。
 *
 * - 作用：创建 [RacAcpAgent]（将 RAC 的 AI 调用能力适配为 ACP Agent）并包装进 [AcpAgentServer]，
 *   使任何 ACP 兼容的 Client（如 Zed、JetBrains IDE）都能通过 ACP 协议调用 RAC 管理的 AI 供应商
 * - 必要性：RAC 需支持 ACP Agent 角色（Server 端），与 Client 角色（[chatWithAcpAgent]）对称；
 *   本方法封装 RacAcpAgent + AcpAgentServer 的创建，调用方只需配置 Agent 信息与系统提示
 * - 模块拆分：本扩展函数位于 rac-acp 模块，避免 core 模块依赖 ACP 协议包
 * - 设计思路：
 *   1. 构造 [RacAcpAgent]：以当前 RAC 实例为 AI 引擎，配置 agentInfo/agentCapabilities/systemPrompt
 *   2. 构造 [AcpAgentServer]：以 RacAcpAgent 为 handler，创建 stdio 服务端连接
 *   3. 返回 AcpAgentServer（尚未启动），调用方需调用 [AcpAgentServer.start] 启动 dispatcher
 * - 实现方式：fun 返回 AcpAgentServer，不启动 dispatcher（调用方控制启动时机）
 * - 可能的问题：stdio 服务端连接读取自身 stdin/stdout，仅 JVM 平台支持；
 *   调用方需在合适的作用域调用 start().join() 阻塞直至连接关闭
 * - 边缘情况：systemPrompt 为 null 时不注入系统消息；agentCapabilities 默认无特殊能力
 * - 优点：一行代码完成 ACP Agent 启动准备；与 AcpAgentServer 生命周期管理一致
 *
 * @receiver RAC 实例，作为 Agent 的 AI 引擎
 * @param agentInfo Agent 实现信息，默认 name="rac-agent"、version="0.2.0"
 * @param agentCapabilities Agent 能力声明，默认无特殊能力
 * @param systemPrompt 系统提示词，每次 chat 调用时注入；null 表示不注入
 * @return 已配置但尚未启动的 AcpAgentServer（调用方需调用 start()）
 * @throws UnsupportedOperationException 当前平台不支持 stdio 服务端传输时抛出
 */
@Throws(UnsupportedOperationException::class)
fun RAC.serveAsAcpAgent(
    agentInfo: ImplementationInfo = ImplementationInfo(
        name = "rac-agent",
        title = "RAC Agent",
        version = "0.2.0",
    ),
    agentCapabilities: AgentCapabilities = AgentCapabilities(),
    systemPrompt: String? = null,
): AcpAgentServer {
    val handler = RacAcpAgent(
        rac = this,
        agentInfo = agentInfo,
        agentCapabilities = agentCapabilities,
        systemPrompt = systemPrompt,
    )
    return AcpAgentServer(handler)
}

/**
 * ACP StopReason 到 RAC FinishReason 的映射（文件私有）。
 *
 * - END_TURN → STOP（正常完成）
 * - MAX_TOKENS → LENGTH（达到 token 上限）
 * - MAX_TURN_REQUESTS → LENGTH（达到单轮请求上限，最接近 LENGTH 语义）
 * - REFUSAL → CONTENT_FILTER（Agent 拒绝，映射为内容过滤）
 * - CANCELLED → STOP（用户取消，FinishReason 无对应值，默认 STOP）
 */
private fun StopReason.toFinishReason(): FinishReason = when (this) {
    StopReason.END_TURN -> FinishReason.STOP
    StopReason.MAX_TOKENS -> FinishReason.LENGTH
    StopReason.MAX_TURN_REQUESTS -> FinishReason.LENGTH
    StopReason.REFUSAL -> FinishReason.CONTENT_FILTER
    StopReason.CANCELLED -> FinishReason.STOP
}
