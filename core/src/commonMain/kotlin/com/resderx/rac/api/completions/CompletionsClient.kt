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

package com.resderx.rac.api.completions

import com.resderx.rac.network.HttpExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Completions API 客户端。
 * 作用：调用 OpenAI Chat Completions 风格接口，提供非流式与流式调用。
 * 必要性：被 10 家 OpenAI 兼容供应商复用。
 * 设计：注入 HttpExecutor（可为 RequestExecutor 或带重试的 RetryExecutor），Json 配置 ignoreUnknownKeys=true。
 * 边缘：stream 遇 [DONE] 跳过。
 * 时间复杂度 O(n)，空间复杂度 O(1)。
 */
class CompletionsClient(private val executor: HttpExecutor) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 非流式调用。 */
    suspend fun complete(
        urlString: String,
        headers: Map<String, String>,
        request: CompletionsRequest,
    ): CompletionsResponse {
        val body = json.encodeToString(CompletionsRequest.serializer(), request.copy(stream = false))
        val respText = executor.postJson(urlString, headers, body)
        return json.decodeFromString(CompletionsResponse.serializer(), respText)
    }

    /** 流式调用，返回冷流。 */
    fun stream(
        urlString: String,
        headers: Map<String, String>,
        request: CompletionsRequest,
    ): Flow<CompletionsStreamChunk> = flow {
        val body = json.encodeToString(CompletionsRequest.serializer(), request.copy(stream = true))
        executor.postSSE(urlString, headers, body).collect { data ->
            if (data != "[DONE]") {
                emit(json.decodeFromString(CompletionsStreamChunk.serializer(), data))
            }
        }
    }
}