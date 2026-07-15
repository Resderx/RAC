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

package top.resderx.rac.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 供应商支持的 API 协议类型。
 *
 * - 作用：标识供应商使用哪种 API 协议（Completions/Responses/Anthropic），供客户端路由选择对应的 API 客户端
 * - 必要性：RAC 支持 11 家供应商分属三种协议，需要统一枚举来决定调用 CompletionsClient/ResponsesClient/AnthropicClient
 * - 设计思路：用枚举而非字符串，编译期类型安全；序列化值使用小写蛇形，便于配置文件与 JSON 表达
 * - 实现方式：`@Serializable` 枚举，每个值用 `@SerialName` 指定稳定的序列化名
 * - 边缘情况：新增供应商若使用全新协议需扩展此枚举并新增对应 API 客户端；未识别的序列化值由调用方容错
 *
 * 枚举值说明：
 * - [COMPLETIONS]：OpenAI Chat Completions 风格，被 DeepSeek、Kimi、GLM、OpenAI、MiniMax、Ollama、Doubao、Qwen、MIMO、Gemini(兼容模式) 共用
 * - [RESPONSES]：OpenAI Responses 风格，当前仅 OpenAI 官方支持
 * - [ANTHROPIC]：Anthropic Messages 风格，Anthropic 原生使用
 */
@Serializable
enum class ApiType {
    @SerialName("completions") COMPLETIONS,
    @SerialName("responses") RESPONSES,
    @SerialName("anthropic") ANTHROPIC,
}
