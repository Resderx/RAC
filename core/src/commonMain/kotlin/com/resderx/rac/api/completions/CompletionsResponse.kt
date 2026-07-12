package com.resderx.rac.api.completions

import com.resderx.rac.messages.Usage
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

/** 工具调用响应。 */
@Serializable
data class ToolCallResponse(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

/** 函数调用详情。 */
@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)
