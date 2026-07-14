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

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package top.resderx.rac.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * ACP v1 会话更新模型，定义 `session/update` 通知的负载结构。
 *
 * - 作用：描述 Agent 向 Client 推送的各类会话更新（计划/消息块/工具调用/用量）
 * - 必要性：`session/update` 是 ACP 的核心通知，Agent 通过它流式推送轮次中的所有进度
 * - 设计思路：密封接口 + `@JsonClassDiscriminator("sessionUpdate")` 多态序列化，
 *   子类型用 `@SerialName` 标记具体的更新类型
 * - 注意：不声明 `sessionUpdate` 属性——`@JsonClassDiscriminator("sessionUpdate")` 会自动在 JSON 中
 *   写入/读取鉴别字段，子类的 `@SerialName` 值即为鉴别字段值；若子类再声明 `sessionUpdate` 属性
 *   会与鉴别器冲突，导致序列化异常
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/prompt-turn
 *
 * 文件内容：
 * - [SessionUpdate]：会话更新密封接口
 * - [PlanUpdate]：Agent 计划更新
 * - [PlanEntry]：计划项
 * - [AgentMessageChunk]：Agent 消息块
 * - [UserMessageChunk]：用户消息块（重放历史时用）
 * - [ToolCallUpdate]：工具调用开始/状态更新
 * - [UsageUpdate]：会话用量与成本更新
 * - [ToolCallStatus]：工具调用状态枚举
 * - [ToolCallKind]：工具调用类型枚举
 */

/**
 * 会话更新密封接口，由 `sessionUpdate` 鉴别字段区分子类型。
 *
 * - 序列化后 JSON 形如 `{"sessionUpdate": "agent_message_chunk", "content": {...}, ...}`，
 *   其中 `"sessionUpdate": "agent_message_chunk"` 由 `@SerialName("agent_message_chunk")` +
 *   `@JsonClassDiscriminator("sessionUpdate")` 自动生成
 */
@Serializable
@JsonClassDiscriminator("sessionUpdate")
sealed interface SessionUpdate

/**
 * Agent 计划更新，推送 Agent 的工作计划。
 *
 * @property entries 计划项列表
 */
@Serializable
@SerialName("plan")
data class PlanUpdate(
    val entries: List<PlanEntry> = emptyList(),
) : SessionUpdate

/** 计划项。 */
@Serializable
data class PlanEntry(
    val content: String,
    val priority: String? = null,
    val status: String? = null,
)

/**
 * Agent 消息块，推送 Agent 的增量消息内容。
 *
 * @property messageId 消息 ID，相同 ID 的块属于同一消息；可空
 * @property content 内容块
 */
@Serializable
@SerialName("agent_message_chunk")
data class AgentMessageChunk(
    val messageId: String? = null,
    val content: AcpContentBlock,
) : SessionUpdate

/**
 * 用户消息块，重放历史会话时推送用户消息。
 *
 * @property messageId 消息 ID，可空
 * @property content 内容块
 */
@Serializable
@SerialName("user_message_chunk")
data class UserMessageChunk(
    val messageId: String? = null,
    val content: AcpContentBlock,
) : SessionUpdate

/**
 * 工具调用更新，推送工具调用的开始或状态变化。
 *
 * - 作用：通知 Client 工具调用的生命周期（pending → in_progress → completed/cancelled）
 * - 设计：`status=pending` 时为工具调用开始；其他状态为更新
 * - 边缘：`content` 仅在 completed 状态时携带工具输出
 *
 * @property toolCallId 工具调用唯一标识
 * @property title 工具调用标题
 * @property kind 工具类型
 * @property status 工具调用状态
 * @property content 工具输出内容块列表，可空
 */
@Serializable
@SerialName("tool_call")
data class ToolCallUpdate(
    val toolCallId: String,
    val title: String? = null,
    val kind: ToolCallKind = ToolCallKind.OTHER,
    val status: ToolCallStatus,
    val content: List<AcpContentBlock>? = null,
) : SessionUpdate

/** 工具调用状态枚举。 */
@Serializable
enum class ToolCallStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

/** 工具调用类型枚举。 */
@Serializable
enum class ToolCallKind {
    @SerialName("read_file") READ_FILE,
    @SerialName("edit_file") EDIT_FILE,
    @SerialName("write_file") WRITE_FILE,
    @SerialName("execute") EXECUTE,
    @SerialName("other") OTHER,
}

/**
 * 会话用量更新，推送当前会话的 token 用量与成本。
 *
 * @property used 当前会话上下文已用 token 数
 * @property size 会话上下文总大小（token）
 * @property cost 成本信息，可空
 */
@Serializable
@SerialName("usage_update")
data class UsageUpdate(
    val used: Long,
    val size: Long,
    val cost: UsageCost? = null,
) : SessionUpdate

/** 用量成本。 */
@Serializable
data class UsageCost(
    val amount: Double,
    val currency: String,
)
