package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.anthropic.AnthropicProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块中注册 Anthropic 供应商。
 *
 * - 作用：以 DSL 风格在 RacBuilder 内注册 Anthropic 供应商，等价于
 *   `registerProvider(AnthropicProvider(providerConfig { ... }))`，但更简洁
 * - 必要性：作为 Task 9 供应商 DSL 扩展的一部分，提供 `anthropic { }` 顶层入口，
 *   避免调用方手动拼装 ProviderConfig 与 AnthropicProvider（含 anthropic-version 默认头）
 * - 设计思路：将 [ProviderConfigBuilder] 的 DSL lambda 透传给 [providerConfig] 构建不可变配置，
 *   再交给 [AnthropicProvider] 工厂产出 [com.resderx.rac.providers.ModelProvider]，
 *   工厂内部自动注入 `anthropic-version: 2023-06-01` 头与 extraHeaders 合并；最终通过
 *   内部 `registerProvider` 注册到 RacBuilder.registry
 * - 实现方式：RacBuilder 的扩展函数，接收带接收者的 lambda；调用 internal registerProvider
 * - 边缘情况：空 lambda 会使用 Anthropic 全默认配置（含 anthropic-version 头与默认 claude 模型）；
 *   apiKey 未设置时生产调用会鉴权失败（x-api-key 头缺失）；extraHeaders 中提供 "anthropic-version"
 *   可覆盖默认版本以使用 Beta API；注册后 `chat { }` 会自动走 ANTHROPIC 分支，`chatStream { }` 会抛
 *   RACException，需改用 `anthropicStream { }`
 * - 优点：与 `openai { }` / `deepseek { }` 对称，DSL 风格统一；anthropic-version 头由工厂自动管理
 *
 * 示例：
 * ```
 * rac {
 *     anthropic {
 *         apiKey("sk-ant-...")
 *         model("claude-3-5-sonnet-20241022")
 *     }
 * }
 * ```
 *
 * @param block 在 ProviderConfigBuilder 作用域内配置 Anthropic 的覆盖项
 */
fun RacBuilder.anthropic(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(AnthropicProvider(providerConfig(block)))
}
