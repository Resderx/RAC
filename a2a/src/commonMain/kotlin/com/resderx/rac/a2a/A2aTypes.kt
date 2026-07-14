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

package com.resderx.rac.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A2A（Agent-to-Agent Protocol）v1.0 核心数据类型定义。
 *
 * - 作用：定义 A2A 协议中 Task 生命周期、Message 角色、任务状态等基础数据模型
 * - 必要性：A2A 协议基于 JSON-RPC 2.0，使用 Protocol Buffer 定义的 canonical data model，
 *   本文件提供 Kotlin 的 `@Serializable` 映射，服务于 [A2aClient] 与 [A2aAgentServer]
 * - 设计思路：所有数据类标注 `@Serializable`，字段名与 A2A v1.0 规范 camelCase 对齐
 * - 规范来源：https://a2a-protocol.org/latest/specification/#4-protocol-data-model
 * - 注意：枚举值使用 `@SerialName` 映射到 snake_case 的 JSON 形式
 *
 * 文件内容：
 * - [Task] / [TaskState] / [TaskStatus]：任务模型与生命周期状态
 * - [Message] / [Role]：消息模型
 * - [FilePartBody] / [DataPartBody]：Part 内嵌类型
 * - [A2aMetadata]：元数据键值对
 */

/** 任务角色枚举（user 或 agent）。 */
@Serializable
enum class Role {
    @SerialName("user") USER,
    @SerialName("agent") AGENT,
}

/** 任务生命周期状态枚举。 */
@Serializable
enum class TaskState {
    @SerialName("working") WORKING,
    @SerialName("input-required") INPUT_REQUIRED,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("canceled") CANCELED,
    @SerialName("rejected") REJECTED,
    @SerialName("auth-required") AUTH_REQUIRED,
}

/**
 * 任务状态快照，包含状态枚举、可选消息与时间戳。
 *
 * @property state 当前任务状态
 * @property message 状态附带的描述性消息，可空
 * @property timestamp ISO 8601 时间戳，可空
 */
@Serializable
data class TaskStatus(
    val state: TaskState,
    val message: String? = null,
    val timestamp: String? = null,
)

/**
 * A2A 任务——协议中的核心工作单元。
 *
 * - 作用：追踪 Agent 处理请求的完整生命周期，包含状态、历史消息、产出物
 * - 边缘：`artifacts` 仅任务完成后非空；`metadata` 为扩展字段
 *
 * @property id 任务唯一标识符
 * @property sessionId 会话 ID，用于多轮交互，可空
 * @property contextId 上下文 ID，用于逻辑分组，可空
 * @property status 任务状态快照
 * @property history 历史消息列表，可空（受 historyLength 限制）
 * @property artifacts 任务产出物列表，可空
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class Task(
    val id: String,
    val sessionId: String? = null,
    val contextId: String? = null,
    val status: TaskStatus,
    val history: List<Message>? = null,
    val artifacts: List<Artifact>? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * A2A 消息——一次通信轮次。
 *
 * - 作用：承载 user 或 agent 角色的内容，由一组 [Part] 构成
 * - 边缘：`taskId` 用于关联任务上下文；[Task.history] 存储消息列表
 *
 * @property role 消息角色
 * @property parts 内容块列表
 * @property taskId 关联任务 ID，可空
 * @property contextId 上下文 ID，可空
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class Message(
    val role: Role,
    val parts: List<Part> = emptyList(),
    val taskId: String? = null,
    val contextId: String? = null,
    val metadata: Map<String, String>? = null,
)

/**
 * 文件 Part 的内嵌文件体。
 *
 * @property name 文件名，可空
 * @property mimeType MIME 类型，可空
 * @property uri 文件的 URI 引用，可空
 * @property bytes Base64 编码的文件内容，可空
 */
@Serializable
data class FilePartBody(
    val name: String? = null,
    val mimeType: String? = null,
    val uri: String? = null,
    val bytes: String? = null,
)

/**
 * Data Part 的内嵌结构化数据体。
 *
 * @property data 任意 JSON 数据（Map 形式序列化）
 */
@Serializable
data class DataPartBody(
    val data: Map<String, String>,
)

/**
 * A2A 元数据键值对。
 *
 * @property key 元数据键
 * @property value 元数据值
 */
@Serializable
data class A2aMetadata(
    val key: String,
    val value: String,
)
