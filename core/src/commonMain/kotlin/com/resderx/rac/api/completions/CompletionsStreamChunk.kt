package com.resderx.rac.api.completions

import com.resderx.rac.messages.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Completions API 流式分块。
 * 作用：封装 SSE 流式响应的单个 chunk。
 * 必要性：流式调用时每个 data 行解析为此类型，delta 含增量内容。
 * 设计：字段全部可空或带默认值，因服务端可能发送不完整 chunk。
 * 边缘：首个 chunk 含 role，后续 chunk 含 content 增量；最后 chunk 可能含 usage。
 */
@Serializable
data class CompletionsStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

/** 流式选项。 */
@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/** 增量内容。 */
@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallResponse>? = null,
)
