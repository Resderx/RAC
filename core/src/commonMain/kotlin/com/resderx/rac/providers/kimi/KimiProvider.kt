package com.resderx.rac.providers.kimi

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * Kimi（Moonshot AI）供应商工厂函数。
 *
 * - 作用：创建 Kimi 的 ModelProvider 实例，预设 baseUrl、默认模型与 API 类型
 * - 必要性：封装 Kimi 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：使用 SimpleModelProvider 减少样板；config 字段覆盖默认值
 * - 实现方式：工厂函数接收 ProviderConfig，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （Kimi 需要 Bearer 鉴权，调用方应设置 apiKey）
 *
 * 默认配置：
 * - baseUrl：https://api.moonshot.cn/v1（Moonshot 官方 OpenAI 兼容端点）
 * - 默认模型：moonshot-v1-8k（8k 上下文窗口版本）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - moonshot-v1-8k 上下文窗口仅 8k tokens，长上下文场景需切换至 moonshot-v1-32k / moonshot-v1-128k
 * - 模型名需与 Moonshot 官方文档一致，错误模型名会返回 404
 *
 * @param config 供应商配置覆盖项
 * @return 配置完成的 ModelProvider 实例
 */
fun KimiProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "kimi",
    baseUrl = config.baseUrl ?: "https://api.moonshot.cn/v1",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "moonshot-v1-8k",
)
