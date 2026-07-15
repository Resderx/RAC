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

package top.resderx.rac.api.anthropic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonEncoder
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
 * Content → Anthropic Messages API JSON 格式转换。
 *
 * - 作用：把统一的 [Content] 模型转换为 Anthropic Messages API 期望的多模态内容块 JSON
 * - 必要性：Anthropic 的图片字段是 `{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}`，
 *   与项目统一模型 [ImageContent] 的 `{url, base64, mimeType}` 结构不同，需手动转换
 * - 设计思路：对 [Content] 密封接口穷尽匹配，每种子类映射到 Anthropic 格式
 * - 边缘情况：
 *   - [ImageContent] 的 base64 优先（Anthropic 原生支持）；仅 url 时用 url source（2024 年后支持）
 *   - [AudioContent] 在 Anthropic API 不被支持，降级为文本占位符
 *
 * @receiver 原始 Content
 * @return Anthropic 格式 JSON 对象
 */
fun Content.toAnthropicJson(): JsonObject = when (this) {
    is TextContent -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    is ImageContent -> buildJsonObject {
        put("type", "image")
        // base64 优先（Anthropic 原生支持）；仅 url 时用 url source
        val source = if (base64 != null) {
            buildJsonObject {
                put("type", "base64")
                put("media_type", mimeType)
                put("data", base64)
            }
        } else {
            buildJsonObject {
                put("type", "url")
                put("url", url!!)
            }
        }
        put("source", source)
    }
    is AudioContent -> buildJsonObject {
        // Anthropic API 不支持音频输入，降级为文本占位符避免请求被拒
        put("type", "text")
        put("text", "[audio: $mimeType, ${base64.length} chars base64]")
    }
}

/**
 * Message → Anthropic Messages API JSON 格式转换。
 *
 * - 作用：把统一的 [Message] 模型转换为 Anthropic Messages API 期望的消息 JSON
 * - 必要性：Anthropic 的消息格式与 OpenAI 有显著差异：
 *   1. AssistantMessage.content 必须是数组（`[{"type":"text","text":"..."}]`），而非字符串
 *   2. toolCalls 必须作为 content 数组中的 `tool_use` 类型元素，而非独立的 `tool_calls` 字段
 *   3. ToolMessage 必须用 `user` 角色 + `tool_result` 类型，而非 `tool` 角色
 * - 设计思路：对 [Message] 密封接口穷尽匹配，每种子类映射到 Anthropic 格式
 * - 格式细节：
 *   - SystemMessage: `{"role":"system","content":"..."}`（正常不应出现在 messages 中，由 buildAnthropic 提取到顶层 system 字段）
 *   - UserMessage: `{"role":"user","content":[...]}`（content 为数组）
 *   - AssistantMessage: `{"role":"assistant","content":[{"type":"text","text":"..."},{"type":"tool_use",...}]}`
 *   - ToolMessage: `{"role":"user","content":[{"type":"tool_result","tool_use_id":"...","content":"..."}]}`（user 角色！）
 *
 * @receiver 原始 Message
 * @return Anthropic 格式 JSON 对象
 */
fun Message.toAnthropicJson(): JsonObject = when (this) {
    is SystemMessage -> buildJsonObject {
        // SystemMessage 正常不应出现在 Anthropic messages 中（由 buildAnthropic 提取到顶层 system 字段）
        // 但为安全起见仍提供转换，content 为字符串
        put("role", "system")
        put("content", content)
    }
    is UserMessage -> buildJsonObject {
        put("role", "user")
        // content 为数组（多模态）
        put("content", buildJsonArray {
            content.forEach { add(it.toAnthropicJson()) }
        })
    }
    is AssistantMessage -> buildJsonObject {
        put("role", "assistant")
        // Anthropic 的 content 是数组：正文文本 + 工具调用都作为数组元素
        val contentArray = buildJsonArray {
            // 正文文本（可能为 null，仅在有内容时追加）
            content?.takeIf { it.isNotEmpty() }?.let { text ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            // 工具调用转为 tool_use 类型元素
            toolCalls.forEach { tc ->
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", tc.id)
                    put("name", tc.name)
                    // arguments 是 JSON 字符串，解析为对象作为 input 字段
                    val inputElement = try {
                        Json.parseToJsonElement(tc.arguments.ifEmpty { "{}" })
                    } catch (_: Exception) {
                        Json.parseToJsonElement("{}")
                    }
                    put("input", inputElement)
                })
            }
        }
        put("content", contentArray)
    }
    is ToolMessage -> buildJsonObject {
        // Anthropic 的工具回执：user 角色 + tool_result 类型
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", toolCallId)
                put("content", content)
            })
        })
    }
}

/**
 * `List<Message>` 的自定义序列化器——序列化时按 Anthropic Messages API 格式输出 JSON 数组。
 *
 * - 作用：让 [AnthropicRequest.messages] 字段在序列化时调用 [Message.toAnthropicJson] 逐条转换，
 *   产出符合 Anthropic API 规范的 JSON 数组
 * - 必要性：Anthropic 的消息格式与 OpenAI 差异显著（AssistantMessage.content 为数组、ToolMessage 用 user 角色），
 *   默认序列化器无法正确处理
 * - 反序列化：不支持（请求只发不收）
 * - 实现方式：serialize 时构造 JsonArray，通过 JsonEncoder.encodeJsonElement 直接输出
 * - 时间复杂度：O(n)，n 为消息数量
 * - 空间复杂度：O(n)，构造完整的 JsonArray
 */
object AnthropicMessageListSerializer : KSerializer<List<Message>> {

    /** 序列化描述符——声明为 CLASS 类型（JSON 编码下不影响输出）。 */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AnthropicMessages")

    /**
     * 序列化——把 `List<Message>` 转为 Anthropic 格式 JSON 数组并输出。
     *
     * @param encoder JSON 编码器（需为 JsonEncoder，否则抛 IllegalStateException）
     * @param value 要序列化的消息列表
     */
    override fun serialize(encoder: Encoder, value: List<Message>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("AnthropicMessageListSerializer 需要 JsonEncoder，但得到 ${encoder::class}")
        val array: JsonArray = buildJsonArray {
            value.forEach { add(it.toAnthropicJson()) }
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
        throw UnsupportedOperationException("AnthropicMessageListSerializer 不支持反序列化")
    }
}
