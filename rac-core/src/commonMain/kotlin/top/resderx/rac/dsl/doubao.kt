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

import top.resderx.rac.providers.doubao.DoubaoProvider

/**
 * 在 `providers { }` 块中注册 Doubao（火山引擎方舟）供应商。
 *
 * 通过 [ProviderDsl] 同时配置连接信息（apiKey/baseUrl/headers）与 `models { }` 子块。
 * 生产环境通常需在 `models { }` 内覆盖模型为实际接入点 ID。
 *
 * 示例：
 * ```
 * llm {
 *     providers {
 *         doubao {
 *             apiKey("...")
 *             models {
 *                 model("doubao-seed-1-6") { maxTokens = 4096 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param block 在 [ProviderDsl] 作用域内配置连接与模型
 */
fun ProvidersBuilder.doubao(block: ProviderDsl.() -> Unit) {
    val dsl = ProviderDsl().apply(block)
    register(DoubaoProvider(dsl.buildConfig(), dsl.buildModels()))
}
