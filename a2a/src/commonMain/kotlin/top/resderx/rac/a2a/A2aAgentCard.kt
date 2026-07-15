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

package top.resderx.rac.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A2A v1.0 Agent 发现模型——Agent Card 及其关联类型。
 *
 * - 作用：定义 A2A Server 发布的元数据文档结构，描述 Agent 身份、能力、技能、服务端点与鉴权要求
 * - 必要性：A2A Client 通过 Agent Card 发现远端 Agent 并协商交互方式；
 *   规范定义在 `/.well-known/agent.json` 路径下发布
 * - 设计思路：所有数据类标注 `@Serializable`，字段名与 A2A v1.0 规范 camelCase 对齐
 * - 规范来源：https://a2a-protocol.org/latest/specification/#8-agent-discovery-the-agent-card
 *
 * 文件内容：
 * - [top.resderx.rac.a2a.AgentCard]：Agent Card 主文档
 * - [top.resderx.rac.a2a.AgentProvider]：Agent 提供方信息
 * - [top.resderx.rac.a2a.AgentCapabilities]：Agent 能力声明（流式、推送通知等）
 * - [top.resderx.rac.a2a.AgentSkill]：Agent 技能描述
 * - [top.resderx.rac.a2a.AgentInterface]：Agent 服务接口（协议 + URL）
 * - [top.resderx.rac.a2a.AgentExtension]：Agent 扩展声明
 * - [top.resderx.rac.a2a.SecurityScheme] / [top.resderx.rac.a2a.APIKeySecurityScheme]：鉴权方案
 */

/**
 * Agent Card 主文档，A2A Server 的元数据描述。
 *
 * - 作用：让 Client 发现并了解 Agent 的能力、技能、端点与鉴权方式
 * - 边缘：`version` 为协议版本（如 "1.0.0"）；`description` 可空；`securitySchemes` 与 `security` 配合使用
 *
 * @property protocolVersion A2A 协议版本字符串
 * @property name Agent 名称
 * @property description Agent 描述，可空
 * @property url Agent 服务端点 URL
 * @property version Agent 实现版本
 * @property provider Agent 提供方，可空
 * @property capabilities Agent 能力声明
 * @property skills Agent 技能列表，默认空
 * @property defaultInputModes 默认接受的输入 MIME 类型，默认空
 * @property defaultOutputModes 默认输出的 MIME 类型，默认空
 * @property securitySchemes 鉴权方案映射，可空
 * @property security 应用的鉴权方案引用列表，可空
 * @property supportsAuthenticatedExtendedAgentCard 是否支持认证后的扩展 Agent Card
 */
@Serializable
data class AgentCard(
    val protocolVersion: String = "1.0.0",
    val name: String,
    val description: String? = null,
    val url: String,
    val version: String = "1.0.0",
    val provider: AgentProvider? = null,
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val skills: List<AgentSkill> = emptyList(),
    val defaultInputModes: List<String> = emptyList(),
    val defaultOutputModes: List<String> = emptyList(),
    val securitySchemes: Map<String, SecurityScheme>? = null,
    val security: List<Map<String, List<String>>>? = null,
    val supportsAuthenticatedExtendedAgentCard: Boolean? = null,
)

/**
 * Agent 提供方信息。
 *
 * @property organization 组织名称
 * @property url 组织 URL，可空
 */
@Serializable
data class AgentProvider(
    val organization: String,
    val url: String? = null,
)

/**
 * Agent 能力声明。
 *
 * @property streaming 是否支持流式更新（SSE）
 * @property pushNotifications 是否支持推送通知
 * @property stateTransitionUpdate 是否在状态转换时推送更新
 */
@Serializable
data class AgentCapabilities(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
    val stateTransitionUpdate: Boolean = false,
)

/**
 * Agent 技能描述。
 *
 * @property id 技能唯一标识
 * @property name 技能名称
 * @property description 技能描述，可空
 * @property tags 技能标签列表，默认空
 * @property examples 示例输入列表，默认空
 * @property inputModes 技能接受的输入 MIME 类型，可空
 * @property outputModes 技能输出的 MIME 类型，可空
 */
@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val inputModes: List<String>? = null,
    val outputModes: List<String>? = null,
)

/**
 * Agent 服务接口声明。
 *
 * @property protocol 协议类型（如 "json-rpc-2.0"、"grpc"、"http+json"）
 * @property url 该协议的服务端点 URL
 * @property tenant 租户标识，可空
 */
@Serializable
data class AgentInterface(
    val protocol: String,
    val url: String,
    val tenant: String? = null,
)

/**
 * Agent 扩展声明。
 *
 * @property uri 扩展命名空间 URI
 * @property description 扩展描述，可空
 * @property required 是否必须支持，默认 false
 */
@Serializable
data class AgentExtension(
    val uri: String,
    val description: String? = null,
    val required: Boolean = false,
)

/**
 * 鉴权方案密封接口，支持多种鉴权方式。
 *
 * - 当前仅实现 [top.resderx.rac.a2a.APIKeySecurityScheme]；OAuth 等可后续扩展
 */
@Serializable
sealed interface SecurityScheme

/**
 * API Key 鉴权方案。
 *
 * @property name API Key 的 header 或 query 参数名
 * @property location Key 位置（"header" 或 "query"）
 */
@Serializable
@SerialName("apiKey")
data class APIKeySecurityScheme(
    val name: String,
    val location: String = "header",
) : top.resderx.rac.a2a.SecurityScheme
