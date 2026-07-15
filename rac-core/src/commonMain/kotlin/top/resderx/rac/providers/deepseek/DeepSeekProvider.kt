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

package top.resderx.rac.providers.deepseek

import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ModelProvider
import top.resderx.rac.providers.ProviderConfig
import top.resderx.rac.providers.SimpleModelProvider

/**
 * 工厂函数：创建 DeepSeek 供应商的 ModelProvider 实例。
 *
 * - 作用：以 RAC 内置的 DeepSeek 默认连接配置（baseUrl/apiType）为基准，套用调用方传入的
 *   [ProviderConfig] 覆盖项，结合模型注册表 [models] 产出不可变的 [SimpleModelProvider]
 * - 必要性：DeepSeek 是 OpenAI Chat Completions 协议兼容的国产供应商，提供工厂函数集中管理默认值
 * - 设计思路：连接配置与模型配置分离——config 仅含连接信息（apiKey/baseUrl/headers），
 *   models 为模型名→配置的 Map；models 为空时自动注册默认模型 "deepseek-v4-flash"（空 ModelConfig）
 * - 实现方式：纯函数，返回 [SimpleModelProvider]；name 固定为 "deepseek" 用作注册键
 * - 边缘情况：config.apiKey 为 null 时生产调用会鉴权失败，但 Mock 测试可透传 null；
 *   models 为空时回落到默认模型，保持零配置可用
 *
 * DeepSeek 特性说明：
 * - baseUrl 默认 "https://api.deepseek.com"（不含 /v1 后缀，DeepSeek 官方约定）
 * - defaultApiType 为 [ApiType.COMPLETIONS]（OpenAI 兼容）
 * - 鉴权方式：Bearer Token（由 Llm.buildHeaders 注入 `Authorization: Bearer <apiKey>`）
 *
 * 模型迁移提示（2026-07）：
 * - 旧模型名 `deepseek-chat` 与 `deepseek-reasoner` 将于 **2026-07-24** 停用
 * - 自 v0.2.0 起默认模型已切换为 `deepseek-v4-flash`
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 DeepSeek 默认
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 配置完成的 [ModelProvider]，可直接传入 LLM 注册表
 */
fun DeepSeekProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("deepseek-v4-flash" to ModelConfig())
    return SimpleModelProvider(
        name = "deepseek",
        baseUrl = config.baseUrl ?: "https://api.deepseek.com",
        apiKey = config.apiKey,
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
