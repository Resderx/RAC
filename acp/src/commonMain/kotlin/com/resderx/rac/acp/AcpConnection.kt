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

package com.resderx.rac.acp

import kotlinx.coroutines.flow.SharedFlow

/**
 * ACP 传输层连接接口，定义底层双向通信的统一契约。
 *
 * - 作用：为 [AcpClient] 与 [AcpAgentServer] 抽象底层传输（stdio/HTTP），
 *   上层面向此接口编程，通过 [incoming] 流接收消息、通过 [send] 发送消息
 * - 必要性：ACP 是双向 JSON-RPC 协议——Client 与 Agent 都可发起请求与通知，
 *   连接需支持双向消息交换；统一接口使上层协议逻辑与传输实现解耦
 * - 设计思路：
 *   - [send]：发送任意 JSON-RPC 消息（请求/响应/通知）
 *   - [incoming]：接收的 JSON-RPC 消息流（每条消息为 JSON 字符串），上层自行解析
 *   - [connect]：建立连接（stdio 启动子进程，HTTP 建立会话）
 *   - [close]：释放资源
 * - 与 MCP 的差异：MCP 连接的 request() 内部匹配 id 等待响应；
 *   ACP 因双向请求需求，改为暴露 incoming 流，由上层统一处理请求路由与响应匹配
 * - 边缘：incoming 为 SharedFlow，多消费者可共享；close 后 incoming 完成且 send 抛异常
 * - 时间复杂度：由实现决定（stdio 为管道 I/O，HTTP 为网络 I/O）
 * - 空间复杂度：由实现决定
 */
internal interface AcpConnection {

    /** 建立连接。stdio 传输启动子进程；HTTP 传输建立会话。 */
    suspend fun connect()

    /**
     * 发送一条 JSON-RPC 消息（请求/响应/通知）。
     *
     * @param message JSON-RPC 消息字符串
     */
    suspend fun send(message: String)

    /**
     * 接收的 JSON-RPC 消息流，每条消息为 JSON 字符串。
     * 上层自行解析消息类型（请求有 id、通知无 id、响应有 id+result/error）。
     */
    val incoming: SharedFlow<String>

    /** 关闭连接并释放资源。幂等。 */
    suspend fun close()
}

/**
 * 创建 stdio 传输连接的工厂函数（expect，需各平台提供 actual）。
 *
 * - 作用：平台相关的 stdio 连接创建，JVM 使用 ProcessBuilder，
 *   其他平台抛 [UnsupportedOperationException]
 * - 必要性：commonMain 无法直接使用 ProcessBuilder（JVM 专属 API），
 *   需通过 expect/actual 机制委托到平台源集
 *
 * @param transport stdio 传输配置
 * @return ACP 连接实例
 * @throws UnsupportedOperationException 当前平台不支持 stdio 传输时抛出
 */
@Throws(UnsupportedOperationException::class)
internal expect fun createAcpStdioConnection(
    transport: AcpStdioTransport,
): AcpConnection

/**
 * 创建服务端 stdio 传输连接的工厂函数（expect，需各平台提供 actual）。
 *
 * - 作用：为 [AcpAgentServer] 创建读取自身 stdin / 写入自身 stdout 的连接，
 *   使 RAC 能作为 ACP Agent 子进程运行
 * - 必要性：ACP Agent 作为 Client 的子进程运行时，需通过自身 stdin/stdout 交换 JSON-RPC；
 *   commonMain 无法直接访问 System.in/System.out（JVM 专属 API），需 expect/actual 委托
 * - 与 [createAcpStdioConnection] 的区别：后者用于 Client 端（ProcessBuilder 启动子进程），
 *   本函数用于 Agent 端（读取自身 stdin）
 *
 * @return 服务端 ACP 连接实例
 * @throws UnsupportedOperationException 当前平台不支持时抛出
 */
@Throws(UnsupportedOperationException::class)
internal expect fun createAcpStdioServerConnection(): AcpConnection
