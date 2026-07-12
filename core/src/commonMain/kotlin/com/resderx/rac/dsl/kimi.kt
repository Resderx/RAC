package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.kimi.KimiProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块内注册 Kimi（Moonshot AI）供应商。
 *
 * - 作用：以 DSL 风格注册 Kimi 供应商，封装默认 baseUrl 与模型
 * - 必要性：提供 kimi { } DSL 入口，调用方仅需配置 apiKey 即可使用
 * - 设计思路：通过 ProviderConfigBuilder 收集覆盖项，调用 KimiProvider 工厂构造实例后注册
 * - 实现方式：RacBuilder 扩展函数，block 在 ProviderConfigBuilder 作用域内执行
 * - 边缘情况：block 未设置 apiKey 时供应商无鉴权，调用 Kimi 接口将返回 401
 *
 * @param block Kimi 供应商配置 lambda，在 ProviderConfigBuilder 作用域内执行
 */
fun RacBuilder.kimi(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(KimiProvider(providerConfig(block)))
}
