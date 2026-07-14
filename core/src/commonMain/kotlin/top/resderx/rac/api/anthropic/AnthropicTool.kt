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

package top.resderx.rac.api.anthropic

import top.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Anthropic API 工具类型，序列化为 Anthropic Messages API 要求的 `{"name","description","input_schema":{...}}` 结构。
 *
 * - 作用：将 RAC 内部的 [ToolDefinition] 转换为 Anthropic 协议期望的工具格式——
 *   与 OpenAI Completions 不同，Anthropic 不使用 `type`/`function` 包装，而是扁平的
 *   `{name, description, input_schema}` 结构，且参数 schema 字段名为 `input_schema` 而非 `parameters`
 * - 必要性：Anthropic API 反序列化器要求 `input_schema` 字段（非 `parameters`），且无 `type`/`function` 包装；
 *   直接复用 [ToolDefinition] 或 [top.resderx.rac.api.completions.CompletionsTool] 均不兼容
 * - 设计思路：单层 data class，三个字段——name/description/input_schema；input_schema 类型为 [JsonElement]
 *   以对象形式序列化（同 CompletionsTool 的 parameters 处理方式）
 * - 实现方式：`@Serializable` data class；通过 [ToolDefinition.toAnthropicTool] 扩展函数构造，
 *   解析 parameters 字符串为 JsonElement，失败时回退为空对象
 * - 边缘情况：parameters 为空 schema 时解析为空 JsonObject；非法 JSON 回退为空对象
 *
 * @property name 工具（函数）名称
 * @property description 工具描述
 * @property inputSchema 参数 JSON Schema，序列化名为 `input_schema`
 */
@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

/**
 * 将 RAC 内部的 [ToolDefinition] 转换为 Anthropic API 期望的 [AnthropicTool] 类型。
 *
 * - 作用：在 [top.resderx.rac.dsl.ChatRequestBuilder.buildAnthropic] 时将用户声明的工具定义
 *   转换为 Anthropic 协议要求的 `{name, description, input_schema}` 结构
 * - 必要性：Anthropic 用 `input_schema` 而非 `parameters`，且无 `type`/`function` 包装
 * - 实现方式：解析 parameters 字符串为 [JsonElement]；失败时回退为空 [JsonObject]
 *
 * @receiver RAC 内部工具定义
 * @return Anthropic API 工具类型
 */
fun ToolDefinition.toAnthropicTool(): AnthropicTool {
    val schema: JsonElement = try {
        Json.parseToJsonElement(parameters)
    } catch (e: Exception) {
        buildJsonObject { }
    }
    return AnthropicTool(
        name = name,
        description = description,
        inputSchema = schema,
    )
}
