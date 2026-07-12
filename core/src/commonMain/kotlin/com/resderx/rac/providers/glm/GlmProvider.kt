package com.resderx.rac.providers.glm

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * GLM（智谱 AI）供应商工厂函数。
 *
 * - 作用：创建 GLM 的 ModelProvider 实例，预设 baseUrl、默认模型与 API 类型
 * - 必要性：封装 GLM 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：使用 SimpleModelProvider 减少样板；config 字段覆盖默认值
 * - 实现方式：工厂函数接收 ProviderConfig，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （GLM 需要 Bearer 鉴权，调用方应设置 apiKey）
 *
 * 默认配置：
 * - baseUrl：https://open.bigmodel.cn/api/paas/v4（智谱开放平台 OpenAI 兼容端点）
 * - 默认模型：glm-4-flash（免费档快速模型）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - glm-4-flash 为免费档，存在速率限制与并发上限，生产环境建议切换至 glm-4 / glm-4-plus
 * - 流式响应需通过 stream 参数显式开启
 *
 * @param config 供应商配置覆盖项
 * @return 配置完成的 ModelProvider 实例
 */
fun GlmProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "glm",
    baseUrl = config.baseUrl ?: "https://open.bigmodel.cn/api/paas/v4",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "glm-4-flash",
)
