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

import com.resderx.rac.api.completions.CompletionsTool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Responses API 请求。
 * 作用：封装 /v1/responses 请求体（OpenAI 新 API）。
 * 必要性：Responses API 是 OpenAI 替代 Chat Completions 的新协议。
 * 设计：input 可为字符串或消息列表；instructions 为系统指令。
 * 边缘：input 为字符串时自动包装为单条 user 消息；
 *   tools 复用 [CompletionsTool] 包装类型（Responses API 与 Completions API 工具格式一致）。
 *
 * 定制化参数差异：
 * - seed：Responses API 支持，字段名为 `seed`（与 Completions 一致）
 * - stop：Responses API 不支持停止序列（设计上用工具调用结束），本类不暴露该字段
 * - enableThinking：Responses API 暂不支持思考开关，本类不暴露该字段
 */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String? = null,
    val stream: Boolean = false,
    val tools: List<CompletionsTool>? = null,
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Long? = null,
    /** 随机种子——用于可重现的确定性输出；不传时由服务端随机。 */
    val seed: Long? = null,
)
