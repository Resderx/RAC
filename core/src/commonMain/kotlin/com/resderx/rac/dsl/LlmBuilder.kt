/*
 * Copyright 2026 Resderx
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resderx.rac.dsl

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.network.HttpClientFactory
import com.resderx.rac.network.RetryPolicy
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.ProviderRegistry
import com.resderx.rac.providers.presets.ModelPreset

/**
 * LLM 顶层实例的 DSL 构建器，以声明式风格注册供应商并配置全局参数。
 *
 * - 作用：在 `llm { }` 块内通过 `providers { }` 子块注册 LLM 供应商、设置默认供应商与超时等全局参数，
 *   最终产出可用的 [Llm] 实例
 * - 必要性：提供分层 DSL 入口——全局配置在外层，供应商注册在 `providers { }` 内，模型注册在
 *   `models { }` 内，使结构清晰有序，避免信息割裂
 * - 设计思路：内部持有 [ProviderRegistry]；`providers { }` 块由 [ProvidersBuilder] 承载；
 *   首个注册的供应商自动成为默认（基于 LinkedHashMap 插入序），可通过 [defaultProviderName] 覆盖；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 实现方式：类持有 ProviderRegistry 与可变 var 字段，build() 时创建 HttpClient 并装配 [Llm] 实例
 * - 可能的问题：构建器实例非线程安全；未注册任何供应商时 build() 抛 [RACException]
 * - 边缘情况：defaultProviderName 指向未注册的供应商时 build() 抛 [RACException]；
 *   timeoutMillis 为 null 时使用 HttpClientFactory 默认超时 60s
 * - 优点：分层 DSL 结构清晰——全局配置 / 供应商注册 / 模型注册三层分离，符合人类思考逻辑
 *
 * @property defaultProviderName 默认供应商名称，覆盖"首个注册即默认"规则，null 表示沿用首个注册
 * @property timeoutMillis HttpClient 超时毫秒数，null 表示使用默认 60s
 * @property retryPolicy 重试策略，null 表示使用默认 RetryPolicy()（3 次重试、指数退避）
 */
@RacDslMarker
class LlmBuilder {
    internal val registry = ProviderRegistry()

    /** 默认供应商名称，覆盖"首个注册即默认"规则，null 表示沿用首个注册的供应商。 */
    var defaultProviderName: String? = null

    /** HttpClient 超时毫秒数，null 表示使用默认 60s。 */
    var timeoutMillis: Long? = null

    /** 重试策略，null 表示使用默认 RetryPolicy()（3 次重试、指数退避）。 */
    var retryPolicy: RetryPolicy? = null

    /**
     * providers 块入口，在 lambda 内逐个注册供应商。
     *
     * - 作用：提供 `providers { deepseek { ... }; openai { ... } }` 风格的集中注册入口，
     *   使供应商注册与全局配置（timeoutMillis 等）分离，结构更清晰
     * - 必要性：旧设计将 `deepseek { }` 直接放在 `rac { }` 顶层，导致供应商注册与全局配置混在一起；
     *   新设计通过 `providers { }` 块显式分隔，符合"先配全局，再注册供应商"的思考逻辑
     *
     * @param block 在 [ProvidersBuilder] 作用域内注册供应商
     */
    fun providers(block: ProvidersBuilder.() -> Unit) {
        ProvidersBuilder(this).apply(block)
    }

    /**
     * 注册一个供应商到注册表（供 [ProvidersBuilder] 的扩展函数调用）。
     *
     * - 首个注册的供应商自动成为默认供应商（基于 LinkedHashMap 插入序）
     * - 可通过 [defaultProviderName] 覆盖默认选择
     * - internal 可见性供供应商 DSL 扩展（如 `deepseek { }`）调用
     *
     * @param provider 要注册的供应商实例
     */
    internal fun registerProvider(provider: ModelProvider) {
        registry.register(provider)
    }

    /**
     * 构建不可变的 [Llm] 实例。
     *
     * - 解析默认供应商（defaultProviderName 优先，否则取首个注册）
     * - 创建 HttpClient（通过 HttpClientFactory）
     * - 装配 [Llm] 实例
     *
     * @return 构建完成的 [Llm] 实例
     * @throws RACException 当未注册任何供应商或 defaultProviderName 指向未注册的供应商时
     */
    fun build(): Llm {
        val defaultName = defaultProviderName ?: registry.providers.keys.firstOrNull()
            ?: throw RACException("No provider registered in LLM")
        val defaultProvider = registry.get(defaultName)
        val timeout = timeoutMillis ?: 60_000L
        val client = HttpClientFactory.create(timeout)
        val policy = retryPolicy ?: RetryPolicy()
        return Llm(
            httpClient = client,
            registry = registry,
            defaultProvider = defaultProvider,
            retryPolicy = policy,
        )
    }
}

