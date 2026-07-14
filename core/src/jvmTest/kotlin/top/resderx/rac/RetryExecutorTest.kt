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

package top.resderx.rac

import top.resderx.rac.exceptions.RACApiException
import top.resderx.rac.exceptions.RACTimeoutException
import top.resderx.rac.network.HttpClientFactory
import top.resderx.rac.network.RequestExecutor
import top.resderx.rac.network.RetryExecutor
import top.resderx.rac.network.RetryPolicy
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RetryExecutor 单元测试，验证重试策略、指数退避与异常处理。
 *
 * - 作用：使用 MockEngine 模拟 503/401/超时场景，验证 RetryExecutor 按策略重试或立即抛出
 * - 必要性：重试逻辑是网络韧性的核心，需覆盖可重试/不可重试状态码、Retry-After 头、流式重连
 * - 设计：通过 AtomicInteger 计数器追踪请求次数；使用 maxRetries=2 与 initialDelayMillis=1 加速测试
 * - 边缘：maxRetries=0 不重试；4xx 非 retryable 立即抛出；5xx 按 retryableStatusCodes 重试
 */
class RetryExecutorTest {

    /**
     * 创建带 MockEngine 的 RetryExecutor，通过 requestCount 计数器追踪请求次数。
     *
     * @param requestCount 外部计数器，每次请求自增
     * @param handler 请求处理器，返回 MockEngine 标准响应
     * @param policy 重试策略，默认 maxRetries=2、initialDelay=1ms 加速测试
     * @return 配置好的 RetryExecutor
     */
    private fun createRetryExecutor(
        requestCount: java.util.concurrent.atomic.AtomicInteger,
        policy: RetryPolicy = RetryPolicy(maxRetries = 2, initialDelayMillis = 1, maxDelayMillis = 10),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): RetryExecutor {
        // 使用 SseCapableMockEngine 替代原生 MockEngine，使其同时支持 SSE 与普通 HTTP 请求：
        // 非流式 postJson 请求不携带 ResponseAdapterAttributeKey，SseCapableMockEngine 透传原始响应；
        // 流式 postSSE 请求携带 adapter，引擎调用 adapt() 转换 ByteReadChannel 为 SSESession
        val engine = SseCapableMockEngine { requestData ->
            requestCount.incrementAndGet()
            handler(requestData)
        }
        val client = HttpClientFactory.createWithEngine(engine, timeoutMs = 5_000)
        val executor = RequestExecutor(client)
        return RetryExecutor(executor, policy)
    }

    @Test
    fun postJsonSucceedsOnFirstAttempt() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(count) { _ ->
            respond("""{"result":"ok"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        assertEquals("""{"result":"ok"}""", result)
        assertEquals(1, count.get(), "Should succeed on first attempt, no retry")
    }

    @Test
    fun postJsonRetriesOn503AndSucceeds() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(count) { _ ->
            // incrementAndGet 在 handler 前执行，故 count=1,2 时返回 503，count=3 时返回 200
            if (count.get() < 3) {
                respond("Service Unavailable", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("""{"result":"ok"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val result = retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        assertEquals("""{"result":"ok"}""", result)
        assertEquals(3, count.get(), "Should retry twice then succeed on third attempt")
    }

    @Test
    fun postJsonDoesNotRetryOn401() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(count) { _ ->
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val exception = assertFailsWith<RACApiException> {
            retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        }
        assertEquals(401, exception.statusCode)
        assertEquals(1, count.get(), "Should not retry on 401")
    }

    @Test
    fun postJsonRetriesAndFailsAfterMaxRetries() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(count) { _ ->
            respond("Service Unavailable", HttpStatusCode.ServiceUnavailable)
        }
        val exception = assertFailsWith<RACApiException> {
            retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        }
        assertEquals(503, exception.statusCode)
        // maxRetries=2，首次 + 2 次重试 = 3 次请求
        assertEquals(3, count.get(), "Should attempt 3 times (1 initial + 2 retries)")
    }

    @Test
    fun postJsonHonorsRetryAfterHeader() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(
            count,
            policy = RetryPolicy(maxRetries = 2, initialDelayMillis = 10_000, maxDelayMillis = 30_000),
        ) { _ ->
            // incrementAndGet 在 handler 前执行，故 count=1 时返回 429，count=2 时返回 200
            if (count.get() < 2) {
                respond(
                    "Too Many Requests",
                    HttpStatusCode.TooManyRequests,
                    headersOf("Retry-After", "1"),
                )
            } else {
                respond("""{"result":"ok"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val result = retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        assertEquals("""{"result":"ok"}""", result)
        assertEquals(2, count.get(), "Should retry once after Retry-After delay then succeed")
    }

    @Test
    fun postJsonWithMaxRetriesZeroDoesNotRetry() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val retryExecutor = createRetryExecutor(
            count,
            policy = RetryPolicy(maxRetries = 0),
        ) { _ ->
            respond("Service Unavailable", HttpStatusCode.ServiceUnavailable)
        }
        val exception = assertFailsWith<RACApiException> {
            retryExecutor.postJson("http://localhost/test", emptyMap(), "{}")
        }
        assertEquals(503, exception.statusCode)
        assertEquals(1, count.get(), "Should not retry when maxRetries=0")
    }

    @Test
    fun postSSERetriesOn503AndFailsAfterMaxRetries() = runTest {
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        // 使用 SseCapableMockEngine 支持 SSE 请求；始终返回 503 验证重试耗尽后抛出 RACApiException
        val retryExecutor = createRetryExecutor(count) { _ ->
            respond("Service Unavailable", HttpStatusCode.ServiceUnavailable)
        }
        val exception = assertFailsWith<RACApiException> {
            retryExecutor.postSSE("http://localhost/test", emptyMap(), "{}").toList()
        }
        assertEquals(503, exception.statusCode)
        // maxRetries=2：首次 + 2 次重试 = 3 次请求
        assertEquals(3, count.get(), "Should attempt 3 times (1 initial + 2 retries) on SSE 503")
    }

    @Test
    fun retryPolicyIsRetryableStatus() {
        val policy = RetryPolicy()
        assertTrue(policy.isRetryableStatus(503))
        assertTrue(policy.isRetryableStatus(429))
        assertTrue(!policy.isRetryableStatus(401))
        assertTrue(!policy.isRetryableStatus(404))
    }

    @Test
    fun retryPolicyRejectsNegativeMaxRetries() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(maxRetries = -1)
        }
    }
}
