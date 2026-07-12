package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.openai.OpenAIProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块中注册 OpenAI 供应商。
 *
 * - 作用：以 DSL 风格在 RacBuilder 内注册 OpenAI 供应商，等价于
 *   `registerProvider(OpenAIProvider(providerConfig { ... }))`，但更简洁
 * - 必要性：作为 Task 9 供应商 DSL 扩展的一部分，提供 `openai { }` 顶层入口，
 *   避免调用方手动拼装 ProviderConfig 与 OpenAIProvider
 * - 设计思路：将 [ProviderConfigBuilder] 的 DSL lambda 透传给 [providerConfig] 构建不可变配置，
 *   再交给 [OpenAIProvider] 工厂产出 [com.resderx.rac.providers.ModelProvider]，最终通过
 *   内部 `registerProvider` 注册到 RacBuilder.registry
 * - 实现方式：RacBuilder 的扩展函数，接收带接收者的 lambda；调用 internal registerProvider
 * - 边缘情况：空 lambda 会使用 OpenAI 全默认配置（baseUrl/apiKey/model 沿用工厂默认）；
 *   apiKey 未在 lambda 中设置时生产调用会鉴权失败，需调用方显式 `apiKey("sk-...")`
 * - 优点：与 `deepseek { }` / `anthropic { }` 对称，DSL 风格统一
 *
 * 示例：
 * ```
 * rac {
 *     openai {
 *         apiKey("sk-...")
 *         model("gpt-4o")
 *     }
 * }
 * ```
 *
 * @param block 在 ProviderConfigBuilder 作用域内配置 OpenAI 的覆盖项
 */
fun RacBuilder.openai(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(OpenAIProvider(providerConfig(block)))
}
