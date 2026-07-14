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

package top.resderx.rac.dsl

import top.resderx.rac.providers.glm.GlmProvider

/**
 * 在 `providers { }` 块中注册 GLM（智谱 AI）供应商。
 *
 * 通过 [ProviderDsl] 同时配置连接信息（apiKey/baseUrl/headers）与 `models { }` 子块。
 * 空 lambda 使用 GLM 全默认配置；`models { }` 为空时由工厂函数回落到默认模型。
 *
 * 示例：
 * ```
 * llm {
 *     providers {
 *         glm {
 *             apiKey("...")
 *             models {
 *                 model("glm-4-flash") { maxTokens = 4096 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param block 在 [ProviderDsl] 作用域内配置连接与模型
 */
fun ProvidersBuilder.glm(block: ProviderDsl.() -> Unit) {
    val dsl = ProviderDsl().apply(block)
    register(GlmProvider(dsl.buildConfig(), dsl.buildModels()))
}
