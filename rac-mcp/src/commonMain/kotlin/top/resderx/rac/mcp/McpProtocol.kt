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

package top.resderx.rac.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP 协议特定的请求参数与响应结果模型。
 *
 * - 作用：定义 MCP 协议中 tools/list、tools/call、resources/list、resources/read、
 *   initialize 等方法的参数与结果结构，供 [DefaultMcpClient] 序列化/反序列化 JSON-RPC 消息体
 * - 必要性：MCP 协议在 JSON-RPC 之上定义了特定字段结构（如 inputSchema、content 数组），
 *   需专用模型映射以避免手动 JSON 解析
 * - 设计思路：每个方法的结果为一个 @Serializable data class，使用 @SerialName 映射 snake_case；
 *   可选字段提供默认值以兼容不同服务器实现
 * - 边缘：服务器可能省略可选字段（如 description、mimeType），反序列化时需 ignoreUnknownKeys=true
 * - 优点：类型安全、自动序列化、字段名映射集中管理
 * - 时间复杂度：序列化/反序列化 O(n)，n 为 JSON 节点数
 * - 空间复杂度：O(n)
 */

// ==================== Initialize ====================

/**
 * initialize 请求参数。
 *
 * @property protocolVersion MCP 协议版本，默认 "2024-11-05"
 * @property capabilities 客户端能力声明（JSON 元素，通常为空对象）
 * @property clientInfo 客户端信息（名称+版本）
 */
@Serializable
data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: JsonElement? = null,
    val clientInfo: McpClientInfo = McpClientInfo(),
)

/**
 * 客户端信息。
 *
 * @property name 客户端名称
 * @property version 客户端版本
 */
@Serializable
data class McpClientInfo(
    val name: String = "rac",
    val version: String = "0.2.0",
)

/**
 * initialize 响应结果。
 *
 * @property protocolVersion 服务器支持的协议版本
 * @property capabilities 服务器能力声明
 * @property serverInfo 服务器信息
 */
@Serializable
data class McpInitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: JsonElement? = null,
    val serverInfo: McpServerInfo? = null,
)

/**
 * 服务器信息。
 *
 * @property name 服务器名称
 * @property version 服务器版本
 */
@Serializable
data class McpServerInfo(
    val name: String? = null,
    val version: String? = null,
)

// ==================== Tools ====================

/**
 * tools/list 响应结果。
 *
 * @property tools 工具信息列表
 */
@Serializable
data class McpToolListResult(
    val tools: List<McpToolInfo> = emptyList(),
)

/**
 * MCP 工具信息（服务器端格式）。
 *
 * - 作用：映射 MCP 服务器返回的工具定义，后续转换为 RAC 的 [top.resderx.rac.messages.ToolDefinition]
 * - 边缘：description 可为 null；inputSchema 为 JSON Schema 对象
 *
 * @property name 工具名称
 * @property description 工具描述；可为 null
 * @property inputSchema 工具参数的 JSON Schema，JsonElement 类型
 */
@Serializable
data class McpToolInfo(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)

/**
 * tools/call 请求参数。
 *
 * @property name 工具名称
 * @property arguments 工具参数，JSON 元素（通常为 JsonObject）
 */
@Serializable
data class McpToolCallParams(
    val name: String,
    val arguments: JsonElement? = null,
)

/**
 * tools/call 响应结果。
 *
 * @property content 工具执行返回的内容块列表
 * @property isError 是否为错误结果；true 表示工具执行出错但返回了错误信息而非抛异常
 */
@Serializable
data class McpToolCallResult(
    val content: List<McpContent> = emptyList(),
    val isError: Boolean? = null,
)

/**
 * MCP 内容块（工具调用结果或资源内容的基本单元）。
 *
 * - 作用：统一表示文本、图片等不同类型的内容
 * - 边缘：text 字段仅 type="text" 时有值；其他类型（image、resource）可能携带不同字段
 *
 * @property type 内容类型（"text"、"image"、"resource" 等）
 * @property text 文本内容；仅 type="text" 时有值
 */
@Serializable
data class McpContent(
    val type: String = "text",
    val text: String? = null,
)

// ==================== Resources ====================

/**
 * resources/list 响应结果。
 *
 * @property resources 资源列表
 */
@Serializable
data class McpResourceListResult(
    val resources: List<McpResourceInfo> = emptyList(),
)

/**
 * MCP 资源信息（服务器端格式，映射为 [McpResource]）。
 *
 * @property uri 资源 URI
 * @property name 资源名称
 * @property description 资源描述；可为 null
 * @property mimeType MIME 类型；可为 null
 */
@Serializable
data class McpResourceInfo(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)

/**
 * resources/read 请求参数。
 *
 * @property uri 资源 URI
 */
@Serializable
data class McpResourceReadParams(
    val uri: String,
)

/**
 * resources/read 响应结果。
 *
 * @property contents 资源内容块列表
 */
@Serializable
data class McpResourceReadResult(
    val contents: List<McpResourceContent> = emptyList(),
)

/**
 * MCP 资源内容块。
 *
 * @property uri 资源 URI
 * @property text 文本内容；可为 null（二进制资源使用 blob 字段，此处暂不处理）
 * @property mimeType MIME 类型；可为 null
 */
@Serializable
data class McpResourceContent(
    val uri: String? = null,
    val text: String? = null,
    val mimeType: String? = null,
)
