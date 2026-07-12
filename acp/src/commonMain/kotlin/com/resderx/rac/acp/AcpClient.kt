package com.resderx.rac.acp

/**
 * ACP 客户端接口，定义与 ACP Agent 交互的统一契约。
 *
 * - 作用：抽象 ACP 协议的初始化握手、会话管理、提示轮次与取消等核心操作，
 *   为 RAC 调用远程 ACP Agent 提供统一入口
 * - 必要性：ACP（Agent Client Protocol）是 Zed + JetBrains 联合推出的标准化 AI 编码助手通信协议，
 *   基于双向 JSON-RPC 2.0；RAC 作为 AI 调用库需原生支持 ACP Client 角色，
 *   使调用方无需关心底层 stdio/HTTP 传输与 JSON-RPC 协议细节
 * - 设计思路：
 *   1. 生命周期：initialize（握手）→ sessionNew（创建会话）→ sessionPrompt（提示轮次）→ close
 *   2. sessionPrompt 采用回调式流式更新：Agent 通过 session/update 通知推送进度，
 *      Client 通过 onUpdate 回调接收，最终返回 StopReason 标识轮次结束
 *   3. 双向请求支持：Agent 可向 Client 发起 session/request_permission 请求，
 *      由 [AcpClientConfig.permissionHandler] 处理
 *   4. 面向接口编程：由 [DefaultAcpClient] 提供默认实现，便于测试时替换 mock
 * - 实现方式：interface，由 [DefaultAcpClient] 实现
 * - 可能的问题：sessionPrompt 为 suspend，长时间轮次会阻塞协程；调用方需在合适作用域调用
 * - 边缘情况：
 *   - 未调用 initialize 即调用其他方法抛 IllegalStateException
 *   - sessionPrompt 并发调用由内部 Mutex 串行化（ACP 规范限制每会话单轮次）
 *   - close 幂等，多次调用不报错
 * - 优点：接口最小化，覆盖 ACP v1 核心生命周期；回调式流式更新符合 Kotlin 惯例
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/
 */
interface AcpClient {

    /**
     * ACP 协议握手，协商协议版本与双方能力。
     *
     * - 作用：建立连接后首次调用，协商协议版本、交换 clientInfo/agentInfo 与能力声明
     * - 实现：发送 `initialize` JSON-RPC 请求，返回 Agent 的能力与信息
     * - 必须在其他方法之前调用
     *
     * @return Agent 的初始化响应（协议版本、能力、信息、认证方法）
     * @throws com.resderx.rac.exceptions.RACException 握手失败或 Agent 返回错误
     */
    suspend fun initialize(): InitializeResult

    /**
     * 创建新会话。
     *
     * - 作用：在 Agent 侧创建新会话，指定工作目录与 MCP 服务器配置
     * - 实现：发送 `session/new` JSON-RPC 请求，返回会话 ID
     *
     * @param cwd 当前工作目录（Agent 在此目录下执行文件操作）
     * @param mcpServers MCP 服务器配置列表，Agent 将连接这些服务器获取工具
     * @param additionalDirectories 额外可访问目录列表
     * @return 会话创建结果（含 sessionId）
     * @throws com.resderx.rac.exceptions.RACException 创建失败
     */
    suspend fun sessionNew(
        cwd: String,
        mcpServers: List<AcpMcpServerConfig> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
    ): SessionNewResult

    /**
     * 加载已有会话（需 Agent 声明 loadSession 能力）。
     *
     * - 作用：恢复之前创建的会话，Agent 会通过 session/update 重放历史消息
     * - 实现：发送 `session/load` JSON-RPC 请求；重放期间的 session/update 通知
     *   通过 onUpdate 回调推送
     *
     * @param sessionId 已有会话 ID
     * @param cwd 当前工作目录
     * @param mcpServers MCP 服务器配置列表
     * @param additionalDirectories 额外可访问目录列表
     * @param onUpdate 会话重放更新回调（接收历史消息块等）
     * @return 会话加载结果
     * @throws com.resderx.rac.exceptions.RACException 加载失败或 Agent 不支持 loadSession
     */
    suspend fun sessionLoad(
        sessionId: String,
        cwd: String,
        mcpServers: List<AcpMcpServerConfig> = emptyList(),
        additionalDirectories: List<String> = emptyList(),
        onUpdate: suspend (SessionUpdate) -> Unit = {},
    ): SessionLoadResult

