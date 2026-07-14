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

package com.resderx.rac.mcp

/**
 * MCP 传输层连接接口，定义底层通信的统一契约。
 *
 * - 作用：为 [DefaultMcpClient] 抽象不同的传输方式（HTTP/Stdio/WebSocket），
 *   客户端面向此接口编程，无需关心底层传输细节
 * - 必要性：MCP 协议支持多种传输方式，统一接口使 DefaultMcpClient 的 JSON-RPC 逻辑
 *   与传输实现解耦，符合依赖倒置原则
 * - 设计思路：最小接口——connect 建立连接、request 发送请求并等待响应、
 *   notify 发送单向通知、close 释放资源
 * - 边缘：request 为 suspend，阻塞直到收到响应；notify 不等待响应；
 *   close 幂等，多次调用不报错
 * - 时间复杂度：由实现决定（HTTP 为网络 I/O，Stdio 为管道 I/O）
 * - 空间复杂度：由实现决定
 */
internal interface McpConnection {

    /** 建立连接。HTTP 传输为空操作（无状态），Stdio 传输启动子进程。 */
    suspend fun connect()

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * @param message JSON-RPC 请求消息字符串
     * @return JSON-RPC 响应消息字符串
     */
    suspend fun request(message: String): String

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * @param message JSON-RPC 通知消息字符串
     */
    suspend fun notify(message: String)

    /** 关闭连接并释放资源。幂等。 */
    suspend fun close()
}

/**
 * 创建 Stdio 传输连接的工厂函数（expect，需各平台提供 actual）。
 *
 * - 作用：平台相关的 Stdio 连接创建，JVM/Android 使用 ProcessBuilder，
 *   其他平台抛 UnsupportedOperationException
 * - 必要性：commonMain 无法直接使用 ProcessBuilder（JVM 专属 API），
 *   需通过 expect/actual 机制委托到平台源集
 *
 * @param transport Stdio 传输配置
 * @param timeoutMillis 请求超时毫秒数
 * @return Stdio 连接实例
 * @throws UnsupportedOperationException 当前平台不支持 Stdio 传输时抛出
 */
@Throws(UnsupportedOperationException::class)
internal expect fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection
