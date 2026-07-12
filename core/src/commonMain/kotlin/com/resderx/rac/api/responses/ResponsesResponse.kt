package com.resderx.rac.api.responses

import com.resderx.rac.messages.Usage
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
