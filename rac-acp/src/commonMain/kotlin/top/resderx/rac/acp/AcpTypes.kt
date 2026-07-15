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

package top.resderx.rac.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ACP（Agent Client Protocol）v1 基础数据类型定义。
 *
 * - 作用：定义 ACP 协议中初始化握手、能力协商与轮次停止原因等基础数据模型，
 *   供 [AcpClient] 与 [AcpAgentServer] 在 JSON-RPC 消息序列化时复用
 * - 必要性：ACP 协议基于 JSON-RPC 2.0，消息体需遵循规范定义的字段名（camelCase）与结构；
 *   集中定义避免散落在各方法模型中
 * - 设计思路：所有数据类标注 `@Serializable`，字段名与 ACP v1 规范完全对齐；
 *   能力类（[ClientCapabilities]/[AgentCapabilities]）字段全部可选，省略视为不支持
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/initialization
 *
 * 文件内容：
 * - [ImplementationInfo]：实现信息（clientInfo/agentInfo 共用）
 * - [ClientCapabilities]：客户端能力声明
 * - [AgentCapabilities]：Agent 能力声明
 * - [PromptCapabilities]：提示支持的内容类型
 * - [McpCapabilities]：MCP 服务器连接能力
 * - [AgentAuthCapabilities]：认证相关能力
 * - [StopReason]：轮次停止原因枚举
 * - [AcpError]：ACP 协议错误码常量
 */

/**
 * 实现信息，用于 `initialize` 请求的 `clientInfo` 与响应的 `agentInfo`。
 *
 * - 作用：标识 Client/Agent 的名称、版本与显示标题，便于调试与日志
 * - 边缘：`title` 缺失时 UI 可回退到 `name`；`version` 为字符串，格式由实现决定
 *
 * @property name 程序化标识名（如 "rac"）
 * @property title 面向用户的显示名（如 "RAC Agent"），可空
 * @property version 实现版本号字符串
 */
@Serializable
data class ImplementationInfo(
    val name: String,
    val title: String? = null,
    val version: String = "0.1.0",
)

/**
 * 客户端能力声明，由 Client 在 `initialize` 请求中发送。
 *
 * - 作用：告知 Agent Client 支持哪些可选方法（文件系统/终端/会话配置）
 * - 设计：所有字段可选，省略视为不支持；`fs` 与 `terminal` 为布尔开关
 * - 边缘：`sessionConfigOptions.boolean` 为空对象 `{}` 表示支持 boolean 配置选项
 *
 * @property fs 文件系统能力
 * @property terminal 是否支持所有 terminal 命名空间下的方法
 * @property sessionConfigOptions 会话配置选项能力
 */
@Serializable
data class ClientCapabilities(
    val fs: ClientFsCapabilities? = null,
    val terminal: Boolean? = null,
    @SerialName("session.configOptions")
    val sessionConfigOptions: SessionConfigOptionsCapability? = null,
)

/** 客户端文件系统能力。 */
@Serializable
data class ClientFsCapabilities(
    @SerialName("readTextFile") val readTextFile: Boolean? = null,
    @SerialName("writeTextFile") val writeTextFile: Boolean? = null,
)

/** 会话配置选项能力（当前仅 boolean 类型）。 */
@Serializable
data class SessionConfigOptionsCapability(
    val boolean: Boolean? = null,
)

/**
 * Agent 能力声明，由 Agent 在 `initialize` 响应中返回。
 *
 * - 作用：告知 Client Agent 支持哪些可选方法（会话加载/恢复/关闭/删除等）
 * - 设计：所有字段可选，省略视为不支持；使用空对象标记支持某能力（如 `sessionCapabilities.delete = {}`）
 *
 * @property loadSession 是否支持 `session/load`
 * @property promptCapabilities 提示支持的内容类型
 * @property mcpCapabilities MCP 服务器连接能力
 * @property auth 认证能力
 * @property sessionCapabilities 会话级能力（delete/additionalDirectories/resume/close）
 */
