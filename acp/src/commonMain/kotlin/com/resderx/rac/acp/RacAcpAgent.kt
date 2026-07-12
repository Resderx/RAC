package com.resderx.rac.acp

import com.resderx.rac.dsl.RAC
import com.resderx.rac.exceptions.RACException
import com.resderx.rac.messages.AudioContent
import com.resderx.rac.messages.AIMessage
import com.resderx.rac.messages.AssistantMessage
import com.resderx.rac.messages.Content
import com.resderx.rac.messages.FinishReason
import com.resderx.rac.messages.ImageContent
import com.resderx.rac.messages.Message
import com.resderx.rac.messages.SystemMessage
import com.resderx.rac.messages.TextContent
import com.resderx.rac.messages.ToolMessage
import com.resderx.rac.messages.UserMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * RAC 到 ACP Agent 的适配器，将 RAC 的 AI 调用能力暴露为 ACP Agent。
 *
 * - 作用：实现 [AcpAgentHandler]，将 ACP 协议的 session/prompt 请求映射为 RAC 的 [RAC.chat] 调用，
 *   使任何 ACP 兼容的 Client（如 Zed、JetBrains IDE）都能通过 ACP 调用 RAC 管理的 AI 供应商
 * - 必要性：RAC 库需要支持作为 ACP Agent 运行（Server 角色），与 Client 角色对称；
 *   RacAcpAgent 桥接 ACP 协议语义与 RAC 的 AI 调用语义
 * - 设计思路：
 *   1. 会话管理：sessions Map 维护每个 ACP 会话的对话历史（List<Message>），跨轮次保持上下文
 *   2. 内容转换：ACP ContentBlock → RAC Content（text/image/audio 直接映射，resource 转文本）
 *   3. 提示处理：将 ACP prompt 转为 RAC UserMessage → 追加到历史 → 调用 rac.chat → 推送 AgentMessageChunk
 *   4. 停止原因映射：RAC FinishReason → ACP StopReason
 *   5. 可选系统提示：构造时配置 systemPrompt，每次 chat 调用时注入
 * - 实现：
 *   - initialize：返回 agentInfo 与 agentCapabilities
 *   - sessionNew：生成 sessionId，创建空会话
 *   - sessionPrompt：转换 prompt → chat → 推送更新 → 返回 stopReason
 *   - sessionCancel：标记取消（当前为简化实现，依赖协程取消）
 *   - close：清空所有会话
 * - 边缘：
 *   - 会话不存在时抛 RACException（映射为 ACP SESSION_NOT_FOUND 错误）
 *   - 空响应内容时不推送 AgentMessageChunk
 *   - systemPrompt 为 null 时不注入系统消息
 *   - 资源链接块（AcpResourceLinkBlock）转为文本描述（Agent 需自行读取文件）
 * - 时间复杂度：sessionPrompt 为 O(H + N)，H 为历史消息数（replay），N 为响应解析
 * - 空间复杂度：O(S * H)，S 为会话数，H 为平均历史长度
 *
 * @property rac RAC 实例，提供 AI 调用能力
 * @property agentInfo Agent 实现信息
 * @property agentCapabilities Agent 能力声明
 * @property systemPrompt 系统提示词，每次 chat 调用时注入；null 表示不注入
 */
