package com.resderx.rac.network

import kotlinx.coroutines.flow.Flow

/**
 * HTTP 请求执行器抽象接口，定义 POST JSON 与 SSE 流式请求的统一契约。
 *
 * - 作用：为 [RequestExecutor] 与 [RetryExecutor] 提供共同接口，允许 API 客户端面向接口编程，
 *   无需关心是否带重试能力
 * - 必要性：v0.2.0 引入 [RetryExecutor] 后，API 客户端需同时兼容带重试与不带重试的执行器；
 *   面向接口编程遵循依赖倒置原则，便于测试时注入 Mock 实现
 * - 设计思路：仅定义两个方法 postJson（非流式）与 postSSE（流式），签名与 [RequestExecutor] 一致；
 *   不包含重试相关方法，保持接口最小化
 * - 实现方式：interface，两个方法签名
 * - 边缘情况：postJson 为 suspend 函数；postSSE 返回冷流
 * - 优点：API 客户端面向接口编程，可灵活替换实现
 * - 时间复杂度：由实现类决定
 * - 空间复杂度：O(1)
 */
interface HttpExecutor {
    /**
     * 发起 POST JSON 请求并返回响应体字符串。
     *
     * @param urlString 请求地址
     * @param headers 请求头
     * @param body JSON 请求体字符串
     * @return 响应体字符串
     */
    suspend fun postJson(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): String

    /**
     * 发起 SSE 流式 POST 请求，返回冷流。
     *
     * @param urlString 请求地址
     * @param headers 请求头
     * @param body JSON 请求体字符串
     * @return 冷流，每个元素为一条 SSE data 内容
     */
    fun postSSE(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): Flow<String>
}
