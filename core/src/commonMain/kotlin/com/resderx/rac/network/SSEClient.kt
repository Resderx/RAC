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

package com.resderx.rac.network

import com.resderx.rac.exceptions.RACApiException
import com.resderx.rac.exceptions.RACTimeoutException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SSEClient(private val client: HttpClient) {
    fun stream(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): Flow<String> = flow {
        try {
            client.sse(
                urlString = urlString,
                request = {
                    method = HttpMethod.Post
                    headers.forEach { (k, v) -> header(k, v) }
                    setBody(body)
                }
            ) {
                incoming.collect { event ->
                    val data = event.data
                    if (data == "[DONE]") return@collect
                    if (data != null) emit(data)
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw RACTimeoutException(cause = e)
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            val errBody = e.response.bodyAsText()
            val respHeaders = buildMap {
                e.response.headers.names().forEach { name ->
                    put(name.lowercase(), e.response.headers[name].orEmpty())
                }
            }
            throw RACApiException(statusCode = status, errorBody = errBody, headers = respHeaders)
        } catch (e: SSEClientException) {
            // SSEClientException 在流正常结束时也可能被抛出（如连接关闭），
            // 此时 response.status 仍为 2xx。只有非 2xx 才视为真正的 API 错误。
            val status = e.response?.status?.value ?: 0
            if (status in 200..299) {
                // 2xx 状态码：SSE 流正常结束，不抛异常，流自然完成
                return@flow
            }
            val errBody = try { e.response?.bodyAsText() ?: "" } catch (_: Throwable) { "" }
            val respHeaders = buildMap {
                e.response?.headers?.names()?.forEach { name ->
                    put(name.lowercase(), e.response?.headers?.get(name).orEmpty())
                }
            }
            throw RACApiException(statusCode = status, errorBody = errBody, headers = respHeaders)
        }
    }
}
