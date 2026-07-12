package com.resderx.rac.api.anthropic

import com.resderx.rac.messages.Message
import com.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Anthropic Messages API 请求。
 * 作用：封装 /v1/messages 请求体。
 * 必要性：Anthropic 协议与 OpenAI 不同，system 为顶层字段。
 * 设计：maxTokens 必填（Anthropic 要求），字段名用 @SerialName 转 snake_case。
 * 边缘：messages 不含 system 消息，system 单独传。
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<Message>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Long,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val tools: List<ToolDefinition>? = null,
    val stream: Boolean = false,
)
