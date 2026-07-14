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

import top.resderx.rac.exceptions.RACException
import top.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCP 客户端默认实现，通过 JSON-RPC 2.0 与 MCP 服务器交互。
 *
 * - 作用：实现 [McpClient] 接口，封装 MCP 协议的初始化握手、工具发现、工具调用、
 *   资源读取等操作，面向 [McpConnection] 传输层抽象编程
 * - 必要性：RAC 库需要原生支持 MCP 协议，使 LLM 能通过统一接口调用外部工具与读取资源；
 *   DefaultMcpClient 是 MCP 支持的核心实现，连接 MCP 生态与 RAC 的工具调用体系
 * - 设计思路：
 *   1. 传输层抽象：构造时根据 [McpTransport] 密封接口的子类型（HTTP/Stdio/WebSocket）
 *      创建对应的 [McpConnection]，客户端面向接口编程
 *   2. 懒初始化：首次请求时自动执行 MCP initialize 握手（协议版本协商 + 能力声明），
 *      发送 notifications/initialized 通知，后续请求复用已建立的连接
 *   3. 请求 ID 管理：AtomicLong 自增分配 JSON-RPC 请求 id，确保并发安全
 *   4. 错误处理：JSON-RPC error 响应转为 RACException 抛出，含错误码与消息
 *   5. 线程安全：Mutex 保护初始化过程，防止并发重复初始化
 * - 实现方式：
 *   - sendRequest：序列化 JsonRpcRequest → connection.request → 解析 JsonRpcResponse → 错误检查
 *   - sendNotification：序列化 JsonRpcNotification → connection.notify
 *   - listTools：tools/list → McpToolListResult → 映射为 List<ToolDefinition>
 *   - callTool：tools/call → McpToolCallResult → 提取 text content
 *   - listResources/readResource：类似模式
 * - 可能的问题：
 *   1. 不支持服务器 SSE 推送通知（如资源变更），仅支持请求-响应模式
 *   2. 工具调用超时由 [McpClientConfig.timeoutMillis] 控制，但长耗时工具可能超时
 *   3. 连接断开后不自动重连（HTTP 连接由 RetryExecutor 在单次请求级别重试）
 * - 边缘情况：
 *   - 服务器无工具时 listTools 返回空列表
 *   - callTool 的 isError=true 时抛 RACException 含错误文本
 *   - close 幂等，多次调用不报错
 *   - 初始化失败后后续请求仍会尝试初始化（initialized 标志仅在成功后置 true）
 * - 优点：懒初始化减少不必要握手；密封接口穷尽匹配；AtomicLong 并发安全；
 *   复用 RAC 异常体系
 * - 算法/数据结构：
 *   - 请求-响应匹配：Long 自增 id
 *   - 初始化保护：Mutex + double-check
 *   - JSON 序列化：kotlinx-serialization
 * - 时间复杂度：单次请求为 O(n) 序列化 + O(1) id 分配 + 网络 RTT
 * - 空间复杂度：O(1)（持有连接与配置引用）
 *
 * @property config MCP 客户端配置
 */
