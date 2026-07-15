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

package top.resderx.rac.api.responses

import top.resderx.rac.messages.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Responses API 非流式响应。
 * 作用：封装 /v1/responses 响应体。
 * 必要性：Responses API 响应含 output 列表（message/function_call）。
 * 设计：output 为密封类列表，支持多种输出项类型。
 * 边缘：output 可能为空（仅 reasoning 时）。
 */
@Serializable
data class ResponsesResponse(
    val id: String,
    val model: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: Usage? = null,
)

/** 输出项密封类。 */
@Serializable
sealed class OutputItem {

    /** 消息输出项。 */
    @Serializable
    @SerialName("message")
    data class MessageOutput(
        val id: String? = null,
        val role: String = "assistant",
        val content: List<ResponseContent> = emptyList(),
        val status: String? = null,
    ) : OutputItem()

    /** 函数调用输出项。 */
    @Serializable
    @SerialName("function_call")
    data class FunctionCallOutput(
        val id: String? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String,
        val arguments: String,
    ) : OutputItem()
}

/** 响应内容块。 */
@Serializable
data class ResponseContent(
    val type: String = "output_text",
    val text: String? = null,
)
