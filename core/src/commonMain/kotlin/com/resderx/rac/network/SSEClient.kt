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
            val status = e.response?.status?.value ?: 0
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