class DefaultMcpClient(
    private val config: McpClientConfig,
) : McpClient {

    /** JSON 序列化器，ignoreUnknownKeys 兼容不同服务器实现，encodeDefaults=false 省略 null params。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** JSON-RPC 请求 id 自增计数器，由 [idMutex] 保护以支持并发安全。 */
    private var requestId = 0L

    /** 请求 id 自增互斥锁，保证并发请求分配唯一 id。 */
    private val idMutex = Mutex()

    /** 初始化互斥锁，防止并发请求同时触发 initialize 握手。 */
    private val initMutex = Mutex()

    /** 是否已完成 MCP initialize 握手。由 [initMutex] 保护可见性。 */
    private var initialized = false

    /** 是否已关闭。 */
    private var closed = false

    /** 底层传输连接，懒创建。 */
    private val connection: McpConnection by lazy { createConnection() }

    /**
     * 根据传输配置创建对应的连接实例。
     *
     * - 作用：密封接口 when 穷尽匹配，为每种传输方式创建对应连接
     * - 边缘：WebSocketTransport 暂未实现，抛 UnsupportedOperationException
     *
     * @return 传输连接实例
     */
    private fun createConnection(): McpConnection = when (val transport = config.transport) {
        is HttpTransport -> HttpMcpConnection(transport, config.timeoutMillis)
        is StdioTransport -> createStdioConnection(transport, config.timeoutMillis)
        is WebSocketTransport -> throw UnsupportedOperationException(
            "WebSocket transport is not yet implemented, use HttpTransport or StdioTransport"
        )
    }

    /**
     * 确保已完成 MCP initialize 握手（懒初始化）。
     *
     * - 作用：首次调用时执行 initialize 请求 + initialized 通知
     * - 线程安全：Mutex + double-check 防止并发重复初始化
     */
    private suspend fun ensureInitialized() {
        check(!closed) { "McpClient is closed" }
        if (initialized) return
        initMutex.withLock {
            if (initialized) return  // double-check after acquiring lock
            connection.connect()
            doInitialize()
            initialized = true
        }
    }

    /**
     * 执行 MCP initialize 握手。
     *
     * - 作用：发送 initialize 请求协商协议版本与能力，然后发送 initialized 通知
     * - 实现：构造 McpInitializeParams → sendRequest → 解析 McpInitializeResult →
     *   sendNotification("notifications/initialized")
     */
    private suspend fun doInitialize() {
        val params = McpInitializeParams(
            protocolVersion = "2024-11-05",
            capabilities = JsonObject(emptyMap()),
            clientInfo = McpClientInfo(name = "rac", version = "0.2.0"),
        )
        val result = sendRequest("initialize", json.encodeToJsonElement(McpInitializeParams.serializer(), params))
        val initResult = json.decodeFromJsonElement(McpInitializeResult.serializer(), result)
        // 协议版本协商：服务器返回其支持的版本，此处不强制校验（兼容性优先）
        // 发送 initialized 通知，完成握手
        sendNotification("notifications/initialized")
    }

    /**
     * 发送 JSON-RPC 请求并返回 result（成功时）或抛异常（失败时）。
     *
     * - 作用：统一的请求发送 + 响应解析 + 错误处理
     * - 实现：构造 JsonRpcRequest → 序列化 → connection.request → 解析 JsonRpcResponse →
     *   error 则抛 RACException，否则返回 result
     *
     * @param method JSON-RPC 方法名
     * @param params 方法参数 JsonElement；null 表示无参数
     * @return 响应 result 的 JsonElement
     * @throws RACException 服务器返回 JSON-RPC error 或连接失败
     */
    private suspend fun sendRequest(method: String, params: JsonElement?): JsonElement {
        val id = idMutex.withLock { ++requestId }
        val request = JsonRpcRequest.create(id = id, method = method, params = params)
        val requestStr = json.encodeToString(JsonRpcRequest.serializer(), request)
        val responseStr = connection.request(requestStr)
        val response = json.decodeFromString(JsonRpcResponse.serializer(), responseStr)
        if (response.isError()) {
            val err = response.error!!
            throw RACException("MCP error [${err.code}]: ${err.message}" +
                (err.data?.let { ", data: $it" } ?: ""))
        }
        return response.result ?: throw RACException("MCP response missing 'result' field for method '$method'")
    }

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * @param method 通知方法名
     * @param params 通知参数；null 表示无参数
     */
    private suspend fun sendNotification(method: String, params: JsonElement? = null) {
        val notification = JsonRpcNotification.create(method = method, params = params)
        val notificationStr = json.encodeToString(JsonRpcNotification.serializer(), notification)
        connection.notify(notificationStr)
    }

    /**
     * 列出 MCP 服务器工具，转换为 RAC ToolDefinition。
     *
     * - 实现：ensureInitialized → tools/list → McpToolListResult → 映射为 ToolDefinition
     * - 转换：MCP 的 inputSchema（JsonElement）转为 JSON 字符串作为 parameters；
     *   description 为 null 时使用空字符串（ToolDefinition.description 非空）
     */
    override suspend fun listTools(): List<ToolDefinition> {
        ensureInitialized()
        val result = sendRequest("tools/list", null)
        val toolList = json.decodeFromJsonElement(McpToolListResult.serializer(), result)
        return toolList.tools.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description ?: "",
                parameters = tool.inputSchema?.toString() ?: "{}",
            )
        }
    }

    /**
     * 调用 MCP 服务器工具。
     *
     * - 实现：ensureInitialized → 解析 arguments 为 JsonElement → tools/call →
     *   McpToolCallResult → 提取 text content
     * - 错误处理：isError=true 时抛 RACException 含工具错误文本
     *
     * @param name 工具名称
     * @param arguments 工具参数 JSON 字符串
     * @return 工具执行结果文本（多个 content 块以换行拼接）
     */
    override suspend fun callTool(name: String, arguments: String): String {
        ensureInitialized()
        // 将 arguments 字符串解析为 JsonElement；空字符串视为空对象
        val argsJson: JsonElement = if (arguments.isBlank()) {
            JsonObject(emptyMap())
        } else {
            json.parseToJsonElement(arguments)
        }
        val params = McpToolCallParams(name = name, arguments = argsJson)
        val result = sendRequest("tools/call", json.encodeToJsonElement(McpToolCallParams.serializer(), params))
        val callResult = json.decodeFromJsonElement(McpToolCallResult.serializer(), result)
        // 提取所有 text 类型内容块，以换行拼接
        val text = callResult.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
        // isError=true 表示工具执行出错（但服务器仍返回了错误信息而非 JSON-RPC error）
        if (callResult.isError == true) {
            throw RACException("MCP tool '$name' returned error: $text")
        }
        return text
    }

    /**
     * 列出 MCP 服务器资源。
     *
     * - 实现：ensureInitialized → resources/list → McpResourceListResult → 映射为 McpResource
     */
    override suspend fun listResources(): List<McpResource> {
        ensureInitialized()
        val result = sendRequest("resources/list", null)
        val resourceList = json.decodeFromJsonElement(McpResourceListResult.serializer(), result)
        return resourceList.resources.map { info ->
            McpResource(
                uri = info.uri,
                name = info.name,
                description = info.description,
                mimeType = info.mimeType,
            )
        }
    }

    /**
     * 读取指定 URI 的资源内容。
     *
     * - 实现：ensureInitialized → resources/read → McpResourceReadResult → 提取 text content
     *
     * @param uri 资源 URI
     * @return 资源文本内容
     */
    override suspend fun readResource(uri: String): String {
        ensureInitialized()
        val params = McpResourceReadParams(uri = uri)
        val result = sendRequest("resources/read", json.encodeToJsonElement(McpResourceReadParams.serializer(), params))
        val readResult = json.decodeFromJsonElement(McpResourceReadResult.serializer(), result)
        return readResult.contents
            .mapNotNull { it.text }
            .joinToString("\n")
    }

    /**
     * 关闭客户端连接并释放资源。
     *
     * - 实现：标记 closed → connection.close()
     * - 边缘：幂等，多次调用不报错；close 后调用其他方法抛 IllegalStateException
     */
    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: Exception) {
            // 关闭时忽略异常，确保不阻塞调用方
        }
    }
}

/**
 * 创建 MCP 客户端的顶层工厂函数。
 *
 * - 作用：提供简洁的 MCP 客户端创建入口，封装 DefaultMcpClient 构造
 * - 用法：`val client = McpClient(McpClientConfig(transport = HttpTransport("http://...")))`
 *
 * @param config MCP 客户端配置
 * @return DefaultMcpClient 实例
 */
fun McpClient(config: McpClientConfig): McpClient = DefaultMcpClient(config)
