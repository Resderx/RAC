package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.ollama.OllamaProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 [RacBuilder] 作用域内以 DSL 风格注册 Ollama 供应商。
 *
 * - 作用：提供 `rac { ollama { } }` 风格的 DSL 入口，封装 [OllamaProvider] 工厂调用与
 *   [ProviderConfigBuilder] 配置构建，调用方无需手动拼装 config 与 provider
 * - 必要性：Task 9 为每家供应商提供独立的 DSL 扩展函数，统一在 `rac { }` 块内声明式注册，
 *   提升可读性与一致性
 * - 设计思路：接收 [ProviderConfigBuilder] 带接收者的 lambda，先用 [providerConfig] 顶层
 *   函数构建不可变 [com.resderx.rac.providers.ProviderConfig]，再传给 [OllamaProvider] 工厂
 *   构造 [com.resderx.rac.providers.ModelProvider]，最后通过 [RacBuilder.registerProvider]
 *   注册到注册表
 * - 实现方式：RacBuilder 的扩展函数，内部调用 internal registerProvider；block 默认参数
 *   为空 lambda，即 `ollama()` 等价于 `ollama {}`，使用 Ollama 本地模式默认值
 * - 边缘情况：
 *   - 空 block（不传任何配置）时使用本地模式默认值：`baseUrl=http://localhost:11434/v1`、
 *     `apiKey=null`（无鉴权）、`defaultModel=llama3.1`，适用于本地裸跑 Ollama
 *   - 在 block 内设置 `baseUrl = "..."; apiKey = "..."` 即切换为云端模式
 *   - 重复调用 `ollama { }` 会以最后一次注册覆盖注册表中的 "ollama" 键
 *   - 此函数依赖 [RacBuilder.registerProvider] 的 internal 可见性，仅在同模块内可用
 * - 优点：零配置即可注册本地 Ollama；DSL 风格比直接 `registerProvider(OllamaProvider(...))`
 *   更简洁且符合 Kotlin 惯例
 *
 * @param block 在 [ProviderConfigBuilder] 作用域内执行的配置 lambda，可为空
 */
fun RacBuilder.ollama(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(OllamaProvider(providerConfig(block)))
}
