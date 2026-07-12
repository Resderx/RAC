package com.resderx.rac.api.responses

import com.resderx.rac.messages.Message
import com.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Responses API 请求。
 * 作用：封装 /v1/responses 请求体（OpenAI 新 API）。
 * 必要性：Responses API 是 OpenAI 替代 Chat Completions 的新协议。
 * 设计：input 可为字符串或消息列表；instructions 为系统指令。
 * 边缘：input 为字符串时自动包装为单条 user 消息。
 */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Long? = null,
)
