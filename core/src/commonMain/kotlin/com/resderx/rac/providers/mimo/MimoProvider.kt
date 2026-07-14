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

package com.resderx.rac.providers.mimo

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * 小米 MIMO 供应商工厂函数。
 *
 * - 作用：创建 MIMO 的 ModelProvider 实例，预设 baseUrl、模型注册表与 API 类型
 * - 必要性：封装 MIMO 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：连接配置与模型配置分离——使用 SimpleModelProvider 减少样板；config 字段覆盖连接默认值，
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "mimo-7b"（空 ModelConfig）
 * - 实现方式：工厂函数接收 ProviderConfig 与 models，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （MIMO 需要 Bearer 鉴权，调用方应设置 apiKey）；models 为空时回落到默认模型，保持零配置可用
 *
 * 默认配置：
 * - baseUrl：https://api.mimo.xiaomi.com/v1（小米 MIMO OpenAI 兼容端点）
 * - 默认模型注册项：mimo-7b（7B 参数量轻量模型，models 为空时自动注册）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - mimo-7b 为 7B 参数量轻量模型，复杂推理与长上下文能力有限，建议用于轻量任务
 * - 模型上下文窗口较小，长输入可能被截断或报错
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 MIMO 默认
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 配置完成的 ModelProvider 实例
 */
fun MimoProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("mimo-7b" to ModelConfig())
    return SimpleModelProvider(
        name = "mimo",
        baseUrl = config.baseUrl ?: "https://api.mimo.xiaomi.com/v1",
        apiKey = config.apiKey,
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
