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

import com.resderx.rac.providers.ProviderConfigBuilder

/**
 * 通用供应商 DSL 作用域，聚合连接配置与模型注册，供所有供应商 DSL 扩展函数复用。
 *
 * - 作用：在 `deepseek { }` / `openai { }` 等供应商块内同时提供连接配置方法
 *   （apiKey/baseUrl/header/headers）与 `models { }` 子块入口，使两类配置在单一 lambda 内完成
 * - 必要性：[ProviderConfigBuilder] 只处理连接配置，[ModelsBuilder] 只处理模型注册，
 *   需要一个聚合类型使两者共存于同一 DSL 作用域；提取为通用类避免每个供应商重复定义
 * - 设计思路：内部持有 [ProviderConfigBuilder] 与可选的 [ModelsBuilder] 实例；
 *   连接配置方法委托给 [ProviderConfigBuilder]；`models { }` 委托给 [ModelsBuilder]；
 *   标注 @RacDslMarker 防止作用域污染
 * - 实现方式：类持有可变状态，buildConfig()/buildModels() 产出不可变结果
 * - 边缘情况：未调用 `models { }` 时 buildModels() 返回空 Map，由供应商工厂函数回落到默认模型
 */
@RacDslMarker
class ProviderDsl {
    private val configBuilder = ProviderConfigBuilder()
    private var modelsBuilder: ModelsBuilder? = null

    /** 设置 API 密钥，覆盖供应商默认。传 null 表示沿用默认。 */
    fun apiKey(key: String?) = configBuilder.apiKey(key)

    /** 设置 baseUrl，覆盖供应商默认。传 null 表示沿用默认。 */
    fun baseUrl(url: String?) = configBuilder.baseUrl(url)

    /** 追加单个额外请求头。 */
    fun header(name: String, value: String) = configBuilder.header(name, value)

    /** 批量追加额外请求头。 */
    fun headers(headers: Map<String, String>) = configBuilder.headers(headers)

    /**
     * models 块入口，在 lambda 内逐个注册模型。
     *
     * @param block 在 [ModelsBuilder] 作用域内注册模型
     */
    fun models(block: ModelsBuilder.() -> Unit) {
        modelsBuilder = ModelsBuilder().apply(block)
    }

    /** 构建连接配置。 */
    internal fun buildConfig() = configBuilder.build()

    /** 构建模型注册表，未调用 models { } 时返回空 Map（由工厂函数回落到默认模型）。 */
    internal fun buildModels() = modelsBuilder?.build() ?: emptyMap()
}
