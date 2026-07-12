package com.resderx.rac.providers

/**
 * DSL 作用域标记，限制 ProviderConfigBuilder 的嵌套调用范围。
 *
 * - 作用：标注 ProviderConfigBuilder 的 DSL 作用域，防止在嵌套 lambda 中误访问外层接收者的成员
 * - 必要性：DSL 构建器使用 @DslMarker 防止作用域污染，编译期捕获跨作用域调用
 * - 实现方式：注解类标注 @DslMarker，目标为类
 * - 边缘情况：此为本地标记，Task 8 将引入统一的 RacDslMarker，届时可迁移
 */
@DslMarker
annotation class ProviderConfigDsl

/**
 * 供应商配置，允许调用方覆盖供应商的默认值。
 *
 * - 作用：承载对供应商默认配置的覆盖项（apiKey/model/baseUrl/timeout/headers）
 * - 必要性：供应商提供默认 baseUrl 与 model，但调用方常需覆盖（如自定义代理地址、切换模型、注入鉴权）
 * - 设计思路：所有字段可空，null 表示不覆盖、沿用供应商默认；extraHeaders 用于追加自定义头
 * - 实现方式：不可变数据类，所有字段 val 且有默认值；通过 ProviderConfigBuilder 构造
 * - 边缘情况：apiKey 覆盖为 null 时表示沿用供应商默认（不等于显式禁用鉴权）；extraHeaders 与供应商
 *   defaultHeaders 键冲突时，由调用方决定合并策略
 *
 * @property apiKey 覆盖供应商的 API 密钥，null 表示沿用供应商默认
 * @property model 覆盖默认模型名，null 表示沿用供应商默认
 * @property baseUrl 覆盖供应商 baseUrl，null 表示沿用供应商默认
 * @property timeoutMillis 请求超时毫秒数，null 表示沿用全局默认
 * @property extraHeaders 额外请求头，追加到供应商默认头之上
 */
data class ProviderConfig(
    val apiKey: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val timeoutMillis: Long? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * ProviderConfig 的 DSL 构建器。
 *
 * - 作用：以 DSL 风格逐步构建不可变的 ProviderConfig
 * - 必要性：提供 lambda 风格的配置 API，比直接调用构造函数更清晰，支持条件配置
 * - 设计思路：可变内部状态，build() 时产出不可变 ProviderConfig；标注 @ProviderConfigDsl 限制作用域
 * - 实现方式：类持有可变 var 字段，每个配置方法返回 Unit（DSL 惯例），build() 收集为不可变数据类
 * - 可能的问题：构建器实例非线程安全，多线程并发使用同一实例需调用方同步
 * - 边缘情况：未调用的配置项保持 null/空，build() 时沿用默认；重复调用同一方法以后值为准
 * - 优点：相比构造函数，DSL 方式可读性更高，支持在 lambda 内条件分支配置
 * - 数据结构：扁平可变字段 + MutableMap，无嵌套结构
 * - 时间复杂度：build() O(m)，m 为 extraHeaders 大小
 * - 空间复杂度：O(m)，m 为 extraHeaders 大小
 */
@ProviderConfigDsl
class ProviderConfigBuilder {
    private var apiKey: String? = null
    private var model: String? = null
    private var baseUrl: String? = null
    private var timeoutMillis: Long? = null
    private var extraHeaders: MutableMap<String, String> = mutableMapOf()

    /** 设置 API 密钥，覆盖供应商默认。传 null 表示沿用默认。 */
    fun apiKey(key: String?) {
        this.apiKey = key
    }

    /** 设置模型名，覆盖供应商默认。传 null 表示沿用默认。 */
    fun model(model: String?) {
        this.model = model
    }

    /** 设置 baseUrl，覆盖供应商默认。传 null 表示沿用默认。 */
    fun baseUrl(url: String?) {
        this.baseUrl = url
    }

    /** 设置请求超时毫秒数。传 null 表示沿用全局默认。 */
    fun timeoutMillis(ms: Long?) {
        this.timeoutMillis = ms
    }

    /** 追加单个额外请求头。 */
    fun header(name: String, value: String) {
        this.extraHeaders[name] = value
    }

    /** 批量追加额外请求头。 */
    fun headers(headers: Map<String, String>) {
        this.extraHeaders.putAll(headers)
    }

    /** 构建不可变的 ProviderConfig。 */
    fun build(): ProviderConfig = ProviderConfig(
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        timeoutMillis = timeoutMillis,
        extraHeaders = extraHeaders.toMap(),
    )
}

/**
 * 以 DSL 风格构建 ProviderConfig。
 *
 * - 作用：提供 providerConfig { } 顶层入口，简化 ProviderConfig 构造
 * - 必要性：DSL 入口比直接 ProviderConfigBuilder() 更符合 Kotlin 惯例
 * - 实现方式：顶层 inline 函数接收带接收者的 lambda，在 builder 上执行后返回 build() 结果
 * - 边缘情况：空 lambda 返回全默认配置（所有字段 null/空）
 *
 * @param block 配置 lambda，在 ProviderConfigBuilder 作用域内执行
 * @return 构建完成的不可变 ProviderConfig
 */
inline fun providerConfig(block: ProviderConfigBuilder.() -> Unit): ProviderConfig =
    ProviderConfigBuilder().apply(block).build()
