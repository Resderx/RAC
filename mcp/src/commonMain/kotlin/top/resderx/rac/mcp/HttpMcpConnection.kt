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

package top.resderx.rac.mcp

import com.resderx.rac.network.HttpClientFactory
import com.resderx.rac.network.HttpExecutor
import com.resderx.rac.network.RequestExecutor
import com.resderx.rac.network.RetryExecutor
import com.resderx.rac.network.RetryPolicy
import io.ktor.client.HttpClient

/**
 * HTTP 传输层连接实现，通过 HTTP POST 发送 JSON-RPC 请求并接收响应。
 *
 * - 作用：为 [DefaultMcpClient] 提供基于 HTTP 的 MCP 通信，支持全平台（JVM/Native/JS/Wasm）
 * - 必要性：HTTP 是 MCP 协议的标准传输方式之一，适合远程 MCP 服务器；
 *   复用 RAC 的 [RetryExecutor] 获得自动重试与指数退避能力
 * - 设计思路：内部使用 [RetryExecutor] 包装 [RequestExecutor]，每个 JSON-RPC 请求为一次
 *   HTTP POST，响应体为 JSON-RPC 响应；notify 发送 POST 但忽略响应（fire-and-forget）；
 *   MCP 服务器通常对 JSON-RPC 错误也返回 HTTP 200（错误信息在响应体的 error 字段），
 *   因此 HTTP 层错误（5xx/超时）由 RetryExecutor 自动重试，JSON-RPC 层错误由 DefaultMcpClient 解析
 * - 实现方式：构造时创建 HttpClient + RetryExecutor；request 调用 executor.postJson；
 *   notify 同样调用 postJson 但 catch 异常后忽略；close 关闭 HttpClient
 * - 可能的问题：不支持服务器 SSE 推送通知（后续版本可扩展）；notify 的忽略异常策略
 *   可能丢失重要错误（但通知语义本身为 best-effort）
 * - 边缘：close 幂等（HttpClient.close 幂等）；connect 为空操作（HTTP 无状态）
 * - 优点：复用 RetryExecutor 的重试能力；全平台支持；实现简洁
 * - 时间复杂度：单次请求为 O(n)（n 为消息大小）+ 网络 RTT
 * - 空间复杂度：O(1)（持有 HttpClient 与 executor 引用）
 *
 * @property transport HTTP 传输配置（含服务器 URL 与自定义头）
 * @property timeoutMillis 请求超时毫秒数
 * @property retryPolicy 重试策略；默认使用 [RetryPolicy] 默认值
 */
internal class HttpMcpConnection(
    private val transport: HttpTransport,
    timeoutMillis: Long,
    retryPolicy: RetryPolicy = RetryPolicy(),
) : McpConnection {

    /** HttpClient 实例，由 HttpClientFactory 创建，安装 SSE 与超时插件。 */
    private val client: HttpClient = HttpClientFactory.create(timeoutMillis)

    /** 请求执行器，包装 RetryExecutor 提供自动重试。 */
    private val executor: HttpExecutor = RetryExecutor(RequestExecutor(client), retryPolicy)

    /**
     * 建立连接（HTTP 无状态，空操作）。
     * - 边缘：HttpClient 已在构造时创建，无需额外连接建立
     */
    override suspend fun connect() {
        // HTTP 传输无状态，无需连接建立
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * - 实现：调用 executor.postJson 将消息 POST 到服务器 URL，返回响应体
     * - 错误处理：HTTP 层错误（5xx/429/超时）由 RetryExecutor 自动重试；
     *   最终失败抛 RACApiException/RACTimeoutException
     *
     * @param message JSON-RPC 请求消息字符串
     * @return JSON-RPC 响应消息字符串（HTTP 响应体）
     */
    override suspend fun request(message: String): String {
        return executor.postJson(transport.serverUrl, transport.headers, message)
    }

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * - 实现：调用 executor.postJson 但 catch 所有异常后忽略（通知为 best-effort 语义）
     * - 边缘：网络错误不抛出，因为通知不期望响应
     *
     * @param message JSON-RPC 通知消息字符串
     */
    override suspend fun notify(message: String) {
        try {
            executor.postJson(transport.serverUrl, transport.headers, message)
        } catch (_: Exception) {
            // 通知为 best-effort 语义，忽略传输错误
        }
    }

    /**
     * 关闭连接并释放 HttpClient 资源。
     * - 边缘：幂等（HttpClient.close() 幂等）
     */
    override suspend fun close() {
        client.close()
    }
}
