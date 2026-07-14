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

import com.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Completions API 工具包装类型，序列化为 OpenAI 要求的 `{"type":"function","function":{...}}` 结构。
 *
 * - 作用：将 RAC 内部的 [ToolDefinition] 包装为 OpenAI Chat Completions API 期望的工具格式——
 *   外层 `type` 固定为 `"function"`，内层 `function` 含 name/description/parameters 三字段
 * - 必要性：OpenAI/DeepSeek 等供应商的反序列化器严格要求 `tools` 数组每项含 `type` 字段与 `function` 对象，
 *   缺少 `type` 会导致 `400 missing field type`；直接序列化 [ToolDefinition] 会产出扁平的
 *   `{name,description,parameters}` 结构，不符合 API 规范
 * - 设计思路：两层嵌套 data class——外层 [CompletionsTool] 持有固定 `type="function"` 与 [function]，
 *   内层 [CompletionsFunction] 持有 name/description/parameters；parameters 类型为 [JsonElement]
 *   而非 String，确保 JSON Schema 以对象形式（`{"type":"object",...}`）而非字符串嵌入请求体
 * - 实现方式：`@Serializable` data class；通过 [ToolDefinition.toCompletionsTool] 扩展函数构造，
 *   该扩展函数用 `Json.parseToJsonElement` 将 parameters 字符串解析为 JsonElement，解析失败时回退为空对象
 * - 边缘情况：parameters 为空 schema `"{}"` 时解析为空 JsonObject，合法；parameters 为非法 JSON 字符串时
 *   回退为空对象（避免运行时崩溃）；description 为空字符串时仍保留（部分模型要求字段存在）
 *
 * @property type 工具类型，固定 `"function"`（OpenAI 当前仅支持函数工具）
 * @property function 函数定义，含 name/description/parameters
 */
@Serializable
data class CompletionsTool(
    val type: String,
    val function: CompletionsFunction,
)

/**
 * Completions API 工具的函数定义部分。
 *
 * @property name 函数名
 * @property description 函数描述
 * @property parameters 参数 JSON Schema，类型为 [JsonElement] 以对象形式序列化
 */
@Serializable
data class CompletionsFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

/**
 * 将 RAC 内部的 [ToolDefinition] 转换为 Completions API 期望的 [CompletionsTool] 包装类型。
 *
 * - 作用：在 [com.resderx.rac.dsl.ChatRequestBuilder.build] 时将用户声明的工具定义转换为
 *   OpenAI/DeepSeek 等 Completions 协议供应商要求的 `{"type":"function","function":{...}}` 结构
 * - 必要性：[ToolDefinition.parameters] 为 JSON Schema 字符串，需解析为 [JsonElement] 以对象形式嵌入请求体；
 *   同时需添加外层 `type`/`function` 包装以满足 API 反序列化要求
 * - 实现方式：用 `Json.parseToJsonElement` 解析 parameters 字符串；解析失败时回退为空 [JsonObject]，
 *   避免非法 JSON 导致运行时崩溃（容错策略优于崩溃，调用方应在声明工具时保证 parameters 为合法 JSON Schema）
 *
 * @receiver RAC 内部工具定义
 * @return Completions API 包装类型
 */
fun ToolDefinition.toCompletionsTool(): CompletionsTool {
    // 解析 parameters 字符串为 JsonElement；失败时回退为空对象（容错）
    val schema: JsonElement = try {
        Json.parseToJsonElement(parameters)
    } catch (e: Exception) {
        buildJsonObject { /* 空 JSON Schema，表示无结构化参数 */ }
    }
    return CompletionsTool(
        type = "function",
        function = CompletionsFunction(
            name = name,
            description = description,
            parameters = schema,
        ),
    )
}