@Serializable
data class AgentCapabilities(
    val loadSession: Boolean? = null,
    val promptCapabilities: PromptCapabilities? = null,
    val mcpCapabilities: McpCapabilities? = null,
    val auth: AgentAuthCapabilities? = null,
    val sessionCapabilities: AgentSessionCapabilities? = null,
)

/** 提示支持的内容类型能力。 */
@Serializable
data class PromptCapabilities(
    val image: Boolean? = null,
    val audio: Boolean? = null,
    val embeddedContext: Boolean? = null,
)

/** MCP 服务器连接能力。 */
@Serializable
data class McpCapabilities(
    val http: Boolean? = null,
    val sse: Boolean? = null,
)

/** Agent 认证能力。 */
@Serializable
data class AgentAuthCapabilities(
    val logout: AgentAuthLogoutCapability? = null,
)

/** 认证登出能力标记（空对象表示支持）。 */
@Serializable
data class AgentAuthLogoutCapability(
    val supported: Boolean = true,
)

/** Agent 会话级能力集合。 */
@Serializable
data class AgentSessionCapabilities(
    val delete: AgentSessionDeleteCapability? = null,
    val additionalDirectories: AgentSessionAdditionalDirsCapability? = null,
    val resume: AgentSessionResumeCapability? = null,
    val close: AgentSessionCloseCapability? = null,
)

/** 会话删除能力标记。 */
@Serializable
data class AgentSessionDeleteCapability(
    val supported: Boolean = true,
)

/** 额外目录能力标记。 */
@Serializable
data class AgentSessionAdditionalDirsCapability(
    val supported: Boolean = true,
)

/** 会话恢复能力标记。 */
@Serializable
data class AgentSessionResumeCapability(
    val supported: Boolean = true,
)

/** 会话关闭能力标记。 */
@Serializable
data class AgentSessionCloseCapability(
    val supported: Boolean = true,
)

/**
 * 轮次停止原因枚举。
 *
 * - 作用：标识一次 `session/prompt` 轮次为何结束，Client 据此更新 UI 状态
 * - 规范：序列化值与 ACP v1 规范对齐（snake_case）
 *
 * @property END_TURN LLM 完成响应且未请求更多工具
 * @property MAX_TOKENS 达到最大 token 限制
 * @property MAX_TURN_REQUESTS 单轮内模型请求次数超限
 * @property REFUSAL Agent 拒绝继续
 * @property CANCELLED Client 取消了轮次
 */
@Serializable
enum class StopReason {
    @SerialName("end_turn") END_TURN,
    @SerialName("max_tokens") MAX_TOKENS,
    @SerialName("max_turn_requests") MAX_TURN_REQUESTS,
    @SerialName("refusal") REFUSAL,
    @SerialName("cancelled") CANCELLED,
}

/**
 * ACP 协议错误码常量。
 *
 * - 作用：定义 ACP JSON-RPC 错误响应中使用的标准错误码，与 JSON-RPC 2.0 规范对齐
 * - 必要性：Agent 与 Client 需用一致的错误码标识失败原因，便于调用方按码处理
 */
object AcpError {
    /** 解析错误（-32700） */
    const val PARSE_ERROR = -32700
    /** 无效请求（-32600） */
    const val INVALID_REQUEST = -32600
    /** 方法不存在（-32601） */
    const val METHOD_NOT_FOUND = -32601
    /** 无效参数（-32602） */
    const val INVALID_PARAMS = -32602
    /** 内部错误（-32603） */
    const val INTERNAL_ERROR = -32603
    /** 会话不存在（实现定义，-32000 起） */
    const val SESSION_NOT_FOUND = -32000
    /** 认证失败 */
    const val AUTH_FAILED = -32001
    /** 权限被拒绝 */
    const val PERMISSION_DENIED = -32002
}
