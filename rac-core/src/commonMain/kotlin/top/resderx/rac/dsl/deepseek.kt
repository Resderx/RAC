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

import top.resderx.rac.providers.deepseek.DeepSeekProvider

/**
 * 在 `providers { }` 块中注册 DeepSeek 供应商。
 *
 * - 作用：以 DSL 风格在 [ProvidersBuilder] 内注册 DeepSeek 供应商
 * - 必要性：提供 `deepseek { }` 入口，避免调用方手动拼装 ProviderConfig、ModelsBuilder 与 DeepSeekProvider
 * - 设计思路：lambda 内通过 [ProviderDsl] 同时承载连接配置（apiKey/baseUrl/headers）与
 *   `models { }` 子块，使两类配置在单一 lambda 内完成
 * - 边缘情况：空 lambda 使用 DeepSeek 全默认配置（默认 baseUrl + 默认模型 deepseek-v4-flash）；
 *   `models { }` 块为空时由工厂函数回落到默认模型
 *
 * 示例：
 * ```
 * llm {
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
 * @param block 在 [ProviderDsl] 作用域内配置连接与模型
 */
fun ProvidersBuilder.deepseek(block: ProviderDsl.() -> Unit) {
    val dsl = ProviderDsl().apply(block)
    register(DeepSeekProvider(dsl.buildConfig(), dsl.buildModels()))
}
