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

import top.resderx.rac.exceptions.RACApiException
import top.resderx.rac.exceptions.RACNetworkException
import top.resderx.rac.exceptions.RACTimeoutException
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * 带自动重试能力的请求执行器，包装 [RequestExecutor] 并按 [RetryPolicy] 重试瞬时错误。
 *
 * - 作用：在 [RequestExecutor] 之上增加重试层，对非流式 postJson 与流式 postSSE 提供统一的
 *   指数退避重试机制，处理网络中断、5xx 服务端错误、429 限流、超时等瞬时故障
 * - 必要性：LLM 推理耗时长（数十秒），网络中断概率高；无重试机制时调用方需手动 catch + 重试，
 *   代码冗余且易出错；集中化重试保证库的可用性与调用方代码简洁
 * - 设计思路：装饰器模式包装 RequestExecutor，保持接口兼容（postJson/postSSE 签名一致）；
 *   非流式请求使用 retrySuspending 循环重试；流式请求使用 flow 的 catch 操作符在异常时重连
 *   （重新发起完整流式请求，因 LLM 流式响应不支持断点续传）；重试前解析 Retry-After 响应头
 *   （HTTP 标准，429/503 场景下服务端建议的等待时间），否则按指数退避计算；添加随机抖动（jitter）
 *   避免多客户端同步重试导致雪崩
 * - 实现方式：
 *   1. postJson：retrySuspending 循环，catch RACApiException（按状态码判断）与 RACTimeoutException/
 *      RACNetworkException（网络层瞬时错误），按策略 delay 后重试，超过 maxRetries 抛出最后一次异常
 *   2. postSSE：返回 flow { }，内部 emitAll 原始流；catch 操作符捕获异常后递归重新订阅
 *   3. computeDelay：解析 Retry-After 头（秒或 HTTP-Date），否则按 min(initial * mult^attempt, max) 计算
 *   4. addJitter：在计算延迟基础上添加 0~50% 的随机抖动
 * - 可能的问题：
 *   1. 流式重连会从头开始（已接收的 chunk 丢失），因 LLM API 不支持 Last-Event-ID 续传；
 *      调用方需自行处理重复内容（通常重连会得到完整新响应）
 *   2. POST 请求重试可能产生副作用（如计费），但 LLM API 通常幂等（同一 prompt 多次调用结果不同但不冲突）
 *   3. 不实现断路器（circuit breaker），连续失败时仍会尝试 maxRetries 次
 *   4. Retry-After 头解析仅支持秒数格式，HTTP-Date 格式因平台差异未实现
 * - 边缘情况：maxRetries=0 时等价于直接调用 executor，不重试；Retry-After 超过 maxDelayMillis 时
 *   以 maxDelayMillis 为准；网络异常（非 RACApiException）视为可重试；4xx 非 retryable 立即抛出
 * - 优点：装饰器模式保持接口兼容，调用方无感知；指数退避 + 抖动避免雪崩；遵守 Retry-After 头；
 *   流式与非流式统一重试策略
 * - 算法/数据结构：
 *   - 指数退避：delay = min(initial * multiplier^attempt, maxDelay) + jitter
 *   - 重试循环：for attempt in 0..maxRetries，try-catch 累积异常
 * - 时间复杂度：最坏 O(maxRetries) 次请求 + O(maxRetries) 次 delay
 * - 空间复杂度：O(1)（仅持有策略与 executor 引用，无额外数据结构）
 *
 * @property executor 被包装的请求执行器
 * @property policy 重试策略
 */
