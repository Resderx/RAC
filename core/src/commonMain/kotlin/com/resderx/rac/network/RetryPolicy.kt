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

/**
 * 重试策略配置，定义网络请求失败时的自动重试行为。
 *
 * - 作用：封装重试次数、退避间隔、可重试状态码等参数，供 [RetryExecutor] 在执行请求时按策略自动重试
 * - 必要性：网络环境不稳定（瞬时 5xx、429 限流、连接中断），无重试机制会导致调用方需手动处理
 *   每次失败，严重影响库的可用性；集中化的策略配置避免每个调用点重复编写重试逻辑
 * - 设计思路：采用不可变数据类，所有字段提供合理默认值（maxRetries=3、指数退避 base=1s、cap=30s、
 *   multiplier=2.0）；retryableStatusCodes 遵循业界惯例（408 请求超时、429 限流、5xx 服务端错误）；
 *   使用 Set<Int> 保证 O(1) 查询；策略实例可跨请求复用（线程安全因不可变）
 * - 实现方式：data class，所有字段为 val，提供默认值；无方法，纯配置载体
 * - 可能的问题：maxRetries 过大会导致调用方长时间等待（最坏情况 1+2+4+8+16+30 = 61s）；
 *   不区分幂等性（POST 请求重试可能产生副作用，但 LLM API 通常幂等）；不处理 circuit breaker
 * - 边缘情况：maxRetries=0 表示不重试；initialDelayMillis > maxDelayMillis 时以 maxDelayMillis 为准；
 *   backoffMultiplier=1.0 退化为固定间隔重试；retryableStatusCodes 为空集表示不按状态码重试
 * - 优点：配置集中、不可变、可复用；指数退避 + 上限避免雪崩；抖动（jitter）由 RetryExecutor 添加
 *   避免多客户端同步重试
 * - 算法/数据结构：纯数据类，无算法；retryableStatusCodes 使用 HashSet 实现 O(1) contains
 * - 时间复杂度：字段访问 O(1)；retryableStatusCodes.contains() O(1)
 * - 空间复杂度：O(k)，k 为 retryableStatusCodes 大小（固定 6 个，常数空间）
 *
 * @property maxRetries 最大重试次数（不含首次请求），默认 3；0 表示不重试
 * @property initialDelayMillis 首次重试前等待的毫秒数，默认 1000（1 秒）
 * @property maxDelayMillis 单次重试等待的上限毫秒数，默认 30_000（30 秒），防止指数增长过大
 * @property backoffMultiplier 退避乘数，每次重试等待时间 = min(initialDelay * multiplier^attempt, maxDelay)，
 *   默认 2.0（标准指数退避）
 * @property retryableStatusCodes 可重试的 HTTP 状态码集合，默认含 408/429/500/502/503/504；
 *   4xx 中除 408/429 外不重试（客户端错误不可恢复）
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val backoffMultiplier: Double = 2.0,
    val retryableStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
) {
    init {
        // 前置条件校验：防止不合理配置导致运行时异常
        // 必要性：尽早暴露配置错误，避免在重试循环中才发现问题
        require(maxRetries >= 0) { "maxRetries must be non-negative, got $maxRetries" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be non-negative, got $initialDelayMillis" }
        require(maxDelayMillis >= 0) { "maxDelayMillis must be non-negative, got $maxDelayMillis" }
        require(backoffMultiplier >= 0.0) { "backoffMultiplier must be non-negative, got $backoffMultiplier" }
    }

    /**
     * 判断给定 HTTP 状态码是否可重试。
     *
     * - 作用：快速判断某次失败响应是否应触发重试
     * - 实现：O(1) Set 查询
     *
     * @param statusCode HTTP 状态码
     * @return true 表示该状态码可重试
     */
    fun isRetryableStatus(statusCode: Int): Boolean = statusCode in retryableStatusCodes
}
