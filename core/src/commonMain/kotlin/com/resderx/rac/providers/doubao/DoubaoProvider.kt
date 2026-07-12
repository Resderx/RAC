package com.resderx.rac.providers.doubao

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * Doubao Seed（火山引擎方舟）供应商工厂函数。
 *
 * - 作用：创建 Doubao 的 ModelProvider 实例，预设 baseUrl、默认模型与 API 类型
 * - 必要性：封装 Doubao 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：使用 SimpleModelProvider 减少样板；config 字段覆盖默认值
 * - 实现方式：工厂函数接收 ProviderConfig，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （Doubao 需要 Bearer 鉴权，调用方应设置 apiKey）
 *
 * 默认配置：
 * - baseUrl：https://ark.cn-beijing.volces.com/api/v3（火山方舟 OpenAI 兼容端点）
 * - 默认模型：doubao-seed-1-6（豆包 Seed 1.6 通用模型）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - 火山方舟实际调用使用"接入点 ID"（endpoint id）作为 model 参数，doubao-seed-1-6 为预置模型名，
 *   生产环境可能需在 config.model 中覆盖为实际 endpoint id（形如 ep-xxxxxxxx）
 * - 接入点需在火山方舟控制台显式创建并启用，否则返回 404
 *
 * @param config 供应商配置覆盖项
 * @return 配置完成的 ModelProvider 实例
 */
fun DoubaoProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "doubao",
    baseUrl = config.baseUrl ?: "https://ark.cn-beijing.volces.com/api/v3",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "doubao-seed-1-6",
)
