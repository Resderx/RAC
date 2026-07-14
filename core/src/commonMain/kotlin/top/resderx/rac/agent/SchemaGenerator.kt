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

package top.resderx.rac.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 从 `@Serializable` data class 的 [KSerializer] 自动生成 JSON Schema 字符串。
 *
 * - 作用：为 [Agent] 的类型安全工具注册（`tool<Args>(...) { }`）自动生成工具参数 schema，
 *   调用方无需手写 `param()` 或 JSON Schema 字符串，框架从 `@Serializable` data class 的
 *   [SerialDescriptor] 反射式遍历生成符合 OpenAI/DeepSeek 工具调用规范的 schema
 * - 必要性：工具调用要求 `parameters` 字段为 JSON Schema 字符串；手动维护易出错且重复，
 *   本生成器消除样板代码，data class 改动时 schema 自动同步
 * - 设计思路：
 *   1. 入口 [toJsonSchema] 接收 [KSerializer]，取其 `descriptor`（描述 data class 结构）
 *   2. [buildObjectSchema] 生成 `{"type":"object","properties":{...},"required":[...]}`
 *   3. [buildPropertySchema] 递归处理每个属性：primitive → 对应 JSON type；
 *      enum → string + enum 数组；list → array + items；class → 递归 object
 *   4. `descriptor.isElementOptional(i)` 区分 required/optional
 * - KMP 兼容：[SerialDescriptor] API 在所有 KMP 平台行为一致（JVM/JS/Native），
 *   是 kotlinx.serialization 的稳定公开 API，无需反射
 * - 实现方式：用 `buildJsonObject`/`buildJsonArray` 构造不可变 JSON 树，再 `toString()` 输出
 * - 边缘情况：
 *   - 无属性 data class → `{"type":"object"}`（无 properties/required 字段）
 *   - 可空字段（`String?`）→ 自动进 optional，不进 required
 *   - 嵌套 `@Serializable` 对象 → 递归生成嵌套 properties
 *   - 数组 `List<String>` → `{"type":"array","items":{"type":"string"}}`
 *   - 未支持的类型（如 MAP）→ 兜底为 `{"type":"object"}`
 * - 优点：类型安全、自动同步、KMP 友好；开发者只需定义 data class
 * - 算法/数据结构：递归遍历 [SerialDescriptor] 树
 * - 时间复杂度：O(n)，n 为所有层级的属性总数
 * - 空间复杂度：O(n)，生成对应大小的 JSON 树
 */

/**
 * 生成 data class 顶层对应的 object schema。
 *
 * - 作用：构造 `{"type":"object","properties":{...},"required":[...]}` 结构
 * - 边缘：`elementsCount == 0` 时仅输出 `{"type":"object"}`，不输出空 properties/required
 *
 * @param descriptor 顶层 data class 的 [SerialDescriptor]（kind 应为 [StructureKind.CLASS]）
 * @return JSON Schema 的 [JsonObject] 表示
 */
private fun buildObjectSchema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    if (descriptor.elementsCount > 0) {
        // properties：每个属性名 → 属性 schema
        put("properties", buildJsonObject {
            for (i in 0 until descriptor.elementsCount) {
                put(descriptor.getElementName(i), buildPropertySchema(descriptor.getElementDescriptor(i)))
            }
        })
        // required：非可选属性名列表（isElementOptional == false）
        val requiredNames = (0 until descriptor.elementsCount)
            .filter { !descriptor.isElementOptional(it) }
            .map { descriptor.getElementName(it) }
        if (requiredNames.isNotEmpty()) {
            put("required", buildJsonArray {
                requiredNames.forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}

/**
 * 生成单个属性的 schema（递归处理嵌套对象与数组）。
 *
 * - 作用：根据 [SerialKind] 映射到 JSON Schema 类型
 * - 递归：[StructureKind.CLASS] 递归调 [buildObjectSchema]；[StructureKind.LIST] 递归处理 items
 * - 边缘：未识别 kind 兜底为 `{"type":"string"}`，避免生成空 schema
 *
 * @param descriptor 属性的 [SerialDescriptor]
 * @return 属性 schema 的 [JsonElement] 表示
 */
private fun buildPropertySchema(descriptor: SerialDescriptor): JsonElement = when (descriptor.kind) {
    // 字符串
    PrimitiveKind.STRING -> buildJsonObject { put("type", JsonPrimitive("string")) }
    // 整数类型统一为 integer
    PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
        buildJsonObject { put("type", JsonPrimitive("integer")) }
    // 浮点类型统一为 number
    PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT ->
        buildJsonObject { put("type", JsonPrimitive("number")) }
    // 布尔
    PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", JsonPrimitive("boolean")) }
    PrimitiveKind.CHAR -> buildJsonObject { put("type", JsonPrimitive("string")) }
    // 枚举：type=string + enum 数组（遍历枚举描述符的元素名）
    SerialKind.ENUM -> buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            for (i in 0 until descriptor.elementsCount) {
                add(JsonPrimitive(descriptor.getElementName(i)))
            }
        })
    }
    // 数组：type=array + items（取首个元素描述符递归）
    StructureKind.LIST -> {
        val itemDescriptor = descriptor.getElementDescriptor(0)
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", buildPropertySchema(itemDescriptor))
        }
    }
    // Map：简化为 type=object（不展开键值对 schema，因 JSON Schema 对 map 支持有限）
    StructureKind.MAP -> buildJsonObject { put("type", JsonPrimitive("object")) }
    // 嵌套对象：递归生成完整 object schema
    StructureKind.CLASS -> buildObjectSchema(descriptor)
    // 兜底：未识别类型标为 string，避免空 schema
    else -> buildJsonObject { put("type", JsonPrimitive("string")) }
}

/**
 * 将 `@Serializable` data class 的 [KSerializer] 转换为 JSON Schema 字符串。
 *
 * - 作用：Agent 工具注册时调用，自动生成 [top.resderx.rac.messages.ToolDefinition.parameters]
 * - 用法：`serializer<WeatherArgs>().toJsonSchema()` 得到 `{"type":"object","properties":{...}}`
 *
 * @return JSON Schema 字符串
 */
fun <T> KSerializer<T>.toJsonSchema(): String = buildObjectSchema(descriptor).toString()
