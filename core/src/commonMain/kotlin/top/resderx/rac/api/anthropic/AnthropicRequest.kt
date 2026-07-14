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

package top.resderx.rac.api.anthropic

import com.resderx.rac.messages.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Anthropic Messages API 请求。
 * 作用：封装 /v1/messages 请求体。
 * 必要性：Anthropic 协议与 OpenAI 不同，system 为顶层字段。
 * 设计：maxTokens 必填（Anthropic 要求），字段名用 @SerialName 转 snake_case。
 * 边缘：messages 不含 system 消息，system 单独传；
 *   tools 元素为 [AnthropicTool] 类型，序列化为 `{name, description, input_schema}`。
 *
 * 定制化参数差异：
 * - stop：Anthropic 字段名为 `stop_sequences`（注意复数）
 * - seed：Anthropic 不支持随机种子，本类不暴露该字段（设置时静默忽略）
 * - enableThinking：通过 `thinking` 对象控制，包含 `type`（"enabled"/"disabled"）与
 *   `budget_tokens`（思考预算上限，必须小于 maxTokens），见 [AnthropicThinking]
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<Message>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Long,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val tools: List<AnthropicTool>? = null,
    val stream: Boolean = false,
    /** 停止序列（Anthropic 字段名为 stop_sequences）；不传时由服务端默认。 */
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    /** 思考开关配置——enableThinking=true 时构造 {type:"enabled", budget_tokens:maxTokens}；null 表示不发送该字段。 */
    val thinking: AnthropicThinking? = null,
)

/**
 * Anthropic 思考（Extended Thinking）配置。
 *
 * - 作用：控制 Claude 模型的扩展思考行为，序列化为 `{"type":"...","budget_tokens":N}`
 * - 必要性：Anthropic 的思考开关不是简单的 boolean，而是带预算的结构化对象
 * - 设计思路：
 *   1. `type`：取值 "enabled" 或 "disabled"（启用/禁用思考）
 *   2. `budgetTokens`：思考预算上限（token 数），必须小于 [AnthropicRequest.maxTokens]
 * - 实现方式：由 [com.resderx.rac.dsl.ChatRequestBuilder.buildAnthropic] 根据 enableThinking + maxTokens 构造
 * - 边缘：budget_tokens 必须 > 0 且 < maxTokens，否则 API 返回 400
 *
 * @property type 思考类型，"enabled" 启用扩展思考，"disabled" 禁用
 * @property budgetTokens 思考预算上限（token 数），需小于 maxTokens
 */
@Serializable
data class AnthropicThinking(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Long,
)
