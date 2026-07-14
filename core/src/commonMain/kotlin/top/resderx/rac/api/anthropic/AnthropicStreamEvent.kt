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
 * Anthropic 流式事件密封类。
 * 作用：映射 Anthropic SSE 事件类型（message_start, content_block_delta 等）。
 * 必要性：Anthropic 流式协议有 6 种事件类型，需分别建模。
 * 设计：每个子类用 @SerialName 对应事件 type 字段。
 * 边缘：delta 类型可能是 text_delta 或 input_json_delta。
 */
@Serializable
sealed class AnthropicStreamEvent {

    @Serializable
    @SerialName("message_start")
    data class MessageStart(val message: AnthropicResponse? = null) : AnthropicStreamEvent()

    @Serializable
    @SerialName("content_block_start")
    data class ContentBlockStart(val index: Int = 0, val contentBlock: ContentBlock? = null) : AnthropicStreamEvent()

    @Serializable
    @SerialName("content_block_delta")
    data class ContentBlockDelta(val index: Int = 0, val delta: Delta? = null) : AnthropicStreamEvent()

    @Serializable
    @SerialName("content_block_stop")
    data class ContentBlockStop(val index: Int = 0) : AnthropicStreamEvent()

    @Serializable
    @SerialName("message_delta")
    data class MessageDelta(val delta: MessageDeltaBody? = null, val usage: AnthropicUsage? = null) : AnthropicStreamEvent()

    @Serializable
    @SerialName("message_stop")
    class MessageStop : AnthropicStreamEvent()
}

/** 内容块增量。 */
@Serializable
sealed class Delta {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val text: String? = null) : Delta()

    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(val partialJson: String? = null) : Delta()
}

/** message_delta 事件体。 */
@Serializable
data class MessageDeltaBody(
    @SerialName("stop_reason") val stopReason: String? = null,
)
