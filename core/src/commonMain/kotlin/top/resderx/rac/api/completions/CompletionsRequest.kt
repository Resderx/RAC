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

package top.resderx.rac.api.completions

import top.resderx.rac.messages.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Completions API 请求（OpenAI Chat Completions 风格）。
 * 作用：封装 /v1/chat/completions 请求体，被 10 家 OpenAI 兼容供应商共用。
 * 必要性：统一请求模型，字段名通过 @SerialName 映射为 snake_case。
 * 设计：可选字段用可空类型 + 默认 null，序列化时省略 null（Json encodeDefaults=false）。
 * 边缘：messages 为空时由服务端报错；tools 为空列表时不发送 tools 字段；
 *   tools 元素为 [CompletionsTool] 包装类型，序列化为 `{"type":"function","function":{...}}`。
 *
 * 定制化参数（stop/seed）：
 * - stop：停止序列，模型生成到任一字符串时立即停止（OpenAI/DeepSeek/Qwen 等均支持）
 * - seed：随机种子，用于确定性输出（OpenAI/DeepSeek 等支持，部分供应商忽略）
 * - enableThinking 不在此处直接体现——它通过 reasoningEffort 间接控制（见 ChatRequestBuilder.build）
 */
@Serializable
data class CompletionsRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
    val stream: Boolean = false,
    val tools: List<CompletionsTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** 停止序列——模型生成到任一字符串时立即停止；不传时由服务端默认（通常无停止序列）。 */
    val stop: List<String>? = null,
    /** 随机种子——用于可重现的确定性输出；不传时由服务端随机。 */
    val seed: Long? = null,
)
