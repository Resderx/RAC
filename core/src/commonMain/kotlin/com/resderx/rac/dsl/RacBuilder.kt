package com.resderx.rac.dsl

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.network.HttpClientFactory
import com.resderx.rac.network.RetryPolicy
import com.resderx.rac.providers.ModelProvider
import com.resderx.rac.providers.ProviderRegistry

/**
 * RAC 顶层实例的 DSL 构建器，以声明式风格注册供应商并配置全局参数。
 *
 * - 作用：在 rac { } 块内注册 LLM 供应商、设置默认供应商与超时等全局参数，
 *   最终产出可用的 RAC 实例
 * - 必要性：提供 DSL 入口集中管理多供应商注册与 HttpClient 配置，避免调用方手动拼装依赖
 * - 设计思路：内部持有 ProviderRegistry，registerProvider 为 internal 供 Task 9 的供应商 DSL 扩展调用；
 *   首个注册的供应商自动成为默认（基于 LinkedHashMap 插入序），可通过 defaultProviderName 覆盖；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 实现方式：类持有 ProviderRegistry 与可变 var 字段，build() 时创建 HttpClient 并装配 RAC 实例；
 *   HttpClient 由 HttpClientFactory 创建，不在 RAC 类内硬依赖工厂（通过构造注入）
 * - 可能的问题：构建器实例非线程安全；未注册任何供应商时 build() 抛 RACException
 * - 边缘情况：defaultProviderName 指向未注册的供应商时 build() 抛 RACException；
 *   timeoutMillis 为 null 时使用 HttpClientFactory 默认超时 60s
 * - 优点：DSL 风格简洁，供应商注册与全局配置一目了然；internal registerProvider 为 Task 9 扩展预留入口
 * - 数据结构：ProviderRegistry（内部 LinkedHashMap）+ 扁平可变字段
 * - 时间复杂度：build() 为 O(1)（注册表查找 + HttpClient 装配）
 * - 空间复杂度：O(n)，n 为已注册供应商数量
 *
 * @property defaultProviderName 默认供应商名称，覆盖"首个注册即默认"规则，null 表示沿用首个注册
 * @property timeoutMillis HttpClient 超时毫秒数，null 表示使用默认 60s
 */
@RacDslMarker
class RacBuilder {
    internal val registry = ProviderRegistry()

    /** 默认供应商名称，覆盖"首个注册即默认"规则，null 表示沿用首个注册的供应商。 */
    var defaultProviderName: String? = null

    /** HttpClient 超时毫秒数，null 表示使用默认 60s。 */
    var timeoutMillis: Long? = null

    /** 重试策略，null 表示使用默认 RetryPolicy()（3 次重试、指数退避）。 */
    var retryPolicy: RetryPolicy? = null

    /**
     * 注册一个供应商到注册表。
     *
     * - 首个注册的供应商自动成为默认供应商（基于 LinkedHashMap 插入序）
     * - 可通过 defaultProviderName 覆盖默认选择
     * - internal 可见性供 Task 9 的供应商 DSL 扩展（如 deepseek { }）调用
     *
     * @param provider 要注册的供应商实例
     */
    internal fun registerProvider(provider: ModelProvider) {
        registry.register(provider)
    }

    /**
     * 构建不可变的 RAC 实例。
     *
     * - 解析默认供应商（defaultProviderName 优先，否则取首个注册）
     * - 创建 HttpClient（通过 HttpClientFactory）
     * - 装配 RAC 实例
     *
     * @return 构建完成的 RAC 实例
     * @throws RACException 当未注册任何供应商或 defaultProviderName 指向未注册的供应商时
     */
    fun build(): RAC {
        val defaultName = defaultProviderName ?: registry.providers.keys.firstOrNull()
            ?: throw RACException("No provider registered in RAC")
        val defaultProvider = registry.get(defaultName)
        val timeout = timeoutMillis ?: 60_000L
        val client = HttpClientFactory.create(timeout)
        val policy = retryPolicy ?: RetryPolicy()
        return RAC(
            httpClient = client,
            registry = registry,
            defaultProvider = defaultProvider,
            retryPolicy = policy,
        )
    }
}

/**
 * RAC 顶层 DSL 入口函数，创建并构建 RAC 实例。
 *
 * - 作用：提供 rac { } 顶层入口，在 lambda 内注册供应商与配置全局参数，返回可用的 RAC 实例
 * - 必要性：DSL 入口比直接 RacBuilder().build() 更符合 Kotlin 惯例，是库的主入口点
 * - 设计思路：创建 RacBuilder，执行 block，调用 build() 产出 RAC 实例
 * - 实现方式：顶层 inline 函数接收带接收者的 lambda
 * - 可能的问题：block 内未注册任何供应商时 build() 抛 RACException
 * - 边缘情况：空 block 会因无供应商注册而抛异常
 * - 优点：简洁的 DSL 入口，支持在 lambda 内条件注册供应商
 * - 算法/数据结构：委托 RacBuilder
 * - 时间复杂度：O(n)，n 为注册的供应商数
 * - 空间复杂度：O(n)，n 为注册的供应商数
 *
 * @param block 在 RacBuilder 作用域内执行的配置 lambda
 * @return 构建完成的 RAC 实例
 */
inline fun rac(block: RacBuilder.() -> Unit): RAC =
    RacBuilder().apply(block).build()
