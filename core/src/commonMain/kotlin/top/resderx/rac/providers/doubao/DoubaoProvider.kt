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

package top.resderx.rac.providers.doubao

import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ModelProvider
import top.resderx.rac.providers.ProviderConfig
import top.resderx.rac.providers.SimpleModelProvider

/**
 * Doubao Seed（火山引擎方舟）供应商工厂函数。
 *
 * - 作用：创建 Doubao 的 ModelProvider 实例，预设 baseUrl、模型注册表与 API 类型
 * - 必要性：封装 Doubao 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：连接配置与模型配置分离——使用 SimpleModelProvider 减少样板；config 字段覆盖连接默认值，
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "doubao-seed-1-6"（空 ModelConfig）
 * - 实现方式：工厂函数接收 ProviderConfig 与 models，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （Doubao 需要 Bearer 鉴权，调用方应设置 apiKey）；models 为空时回落到默认模型，保持零配置可用
 *
 * 默认配置：
 * - baseUrl：https://ark.cn-beijing.volces.com/api/v3（火山方舟 OpenAI 兼容端点）
 * - 默认模型注册项：doubao-seed-1-6（豆包 Seed 1.6 通用模型，models 为空时自动注册）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - 火山方舟实际调用使用"接入点 ID"（endpoint id）作为 model 参数，doubao-seed-1-6 为预置模型名，
 *   生产环境可能需在 models 中覆盖为实际 endpoint id（形如 ep-xxxxxxxx）
 * - 接入点需在火山方舟控制台显式创建并启用，否则返回 404
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 Doubao 默认
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 配置完成的 ModelProvider 实例
 */
fun DoubaoProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("doubao-seed-1-6" to ModelConfig())
    return SimpleModelProvider(
        name = "doubao",
        baseUrl = config.baseUrl ?: "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = config.apiKey,
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
