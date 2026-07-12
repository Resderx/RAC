package com.resderx.rac.dsl

import com.resderx.rac.providers.ProviderConfigBuilder
import com.resderx.rac.providers.qwen.QwenProvider
import com.resderx.rac.providers.providerConfig

/**
 * 在 rac { } 块内注册 Qwen（阿里 DashScope 兼容模式）供应商。
 *
 * - 作用：以 DSL 风格注册 Qwen 供应商，封装默认 baseUrl 与模型
 * - 必要性：提供 qwen { } DSL 入口，调用方仅需配置 apiKey 即可使用
 * - 设计思路：通过 ProviderConfigBuilder 收集覆盖项，调用 QwenProvider 工厂构造实例后注册
 * - 实现方式：RacBuilder 扩展函数，block 在 ProviderConfigBuilder 作用域内执行
 * - 边缘情况：block 未设置 apiKey 时供应商无鉴权，调用 DashScope 接口将返回 401
 *
 * @param block Qwen 供应商配置 lambda，在 ProviderConfigBuilder 作用域内执行
 */
fun RacBuilder.qwen(block: ProviderConfigBuilder.() -> Unit) {
    registerProvider(QwenProvider(providerConfig(block)))
}