    /**
     * 发送提示并执行一轮 Agent 对话。
     *
     * - 作用：向 Agent 发送用户提示，Agent 执行工具调用、生成回复，
     *   通过 session/update 通知流式推送进度，最终返回停止原因
     * - 实现：发送 `session/prompt` JSON-RPC 请求，同时收集 Agent 的 session/update 通知
     *   转发给 onUpdate 回调；处理 Agent 的 session/request_permission 请求；
     *   收到 session/prompt 响应后返回 StopReason
     * - 并发：ACP 规范限制每会话同时只能有一个活跃轮次，内部 Mutex 串行化
     *
     * @param sessionId 会话 ID（由 sessionNew 或 sessionLoad 返回）
     * @param prompt 提示内容块列表（文本/图片/资源链接等）
     * @param onUpdate 会话更新回调，接收 Agent 推送的进度更新
     * @return 轮次停止原因（end_turn/max_tokens/refusal/cancelled 等）
     * @throws com.resderx.rac.exceptions.RACException 请求失败或 Agent 返回错误
     */
    suspend fun sessionPrompt(
        sessionId: String,
        prompt: List<AcpContentBlock>,
        onUpdate: suspend (SessionUpdate) -> Unit = {},
    ): StopReason

    /**
     * 取消当前进行中的轮次。
     *
     * - 作用：通知 Agent 取消正在执行的 session/prompt 轮次
     * - 实现：发送 `session/cancel` JSON-RPC 通知（无响应）
     * - 边缘：如果当前无活跃轮次，Agent 应忽略此通知
     *
     * @param sessionId 会话 ID
     */
    suspend fun sessionCancel(sessionId: String)

    /**
     * 关闭客户端连接并释放资源。
     *
     * - 作用：终止与 Agent 的连接，关闭底层传输（子进程/HTTP 连接）
     * - 边缘：幂等操作，多次调用不报错；关闭后调用其他方法抛异常
     */
    suspend fun close()
}

/**
 * ACP 客户端配置。
 *
 * - 作用：封装 AcpClient 的传输配置、客户端信息、能力声明与回调处理器
 * - 设计：所有可选字段提供合理默认值，调用方按需覆盖
 *
 * @property transport ACP 传输配置（stdio 或 HTTP）
 * @property clientInfo 客户端实现信息，默认 name="rac"、version="0.2.0"
 * @property clientCapabilities 客户端能力声明，默认无文件系统/终端能力
 * @property protocolVersion 协议版本，默认 1（ACP v1）
 * @property permissionHandler 权限请求处理器，Agent 发起 session/request_permission 时调用；
 *   默认拒绝所有权限请求（返回 deny）
 */
data class AcpClientConfig(
    val transport: AcpTransport,
    val clientInfo: ImplementationInfo = ImplementationInfo(name = "rac", version = "0.2.0"),
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val protocolVersion: Int = 1,
    val permissionHandler: suspend (PermissionRequest) -> PermissionOutcome = {
        PermissionOutcome(selected = PermissionOutcomeValue.DENY)
    },
)

/**
 * 创建 ACP 客户端的顶层工厂函数。
 *
 * - 作用：提供简洁的 ACP 客户端创建入口，封装 DefaultAcpClient 构造
 * - 用法：`val client = AcpClient(AcpClientConfig(transport = AcpStdioTransport(command = "agent")))`
 *
 * @param config ACP 客户端配置
 * @return DefaultAcpClient 实例
 */
fun AcpClient(config: AcpClientConfig): AcpClient = DefaultAcpClient(config)
