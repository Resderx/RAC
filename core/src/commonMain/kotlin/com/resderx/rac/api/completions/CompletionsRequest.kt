package com.resderx.rac.api.completions

import com.resderx.rac.messages.Message
import com.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Completions API 请求（OpenAI Chat Completions 风格）。
 * 作用：封装 /v1/chat/completions 请求体，被 10 家 OpenAI 兼容供应商共用。
 * 必要性：统一请求模型，字段名通过 @SerialName 映射为 snake_case。
 * 设计：可选字段用可空类型 + 默认 null，序列化时省略 null（Json encodeDefaults=false）。
 * 边缘：messages 为空时由服务端报错；tools 为空列表时不发送 tools 字段。
 */
@Serializable
data class CompletionsRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)