/**
 * providers 块构建器，在 `llm { providers { ... } }` 内使用，承载各供应商的注册扩展函数。
 *
 * - 作用：作为 `deepseek { }` / `openai { }` 等供应商 DSL 扩展函数的接收者，使它们只能在
 *   `providers { }` 块内调用，避免散落在顶层
 * - 必要性：通过独立的构建器类型限定供应商 DSL 的作用域，编译期保证结构层次清晰
 * - 设计思路：持有外层 [LlmBuilder] 引用，供应商注册时委托外层 builder 的 registerProvider；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 *
 * @param parent 外层 [LlmBuilder]，供应商注册委托其 registerProvider
 */
@RacDslMarker
class ProvidersBuilder internal constructor(private val parent: LlmBuilder) {

    /**
     * 注册一个已构造好的 [ModelProvider] 到外层 builder。
     *
     * - 供供应商 DSL 扩展函数（如 `deepseek { }`）在构造完 provider 后调用
     *
     * @param provider 要注册的供应商实例
     */
    internal fun register(provider: ModelProvider) {
        parent.registerProvider(provider)
    }
}

/**
 * 单个模型配置的 DSL 构建器，在 `models { model("xxx") { ... } }` 内使用。
 *
 * - 作用：以 DSL 风格逐步构建不可变的 [ModelConfig]，承载与具体模型绑定的参数
 * - 必要性：提供 `model("gpt-4o") { maxTokens = 4096; temperature = 0.7 }` 风格的配置 API，
 *   使模型参数可视化、结构化，避免在 chat { } 调用时重复设置
 * - 设计思路：可变 var 字段承载参数，build() 时产出不可变 [ModelConfig]；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染；
 *   支持从 [ModelConfig] 初始化（供 `model(preset)` 重载复用预设推荐配置）
 * - 实现方式：类持有可变 var 字段，build() 收集为不可变数据类；
 *   主构造为空参（默认全 null），internal 次构造接收 [ModelConfig] 作为初始值
 * - 边缘情况：未设置的参数为 null，表示沿用服务端默认
 *
 * @property maxTokens 最大生成 token 数，null 表示沿用服务端默认
 * @property temperature 采样温度，null 表示沿用服务端默认
 * @property topP nucleus sampling 参数，null 表示沿用服务端默认
 * @property systemPrompt 模型专属系统提示词，null 表示不设置
 * @property reasoningEffort 推理强度（如 "low"/"medium"/"high"），仅推理模型支持，null 表示不设置
 * @property stop 停止序列，null 表示不设置
 * @property seed 随机种子，null 表示沿用服务端随机
 * @property enableThinking 思考开关，true 启用扩展思考，false 禁用，null 表示沿用默认行为
 */
@RacDslMarker
class ModelBuilder {
    /** 最大生成 token 数，null 表示沿用服务端默认。 */
    var maxTokens: Long? = null

    /** 采样温度，null 表示沿用服务端默认。 */
    var temperature: Double? = null

    /** nucleus sampling 参数，null 表示沿用服务端默认。 */
    var topP: Double? = null

    /** 模型专属系统提示词，null 表示不设置（调用方可在 chat { } 内用 system() 覆盖）。 */
    var systemPrompt: String? = null

    /** 推理强度（如 "low"/"medium"/"high"），仅推理模型支持，null 表示不设置。 */
    var reasoningEffort: String? = null

    /** 停止序列，模型生成到任一字符串时立即停止，null 表示不设置。 */
    var stop: List<String>? = null

    /** 随机种子，用于确定性输出，null 表示沿用服务端随机。 */
    var seed: Long? = null

    /** 思考开关，true 启用扩展思考，false 禁用，null 表示沿用默认行为。 */
    var enableThinking: Boolean? = null

    /**
     * 从已有 [ModelConfig] 初始化构建器（供 `model(preset)` 重载复用预设推荐配置）。
     *
     * - 作用：将预设枚举的 recommendedConfig 字段拷贝到 var 字段作为初始值，
     *   随后 block 可覆盖部分字段，最终 build() 产出融合预设与覆盖的 [ModelConfig]
     * - 必要性：`model(DeepSeekModel.V4_FLASH) { maxTokens = 4096 }` 需要先用预设填充、再让 block 覆盖
     * - 可见性：internal——仅 [ModelsBuilder.model] 的 preset 重载使用
     *
     * @param initial 预设推荐配置，作为 var 字段初始值
     */
    internal constructor(initial: ModelConfig) {
        maxTokens = initial.maxTokens
        temperature = initial.temperature
        topP = initial.topP
        systemPrompt = initial.systemPrompt
        reasoningEffort = initial.reasoningEffort
        stop = initial.stop
        seed = initial.seed
        enableThinking = initial.enableThinking
    }

    /** 默认构造——所有字段为 null，表示无模型级默认覆盖。 */
    internal constructor()

    /** 构建不可变的 [ModelConfig]。 */
    internal fun build(): ModelConfig = ModelConfig(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        systemPrompt = systemPrompt,
        reasoningEffort = reasoningEffort,
        stop = stop,
        seed = seed,
        enableThinking = enableThinking,
    )
}