class RetryExecutor(
    private val executor: HttpExecutor,
    private val policy: RetryPolicy = RetryPolicy(),
) : HttpExecutor {
    /**
     * 解析 HTTP 响应中的 Retry-After 头值（秒数）。
     *
     * - 作用：从异常的响应头中提取服务端建议的等待时间
     * - 实现：尝试将 Retry-After 头解析为 Long 秒数；不存在或解析失败返回 null
     *
     * @param exception API 异常
     * @return Retry-After 秒数，或 null（不存在/不可解析）
     */
    private fun parseRetryAfter(exception: RACApiException): Long? {
        val header = exception.headers["Retry-After"] ?: return null
        // 仅解析秒数格式（如 "120"），HTTP-Date 格式因跨平台日期解析差异暂不支持
        return header.toLongOrNull()
    }

    /**
     * 计算第 attempt 次重试前的等待毫秒数（含抖动）。
     *
     * - 作用：按指数退避公式计算延迟，添加随机抖动
     * - 算法：base = min(initial * multiplier^attempt, maxDelay)；jitter = base * random(0, 0.5)；
     *   total = base + jitter
     * - 边缘：attempt=0 时返回 initialDelayMillis（首次重试）；计算结果超过 maxDelayMillis 时截断
     *
     * @param attempt 重试次数（0 表示首次重试前）
     * @param retryAfterMillis Retry-After 头指定的等待时间（毫秒），优先于指数退避
     * @return 等待毫秒数
     */
    private fun computeDelay(attempt: Int, retryAfterMillis: Long? = null): Long {
        if (retryAfterMillis != null) {
            // 遵守 Retry-After 头，但不超过 maxDelayMillis 上限
            return minOf(retryAfterMillis, policy.maxDelayMillis)
        }
        // 指数退避：initial * multiplier^attempt
        val base = (policy.initialDelayMillis * policy.backoffMultiplier.pow(attempt.toDouble())).toLong()
        val capped = minOf(base, policy.maxDelayMillis)
        // 添加 0~50% 的随机抖动，避免多客户端同步重试
        val jitterRatio = Random.nextDouble(0.0, 0.5)
        val jitter = (capped * jitterRatio).toLong()
        return capped + jitter
    }

    /**
     * 带重试的非流式 POST JSON 请求。
     *
     * - 作用：执行 postJson 并在瞬时错误时按策略重试
     * - 实现：retrySuspending 循环，catch RACApiException（按状态码判断可重试）与
     *   RACTimeoutException/RACNetworkException（网络层错误总是可重试）
     *
     * @param urlString 请求地址
     * @param headers 请求头
     * @param body JSON 请求体
     * @return 响应体字符串
     * @throws RACApiException 非 retryable 状态码或超过最大重试次数
     * @throws RACTimeoutException 超过最大重试次数后仍超时
     */
    override suspend fun postJson(
        urlString: String,
        headers: Map<String, String>,
        body: String,
    ): String {
        var lastException: Throwable? = null
        for (attempt in 0..policy.maxRetries) {
            try {
                return executor.postJson(urlString, headers, body)
            } catch (e: RACApiException) {
                lastException = e
                // 4xx 非 retryable 立即抛出，不重试
                if (!policy.isRetryableStatus(e.statusCode)) throw e
                // 已达最大重试次数，抛出
                if (attempt >= policy.maxRetries) throw e
                // 计算延迟：优先 Retry-After 头
                val retryAfterSec = parseRetryAfter(e)
                val retryAfterMillis = retryAfterSec?.times(1000)
                val delayMillis = computeDelay(attempt, retryAfterMillis)
                delay(delayMillis.milliseconds)
            } catch (e: RACTimeoutException) {
                lastException = e
                if (attempt >= policy.maxRetries) throw e
                val delayMillis = computeDelay(attempt)
                delay(delayMillis.milliseconds)
            } catch (e: RACNetworkException) {
                lastException = e
                if (attempt >= policy.maxRetries) throw e
                val delayMillis = computeDelay(attempt)
                delay(delayMillis.milliseconds)
            }
        }
        // 理论上不会到达，for 循环要么 return 要么 throw
        throw lastException ?: RACNetworkException("Retry exhausted without exception captured")
    }

    /**
     * 带重连的流式 SSE 请求。
     *
     * - 作用：执行 postSSE 并在流中断时自动重新发起完整流式请求
     * - 设计：使用 flow { } 包装原始流，catch 异常后递归重新订阅；因 LLM 流式响应不支持断点续传，
     *   重连会从头开始（调用方可能收到重复内容，需自行去重或接受新完整响应）
     * - 边缘：maxRetries=0 时等价于直接调用 executor.postSSE；重连计数通过流内部状态维护
     *
     * @param urlString 请求地址
     * @param headers 请求头
     * @param body JSON 请求体
     * @return 冷流，每个元素为 SSE data 内容
     */
    override fun postSSE(
        urlString: String,
        headers: Map<String, String>,
        body: String,
    ): Flow<String> = flow {
        var retryCount = 0
        var lastException: Throwable? = null
        while (true) {
            try {
                // emitAll 将原始流的所有元素转发到外层流
                emitAll(executor.postSSE(urlString, headers, body))
                // 正常完成，退出循环
                return@flow
            } catch (e: RACApiException) {
                lastException = e
                // 4xx 非 retryable 立即抛出
                if (!policy.isRetryableStatus(e.statusCode)) throw e
                // 达到最大重试次数，抛出
                if (retryCount >= policy.maxRetries) throw e
                val retryAfterSec = parseRetryAfter(e)
                val retryAfterMillis = retryAfterSec?.times(1000)
                val delayMillis = computeDelay(retryCount, retryAfterMillis)
                delay(delayMillis.milliseconds)
                retryCount++
            } catch (e: RACTimeoutException) {
                lastException = e
                if (retryCount >= policy.maxRetries) throw e
                val delayMillis = computeDelay(retryCount)
                delay(delayMillis.milliseconds)
                retryCount++
            } catch (e: RACNetworkException) {
                lastException = e
                if (retryCount >= policy.maxRetries) throw e
                val delayMillis = computeDelay(retryCount)
                delay(delayMillis.milliseconds)
                retryCount++
            }
        }
    }
}
