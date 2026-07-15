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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Anthropic Messages API 非流式响应。
 * 作用：封装 /v1/messages 响应体。
 * 必要性：Anthropic 响应结构含 content blocks（text/tool_use）与 stop_reason。
 * 设计：content 为密封类列表，支持多模态块；usage 用 input/output tokens。
 * 边缘：content 可能有多个块（文本+工具调用混合）。
 */
@Serializable
data class AnthropicResponse(
    val id: String,
    val model: String? = null,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

/** 内容块密封类。 */
@Serializable
sealed class ContentBlock {
    /** 文本块。 */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : ContentBlock()

    /** 工具调用块。 */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: String,
    ) : ContentBlock()
}

/** Anthropic token 用量。 */
@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
)