/**
 * models 块构建器，在 `deepseek { models { ... } }` 内使用，承载 model() 注册方法。
 *
 * - 作用：以 `model("xxx") { ... }` 或 `model(Preset.XXX) { ... }` 风格逐个注册模型，
 *   内部收集为模型名→配置的 Map
 * - 必要性：提供集中注册模型的入口，使一个 provider 下可注册多个不同配置的模型
 * - 设计思路：内部持有 LinkedHashMap（保持注册顺序，使首个注册的模型成为默认模型）；
 *   支持两种注册方式：
 *   1. `model("name") { ... }` —— 手动指定模型名与配置
 *   2. `model(Preset.XXX) { ... }` —— 从预设枚举读取模型名与推荐配置，block 可覆盖部分字段
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 边缘情况：未调用 model() 时 build() 返回空 Map，由供应商工厂函数回落到默认模型
 */
@RacDslMarker
class ModelsBuilder internal constructor() {
    private val _models: MutableMap<String, ModelConfig> = linkedMapOf()

    /**
     * 注册一个模型及其配置。
     *
     * @param name 模型名（如 "deepseek-v4-flash"、"gpt-4o"）
     * @param block 在 [ModelBuilder] 作用域内配置模型参数，默认空块表示沿用服务端默认
     */
    fun model(name: String, block: ModelBuilder.() -> Unit = {}) {
        _models[name] = ModelBuilder().apply(block).build()
    }

    /**
     * 通过预设枚举注册模型——一行代码拉取预设推荐配置，block 可覆盖部分字段。
     *
     * - 作用：将 `model(DeepSeekModel.V4_FLASH) { maxTokens = 4096 }` 这种调用转为：
     *   1. 用 `preset.modelName` 作为模型名
     *   2. 用 `preset.recommendedConfig` 作为 [ModelBuilder] 的初始值（通过 ModelBuilder(ModelConfig) 次构造）
     *   3. 应用 block，允许覆盖部分预设字段
     *   4. build() 产出融合预设与覆盖的 [ModelConfig]
     * - 必要性：免去用户手写模板代码（modelName + maxTokens + temperature + reasoningEffort ...），
     *   预设已按模型特性调优，用户只需 `model(DeepSeekModel.V4_FLASH)` 即可享受推荐配置
     * - 设计思路：委托 [ModelBuilder] 的 `internal constructor(ModelConfig)` 次构造完成预设注入；
     *   block 在预设之上覆盖，未覆盖的字段保留预设值
     * - 边缘情况：block 为空时完全使用预设配置；block 覆盖某字段时仅该字段被改，其余预设字段保留
     *
     * 示例：
     * ```
     * deepseek {
     *     apiKey("sk-...")
     *     models {
     *         model(DeepSeekModel.V4_FLASH)                     // 完全使用预设
     *         model(DeepSeekModel.V4_PRO) { maxTokens = 4096 }  // 覆盖 maxTokens，其余沿用预设
     *     }
     * }
     * ```
     *
     * @param preset 模型预设枚举（如 [com.resderx.rac.providers.presets.DeepSeekModel.V4_FLASH]）
     * @param block 在 [ModelBuilder] 作用域内覆盖预设参数，默认空块表示完全使用预设
     */
    fun model(preset: ModelPreset, block: ModelBuilder.() -> Unit = {}) {
        _models[preset.modelName] = ModelBuilder(preset.recommendedConfig).apply(block).build()
    }

    /** 构建不可变的模型注册表（保持注册顺序）。 */
    internal fun build(): Map<String, ModelConfig> = _models.toMap()
}

/**
 * LLM 顶层 DSL 入口函数，创建并构建 [Llm] 实例。
 *
 * - 作用：提供 `llm { }` 顶层入口，在 lambda 内通过 `providers { }` 注册供应商与配置全局参数，
 *   返回可用的 [Llm] 实例
 * - 必要性：DSL 入口比直接 LlmBuilder().build() 更符合 Kotlin 惯例，是库的主入口点
 * - 设计思路：创建 [LlmBuilder]，执行 block，调用 build() 产出 [Llm] 实例
 * - 实现方式：顶层 inline 函数接收带接收者的 lambda
 * - 可能的问题：block 内未注册任何供应商时 build() 抛 [RACException]
 * - 边缘情况：空 block 会因无供应商注册而抛异常
 *
 * 示例：
 * ```
 * val ai = llm {
 *     timeoutMillis = 60_000
 *     providers {
 *         deepseek {
 *             apiKey("sk-...")
 *             models {
 *                 model("deepseek-v4-flash") { maxTokens = 4096 }
 *                 model("deepseek-v4-pro") { reasoningEffort = "high" }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param block 在 [LlmBuilder] 作用域内执行的配置 lambda
 * @return 构建完成的 [Llm] 实例
 */
inline fun llm(block: LlmBuilder.() -> Unit): Llm =
    LlmBuilder().apply(block).build()
