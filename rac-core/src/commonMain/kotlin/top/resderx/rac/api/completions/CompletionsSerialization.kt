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

package top.resderx.rac.api.completions

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Content → OpenAI Chat Completions JSON 格式转换。
 *
 * - 作用：把统一的 [Content] 模型转换为 OpenAI Chat Completions API 期望的多模态内容块 JSON
 * - 必要性：OpenAI 的图片字段是 `{"type":"image_url","image_url":{"url":"..."}}`，
 *   与项目统一模型 [ImageContent] 的 `{url, base64, mimeType}` 结构不同，需手动转换
 * - 设计思路：对 [Content] 密封接口穷尽匹配，每种子类映射到 OpenAI 格式
 * - 边缘情况：
 *   - [ImageContent] 的 url 优先；仅 base64 时构造 `data:<mime>;base64,...` data URI
 *   - [AudioContent] 在 Chat Completions API 不被支持，降级为文本占位符
 *
 * @receiver 原始 Content
 * @return OpenAI 格式 JSON 对象
 */
fun Content.toCompletionsJson(): JsonObject = when (this) {
    is TextContent -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    is ImageContent -> buildJsonObject {
        put("type", "image_url")
        // url 优先；仅 base64 时构造 data URI 供 OpenAI 解析
        val urlValue = url ?: "data:$mimeType;base64,$base64"
        put("image_url", buildJsonObject {
            put("url", urlValue)
        })
    }
    is AudioContent -> buildJsonObject {
        // Chat Completions API 不支持音频输入，降级为文本占位符避免请求被拒
        put("type", "text")
        put("text", "[audio: $mimeType, ${base64.length} chars base64]")
    }
}

/**
 * Message → OpenAI Chat Completions JSON 格式转换。
 *
 * - 作用：把统一的 [Message] 模型转换为 OpenAI Chat Completions API 期望的消息 JSON
 * - 必要性：[UserMessage.content] 是 `List<Content>` 需按 OpenAI 多模态格式序列化为数组；
 *   [AssistantMessage] 的 toolCalls 需展开为 `tool_calls` 嵌套结构；其他消息类型也需按 OpenAI 字段名输出
 * - 设计思路：对 [Message] 密封接口穷尽匹配，每种子类映射到 OpenAI 格式
 * - 格式细节：
 *   - SystemMessage: `{"role":"system","content":"..."}`（content 为字符串）
 *   - UserMessage: `{"role":"user","content":[...]}`（content 为数组，支持多模态）
 *   - AssistantMessage: `{"role":"assistant","content":"...","tool_calls":[...],"reasoning_content":"..."}`
 *   - ToolMessage: `{"role":"tool","tool_call_id":"...","content":"..."}`
 *
 * @receiver 原始 Message
 * @return OpenAI 格式 JSON 对象
 */
fun Message.toCompletionsJson(): JsonObject = when (this) {
    is SystemMessage -> buildJsonObject {
        put("role", "system")
        put("content", content)
    }
    is UserMessage -> buildJsonObject {
        put("role", "user")
        // content 为数组（多模态）；纯文本场景也是单元素数组 [{"type":"text","text":"..."}]
        put("content", buildJsonArray {
            content.forEach { add(it.toCompletionsJson()) }
        })
    }
    is AssistantMessage -> buildJsonObject {
        put("role", "assistant")
        // content 为字符串（可能为 null，省略字段）
        content?.let { put("content", it) }
        // tool_calls 展开为 OpenAI 嵌套结构
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            })
        }
        // DeepSeek 扩展字段：推理过程内容
        reasoningContent?.let { put("reasoning_content", it) }
    }
    is ToolMessage -> buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", content)
    }
}

/**
 * `List<Message>` 的自定义序列化器——序列化时按 OpenAI Chat Completions 格式输出 JSON 数组。
 *
 * - 作用：让 [CompletionsRequest.messages] 字段在序列化时调用 [Message.toCompletionsJson] 逐条转换，
 *   产出符合 OpenAI API 规范的 JSON 数组，而非 kotlinx.serialization 默认的多态格式
 * - 必要性：默认序列化器会输出 `{"type":"user","content":[{"type":"text",...}]}`，
 *   而 OpenAI 期望 `{"role":"user","content":[{"type":"text","text":"..."}]}`——字段名与图片格式都不对
 * - 反序列化：不支持（请求只发不收，响应使用独立的 Response 模型）
 * - 实现方式：serialize 时构造 JsonArray，通过 JsonEncoder.encodeJsonElement 直接输出
 * - 时间复杂度：O(n)，n 为消息数量
 * - 空间复杂度：O(n)，构造完整的 JsonArray
 */
object CompletionsMessageListSerializer : KSerializer<List<Message>> {

    /** 序列化描述符——声明为 CLASS 类型（JSON 编码下不影响输出，仅用于 schema 生成）。 */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CompletionsMessages")

    /**
     * 序列化——把 `List<Message>` 转为 OpenAI 格式 JSON 数组并输出。
     *
     * @param encoder JSON 编码器（需为 JsonEncoder，否则抛 IllegalStateException）
     * @param value 要序列化的消息列表
     */
    override fun serialize(encoder: Encoder, value: List<Message>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("CompletionsMessageListSerializer 需要 JsonEncoder，但得到 ${encoder::class}")
        val array: JsonArray = buildJsonArray {
            value.forEach { add(it.toCompletionsJson()) }
        }
        jsonEncoder.encodeJsonElement(array)
    }

    /**
     * 反序列化——不支持（请求只发不收）。
     *
     * @param decoder 解码器
     * @return 永不返回，始终抛异常
     * @throws UnsupportedOperationException 始终抛出
     */
    override fun deserialize(decoder: Decoder): List<Message> {
        throw UnsupportedOperationException("CompletionsMessageListSerializer 不支持反序列化")
    }
}
