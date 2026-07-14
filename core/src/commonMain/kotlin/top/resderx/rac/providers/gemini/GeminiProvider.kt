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

package top.resderx.rac.providers.gemini

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * Gemini 供应商工厂函数，构造一个 [ModelProvider] 用于通过 Google 的 OpenAI 兼容端点连接 Gemini。
 *
 * - 作用：以 RAC 统一的 [ModelProvider] 抽象封装 Gemini 的连接元数据与默认调用参数，
 *   使核心可面向接口编程，无需为 Gemini 单独编写调用分支
 * - 必要性：Gemini 是 Task 9 需支持的 11 家供应商之一；通过 OpenAI 兼容端点接入可复用
 *   现有的 Completions API 客户端，无需实现 Gemini 原生协议
 * - 设计思路：连接配置与模型配置分离——使用 Google 官方提供的 OpenAI 兼容端点
 *   `https://generativelanguage.googleapis.com/v1beta/openai`，而非 Gemini 原生 API
 *   （`/v1beta/models/{model}:generateContent`）。这样 Gemini 与其他 OpenAI 兼容供应商
 *   （DeepSeek/Kimi/GLM 等）共用 [ApiType.COMPLETIONS] 协议，由 Completions API 客户端
 *   统一处理 `/chat/completions` 请求与响应解析。鉴权方面，Google API key 作为 Bearer token
 *   传入 Authorization 头（由 RAC.buildHeaders 在 [ModelProvider.apiKey] 非 null 时自动添加）；
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "gemini-1.5-flash"（空 ModelConfig）
 * - 实现方式：顶层工厂函数返回 [SimpleModelProvider] 不可变数据类实例，所有属性 val；
 *   通过 Elvis 运算符在 config 字段为 null 时回落到 Gemini 默认值
 * - 边缘情况：
 *   - [ProviderConfig.apiKey] 为 null 时不会添加 Authorization 头，Gemini 端点将返回 401；
 *     调用方必须通过 config 设置有效的 Google API key
 *   - [ProviderConfig.baseUrl] 可覆盖默认端点（如使用代理或 Vertex AI 的 OpenAI 兼容端点），
 *     但覆盖后的端点仍须兼容 OpenAI Chat Completions 协议，否则 Completions 客户端无法解析
 *   - models 可切换为其他 Gemini 模型（如 `gemini-1.5-pro`、`gemini-2.0-flash`），
 *     模型名使用 Gemini 原生命名（不含 `models/` 前缀），与 OpenAI 兼容端点的约定一致；
 *     models 为空时回落到默认模型，保持零配置可用
 *   - [ProviderConfig.extraHeaders] 会原样作为 defaultHeaders 传入，调用方可注入自定义头
 *   - 此工厂不调用 Gemini 原生 API（如 `:generateContent`、`:streamGenerateContent`），
 *     若需使用原生特性（如多模态 grounding、安全设置），需另外实现原生客户端
 * - 优点：复用 OpenAI 兼容端点避免协议分裂；复用 [SimpleModelProvider] 减少样板代码；
 *   Google API key 直接作为 Bearer token，无需额外的鉴权适配层
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 Gemini 默认值
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 构造完成的 [ModelProvider] 实例，name 为 "gemini"
 */
fun GeminiProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("gemini-1.5-flash" to ModelConfig())
    return SimpleModelProvider(
        name = "gemini",
        baseUrl = config.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta/openai",
        apiKey = config.apiKey,
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
