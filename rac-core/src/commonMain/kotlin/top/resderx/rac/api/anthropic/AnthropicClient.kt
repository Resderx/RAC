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

package top.resderx.rac.api.anthropic

import top.resderx.rac.network.HttpExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Anthropic API 客户端。
 * 作用：调用 Anthropic Messages 风格接口。
 * 必要性：Anthropic 协议与 OpenAI 不同，需独立客户端。
 * 设计：注入 HttpExecutor（可为 RequestExecutor 或带重试的 RetryExecutor），Json 配置 ignoreUnknownKeys=true。
 * 边缘：stream 遇 [DONE] 跳过。
 * 时间复杂度 O(n)，空间复杂度 O(1)。
 */
class AnthropicClient(private val executor: HttpExecutor) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 非流式调用。 */
    suspend fun complete(
        urlString: String,
        headers: Map<String, String>,
        request: AnthropicRequest,
    ): AnthropicResponse {
        val body = json.encodeToString(AnthropicRequest.serializer(), request.copy(stream = false))
        val respText = executor.postJson(urlString, headers, body)
        return json.decodeFromString(AnthropicResponse.serializer(), respText)
    }

    /** 流式调用，返回冷流。 */
    fun stream(
        urlString: String,
        headers: Map<String, String>,
        request: AnthropicRequest,
    ): Flow<AnthropicStreamEvent> = flow {
        val body = json.encodeToString(AnthropicRequest.serializer(), request.copy(stream = true))
        executor.postSSE(urlString, headers, body).collect { data ->
            if (data != "[DONE]" && data.isNotBlank()) {
                emit(json.decodeFromString(AnthropicStreamEvent.serializer(), data))
            }
        }
    }
}
