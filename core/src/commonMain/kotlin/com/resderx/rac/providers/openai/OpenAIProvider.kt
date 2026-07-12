package com.resderx.rac.providers.openai

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * 工厂函数：创建 OpenAI 供应商的 ModelProvider 实例。
 *
 * - 作用：以 RAC 内置的 OpenAI 默认配置（baseUrl/defaultModel/defaultApiType）为基准，
 *   套用调用方传入的 [ProviderConfig] 覆盖项，产出不可变的 [SimpleModelProvider]
 * - 必要性：OpenAI 是 RAC 支持的 11 家供应商之一，提供工厂函数集中管理默认值与覆盖逻辑，
 *   避免调用方手动拼装 SimpleModelProvider 参数
 * - 设计思路：所有默认值在此处集中声明（baseUrl/apiType/model），config 中对应字段的非 null 值覆盖默认；
 *   apiKey 透传（可空以支持 Mock 测试），Bearer 鉴权头由 [com.resderx.rac.dsl.RAC.buildHeaders] 统一注入，
 *   供应商本身不硬编码 Authorization 头
 * - 实现方式：纯函数，返回 [SimpleModelProvider] data class 实例；name 固定为 "openai" 用作注册键
 * - 边缘情况：config.apiKey 为 null 时供应商的 apiKey 字段为 null，buildHeaders 不会添加 Authorization，
 *   生产调用必须显式提供 apiKey；config.baseUrl 覆盖常用于自建代理或 Azure OpenAI 兼容端点；
 *   config.extraHeaders 用于追加组织级头（如 OpenAI-Beta），与空默认头合并
 * - 优点：默认值集中、覆盖语义清晰；与 DeepSeek/Anthropic 工厂对称，降低学习成本
 *
 * OpenAI 特性说明：
 * - baseUrl 默认 "https://api.openai.com/v1"
 * - defaultApiType 为 [ApiType.COMPLETIONS]（Chat Completions），用户也可显式调用 `respond { }` 走 Responses API
 * - defaultModel 为 "gpt-4o-mini"
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 注入 `Authorization: Bearer <apiKey>`）
 * - 已知字段差异：reasoning_content 不返回（OpenAI 推理模型走 Responses API 的 reasoning items）
 * - 限制：Responses API 当前仅 OpenAI 官方支持，调用 `respond { }` 时其他供应商会失败
 *
 * @param config 调用方对默认配置的覆盖项集合，所有字段可空，null 表示沿用 OpenAI 默认
 * @return 配置完成的 [ModelProvider]（实为 [SimpleModelProvider]），可直接传入 RAC 注册表
 */
fun OpenAIProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "openai",
    baseUrl = config.baseUrl ?: "https://api.openai.com/v1",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "gpt-4o-mini",
)
