@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.resderx.rac.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * ACP v1 MCP 服务器配置模型，用于 `session/new` 的 `mcpServers` 字段。
 *
 * - 作用：定义 Client 传递给 Agent 的 MCP 服务器连接配置（stdio/http/sse 三种变体）
 * - 必要性：ACP 协议复用 MCP 服务器配置格式，Agent 据此连接外部 MCP 服务器获取工具
 * - 设计思路：密封接口 + `type` 鉴别字段；stdio 变体无 `type` 字段（规范约定），
 *   http/sse 变体通过 `type` 字段区分
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/session-setup
 * - 边缘：所有 Agent MUST 支持 stdio；http 需 [McpCapabilities.http]；sse 需 [McpCapabilities.sse]
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface AcpMcpServerConfig {
    /** 服务器名称，人类可读标识。 */
    val name: String
}

/**
 * stdio 传输的 MCP 服务器配置（所有 Agent MUST 支持）。
 *
 * @property name 服务器名称
 * @property command 可执行文件绝对路径
 * @property args 命令行参数列表
 * @property env 环境变量列表，可空
 */
@Serializable
@SerialName("stdio")
data class AcpStdioMcpConfig(
    override val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: List<AcpEnvVariable> = emptyList(),
) : AcpMcpServerConfig

/**
 * HTTP 传输的 MCP 服务器配置（需 [McpCapabilities.http]）。
 *
 * @property name 服务器名称
 * @property url 服务器 URL
 * @property headers HTTP 头列表
 */
@Serializable
@SerialName("http")
data class AcpHttpMcpConfig(
    override val name: String,
    val url: String,
    val headers: List<AcpHttpHeader> = emptyList(),
) : AcpMcpServerConfig

/**
 * SSE 传输的 MCP 服务器配置（需 [McpCapabilities.sse]，已被 MCP 规范弃用）。
 *
 * @property name 服务器名称
 * @property url SSE 端点 URL
 * @property headers HTTP 头列表
 */
@Serializable
@SerialName("sse")
data class AcpSseMcpConfig(
    override val name: String,
    val url: String,
    val headers: List<AcpHttpHeader> = emptyList(),
) : AcpMcpServerConfig

/** 环境变量。 */
@Serializable
data class AcpEnvVariable(
    val name: String,
    val value: String,
)

/** HTTP 头。 */
@Serializable
data class AcpHttpHeader(
    val name: String,
    val value: String,
)