class RacAcpAgent(
    private val rac: RAC,
    private val agentInfo: ImplementationInfo = ImplementationInfo(
        name = "rac-agent",
        title = "RAC Agent",
        version = "0.2.0",
    ),
    private val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    private val systemPrompt: String? = null,
) : AcpAgentHandler {

    /**
     * ACP 会话内部状态，存储工作目录与对话历史。
     *
     * @property sessionId 会话唯一标识
     * @property cwd 工作目录
     * @property messages 对话历史列表（跨轮次累积）
     */
    private data class AcpSession(
        val sessionId: String,
        val cwd: String,
        val messages: MutableList<Message> = mutableListOf(),
    )

    /** 会话映射：sessionId → AcpSession。由 [sessionsMutex] 保护。 */
    private val sessions = mutableMapOf<String, AcpSession>()

    /** sessions 访问互斥锁。 */
    private val sessionsMutex = Mutex()

    /** 会话 ID 自增计数器。 */
    @Volatile
    private var sessionCounter = 0L

    /** 会话 ID 生成互斥锁。 */
    private val idMutex = Mutex()

    /** 当前活跃轮次的会话 ID（用于 sessionCancel）；null 表示无活跃轮次。 */
    @Volatile
    private var activeSessionId: String? = null

    /**
     * 生成唯一会话 ID。
     *
     * - 实现：自增计数器，格式 "session-{counter}"
     */
    private suspend fun generateSessionId(): String {
        val id = idMutex.withLock { ++sessionCounter }
        return "session-$id"
    }

    /** ACP 协议握手，返回 Agent 信息与能力。 */
    override suspend fun initialize(params: InitializeParams): InitializeResult {
        return InitializeResult(
            protocolVersion = 1,
            agentInfo = agentInfo,
            agentCapabilities = agentCapabilities,
        )
    }

    /** 创建新会话。 */
    override suspend fun sessionNew(params: SessionNewParams): SessionNewResult {
        val sessionId = generateSessionId()
        sessionsMutex.withLock {
            sessions[sessionId] = AcpSession(sessionId = sessionId, cwd = params.cwd)
        }
        return SessionNewResult(sessionId = sessionId)
    }

    /** 加载已有会话（当前为简化实现，返回空结果）。 */
    override suspend fun sessionLoad(
        params: SessionLoadParams,
        context: AcpAgentContext,
    ): SessionLoadResult {
        // 简化实现：当前不支持持久化会话恢复，返回空结果
        // 完整实现需从持久化存储加载历史并通过 context.sendUpdate 重放
        return SessionLoadResult()
    }

    /** 处理提示轮次，调用 RAC chat 并流式推送更新。 */
    override suspend fun sessionPrompt(
        params: SessionPromptParams,
        context: AcpAgentContext,
    ): SessionPromptResult {
        // 获取会话
        val session = sessionsMutex.withLock {
            sessions[params.sessionId]
                ?: throw RACException("Session not found: ${params.sessionId}")
        }

        activeSessionId = params.sessionId
        try {
            // 将 ACP 内容块列表转为文本（UserContentBuilder 当前仅支持文本）
            val userText = params.prompt.joinToString("\n") { it.toText() }
            if (userText.isBlank()) {
                return SessionPromptResult(stopReason = StopReason.END_TURN)
            }

            // 构建用户消息并追加到历史
            val userMessage = UserMessage(userText)
            session.messages.add(userMessage)

            // 调用 RAC chat，重放完整对话历史
            val response = rac.chat {
                // 注入系统提示（如果有）
                if (systemPrompt != null) {
                    system(systemPrompt)
                }
                // 重放对话历史
                session.messages.forEach { msg ->
                    when (msg) {
                        is SystemMessage -> system(msg.content)
                        is UserMessage -> {
                            // 提取所有 TextContent 拼接为文本
                            val text = msg.content
                                .filterIsInstance<TextContent>()
                                .joinToString("\n") { it.text }
                            if (text.isNotEmpty()) user(text)
                        }
                        is AssistantMessage -> assistant(msg.content ?: "")
                        is ToolMessage -> tool(msg.toolCallId, msg.content)
                    }
                }
            }

            // 推送 Agent 消息更新
            if (response.content.isNotEmpty()) {
                context.sendUpdate(
                    AgentMessageChunk(content = AcpTextBlock(text = response.content))
                )
            }

            // 推送用量更新（如果有）
            response.usage?.let { usage ->
                context.sendUpdate(
                    UsageUpdate(
                        used = usage.totalTokens,
                        size = usage.totalTokens,
                        cost = null,
                    )
                )
            }

            // 将 assistant 响应追加到历史
            session.messages.add(
                AssistantMessage(content = response.content, toolCalls = response.toolCalls)
            )

            // 映射停止原因
            return SessionPromptResult(stopReason = response.finishReason.toAcpStopReason())
        } finally {
            activeSessionId = null
        }
    }

    /** 取消当前轮次（简化实现）。 */
    override suspend fun sessionCancel(sessionId: String) {
        // 简化实现：仅清除活跃标记；完整实现需取消正在进行的 RAC chat 调用
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
    }

    /** 关闭 Agent，清空所有会话。 */
    override suspend fun close() {
        sessionsMutex.withLock {
            sessions.clear()
        }
    }

    /**
     * RAC FinishReason 到 ACP StopReason 的映射。
     *
     * - STOP → END_TURN（正常完成）
     * - LENGTH → MAX_TOKENS（达到 token 上限）
     * - CONTENT_FILTER → REFUSAL（内容被过滤）
     * - TOOL_CALLS → END_TURN（工具调用循环结束后视为正常完成）
     * - UNKNOWN → END_TURN（未知原因默认正常完成）
     */
    private fun FinishReason.toAcpStopReason(): StopReason = when (this) {
        FinishReason.STOP -> StopReason.END_TURN
        FinishReason.LENGTH -> StopReason.MAX_TOKENS
        FinishReason.CONTENT_FILTER -> StopReason.REFUSAL
        FinishReason.TOOL_CALLS -> StopReason.END_TURN
        FinishReason.UNKNOWN -> StopReason.END_TURN
    }
}

/**
 * ACP ContentBlock 到文本的转换扩展函数。
 *
 * - 作用：将 ACP 的 5 种内容块类型统一转为文本，供 RAC 的 UserContentBuilder 使用
 * - 转换规则：
 *   - AcpTextBlock → 原始文本
 *   - AcpImageBlock → "[Image: mimeType]" 占位文本
 *   - AcpAudioBlock → "[Audio: mimeType]" 占位文本
 *   - AcpResourceBlock → resource.text 或 "[Resource: uri]" 占位
 *   - AcpResourceLinkBlock → "[Resource: uri]" 文本描述
 * - 边缘：AcpResourceBlock 的 text 为 null 时使用 uri 占位
 */
private fun AcpContentBlock.toText(): String = when (this) {
    is AcpTextBlock -> text
    is AcpImageBlock -> "[Image: $mimeType]"
    is AcpAudioBlock -> "[Audio: $mimeType]"
    is AcpResourceBlock -> resource.text ?: "[Resource: ${resource.uri}]"
    is AcpResourceLinkBlock -> "[Resource: $uri${name?.let { " ($it)" } ?: ""}]"
}
