package com.resderx.rac.providers.qwen

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * Qwen（阿里 DashScope OpenAI 兼容模式）供应商工厂函数。
 *
 * - 作用：创建 Qwen 的 ModelProvider 实例，预设 baseUrl、默认模型与 API 类型
 * - 必要性：封装 Qwen 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：使用 SimpleModelProvider 减少样板；config 字段覆盖默认值
 * - 实现方式：工厂函数接收 ProviderConfig，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （Qwen 需要 Bearer 鉴权，调用方应设置 apiKey）
 *
 * 默认配置：
 * - baseUrl：https://dashscope.aliyuncs.com/compatible-mode/v1（DashScope OpenAI 兼容模式端点）
 * - 默认模型：qwen-plus（均衡档位通义千问模型）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - 此 baseUrl 仅适用于 OpenAI 兼容模式，原生 DashScope 协议端点不同，不可混用
 * - qwen-plus 为均衡档位，对长上下文或高精度场景可切换至 qwen-max / qwen-turbo
 * - 部分高级参数（如 enable_search）需通过 extraHeaders 或请求体显式开启
 *
 * @param config 供应商配置覆盖项
 * @return 配置完成的 ModelProvider 实例
 */
fun QwenProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "qwen",
    baseUrl = config.baseUrl ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "qwen-plus",
)
