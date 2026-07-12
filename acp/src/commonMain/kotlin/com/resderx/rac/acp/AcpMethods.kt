package com.resderx.rac.acp

import kotlinx.serialization.Serializable

/**
 * ACP v1 方法的请求与响应数据模型。
 *
 * - 作用：定义 `initialize`、`session/new`、`session/load`、`session/prompt`、
 *   `session/cancel`、`session/request_permission` 等方法的参数与返回结构
 * - 必要性：ACP JSON-RPC 消息的 params 与 result 需遵循规范字段名，集中定义避免散落
 * - 设计思路：每个方法一对数据类（Params + Result），字段名与 ACP v1 规范完全对齐；
 *   可选字段用可空类型 + 默认值
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/
 *
 * 方法清单：
 * - initialize: [InitializeParams] / [InitializeResult]
 * - session/new: [SessionNewParams] / [SessionNewResult]
 * - session/load: [SessionLoadParams] / [SessionLoadResult]
 * - session/prompt: [SessionPromptParams] / [SessionPromptResult]
 * - session/cancel: [SessionCancelParams]（通知，无响应）
 * - session/request_permission: [RequestPermissionParams] / [RequestPermissionResult]
 */

// ==================== initialize ====================

/** `initialize` 请求参数。 */
@Serializable
data class InitializeParams(
    val protocolVersion: Int,
    val clientCapabilities: ClientCapabilities? = null,
    val clientInfo: ImplementationInfo? = null,
)

/** `initialize` 响应结果。 */
@Serializable
data class InitializeResult(
    val protocolVersion: Int,
    val agentCapabilities: AgentCapabilities? = null,
    val agentInfo: ImplementationInfo? = null,
    val authMethods: List<AuthMethod> = emptyList(),
)

/** 认证方法描述。 */
@Serializable
data class AuthMethod(
    val id: String,
    val title: String? = null,
    val description: String? = null,
)

// ==================== session/new ====================

/** `session/new` 请求参数。 */
@Serializable
data class SessionNewParams(
    val cwd: String,
    val mcpServers: List<AcpMcpServerConfig> = emptyList(),
    val additionalDirectories: List<String> = emptyList(),
)

/** `session/new` 响应结果。 */
@Serializable
data class SessionNewResult(
    val sessionId: String,
)

// ==================== session/load ====================

/** `session/load` 请求参数（需 `loadSession` 能力）。 */
@Serializable
data class SessionLoadParams(
    val sessionId: String,
    val cwd: String,
    val mcpServers: List<AcpMcpServerConfig> = emptyList(),
    val additionalDirectories: List<String> = emptyList(),
)

/** `session/load` 响应结果（重放完成后返回空）。 */
@Serializable
data class SessionLoadResult(
    val result: Unit = Unit,
)

// ==================== session/prompt ====================

/** `session/prompt` 请求参数。 */
@Serializable
data class SessionPromptParams(
    val sessionId: String,
    val prompt: List<AcpContentBlock>,
)

/** `session/prompt` 响应结果。 */
@Serializable
data class SessionPromptResult(
    val stopReason: StopReason,
)

// ==================== session/cancel ====================

/** `session/cancel` 通知参数（无响应）。 */
@Serializable
data class SessionCancelParams(
    val sessionId: String,
)

// ==================== session/request_permission ====================

/** `session/request_permission` 请求参数（Agent → Client）。 */
@Serializable
data class RequestPermissionParams(
    val sessionId: String,
    val permission: PermissionRequest,
)

/** 权限请求内容。 */
@Serializable
data class PermissionRequest(
    val type: String,
    val title: String? = null,
    val details: String? = null,
    val options: List<PermissionOption> = emptyList(),
)

/** 权限选项。 */
@Serializable
data class PermissionOption(
    val id: String,
    val title: String? = null,
    val isDefault: Boolean? = null,
)

/** `session/request_permission` 响应结果。 */
@Serializable
data class RequestPermissionResult(
    val outcome: PermissionOutcome,
)

/** 权限请求结果。 */
@Serializable
data class PermissionOutcome(
    val selected: String? = null,
    val reason: String? = null,
)

/** 权限结果标识常量。 */
object PermissionOutcomeValue {
    /** 允许 */
    const val ALLOW = "allow"
    /** 拒绝 */
    const val DENY = "deny"
    /** 取消 */
    const val CANCELLED = "cancelled"
}

// ==================== session/update 通知 ====================

/** `session/update` 通知参数（Agent → Client，无响应）。 */
@Serializable
data class SessionUpdateParams(
    val sessionId: String,
    val update: SessionUpdate,
)
