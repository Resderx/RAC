package com.resderx.rac.providers.minimax

import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.SimpleModelProvider

/**
 * MiniMax 供应商工厂函数。
 *
 * - 作用：创建 MiniMax 的 ModelProvider 实例，预设 baseUrl、默认模型与 API 类型
 * - 必要性：封装 MiniMax 的默认配置，调用方仅需提供 apiKey 即可使用
 * - 设计思路：使用 SimpleModelProvider 减少样板；config 字段覆盖默认值
 * - 实现方式：工厂函数接收 ProviderConfig，应用覆盖后构造 SimpleModelProvider
 * - 边缘情况：config.baseUrl 非 null 时覆盖默认 baseUrl；config.apiKey 为 null 时供应商无鉴权
 *   （MiniMax 需要 Bearer 鉴权，调用方应设置 apiKey）
 *
 * 默认配置：
 * - baseUrl：https://api.minimaxi.chat/v1（MiniMax OpenAI 兼容端点）
 * - 默认模型：abab6.5-chat（通用对话模型）
 * - 鉴权方式：Bearer Token（由 RAC.buildHeaders 自动注入 Authorization 头）
 * - API 类型：ApiType.COMPLETIONS（OpenAI Chat Completions 兼容）
 *
 * 已知限制：
 * - abab6.5-chat 上下文窗口受限，超长输入需切换至 abab6.5s 或其他长上下文模型
 * - 部分高级特性（如语音合成、视频生成）不在 Chat Completions 端点支持范围内
 *
 * @param config 供应商配置覆盖项
 * @return 配置完成的 ModelProvider 实例
 */
fun MiniMaxProvider(config: ProviderConfig): ModelProvider = SimpleModelProvider(
    name = "minimax",
    baseUrl = config.baseUrl ?: "https://api.minimaxi.chat/v1",
    apiKey = config.apiKey,
    defaultHeaders = config.extraHeaders,
    defaultApiType = ApiType.COMPLETIONS,
    defaultModel = config.model ?: "abab6.5-chat",
)
