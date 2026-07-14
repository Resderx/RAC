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

package top.resderx.rac.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * ACP v1 内容块模型，用于 `session/prompt` 的 prompt 字段与 `session/update` 的消息块。
 *
 * - 作用：定义 ACP 协议中用户提示与 Agent 消息的内容载体，支持文本/资源/图片/音频/资源链接五种类型
 * - 必要性：ACP v1 规范定义了与 MCP 兼容的 ContentBlock 结构，RAC 现有的 [com.resderx.rac.messages.Content]
 *   仅覆盖 text/image/audio，缺少 resource 与 resource_link，故独立建模 ACP 内容块
 * - 设计思路：密封接口 + `@JsonClassDiscriminator("type")` 多态序列化，子类型用 `@SerialName` 标记；
 *   基线 Agent MUST 支持 [AcpTextBlock] 与 [AcpResourceLinkBlock]
 * - 注意：不声明 `type` 属性——`@JsonClassDiscriminator("type")` 会自动在 JSON 中写入/读取鉴别字段，
 *   子类的 `@SerialName` 值即为鉴别字段值；若子类再声明 `type` 属性会与鉴别器冲突，导致序列化异常
 * - 规范来源：https://agentclientprotocol.com/protocol/v1/content
 * - 边缘：图片/音频内容需 Agent 的 [PromptCapabilities] 声明支持；resource 用于嵌入文件内容
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface AcpContentBlock

/**
 * 文本内容块。
 *
 * - 序列化后 JSON 形如 `{"type": "text", "text": "..."}`，
 *   其中 `"type": "text"` 由 `@SerialName("text")` + `@JsonClassDiscriminator("type")` 自动生成
 *
 * @property text 文本正文
 */
@Serializable
@SerialName("text")
data class AcpTextBlock(
    val text: String,
) : AcpContentBlock

/**
 * 资源内容块，嵌入文件或资源内容。
 *
 * - 作用：在提示中直接嵌入文件内容（如代码片段），供 Agent 无需读文件即可分析
 * - 边缘：`uri` 通常为 file:// 协议；`text` 与 `blob` 二选一（文本/二进制）
 *
 * @property resource 资源对象
 */
@Serializable
@SerialName("resource")
data class AcpResourceBlock(
    val resource: AcpResource,
) : AcpContentBlock

/** 资源内容，嵌入在 [AcpResourceBlock] 中。 */
@Serializable
data class AcpResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

/**
 * 图片内容块。
 *
 * @property data base64 编码图片数据
 * @property mimeType MIME 类型，默认 image/png
 */
@Serializable
@SerialName("image")
data class AcpImageBlock(
    val data: String,
    val mimeType: String = "image/png",
) : AcpContentBlock

/**
 * 音频内容块。
 *
 * @property data base64 编码音频数据
 * @property mimeType MIME 类型，默认 audio/wav
 */
@Serializable
@SerialName("audio")
data class AcpAudioBlock(
    val data: String,
    val mimeType: String = "audio/wav",
) : AcpContentBlock

/**
 * 资源链接块，引用外部资源（不嵌入内容）。
 *
 * - 作用：提示 Agent 需要读取指定 URI 的资源，而非直接提供内容
 * - 边缘：Agent 需通过 Client 的 `fs/read_text_file` 或自行读取
 *
 * @property uri 资源 URI（通常为 file://）
 * @property name 资源名称，可空
 */
@Serializable
@SerialName("resource_link")
data class AcpResourceLinkBlock(
    val uri: String,
    val name: String? = null,
) : AcpContentBlock
