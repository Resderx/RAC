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

package top.resderx.rac.dsl

import top.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 工具定义列表构建器，在 `tools { }` 块内以 DSL 风格逐个声明工具。
 *
 * - 作用：提供结构化的工具声明 API——通过 `tool("name", "desc") { param(...) }` 风格
 *   可视化配置工具参数，内部自动生成合法 JSON Schema，无需手写 JSON 字符串
 * - 必要性：旧设计要求调用方手写 JSON Schema 字符串（如 `"""{"type":"object",...}"""`），
 *   既易错又不直观；新设计通过 [ToolParamBuilder] 以结构化方式声明参数，编译期类型安全，
 *   IDE 自动补全友好
 * - 设计思路：`tool()` 方法接收函数名、描述与可选的配置 lambda；lambda 内通过 `param()` 声明每个参数，
 *   由 [ToolParamBuilder] 收集参数定义，最终 [ToolBuilder] 将其序列化为合法 JSON Schema 字符串
 * - 实现方式：内部 MutableList<ToolDefinition>，tool() 方法追加；标注 @RacDslMarker 防止作用域污染
 * - 边缘情况：未调用 tool() 时 build() 返回空列表；工具无参数时 lambda 可省略，生成 `{"type":"object"}`
 * - 优点：参数配置可视化、结构化，消除手写 JSON 的繁琐与易错性
 * - 数据结构：MutableList<ToolDefinition>
 * - 时间复杂度：tool() O(p)（p 为参数数，序列化参数为 JSON Schema）；build() O(m)，m 为工具数
 * - 空间复杂度：O(m)，m 为工具数
 */
@RacDslMarker
class ToolsBuilder {
    private val _tools: MutableList<ToolDefinition> = mutableListOf()

    /**
     * 添加一个工具定义，参数通过结构化 [ToolBuilder] 声明。
     *
     * - 作用：以 `tool("get_weather", "查询天气") { param("city", type="string", required=true) }` 风格
     *   声明工具，内部自动生成合法 JSON Schema
     * - 必要性：替代旧的手写 JSON Schema 字符串方式，使参数配置可视化、不易出错
     *
     * @param name 工具（函数）名称，全局唯一
     * @param description 工具功能描述，供模型判断何时调用
     * @param block 在 [ToolBuilder] 作用域内声明参数，默认空块表示无参数
     */
    fun tool(name: String, description: String, block: ToolBuilder.() -> Unit = {}) {
        val builder = ToolBuilder().apply(block)
        _tools.add(ToolDefinition(name = name, description = description, parameters = builder.buildSchema()))
    }

    /**
     * 构建不可变的工具定义列表。
     *
     * @return 工具定义列表（不可变）
     */
    internal fun build(): List<ToolDefinition> = _tools.toList()
}

/**
 * 单个工具的参数声明构建器，在 `tool("name", "desc") { ... }` 块内使用。
 *
 * - 作用：以 `param("city", type = "string", description = "城市名", required = true)` 风格
 *   结构化声明工具参数，内部收集参数定义并生成合法 JSON Schema 字符串
 * - 必要性：消除手写 JSON Schema 的繁琐——调用方只需声明参数名、类型、描述、是否必填等，
 *   由本构建器自动组装为 `{"type":"object","properties":{...},"required":[...]}` 结构
 * - 设计思路：内部持有参数定义列表（[ParamDef]），buildSchema() 时序列化为 JSON Schema 字符串；
 *   标注 @RacDslMarker 防止作用域污染
 * - 实现方式：类持有 MutableList<ParamDef>，param() 方法追加，buildSchema() 用 kotlinx.serialization
 *   构建 JsonObject 再转为字符串
 * - 边缘情况：无参数时生成 `{"type":"object"}`；enum 参数生成 `{"type":"string","enum":[...]}`
 *
 * 示例：
 * ```
 * tool("get_weather", "查询天气") {
 *     param("city", type = "string", description = "城市名称", required = true)
 *     param("unit", type = "string", description = "温度单位", enum = listOf("celsius", "fahrenheit"))
 * }
 * ```
 */
@RacDslMarker
class ToolBuilder {
    /** 单个参数的定义，供内部收集后序列化为 JSON Schema。 */
    private data class ParamDef(
        val name: String,
        val type: String,
        val description: String?,
        val required: Boolean,
        val enum: List<String>?,
    )

    private val _params: MutableList<ParamDef> = mutableListOf()

    /**
     * 声明一个工具参数。
     *
     * @param name 参数名
     * @param type 参数类型（如 "string"/"number"/"integer"/"boolean"/"array"/"object"）
     * @param description 参数描述，供模型理解参数含义，null 表示不设置
     * @param required 是否为必填参数，默认 false
     * @param enum 枚举值列表，限制参数取值范围，null 表示无枚举约束
     */
    fun param(
        name: String,
        type: String,
        description: String? = null,
        required: Boolean = false,
        enum: List<String>? = null,
    ) {
        _params.add(ParamDef(name = name, type = type, description = description, required = required, enum = enum))
    }

    /**
     * 将收集的参数定义序列化为合法 JSON Schema 字符串。
     *
     * - 作用：把结构化的参数定义转为 `{"type":"object","properties":{...},"required":[...]}` 字符串，
     *   供 [ToolDefinition.parameters] 使用，最终由 [top.resderx.rac.api.completions.toCompletionsTool]
     *   解析为 JsonElement 嵌入请求体
     * - 实现方式：用 kotlinx.serialization 的 buildJsonObject 构建 JsonObject，再 encodeToString
     * - 边缘情况：无参数时返回 `{"type":"object"}`；无必填参数时不输出 "required" 字段
     *
     * @return JSON Schema 字符串
     */
    internal fun buildSchema(): String {
        if (_params.isEmpty()) return """{"type":"object"}"""
        val properties = buildJsonObject {
            _params.forEach { p ->
                put(p.name, buildJsonObject {
                    put("type", JsonPrimitive(p.type))
                    if (p.description != null) put("description", JsonPrimitive(p.description))
                    if (p.enum != null) put("enum", JsonArray(p.enum.map { JsonPrimitive(it) }))
                })
            }
        }
        val required = _params.filter { it.required }.map { JsonPrimitive(it.name) }
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            if (required.isNotEmpty()) put("required", JsonArray(required))
        }
        return Json.encodeToString(JsonObject.serializer(), schema)
    }
}
