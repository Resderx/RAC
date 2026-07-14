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

package top.resderx.rac.api.responses

import com.resderx.rac.network.HttpExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/** Responses API 客户端。注入 HttpExecutor（可为 RequestExecutor 或带重试的 RetryExecutor），Json 配置 ignoreUnknownKeys=true。 */
class ResponsesClient(private val executor: HttpExecutor) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun respond(url: String, headers: Map<String, String>, request: ResponsesRequest): ResponsesResponse {
        val body = json.encodeToString(ResponsesRequest.serializer(), request.copy(stream = false))
        return json.decodeFromString(ResponsesResponse.serializer(), executor.postJson(url, headers, body))
    }

    fun stream(url: String, headers: Map<String, String>, request: ResponsesRequest): Flow<ResponsesStreamEvent> = flow {
        val body = json.encodeToString(ResponsesRequest.serializer(), request.copy(stream = true))
        executor.postSSE(url, headers, body).collect { if (it != "[DONE]" && it.isNotBlank()) emit(json.decodeFromString(ResponsesStreamEvent.serializer(), it)) }
    }
}
