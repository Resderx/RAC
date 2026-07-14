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

package top.resderx.rac.providers.anthropic

import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ModelProvider
import top.resderx.rac.providers.ProviderConfig
import top.resderx.rac.providers.SimpleModelProvider

/**
 * 工厂函数：创建 Anthropic 供应商的 ModelProvider 实例。
 *
 * - 作用：以 RAC 内置的 Anthropic 默认配置（baseUrl/defaultApiType/anthropic-version/models）为基准，
 *   套用调用方传入的 [ProviderConfig] 覆盖项，结合模型注册表 [models] 产出不可变的 [SimpleModelProvider]
 * - 必要性：Anthropic 使用与 OpenAI 不同的 Messages API 协议，且鉴权方式不同（x-api-key 而非 Bearer），
 *   需独立工厂函数集中管理默认值与协议头；anthropic-version 头是 Anthropic API 强制要求
 * - 设计思路：defaultApiType 为 [ApiType.ANTHROPIC] 触发 RAC.chat { } 走 anthropicClient 分支；
 *   defaultHeaders 必须包含 "anthropic-version" → "2023-06-01"，与 config.extraHeaders 合并
 *   （extraHeaders 优先，允许调用方覆盖版本以使用 Beta API）；x-api-key 头不在此处注入，
 *   由 [top.resderx.rac.dsl.RAC.buildHeaders] 根据 defaultApiType == ANTHROPIC 分支动态添加；
 *   models 为模型名→配置的 Map，为空时自动注册默认模型 "claude-3-5-sonnet-20241022"（空 ModelConfig）
 * - 实现方式：纯函数，返回 [SimpleModelProvider] data class 实例；name 固定为 "anthropic" 用作注册键
 * - 边缘情况：config.apiKey 为 null 时 buildHeaders 不会添加 x-api-key，生产调用必须显式提供 apiKey；
 *   config.extraHeaders 中若包含 "anthropic-version" 键则会覆盖默认版本（用于 Beta API 切换）；
 *   config.baseUrl 覆盖常用于内网代理或自建 Anthropic 兼容网关；
 *   models 为空时回落到默认模型，保持零配置可用
 * - 优点：默认值集中、版本头与 extraHeaders 合并语义清晰；与 OpenAI/DeepSeek 工厂对称
 *
 * Anthropic 特性说明：
 * - baseUrl 默认 "https://api.anthropic.com/v1"
 * - defaultApiType 为 [ApiType.ANTHROPIC]（Messages API），调用 `chat { }` 时自动路由到 anthropicClient
 * - 默认模型注册项为 "claude-3-5-sonnet-20241022"（models 为空时自动注册）
 * - 鉴权方式：`x-api-key: <apiKey>` 头（由 RAC.buildHeaders 在 ANTHROPIC 分支注入，**非** Bearer Token）
 * - 必填请求头：`anthropic-version: 2023-06-01`（由本工厂注入到 defaultHeaders）
 * - 已知字段差异：Anthropic 将 system 消息作为顶层 `system` 字段而非 messages 列表项，
 *   由 ChatRequestBuilder.buildAnthropic() 处理映射；max_tokens 为必填字段（buildAnthropic 默认 4096）；
 *   响应 content 为 content blocks 列表（text/tool_use），由 AnthropicResponse.toAIMessage() 映射
 * - 限制：不支持 Completions API 与 Responses API；调用 `respond { }` 或在 Anthropic 供应商上调用
 *   `chatStream { }` 会抛 RACException，需改用 `anthropicStream { }`
 *
 * @param config 连接配置覆盖项（apiKey/baseUrl/headers），null 字段表示沿用 Anthropic 默认
 * @param models 模型注册表，键为模型名，值为 [ModelConfig]；为空时自动注册默认模型
 * @return 配置完成的 [ModelProvider]（实为 [SimpleModelProvider]），defaultHeaders 已包含 anthropic-version
 */
fun AnthropicProvider(
    config: ProviderConfig = ProviderConfig(),
    models: Map<String, ModelConfig> = emptyMap(),
): ModelProvider {
    val resolvedModels = models.takeIf { it.isNotEmpty() }
        ?: mapOf("claude-3-5-sonnet-20241022" to ModelConfig())
    return SimpleModelProvider(
        name = "anthropic",
        baseUrl = config.baseUrl ?: "https://api.anthropic.com/v1",
        apiKey = config.apiKey,
        defaultHeaders = mapOf("anthropic-version" to "2023-06-01") + config.extraHeaders,
        defaultApiType = ApiType.ANTHROPIC,
        models = resolvedModels,
    )
}
