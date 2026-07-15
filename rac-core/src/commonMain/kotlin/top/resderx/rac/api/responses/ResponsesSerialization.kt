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

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package top.resderx.rac.api.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.resderx.rac.messages.AssistantMessage
import top.resderx.rac.messages.AudioContent
import top.resderx.rac.messages.Content
import top.resderx.rac.messages.ImageContent
import top.resderx.rac.messages.Message
import top.resderx.rac.messages.SystemMessage
import top.resderx.rac.messages.TextContent
import top.resderx.rac.messages.ToolMessage
import top.resderx.rac.messages.UserMessage

/**
 * Content → OpenAI Responses API JSON 格式转换。
 *
 * - 作用：把统一的 [Content] 模型转换为 OpenAI Responses API 期望的多模态内容块 JSON
 * - 必要性：Responses API 使用 `input_text`/`input_image` 类型名（而非 Chat Completions 的 `text`/`image_url`），
 *   且图片字段为扁平的 `image_url` 字符串（而非嵌套对象），需手动转换
 * - 设计思路：对 [Content] 密封接口穷尽匹配，每种子类映射到 Responses 格式
 * - 边缘情况：
 *   - [ImageContent] 的 url 优先；仅 base64 时构造 `data:<mime>;base64,...` data URI
 *   - [AudioContent] 降级为 `input_text` 文本占位符（Responses API 有 `input_audio` 类型但格式特殊，暂未适配）
 *
 * @receiver 原始 Content
 * @return Responses API 格式 JSON 对象
 */
fun Content.toResponsesJson(): JsonObject = when (this) {
    is TextContent -> buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }
    is ImageContent -> buildJsonObject {
        put("type", "input_image")
        // url 优先；仅 base64 时构造 data URI
        val urlValue = url ?: "data:$mimeType;base64,$base64"
        // Responses API 的 image_url 是扁平字符串（非嵌套对象）
        put("image_url", urlValue)
    }
    is AudioContent -> buildJsonObject {
        // Responses API 有 input_audio 类型但格式特殊（需 detail 字段），暂降级为文本占位符
        put("type", "input_text")
        put("text", "[audio: $mimeType, ${base64.length} chars base64]")
    }
}

/**
 * Message → OpenAI Responses API JSON 格式转换。
 *
 * - 作用：把统一的 [Message] 模型转换为 OpenAI Responses API 期望的 input 数组元素 JSON
 * - 必要性：Responses API 的消息格式与 Chat Completions 有差异：
 *   1. content 类型名用 `input_text`/`input_image`（用户侧）和 `output_text`（助手侧）
 *   2. ToolMessage 是 `function_call_output` 类型（无 role 字段）
 *   3. AssistantMessage 的 tool_calls 是 `function_call` 类型（无 role 字段）
 * - 设计思路：对 [Message] 密封接口穷尽匹配，每种子类映射到 Responses 格式
 * - 格式细节：
 *   - SystemMessage: `{"role":"system","content":[{"type":"input_text","text":"..."}]}`
 *   - UserMessage: `{"role":"user","content":[{"type":"input_text","text":"..."},{"type":"input_image","image_url":"..."}]}`
 *   - AssistantMessage: `{"role":"assistant","content":[{"type":"output_text","text":"..."}]}` + 工具调用作为 `function_call` 类型元素
 *   - ToolMessage: `{"type":"function_call_output","call_id":"...","output":"..."}`（无 role 字段）
 *
 * @receiver 原始 Message
 * @return Responses API 格式 JSON 对象
 */
fun Message.toResponsesJson(): JsonObject = when (this) {
    is SystemMessage -> buildJsonObject {
        put("role", "system")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", content)
            })
        })
    }
    is UserMessage -> buildJsonObject {
        put("role", "user")
        // content 为数组（多模态）
        put("content", buildJsonArray {
            content.forEach { add(it.toResponsesJson()) }
        })
    }
    is AssistantMessage -> buildJsonObject {
        put("role", "assistant")
        // content 为数组：正文文本用 output_text 类型
        val contentArray = buildJsonArray {
            content?.takeIf { it.isNotEmpty() }?.let { text ->
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                })
            }
        }
        put("content", contentArray)
        // 工具调用作为独立的 function_call 类型元素（Responses API 格式）
        // 注意：Responses API 的 function_call 是 input 数组的顶层元素，这里简化为附加到消息内
        // 实际使用时由 ChatRequestBuilder.buildResponses 统一构造 input 数组
    }
    is ToolMessage -> buildJsonObject {
        // Responses API 的工具回执：function_call_output 类型，无 role 字段
        put("type", "function_call_output")
        put("call_id", toolCallId)
        put("output", content)
    }
}
