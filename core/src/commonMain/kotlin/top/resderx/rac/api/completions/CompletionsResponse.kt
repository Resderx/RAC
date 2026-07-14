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

package top.resderx.rac.api.completions

import top.resderx.rac.messages.Usage
import kotlinx.serialization.Serializable

/**
 * Completions API 非流式响应。
 * 作用：封装 /v1/chat/completions 响应体。
 * 必要性：统一响应模型，choices 含模型生成的消息。
 * 设计：字段名与 OpenAI 协议对齐，finishReason 为字符串由调用方映射到 FinishReason 枚举。
 * 边缘：content 可能为 null（纯工具调用时）；reasoningContent 为 DeepSeek 扩展字段。
 */
@Serializable
data class CompletionsResponse(
    val id: String,
    val model: String,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

/** 单个生成选项。 */
@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage,
    @kotlinx.serialization.SerialName("finish_reason")
    val finishReason: String? = null,
)

/** 响应消息。 */
@Serializable
data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @kotlinx.serialization.SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @kotlinx.serialization.SerialName("tool_calls")
    val toolCalls: List<ToolCallResponse>? = null,
)

/**
 * 工具调用响应。
 *
 * - 作用：封装模型返回的工具调用信息（id + type + function 详情）
 * - 必要性：非流式与流式响应均使用此类型；流式场景下 id/function 可能分片到达
 * - 设计：index 标识流式场景下的工具调用序号（非流式默认 0）；
 *   id 与 function 设为可空，因流式后续 chunk 可能仅含 arguments 片段而无 id/name
 * - 边缘：非流式响应中 id/function 始终非空；流式首帧含 id 和 function.name，
 *   后续帧 id 为 null、function.name 为 null、仅 function.arguments 有值
 *
 * @property index 工具调用索引，流式场景用于区分多个并行工具调用，默认 0
 * @property id 工具调用唯一标识，流式后续帧可能为 null
 * @property type 调用类型，固定 "function"，默认 "function"
 * @property function 函数调用详情，流式后续帧可能仅含 arguments 片段
 */
@Serializable
data class ToolCallResponse(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = "function",
    val function: FunctionCall? = null,
)

/**
 * 函数调用详情。
 *
 * - 作用：封装工具调用的函数名与参数 JSON
 * - 必要性：工具调用的核心载荷
 * - 设计：name 与 arguments 设为可空，因流式后续帧仅含 arguments 片段，name 为 null
 * - 边缘：非流式中 name/arguments 始终非空；流式首帧 name 有值、arguments 可能为空串，
 *   后续帧 name 为 null、arguments 为参数片段
 *
 * @property name 函数名称，流式后续帧可能为 null
 * @property arguments 参数 JSON 字符串，流式场景需由调用方累积拼接
 */
@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)
