@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.resderx.rac.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * 消息内容密封接口，统一抽象多模态消息的正文部分。
 *
 * - 作用：抽象对话消息中的内容载体，支持文本/图片/音频三种模态
 * - 必要性：现代大模型支持多模态输入，需要统一类型表达不同模态内容，便于跨供应商复用
 * - 设计思路：密封接口限制子类型集合为已知三种，编译期穷尽匹配；用 `type` 字段作为序列化判别符
 * - 实现方式：接口标注 `@Serializable` 与 `@JsonClassDiscriminator("type")`，每个子类用 `@SerialName`
 *   指定固定的 type 值（text/image/audio），调用方 Json 实例需开启多态支持
 * - 边缘情况：API 客户端把各家供应商的多模态字段映射到对应子类；某些供应商不支持音频时由客户端降级或忽略
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface Content

/**
 * 纯文本内容。
 *
 * - 作用：承载最常见的字符串型消息正文
 * - 必要性：所有供应商都支持纯文本输入，是默认且必选的内容形态
 * - 设计思路：单一不可变字符串字段，序列化为 `{"type":"text","text":"..."}`
 * - 实现方式：`@Serializable` 数据类，`@SerialName("text")` 区分类型
 * - 边缘情况：空字符串合法（表示占位），由调用方决定是否过滤
 *
 * @property text 文本正文，不可为 null 但允许为空字符串
 */
@Serializable
@SerialName("text")
data class TextContent(
    val text: String,
) : Content

/**
 * 图片内容，支持 URL 或 base64 两种来源。
 *
 * - 作用：承载图片模态的输入，用于视觉类模型调用
 * - 必要性：跨供应商统一表达图片输入，OpenAI/Anthropic 等都支持 url 或 base64 两种来源
 * - 设计思路：url 与 base64 互斥可选，至少提供一个；mimeType 默认 image/jpeg 适配最常见场景
 * - 实现方式：`@Serializable` 数据类，`@SerialName("image")` 区分类型；init 块校验 url/base64 至少一个非 null，
 *   否则抛 `IllegalArgumentException`
 * - 边缘情况：两者同时提供时以 base64 为优先（由 API 客户端决定）；mimeType 不区分大小写由客户端处理
 *
 * @property url 图片可访问 URL，与 base64 至少一个非 null
 * @property base64 图片 base64 编码（不含 data: 前缀），与 url 至少一个非 null
 * @property mimeType MIME 类型，默认 "image/jpeg"
 */
@Serializable
@SerialName("image")
data class ImageContent(
    val url: String? = null,
    val base64: String? = null,
    val mimeType: String = "image/jpeg",
) : Content {
    init {
        require(url != null || base64 != null) {
            "ImageContent requires at least one of url or base64 to be non-null"
        }
    }
}

/**
 * 音频内容，以 base64 编码承载。
 *
 * - 作用：承载音频模态的输入，用于语音类模型调用
 * - 必要性：部分供应商（如 OpenAI 音频接口）支持音频输入，需要统一类型表达
 * - 设计思路：base64 为必填（音频无 URL 直传的通用协议），mimeType 默认 audio/wav 适配最常见场景
 * - 实现方式：`@Serializable` 数据类，`@SerialName("audio")` 区分类型
 * - 边缘情况：供应商不支持音频时由 API 客户端在映射阶段报错或降级；大音频注意 base64 体积
 *
 * @property base64 音频 base64 编码（不含 data: 前缀），必填
 * @property mimeType MIME 类型，默认 "audio/wav"
 */
@Serializable
@SerialName("audio")
data class AudioContent(
    val base64: String,
    val mimeType: String = "audio/wav",
) : Content
