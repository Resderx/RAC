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

package top.resderx.rac.network

import com.resderx.rac.exceptions.RACApiException
import com.resderx.rac.exceptions.RACTimeoutException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow

/**
 * HTTP 请求执行器。
 * 作用：封装 POST JSON 与 SSE 流式请求，统一鉴权头注入与异常映射。
 * 必要性：所有 API 客户端共享同一请求逻辑，避免重复代码。
 * 设计：postJson 悬挂函数返回响应体字符串；postSSE 委托 SSEClient 返回冷流。
 * 边缘：非2xx→RACApiException（携带响应头供 RetryExecutor 解析 Retry-After）；
 *   超时→RACTimeoutException；headers 直接透传。
 */
class RequestExecutor(private val client: HttpClient) : HttpExecutor {

    /**
     * 从 HttpResponse 提取响应头为 Map（头部名小写化以便大小写不敏感查询）。
     *
     * - 作用：将 Ktor 的 Headers 对象转为普通 Map，便于在异常中携带与后续查询
     * - 实现：遍历所有头部名，每个头部取第一个值，键转为小写
     *
     * @param response HTTP 响应
     * @return 响应头 Map（键小写）
     */
    private fun extractHeaders(response: HttpResponse): Map<String, String> {
        return buildMap {
            response.headers.names().forEach { name ->
                put(name.lowercase(), response.headers[name].orEmpty())
            }
        }
    }

    /**
     * 发起 POST JSON 请求并返回响应体字符串。
     * @param urlString 请求地址
     * @param headers 请求头（含鉴权等）
     * @param body JSON 请求体字符串
     * @return 响应体字符串
     * @throws RACApiException 非 2xx 响应（含响应头）
     * @throws RACTimeoutException 请求超时
     */
    override suspend fun postJson(
        urlString: String,
        headers: Map<String, String>,
        body: String,
    ): String {
        return try {
            val response: HttpResponse = client.post(urlString) {
                headers.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                val errBody = response.bodyAsText()
                val respHeaders = extractHeaders(response)
                throw RACApiException(
                    statusCode = response.status.value,
                    errorBody = errBody,
                    headers = respHeaders,
                )
            }
            response.bodyAsText()
        } catch (e: HttpRequestTimeoutException) {
            throw RACTimeoutException(cause = e)
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            val errBody = e.response.bodyAsText()
            val respHeaders = extractHeaders(e.response)
            throw RACApiException(
                statusCode = status,
                errorBody = errBody,
                headers = respHeaders,
            )
        }
    }

    /**
     * 发起 SSE 流式 POST 请求，返回冷流。
     * @param urlString 请求地址
     * @param headers 请求头（含鉴权等）
     * @param body JSON 请求体字符串
     * @return 冷流，每个元素为一条 SSE data 内容
     */
    override fun postSSE(
        urlString: String,
        headers: Map<String, String>,
        body: String,
    ): Flow<String> {
        return SSEClient(client).stream(urlString = urlString, headers = headers, body = body)
    }
}

