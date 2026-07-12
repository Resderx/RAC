package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.mimo.MimoProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块内注册小米 MIMO 供应商。
 *
 * - 作用：以 DSL 风格注册 MIMO 供应商，封装默认 baseUrl 与模型
 * - 必要性：提供 mimo { } DSL 入口，调用方仅需配置 apiKey 即可使用
 * - 设计思路：通过 ProviderConfigBuilder 收集覆盖项，调用 MimoProvider 工厂构造实例后注册
 * - 实现方式：RacBuilder 扩展函数，block 在 ProviderConfigBuilder 作用域内执行
 * - 边缘情况：block 未设置 apiKey 时供应商无鉴权，调用 MIMO 接口将返回 401
 *
 * @param block MIMO 供应商配置 lambda，在 ProviderConfigBuilder 作用域内执行
 */
fun RacBuilder.mimo(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(MimoProvider(providerConfig(block)))
}
