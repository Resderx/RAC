package com.resderx.rac.acp

/**
 * ACP 传输方式配置，密封接口定义可用的传输机制。
 *
 * - 作用：为 [AcpClient] 与 [AcpAgentServer] 提供传输层配置，封装连接参数
 * - 必要性：ACP v1 支持 stdio（本地子进程）传输，Streamable HTTP 仍为草案；
 *   统一接口便于扩展新传输方式
 * - 设计思路：密封接口限制子类型为已知集合，编译期穷尽匹配
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/transports
 *
 * 子类型：
 * - [AcpStdioTransport]：stdio 传输（本地子进程，所有平台基线支持）
 * - [AcpHttpTransport]：HTTP 传输（草案，全平台）
 */
sealed interface AcpTransport {
    /** 请求超时毫秒数，0 表示无超时。 */
    val timeoutMillis: Long
}

/**
 * stdio 传输配置（本地子进程，ACP v1 基线传输）。
 *
 * - 作用：通过子进程的 stdin/stdout 交换换行分隔的 JSON-RPC 消息
 * - 平台支持：JVM 完整实现；其他平台抛 [UnsupportedOperationException]
 *
 * @property command 可执行文件路径
 * @property args 命令行参数
 * @property env 环境变量映射
 * @property workingDir 工作目录，可空
 * @property timeoutMillis 请求超时毫秒数，默认 60000（60 秒）
 */
data class AcpStdioTransport(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
    override val timeoutMillis: Long = 60_000L,
) : AcpTransport

/**
 * HTTP 传输配置（草案阶段）。
 *
 * - 作用：通过 HTTP 交换 JSON-RPC 消息，适用于远程 Agent
 * - 状态：ACP v1 的 Streamable HTTP 规范仍在草案，本配置为预留
 *
 * @property serverUrl 服务器 URL
 * @property headers HTTP 头映射
 * @property timeoutMillis 请求超时毫秒数
 */
data class AcpHttpTransport(
    val serverUrl: String,
    val headers: Map<String, String> = emptyMap(),
    override val timeoutMillis: Long = 60_000L,
) : AcpTransport
