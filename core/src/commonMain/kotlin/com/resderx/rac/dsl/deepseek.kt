package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.deepseek.DeepSeekProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块中注册 DeepSeek 供应商。
 *
 * - 作用：以 DSL 风格在 RacBuilder 内注册 DeepSeek 供应商，等价于
 *   `registerProvider(DeepSeekProvider(providerConfig { ... }))`，但更简洁
 * - 必要性：作为 Task 9 供应商 DSL 扩展的一部分，提供 `deepseek { }` 顶层入口，
 *   避免调用方手动拼装 ProviderConfig 与 DeepSeekProvider
 * - 设计思路：将 [ProviderConfigBuilder] 的 DSL lambda 透传给 [providerConfig] 构建不可变配置，
 *   再交给 [DeepSeekProvider] 工厂产出 [com.resderx.rac.providers.ModelProvider]，最终通过
 *   内部 `registerProvider` 注册到 RacBuilder.registry
 * - 实现方式：RacBuilder 的扩展函数，接收带接收者的 lambda；调用 internal registerProvider
 * - 边缘情况：空 lambda 会使用 DeepSeek 全默认配置（baseUrl/apiKey/model 沿用工厂默认）；
 *   切换到 V4-Pro 推理模型需在 lambda 中显式 `model("deepseek-v4-pro")`；
 *   apiKey 未设置时生产调用会鉴权失败
 * - 优点：与 `openai { }` / `anthropic { }` 对称，DSL 风格统一
 *
 * 模型迁移提示：旧模型名 `deepseek-chat`/`deepseek-reasoner` 将于 2026-07-24 停用，
 * 自 v0.2.0 起默认模型已切换为 `deepseek-v4-flash`。
 *
 * 示例：
 * ```
 * rac {
 *     deepseek {
 *         apiKey("sk-...")
 *         model("deepseek-v4-pro")  // 切换到 V4-Pro 推理模型
 *     }
 * }
 * ```
 *
 * @param block 在 ProviderConfigBuilder 作用域内配置 DeepSeek 的覆盖项
 */
fun RacBuilder.deepseek(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(DeepSeekProvider(providerConfig(block)))
}
