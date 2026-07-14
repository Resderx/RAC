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

package com.resderx.rac.api.responses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Responses API 流式事件密封类。
 * 作用：映射 Responses API SSE 事件类型。
 * 必要性：Responses API 有多种事件类型，需分别建模。
 * 设计：每个子类用 @SerialName 对应事件 type 字段。
 * 边缘：部分事件可能无 payload，字段可空。
 */
@Serializable
sealed class ResponsesStreamEvent {

    @Serializable
    @SerialName("response.created")
    data class ResponseCreated(val response: ResponsesResponse? = null) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.in_progress")
    data class ResponseInProgress(val response: ResponsesResponse? = null) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.output_item.added")
    data class OutputItemAdded(val outputIndex: Int = 0, val item: OutputItem? = null) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.output_item.done")
    data class OutputItemDone(val outputIndex: Int = 0, val item: OutputItem? = null) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.content_part.added")
    data class ContentPartAdded(val itemIndex: Int = 0, val outputIndex: Int = 0) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.content_part.done")
    data class ContentPartDone(val itemIndex: Int = 0, val outputIndex: Int = 0) : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.output_text.delta")
    data class OutputTextDelta(val outputIndex: Int = 0, val contentIndex: Int = 0, val delta: String = "") : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.output_text.done")
    data class OutputTextDone(val outputIndex: Int = 0, val contentIndex: Int = 0, val text: String = "") : ResponsesStreamEvent()

    @Serializable
    @SerialName("response.completed")
    data class ResponseCompleted(val response: ResponsesResponse? = null) : ResponsesStreamEvent()

    @Serializable
    @SerialName("error")
    data class ErrorEvent(val message: String? = null, val code: String? = null) : ResponsesStreamEvent()
}
