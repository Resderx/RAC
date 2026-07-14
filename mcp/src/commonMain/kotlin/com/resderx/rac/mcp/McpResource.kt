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

package com.resderx.rac.mcp

import kotlinx.serialization.Serializable

/**
 * MCP 资源描述，表示服务器暴露的可读资源（文件、数据库记录等）。
 *
 * - 作用：标准化 MCP 服务器资源的元信息，供客户端发现与读取
 * - 必要性：MCP 协议的 resources/list 返回资源列表，需统一数据模型；
 *   RAC 的 McpClient.listResources() 返回此类型
 * - 设计思路：不可变数据类，uri 为唯一标识，description/mimeType 可空（服务器可选提供）
 * - 边缘：uri 格式由服务器定义（如 "file:///path"、"db://table/row"），客户端不校验格式
 *
 * @property uri 资源唯一标识符（URI 格式，由服务器定义）
 * @property name 资源名称（人类可读）
 * @property description 资源描述；可为 null（服务器未提供）
 * @property mimeType MIME 类型（如 "text/plain"、"application/json"）；可为 null
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)
