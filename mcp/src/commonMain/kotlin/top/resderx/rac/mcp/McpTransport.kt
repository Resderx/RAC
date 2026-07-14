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

/**
 * MCP 传输层配置，密封接口定义客户端与服务器之间的通信方式。
 *
 * - 作用：以密封接口形式定义三种 MCP 传输方式（Stdio/HTTP/WebSocket），
 *   供 [McpClientConfig] 持有，由 [DefaultMcpClient] 根据具体子类创建对应连接
 * - 必要性：MCP 协议支持多种传输方式，密封接口确保 when 分支穷尽，
 *   编译期保证所有传输方式都被处理
 * - 设计思路：每个子类为不可变 data class，仅携带配置参数（不含连接逻辑）；
 *   连接逻辑由 [DefaultMcpClient] 内部根据传输类型分发创建
 * - 实现方式：sealed interface + data class 子类
 * - 可能的问题：StdioTransport 仅在 JVM/Android 平台可用（需 ProcessBuilder），
 *   其他平台调用将抛 UnsupportedOperationException；WebSocketTransport 暂未实现
 * - 边缘：HttpTransport 的 headers 用于鉴权等自定义请求头；
 *   StdioTransport 的 env 为子进程环境变量覆盖
 * - 优点：密封接口 + when 穷尽检查，新增传输方式时编译器提示所有需更新的分支
 * - 算法/数据结构：无算法，纯配置载体
 * - 时间复杂度：O(1)
 * - 空间复杂度：O(1)（headers/env 为引用持有）
 */

/**
 * Stdio 传输配置：通过启动子进程并经 stdin/stdout 交换 JSON-RPC 2.0 消息。
 *
 * - 适用场景：本地 MCP 服务器（如 Python/Node 脚本），子进程标准输入输出通信
 * - 平台限制：仅 JVM 与 Android 支持（使用 ProcessBuilder）；Native/JS/Web 抛异常
 *
 * @property command 子进程可执行命令（如 "python"、"node"）
 * @property args 命令行参数列表
 * @property env 环境变量覆盖映射；空表示继承当前进程环境
 * @property workingDir 子进程工作目录；null 表示当前目录
 */
data class StdioTransport(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
) : McpTransport

/**
 * HTTP 传输配置：通过 HTTP POST 发送 JSON-RPC 请求，SSE 长连接接收通知。
 *
 * - 适用场景：远程 MCP 服务器，支持全平台（JVM/Native/JS/Wasm）
 * - 通信方式：每个 JSON-RPC 请求为一次 HTTP POST，响应为 HTTP 响应体；
 *   服务器推送通知通过 SSE 长连接（Content-Type: text/event-stream）
 *
 * @property serverUrl MCP 服务器 HTTP 端点 URL
 * @property headers 自定义请求头（如 Authorization: Bearer xxx）
 */
data class HttpTransport(
    val serverUrl: String,
    val headers: Map<String, String> = emptyMap(),
) : McpTransport

/**
 * WebSocket 传输配置：通过 WebSocket 双向交换 JSON-RPC 2.0 消息。
 *
 * - 适用场景：需要全双工通信的 MCP 服务器
 * - 状态：预留接口，暂未实现（后续版本补充）
 *
 * @property serverUrl WebSocket 服务器 URL（ws:// 或 wss://）
 * @property headers 握手阶段自定义请求头
 */
data class WebSocketTransport(
    val serverUrl: String,
    val headers: Map<String, String> = emptyMap(),
) : McpTransport

/**
 * MCP 传输方式密封接口，详见文件级 KDoc。
 */
sealed interface McpTransport
