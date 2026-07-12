package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.gemini.GeminiProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 [RacBuilder] 作用域内以 DSL 风格注册 Gemini 供应商。
 *
 * - 作用：提供 `rac { gemini { } }` 风格的 DSL 入口，封装 [GeminiProvider] 工厂调用与
 *   [ProviderConfigBuilder] 配置构建，调用方无需手动拼装 config 与 provider
 * - 必要性：Task 9 为每家供应商提供独立的 DSL 扩展函数，统一在 `rac { }` 块内声明式注册，
 *   提升可读性与一致性
 * - 设计思路：接收 [ProviderConfigBuilder] 带接收者的 lambda，先用 [providerConfig] 顶层
 *   函数构建不可变 [com.resderx.rac.providers.ProviderConfig]，再传给 [GeminiProvider] 工厂
 *   构造 [com.resderx.rac.providers.ModelProvider]，最后通过 [RacBuilder.registerProvider]
 *   注册到注册表
 * - 实现方式：RacBuilder 的扩展函数，内部调用 internal registerProvider；block 默认参数
 *   为空 lambda，即 `gemini()` 等价于 `gemini {}`，使用 Gemini 默认值（OpenAI 兼容端点 +
 *   gemini-1.5-flash），但此时 apiKey 为 null，调用前必须设置 apiKey
 * - 边缘情况：
 *   - 空 block（不传任何配置）时使用默认值：`baseUrl=generativelanguage.googleapis.com/...`、
 *     `defaultModel=gemini-1.5-flash`，但 `apiKey=null`，调用将返回 401；
 *     调用方应在 block 内设置 `apiKey = "your-google-api-key"`
 *   - 在 block 内设置 `apiKey = "..."` 即可正常调用；设置 `model = "gemini-1.5-pro"`
 *     可切换模型
 *   - 重复调用 `gemini { }` 会以最后一次注册覆盖注册表中的 "gemini" 键
 *   - 此函数依赖 [RacBuilder.registerProvider] 的 internal 可见性，仅在同模块内可用
 * - 优点：DSL 风格比直接 `registerProvider(GeminiProvider(...))` 更简洁且符合 Kotlin 惯例；
 *   与其他供应商 DSL（如 `ollama { }`、`deepseek { }`）形式一致，降低学习成本
 *
 * @param block 在 [ProviderConfigBuilder] 作用域内执行的配置 lambda，可为空
 */
fun RacBuilder.gemini(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(GeminiProvider(providerConfig(block)))
}
