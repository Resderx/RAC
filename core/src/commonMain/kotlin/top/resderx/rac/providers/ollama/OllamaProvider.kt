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

package top.resderx.rac.providers.ollama

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * Ollama 供应商工厂函数，构造一个 [ModelProvider] 用于连接本地或云端 Ollama 实例。
 *
 * - 作用：以 RAC 统一的 [ModelProvider] 抽象封装 Ollama 的连接元数据与默认调用参数，
 *   使核心可面向接口编程，无需为 Ollama 单独编写调用分支
 * - 必要性：Ollama 是 Task 9 需支持的 11 家供应商之一，且其本地模式（无鉴权）是
 *   [ModelProvider.apiKey] 设计为可空的关键场景之一
 * - 设计思路：连接配置与模型配置分离——支持两种模式：
 *   1. **本地模式（默认）**：当 [ProviderConfig.baseUrl] 与 [ProviderConfig.apiKey]
 *      均为 null 时，使用 `baseUrl = "http://localhost:11434/v1"`、`apiKey = null`
 *      （无鉴权）、模型注册表默认注册 "llama3.1"。此时 RAC.buildHeaders 不会添加
 *      Authorization 头，适用于本地裸跑的 Ollama 实例。
 *   2. **云端模式**：调用方通过 [ProviderConfig.baseUrl] 与 [ProviderConfig.apiKey]
 *      同时覆盖，连接远程托管的 Ollama 服务（如内网代理或云端 Ollama 实例）。
 *      此时 apiKey 非 null，将作为 Bearer token 注入 Authorization 头。
 *   两种模式都使用 [ApiType.COMPLETIONS]（Ollama 提供 OpenAI 兼容的
 *   `/v1/chat/completions` 端点），从而复用 Completions API 客户端；
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "llama3.1"（空 ModelConfig）
 * - 实现方式：顶层工厂函数返回 [SimpleModelProvider] 不可变数据类实例，所有属性 val；
 *   通过 Elvis 运算符在 config 字段为 null 时回落到默认值
 * - 边缘情况：
 *   - 仅设置 [ProviderConfig.baseUrl] 而不设置 [ProviderConfig.apiKey] 时，apiKey 仍为 null，
 *     适用于"自定义地址但无鉴权"的场景（如远程无鉴权 Ollama）
 *   - 仅设置 [ProviderConfig.apiKey] 而不设置 [ProviderConfig.baseUrl] 时，会连接本地地址但携带
 *     Authorization 头，本地 Ollama 默认忽略此头，不影响功能
 *   - [ProviderConfig.extraHeaders] 会原样作为 defaultHeaders 传入，调用方可注入自定义头
 *     （如代理所需的 X-Custom-Header）
 *   - models 为空时回落到默认模型，保持零配置可用
 *   - 本地模式下若 Ollama 未启动或端口被占用，请求将在 HttpClient 层失败，错误由调用方处理
 * - 优点：单一函数覆盖本地/云端两种模式，调用方无需感知差异；复用 [SimpleModelProvider]
 *   减少样板代码；本地模式零配置即可使用
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 Ollama 默认值（本地模式默认值）
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 构造完成的 [ModelProvider] 实例，name 为 "ollama"
 */
fun OllamaProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("llama3.1" to ModelConfig())
    return SimpleModelProvider(
        name = "ollama",
        baseUrl = config.baseUrl ?: "http://localhost:11434/v1",
        apiKey = config.apiKey,  // null for local, non-null for cloud
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
