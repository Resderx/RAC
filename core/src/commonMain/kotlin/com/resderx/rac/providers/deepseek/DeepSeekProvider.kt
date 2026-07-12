package com.resderx.rac.providers.deepseek

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * 工厂函数：创建 DeepSeek 供应商的 ModelProvider 实例。
 *
 * - 作用：以 RAC 内置的 DeepSeek 默认配置（baseUrl/defaultModel/defaultApiType）为基准，
 *   套用调用方传入的 [ProviderConfig] 覆盖项，产出不可变的 [SimpleModelProvider]
 * - 必要性：DeepSeek 是 OpenAI Chat Completions 协议兼容的国产供应商，提供工厂函数集中管理默认值，
 *   避免调用方手动拼装参数；DeepSeek-V4-Flash/V4-Pro 等模型仅需切换 model 字段即可复用同一供应商
 * - 设计思路：与 [com.resderx.rac.providers.openai.OpenAIProvider] 对称，仅默认值不同；
 *   Bearer 鉴权头由 [com.resderx.rac.dsl.RAC.buildHeaders] 统一注入，供应商本身不硬编码 Authorization
 * - 实现方式：纯函数，返回 [SimpleModelProvider] data class 实例；name 固定为 "deepseek" 用作注册键
 * - 边缘情况：config.apiKey 为 null 时生产调用会鉴权失败，但 Mock 测试可透传 null；
 *   切换到 V4-Pro 推理模型时通过 config.model = "deepseek-v4-pro" 覆盖默认；
 *   config.baseUrl 覆盖常用于内网代理
 * - 优点：默认值集中、与 OpenAI 工厂对称；reasoning_content 字段已在 CompletionsResponse 映射层处理
 *
 * DeepSeek 特性说明：
 * - baseUrl 默认 "https://api.deepseek.com"（不含 /v1 后缀，DeepSeek 官方约定）
 * - defaultApiType 为 [ApiType.COMPLETIONS]（OpenAI 兼容）
 * - defaultModel 为 "deepseek-v4-flash"（V4 系列轻量模型，非思考模式）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 注入 `Authorization: Bearer <apiKey>`）
 * - 已知字段差异：DeepSeek-V4-Pro 等推理模型在响应中返回 `reasoning_content` 字段，
 *   已由 CompletionsResponse/ResponseMessage 解析并经 toAIMessage() 映射到 AIMessage.reasoningContent，
 *   供应商工厂层无需特殊处理
 * - 限制：不支持 Anthropic 协议与 Responses API；调用 `respond { }` 会失败
 *
 * 模型迁移提示（2026-07）：
 * - 旧模型名 `deepseek-chat` 与 `deepseek-reasoner` 将于 **2026-07-24** 停用
 * - 迁移映射：`deepseek-chat` → `deepseek-v4-flash`（非思考模式），
 *   `deepseek-reasoner` → `deepseek-v4-flash` 思考模式 或 `deepseek-v4-pro`（更强推理能力）
 * - 自 v0.2.0 起，本工厂默认模型已从 `deepseek-chat` 切换为 `deepseek-v4-flash`
 *
 * @param config 调用方对默认配置的覆盖项集合，所有字段可空，null 表示沿用 DeepSeek 默认
 * @return 配置完成的 [ModelProvider]（实为 [SimpleModelProvider]），可直接传入 RAC 注册表
 */
fun DeepSeekProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "deepseek",
    baseUrl = config.baseUrl ?: "https://api.deepseek.com",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "deepseek-v4-flash",
)
