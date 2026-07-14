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

package top.resderx.rac.providers.openai

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * 工厂函数：创建 OpenAI 供应商的 ModelProvider 实例。
 *
 * - 作用：以 RAC 内置的 OpenAI 默认配置（baseUrl/defaultApiType/models）为基准，
 *   套用调用方传入的 [ProviderConfig] 覆盖项，结合模型注册表 [models] 产出不可变的 [SimpleModelProvider]
 * - 必要性：OpenAI 是 RAC 支持的 11 家供应商之一，提供工厂函数集中管理默认值与覆盖逻辑，
 *   避免调用方手动拼装 SimpleModelProvider 参数
 * - 设计思路：连接配置与模型配置分离——所有连接默认值在此处集中声明（baseUrl/apiType），
 *   config 中对应字段的非 null 值覆盖默认；apiKey 透传（可空以支持 Mock 测试），
 *   Bearer 鉴权头由 [com.resderx.rac.dsl.RAC.buildHeaders] 统一注入，供应商本身不硬编码 Authorization 头；
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "gpt-4o-mini"（空 ModelConfig）
 * - 实现方式：纯函数，返回 [SimpleModelProvider] data class 实例；name 固定为 "openai" 用作注册键
 * - 边缘情况：config.apiKey 为 null 时供应商的 apiKey 字段为 null，buildHeaders 不会添加 Authorization，
 *   生产调用必须显式提供 apiKey；config.baseUrl 覆盖常用于自建代理或 Azure OpenAI 兼容端点；
 *   config.extraHeaders 用于追加组织级头（如 OpenAI-Beta），与空默认头合并；
 *   models 为空时回落到默认模型，保持零配置可用
 * - 优点：默认值集中、覆盖语义清晰；与 DeepSeek/Anthropic 工厂对称，降低学习成本
 *
 * OpenAI 特性说明：
 * - baseUrl 默认 "https://api.openai.com/v1"
 * - defaultApiType 为 [ApiType.COMPLETIONS]（Chat Completions），用户也可显式调用 `respond { }` 走 Responses API
 * - 默认模型注册项为 "gpt-4o-mini"（models 为空时自动注册）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 注入 `Authorization: Bearer <apiKey>`）
 * - 已知字段差异：reasoning_content 不返回（OpenAI 推理模型走 Responses API 的 reasoning items）
 * - 限制：Responses API 当前仅 OpenAI 官方支持，调用 `respond { }` 时其他供应商会失败
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 OpenAI 默认
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 配置完成的 [ModelProvider]（实为 [SimpleModelProvider]），可直接传入 RAC 注册表
 */
fun OpenAIProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("gpt-4o-mini" to ModelConfig())
    return SimpleModelProvider(
        name = "openai",
        baseUrl = config.baseUrl ?: "https://api.openai.com/v1",
        apiKey = config.apiKey,
        defaultHeaders = config.extraHeaders,
        defaultApiType = ApiType.COMPLETIONS,
        models = resolvedModels,
    )
}
