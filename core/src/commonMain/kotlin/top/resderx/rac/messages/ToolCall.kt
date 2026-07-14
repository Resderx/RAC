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

package top.resderx.rac.messages

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 模型发起的一次工具调用。
 *
 * - 作用：描述助手消息中模型请求执行的工具调用（函数名 + 参数）
 * - 必要性：工具调用是 Agent 流程的核心数据结构，跨供应商统一表达
 * - 设计思路：arguments 用 JSON 字符串而非结构化对象，避免与具体工具参数 schema 耦合，由调用方自行反序列化
 * - 序列化格式：OpenAI/DeepSeek API 期望嵌套结构 `{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`，
 *   而非扁平的 `{id,name,arguments}`；故用 [ToolCallSerializer] 自定义序列化器，内部数据结构保持扁平便于代码使用，
 *   序列化/反序列化时自动转换为 API 期望的嵌套格式
 * - 实现方式：`@Serializable(with = ToolCallSerializer::class)` 不可变数据类，所有字段为 val
 * - 边缘情况：arguments 可能为空字符串 `{}` 或非法 JSON，由工具执行方负责校验；id 在流式场景下可能分片到达，
 *   由 API 客户端负责聚合后再产出本对象；反序列化时兼容缺失 `type` 字段（默认 "function"）
 *
 * @property id 工具调用唯一标识，由模型生成，用于关联后续的 ToolMessage 回执
 * @property name 要调用的工具（函数）名称，需与 ToolDefinition.name 匹配
 * @property arguments 工具参数的 JSON 字符串，由调用方按工具 schema 反序列化
 */
@Serializable(with = ToolCallSerializer::class)
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * [ToolCall] 的自定义序列化器——在扁平内部结构与 OpenAI/DeepSeek API 嵌套格式之间转换。
 *
 * - 作用：序列化时把 `ToolCall(id, name, arguments)` 转为 `{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`；
 *   反序列化时执行逆向转换，兼容 `function` 字段缺失的宽松格式
 * - 必要性：OpenAI/DeepSeek API 的 tool_calls 数组元素要求嵌套 `function` 对象（含 name + arguments），
 *   并带固定 `type="function"` 字段；若用默认序列化器会产出扁平 `{id,name,arguments}`，API 返回 400 错误
 * - 设计思路：
 *   1. 序列化：用 `JsonEncoder.encodeJsonElement` 直接构造嵌套 JSON 对象——id + type="function" + function{name,arguments}
 *   2. 反序列化：用 `JsonDecoder.decodeJsonElement` 读取 JSON 对象——先取 id，再从嵌套 function 对象取 name/arguments；
 *      兼容 type 字段缺失（不校验值）；兼容扁平格式（若顶层直接有 name/arguments 则作为兜底）
 *   3. descriptor 用 [buildClassSerialDescriptor] 声明为 CLASS 类型，与 API JSON 对象对应
 * - 实现方式：继承 [KSerializer]，实现 serialize/deserialize，内部用 JSON 特定 API（JsonEncoder/JsonDecoder）
 * - 边缘情况：
 *   - `arguments` 为空字符串 → 原样输出到 `function.arguments`
 *   - 反序列化时 `function` 字段缺失 → 尝试从顶层取 `name`/`arguments`（兼容非标准格式）
 *   - 反序列化时 `type` 字段缺失或非 "function" → 不报错（宽松处理，仅关注 id/function）
 * - 优点：内部数据结构保持扁平便于代码使用，序列化格式符合 API 规范，双向转换透明
 * - 算法/数据结构：固定 3 字段的序列化/反序列化
 * - 时间复杂度：O(1)
 * - 空间复杂度：O(1)
 */
object ToolCallSerializer : KSerializer<ToolCall> {

    /** 序列化描述符——声明为 CLASS 类型，语义上对应 API JSON 对象（id/type/function）。 */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ToolCall")

    /**
     * 序列化——把扁平 [ToolCall] 转为 API 嵌套 JSON 格式。
     *
     * - 输出格式：`{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`
     * - 实现：用 `buildJsonObject` 构造嵌套 JSON 树，再委托 `JsonEncoder.encodeJsonElement` 输出
     *
     * @param encoder JSON 编码器（需为 JsonEncoder，否则抛 IllegalStateException）
     * @param value 要序列化的 ToolCall
     */
    override fun serialize(encoder: Encoder, value: ToolCall) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw IllegalStateException("ToolCallSerializer 需要 JsonEncoder，但得到 ${encoder::class}")
        val obj = buildJsonObject {
            put("id", value.id)
            put("type", "function")
            put("function", buildJsonObject {
                put("name", value.name)
                put("arguments", value.arguments)
            })
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    /**
     * 反序列化——从 API 嵌套 JSON 格式还原为扁平 [ToolCall]。
     *
     * - 输入格式：`{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`
     * - 兼容：`function` 字段缺失时尝试从顶层取 `name`/`arguments`（兜底非标准格式）
     * - 实现：用 `JsonDecoder.decodeJsonElement` 读取 JSON 对象，按字段名取值
     *
     * @param decoder JSON 解码器（需为 JsonDecoder，否则抛 IllegalStateException）
     * @return 反序列化的 ToolCall
     */
    override fun deserialize(decoder: Decoder): ToolCall {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw IllegalStateException("ToolCallSerializer 需要 JsonDecoder，但得到 ${decoder::class}")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val id = element["id"]?.jsonPrimitive?.content ?: ""
        // 从嵌套 function 对象取 name/arguments；若 function 缺失则从顶层兜底
        val functionObj = element["function"] as? JsonObject
        val name = functionObj?.get("name")?.jsonPrimitive?.content
            ?: element["name"]?.jsonPrimitive?.content ?: ""
        val arguments = functionObj?.get("arguments")?.jsonPrimitive?.content
            ?: element["arguments"]?.jsonPrimitive?.content ?: ""
        return ToolCall(id = id, name = name, arguments = arguments)
    }
}
