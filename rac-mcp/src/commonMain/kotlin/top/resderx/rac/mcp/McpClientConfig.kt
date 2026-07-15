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
 * MCP 客户端配置，封装传输方式与超时设置。
 *
 * - 作用：为 [McpClient] 的创建提供统一配置入口，携带传输层配置与超时
 * - 必要性：集中管理 MCP 客户端配置，避免构造函数参数过多；
 *   不可变数据类保证线程安全
 * - 设计思路：transport 为密封接口（Stdio/HTTP/WebSocket），timeoutMillis 控制单次请求超时
 * - 边缘：timeoutMillis=0 表示无超时（适合长时间运行的工具调用）；负数按 0 处理
 *
 * @property transport 传输方式配置
 * @property timeoutMillis 单次 JSON-RPC 请求超时毫秒数，默认 30000（30 秒）；0 表示无超时
 */
data class McpClientConfig(
    val transport: McpTransport,
    val timeoutMillis: Long = 30_000L,
) {
    init {
        require(timeoutMillis >= 0) {
            "timeoutMillis must be non-negative, got $timeoutMillis"
        }
    }
}
