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

package top.resderx.rac.providers

/**
 * DSL 作用域标记，限制 ProviderConfigBuilder 的嵌套调用范围。
 *
 * - 作用：标注 ProviderConfigBuilder 的 DSL 作用域，防止在嵌套 lambda 中误访问外层接收者的成员
 * - 必要性：DSL 构建器使用 @DslMarker 防止作用域污染，编译期捕获跨作用域调用
 * - 实现方式：注解类标注 @DslMarker，目标为类
 */
@DslMarker
annotation class ProviderConfigDsl

/**
 * 供应商连接配置（不含模型参数），在 `providers { deepseek { ... } }` 块内声明。
 *
 * - 作用：承载供应商的连接元数据（apiKey/baseUrl/extraHeaders），与模型专属配置（[ModelConfig]）分离
 * - 必要性：一个供应商只需配置一次连接信息，其下可注册多个不同参数的模型；
 *   旧设计将 model 混在 ProviderConfig 中，导致切换模型需重新配置连接，信息割裂
 * - 设计思路：所有字段可空，null 表示沿用供应商工厂默认（如 DeepSeek 的默认 baseUrl）；
 *   extraHeaders 用于追加自定义头（如 anthropic-version 已由工厂注入，此处可覆盖）
 * - 实现方式：不可变数据类，所有字段 val 且有默认值；通过 [ProviderConfigBuilder] 构造
 * - 边缘情况：apiKey 覆盖为 null 时表示沿用供应商默认；extraHeaders 与供应商
 *   defaultHeaders 键冲突时，由工厂函数决定合并策略
 *
 * @property apiKey 覆盖供应商的 API 密钥，null 表示沿用供应商默认
 * @property baseUrl 覆盖供应商 baseUrl，null 表示沿用供应商默认
 * @property extraHeaders 额外请求头，追加到供应商默认头之上
 */
data class ProviderConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * ProviderConfig 的 DSL 构建器，在 `providers { deepseek { ... } }` 的 provider 块内使用。
 *
 * - 作用：以 DSL 风格逐步构建不可变的 [ProviderConfig]（仅连接信息）
 * - 必要性：提供 lambda 风格的配置 API，比直接调用构造函数更清晰，支持条件配置
 * - 设计思路：可变内部状态，build() 时产出不可变 ProviderConfig；标注 @ProviderConfigDsl 限制作用域
 * - 实现方式：类持有可变 var 字段，每个配置方法返回 Unit（DSL 惯例），build() 收集为不可变数据类
 * - 可能的问题：构建器实例非线程安全，多线程并发使用同一实例需调用方同步
 * - 边缘情况：未调用的配置项保持 null/空，build() 时沿用默认；重复调用同一方法以后值为准
 * - 优点：相比构造函数，DSL 方式可读性更高，支持在 lambda 内条件分支配置
 *
 * - 数据结构：扁平可变字段 + MutableMap，无嵌套结构
 * - 时间复杂度：build() O(m)，m 为 extraHeaders 大小
 * - 空间复杂度：O(m)，m 为 extraHeaders 大小
 */
@ProviderConfigDsl
class ProviderConfigBuilder {
    private var apiKey: String? = null
    private var baseUrl: String? = null
    private var extraHeaders: MutableMap<String, String> = mutableMapOf()

    /** 设置 API 密钥，覆盖供应商默认。传 null 表示沿用默认。 */
    fun apiKey(key: String?) {
        this.apiKey = key
    }

    /** 设置 baseUrl，覆盖供应商默认。传 null 表示沿用默认。 */
    fun baseUrl(url: String?) {
        this.baseUrl = url
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
        baseUrl = baseUrl,
        extraHeaders = extraHeaders.toMap(),
    )
}
