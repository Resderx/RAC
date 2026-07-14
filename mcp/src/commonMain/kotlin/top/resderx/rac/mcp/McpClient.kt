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

import top.resderx.rac.messages.ToolDefinition

/**
 * MCP 客户端接口，定义与 MCP 服务器交互的统一契约。
 *
 * - 作用：抽象 MCP 协议的工具发现、工具调用、资源读取等核心操作，
 *   为 RAC 的工具调用集成提供统一入口
 * - 必要性：MCP（Model Context Protocol）是 Anthropic 提出的标准化 AI 工具协议，
 *   允许 LLM 通过统一接口调用外部工具与读取资源；RAC 作为 AI 调用库需原生支持 MCP，
 *   使调用方无需关心底层传输（Stdio/HTTP/WebSocket）与 JSON-RPC 协议细节
 * - 设计思路：接口定义最小必要方法集（工具 + 资源 + 生命周期管理），
 *   listTools 返回 RAC 已有的 ToolDefinition 以无缝集成到 chat 调用；
 *   callTool 接受 JSON 字符串参数，返回 JSON 字符串结果，避免与具体 schema 耦合；
 *   close 确保连接资源释放
 * - 实现方式：interface，由 [DefaultMcpClient] 提供默认实现
 * - 可能的问题：listTools/callTool 为 suspend，在非协程上下文需包装；
 *   工具调用可能耗时较长（外部进程/网络），调用方需处理超时
 * - 边缘：服务器无工具时 listTools 返回空列表；工具调用失败抛 RACMcpException；
 *   close 应幂等（多次调用不报错）
 * - 优点：接口最小化，面向工具调用场景；与 RAC 的 ToolDefinition 无缝衔接；
 *   支持多种传输方式（通过 DefaultMcpClient 的传输层抽象）
 * - 算法/数据结构：无算法（接口定义）；实现类内部维护 JSON-RPC 请求/响应匹配
 * - 时间复杂度：由实现类决定（通常为网络 I/O 主导）
 * - 空间复杂度：由实现类决定
 */
interface McpClient {

    /**
     * 列出 MCP 服务器提供的所有工具。
     *
     * - 作用：发现服务器端可用工具，转换为 RAC 的 [ToolDefinition] 供 chat 调用使用
     * - 实现：发送 JSON-RPC `tools/list` 请求，解析响应中的工具列表，
     *   将 MCP 工具的 inputSchema（JSON Schema）转为 ToolDefinition.parameters 字符串
     *
     * @return 工具定义列表；服务器无工具时返回空列表
     * @throws top.resderx.rac.exceptions.RACException 服务器返回错误或连接失败
     */
    suspend fun listTools(): List<ToolDefinition>

    /**
     * 调用 MCP 服务器上的工具。
     *
     * - 作用：执行指定工具，传入 JSON 参数，返回工具执行结果
     * - 实现：发送 JSON-RPC `tools/call` 请求，参数为 `{name, arguments}`，
     *   解析响应中的 content 数组，提取文本内容拼接返回
     *
     * @param name 工具名称（需与 listTools 返回的 ToolDefinition.name 一致）
     * @param arguments 工具参数，JSON 字符串（如 `'{"query":"kotlin"}'`）
     * @return 工具执行结果字符串（通常为 JSON 或纯文本）
     * @throws top.resderx.rac.exceptions.RACException 工具不存在、参数无效或执行失败
     */
    suspend fun callTool(name: String, arguments: String): String

    /**
     * 列出 MCP 服务器提供的所有资源。
     *
     * - 作用：发现服务器端可读资源（文件、数据库记录等）
     * - 实现：发送 JSON-RPC `resources/list` 请求，解析响应中的资源列表
     *
     * @return 资源列表；服务器无资源时返回空列表
     * @throws top.resderx.rac.exceptions.RACException 服务器返回错误或连接失败
     */
    suspend fun listResources(): List<McpResource>

    /**
     * 读取指定 URI 的资源内容。
     *
     * - 作用：获取资源的文本内容（如文件内容、数据库查询结果）
     * - 实现：发送 JSON-RPC `resources/read` 请求，参数为 `{uri}`，
     *   解析响应中的 contents 数组，提取文本内容返回
     *
     * @param uri 资源 URI（需与 listResources 返回的 McpResource.uri 一致）
     * @return 资源文本内容
     * @throws top.resderx.rac.exceptions.RACException 资源不存在或读取失败
     */
    suspend fun readResource(uri: String): String

    /**
     * 关闭客户端连接并释放资源。
     *
     * - 作用：终止与 MCP 服务器的连接，关闭底层传输（子进程/HTTP 连接/WebSocket）
     * - 边缘：幂等操作，多次调用不报错；关闭后调用其他方法会抛异常
     */
    suspend fun close()
}
