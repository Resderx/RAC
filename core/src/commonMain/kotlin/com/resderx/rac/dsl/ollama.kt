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

import com.resderx.rac.providers.ollama.OllamaProvider

/**
 * 在 `providers { }` 块中注册 Ollama 供应商。
 *
 * 通过 [ProviderDsl] 同时配置连接信息（baseUrl/headers）与 `models { }` 子块。
 * 本地模式默认无需鉴权，可省略 `apiKey`；如需切换云端模式，设置 `baseUrl` 与 `apiKey` 即可。
 *
 * 示例：
 * ```
 * llm {
 *     providers {
 *         ollama {
 *             // apiKey 可省略（本地模式无鉴权）
 *             models {
 *                 model("llama3.1") { maxTokens = 4096 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param block 在 [ProviderDsl] 作用域内配置连接与模型
 */
fun ProvidersBuilder.ollama(block: ProviderDsl.() -> Unit) {
    val dsl = ProviderDsl().apply(block)
    register(OllamaProvider(dsl.buildConfig(), dsl.buildModels()))
}
