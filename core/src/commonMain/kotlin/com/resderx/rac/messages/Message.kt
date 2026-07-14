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

package com.resderx.rac.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * 对话消息密封接口，统一抽象对话历史中的各类消息。
 *
 * - 作用：抽象对话历史中的一条消息，覆盖 system/user/assistant/tool 四种角色
 * - 必要性：跨供应商统一消息表达，DSL 用 `List<Message>` 描述完整对话上下文
 * - 设计思路：密封接口限制子类型为已知四种，编译期穷尽匹配；序列化判别符为 `role`
 *   （OpenAI/Anthropic API 约定），子类用 `@SerialName` 指定 role 值（system/user/assistant/tool），
 *   序列化产物形如 `{"role":"system","content":"..."}`，与各家 API 期望格式一致
 * - 实现方式：接口标注 `@Serializable` 与 `@JsonClassDiscriminator("role")`，每个子类用 `@SerialName`
 *   区分；调用方 Json 实例需开启多态支持（sealed class 默认支持）
 * - 边缘情况：API 客户端把各家供应商的消息字段映射到对应子类；流式场景下 assistant 消息可能分片到达，
 *   由客户端聚合后再产出完整 AssistantMessage
 * - 注意：`@JsonClassDiscriminator("role")` 自动生成 `role` 字段，子类不得再声明同名属性，
 *   否则触发 `JsonEncodingException`（参见 kotlinx.serialization 多态机制）
 */
@JsonClassDiscriminator("role")
@Serializable
sealed interface Message

/**
 * 系统消息，设定模型行为与人设。
 *
 * - 作用：向模型注入系统级指令（角色设定、约束、上下文规则）
 * - 必要性：所有供应商支持 system role，是控制模型行为的主要手段
 * - 设计思路：content 为纯字符串（系统消息一般不含多模态），序列化为 `{"type":"system","content":"..."}`
 * - 实现方式：`@Serializable` 数据类，`@SerialName("system")` 区分类型
 * - 边缘情况：部分供应商（如 Anthropic）将 system 消息从对话历史中分离为顶层字段，由 API 客户端处理映射
 *
 * @property content 系统指令文本
 */
@Serializable
@SerialName("system")
data class SystemMessage(
    val content: String,
) : Message

/**
 * 用户消息，支持多模态内容列表。
 *
 * - 作用：承载用户输入，可包含文本/图片/音频等多种内容
 * - 必要性：用户消息是对话的主驱动，多模态支持是现代模型的标配
 * - 设计思路：content 为 `List<Content>` 以支持多模态混合；提供 String 次构造便利纯文本场景
 * - 实现方式：`@Serializable` 数据类，`@SerialName("user")` 区分类型；次构造 `constructor(text: String)`
 *   委托主构造 `listOf(TextContent(text))`，仅用于编程便利，不参与序列化
 * - 边缘情况：content 列表为空时由调用方决定是否允许；部分供应商对单条消息内多模态数量有限制
 *
 * @property content 内容列表，元素为 Content 子类型
 */
@Serializable
@SerialName("user")
data class UserMessage(
    val content: List<Content>,
) : Message {
    /**
     * 纯文本用户消息的便利构造。
     *
     * @param text 用户输入文本
     */
    constructor(text: String) : this(listOf(TextContent(text)))
}

/**
 * 助手消息，承载模型输出（正文/工具调用/推理）。
 *
 * - 作用：封装模型返回的正文、工具调用请求与推理过程
 * - 必要性：跨供应商统一 assistant 角色表达，支持工具调用与推理模型
 * - 设计思路：content 可空（纯工具调用时模型可能不返回正文）；toolCalls 默认空列表；
 *   reasoningContent 可空（仅推理模型返回）
 * - 实现方式：`@Serializable` 数据类，`@SerialName("assistant")` 区分类型；所有可选字段用可空类型 + 默认值
 * - 边缘情况：content 与 toolCalls 可能同时为空（极端情况）；流式场景下需客户端聚合分片后再构造
 *
 * @property content 模型返回的正文文本，纯工具调用时可能为 null
 * @property toolCalls 模型发起的工具调用列表，默认空
 * @property reasoningContent 推理过程文本，仅推理模型（如 o1/DeepSeek-R1）返回，可空
 */
@Serializable
@SerialName("assistant")
data class AssistantMessage(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
) : Message

/**
 * 工具回执消息，返回工具执行结果给模型。
 *
 * - 作用：将工具执行结果回传给模型，供其继续推理
 * - 必要性：工具调用闭环的必要环节，所有支持工具调用的供应商都需要 tool role 回执
 * - 设计思路：toolCallId 关联对应的 ToolCall.id，content 为工具执行结果文本
 * - 实现方式：`@Serializable` 数据类，`@SerialName("tool")` 区分类型
 * - 边缘情况：content 可能是大体积 JSON 字符串，长输出场景注意 token 上限；多个工具并行调用时
 *   需要为每个 ToolCall 各回一条 ToolMessage
 *
 * @property toolCallId 对应的 ToolCall.id，用于模型关联请求与回执
 * @property content 工具执行结果文本（通常为 JSON 字符串）
 */
@Serializable
@SerialName("tool")
data class ToolMessage(
    @SerialName("tool_call_id") val toolCallId: String,
    val content: String,
) : Message
