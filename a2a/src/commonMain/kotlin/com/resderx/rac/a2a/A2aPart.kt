@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.resderx.rac.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A2A v1.0 Part 模型——Message 或 Artifact 内的最小内容单元。
 *
 * - 作用：定义 A2A 协议中消息与产出物的内容载体，支持文本/文件/结构化数据三种类型
 * - 必要性：A2A 规范用 Part 作为多模态内容的统一抽象，所有消息交互均通过 Part 列表承载
 * - 设计思路：密封接口 + `@JsonClassDiscriminator("kind")` 多态序列化，子类型用 `@SerialName` 标记
 * - 注意：不声明 `kind` 属性——`@JsonClassDiscriminator("kind")` 会自动在 JSON 中写入/读取鉴别字段，
 *   子类的 `@SerialName` 值即为鉴别字段值；若子类再声明 `kind` 属性会与鉴别器冲突
 * - 规范来源：https://a2a-protocol.org/latest/specification/#416-part
 * - 边缘：DataPart 的 `data` 字段为任意 JSON 对象；FilePart 可通过 uri 引用或 bytes 内嵌
 */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Part

/**
 * 文本内容 Part。
 *
 * - 序列化后 JSON 形如 `{"kind": "text", "text": "..."}`，
 *   其中 `"kind": "text"` 由 `@SerialName("text")` + `@JsonClassDiscriminator("kind")` 自动生成
 *
 * @property text 文本内容
 */
@Serializable
@SerialName("text")
data class TextPart(
    val text: String,
) : Part

/**
 * 文件内容 Part，通过 URI 引用或 Base64 内嵌二进制数据。
 *
 * @property file 文件体
 */
@Serializable
@SerialName("file")
data class FilePart(
    val file: FilePartBody,
) : Part

/**
 * 结构化数据 Part，承载任意 JSON 对象。
 *
 * - 作用：传输表单、结构化输出等非文本数据
 * - 边缘：`data` 为 `Map<String, String>`，复杂结构需扁平化为字符串
 *
 * @property data 数据体
 */
@Serializable
@SerialName("data")
data class DataPart(
    val data: DataPartBody,
) : Part

/**
 * A2A v1.0 Artifact 模型——Agent 任务产出物。
 *
 * - 作用：描述 Agent 生成的输出（文档、图片、结构化数据等），由一组 [Part] 构成
 * - 必要性：Task 的 `artifacts` 字段使用此模型；与 Message 区分——Message 是通信轮次，Artifact 是产出
 * - 边缘：`index` 用于多产出物排序；`lastChunk` 标识流式推送的最后一块
 *
 * @property artifactId 产出物唯一标识
 * @property name 产出物名称，可空
 * @property description 产出物描述，可空
 * @property parts 产出物内容块列表
 * @property index 产出物序号（默认 0）
 * @property lastChunk 是否为流式推送的最后一块，可空
 * @property metadata 扩展元数据，可空
 */
@Serializable
data class Artifact(
    val artifactId: String,
    val name: String? = null,
    val description: String? = null,
    val parts: List<Part> = emptyList(),
    val index: Int = 0,
    val lastChunk: Boolean? = null,
    val metadata: Map<String, String>? = null,
)
