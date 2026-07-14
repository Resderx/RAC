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

package top.resderx.rac.acp

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.mcp.JsonRpcError
import com.resderx.rac.mcp.JsonRpcRequest
import com.resderx.rac.mcp.JsonRpcResponse
import com.resderx.rac.mcp.JsonRpcNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * ACP 客户端默认实现，通过双向 JSON-RPC 2.0 与 ACP Agent 交互。
 *
 * - 作用：实现 [AcpClient] 接口，封装 ACP 协议的初始化握手、会话管理、提示轮次、
 *   权限请求处理等操作，面向 [AcpConnection] 传输层抽象编程
 * - 必要性：RAC 库需要原生支持 ACP Client 角色，使 RAC 能调用任何兼容 ACP 的 Agent
 *   （如 Claude Code、Codex CLI、Gemini CLI 等）；DefaultAcpClient 是 ACP 支持的核心实现
 * - 设计思路：
 *   1. 传输层抽象：构造时根据 [AcpTransport] 密封接口的子类型创建对应的 [AcpConnection]
 *   2. 双向消息分发：后台 dispatcher 协程从 connection.incoming 收集消息，
 *      按类型路由——响应（有 id+result/error）匹配 pending 请求；
 *      Agent 请求（有 id+method）调用处理器并回送响应；
 *      通知（有 method 无 id）转发到 update 回调
 *   3. 请求-响应匹配：每个请求分配唯一 id，存入 pendingResponses Map，
 *      用 [CompletableDeferred] 等待 dispatcher 路由响应
 *   4. 流式更新：sessionPrompt 设置 updateCallback，dispatcher 收到 session/update 通知时调用
 *   5. 权限处理：Agent 发起 session/request_permission 请求时，调用 [AcpClientConfig.permissionHandler]
 *   6. 生命周期：initialize 时启动 dispatcher，close 时取消 dispatcher 并失败所有 pending 请求
 * - 与 MCP DefaultMcpClient 的差异：
 *   - MCP 的 request() 内部匹配 id 等待响应（单消费者 Channel）
 *   - ACP 因双向请求需求，改为 SharedFlow + dispatcher 统一路由（支持多消费者与 Agent→Client 请求）
 * - 线程安全：
 *   - pendingResponses：pendingMutex 保护
 *   - updateCallback：callbackMutex 保护
 *   - requestId：idMutex 保护
 *   - 状态标志：stateMutex 保护
 *   - sessionPrompt 串行化：promptMutex 保证同一时刻只有一个活跃轮次
 * - 边缘情况：
 *   - 子进程退出时 incoming 推送空行作为终止信号，dispatcher 检测后失败所有 pending 请求
 *   - close 幂等，多次调用不报错
 *   - 未初始化即调用其他方法抛 IllegalStateException
 *   - Agent 发送未知方法请求时回送 method not found 错误
 * - 时间复杂度：单次请求 O(n) 序列化 + O(1) id 分配 + I/O；dispatcher 每消息 O(n) 解析
 * - 空间复杂度：O(m) pendingResponses（m 为并发请求数）+ O(1) 其他状态
 *
 * @property config ACP 客户端配置
 */
open class DefaultAcpClient(
    private val config: AcpClientConfig,
) : AcpClient {

    /** JSON 序列化器，ignoreUnknownKeys 兼容不同 Agent 实现，encodeDefaults=false 省略 null params。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 协程作用域，管理 dispatcher 生命周期；使用 SupervisorJob 防止子协程异常导致整个作用域取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 底层传输连接，根据配置的 [AcpTransport] 创建。
     *
     * - 使用 `by lazy` 延迟创建：确保子类覆盖的 [createConnection] 在子类属性初始化完成后才被调用，
     *   避免 Kotlin 构造顺序导致的 NPE（父类初始化时子类属性尚未就绪）
     * - 线程安全：lazy 默认使用 synchronized，且首次访问发生在 [ensureInitialized] 的 stateMutex 保护内
     */
    private val connection: AcpConnection by lazy { createConnection() }

    /** 待响应请求映射：requestId → CompletableDeferred<响应 JsonObject>。由 [pendingMutex] 保护。 */
    private val pendingResponses = mutableMapOf<Long, CompletableDeferred<JsonObject>>()

    /** pendingResponses 访问互斥锁，保证并发安全。 */
    private val pendingMutex = Mutex()

    /** 当前会话更新回调，由 sessionPrompt/sessionLoad 设置，dispatcher 读取后调用。由 [callbackMutex] 保护。 */
    private var updateCallback: (suspend (SessionUpdate) -> Unit)? = null

    /** updateCallback 访问互斥锁，保证设置与读取的可见性。 */
    private val callbackMutex = Mutex()

    /** sessionPrompt/sessionLoad 串行化互斥锁，保证同一时刻只有一个活跃轮次（ACP 规范限制）。 */
    private val promptMutex = Mutex()

    /** JSON-RPC 请求 id 自增计数器。由 [idMutex] 保护。 */
    private var requestId = 0L

    /** requestId 自增互斥锁。 */
    private val idMutex = Mutex()

    /** 是否已完成 initialize 握手。由 [stateMutex] 保护。 */
    private var initialized = false

    /** 是否已关闭。由 [stateMutex] 保护。 */
    private var closed = false

    /** 初始化与关闭状态互斥锁。 */
    private val stateMutex = Mutex()

    /** 缓存的 initialize 响应结果，供 [initialize] 多次调用返回。 */
    private var initResult: InitializeResult? = null

    /** dispatcher 协程 Job，用于 close 时取消。 */
    private var dispatcherJob: Job? = null

    /**
     * 根据传输配置创建对应的连接实例。
     *
     * - 作用：密封接口 when 穷尽匹配，为每种传输方式创建对应连接
     * - 可见性：internal open，允许同模块（含测试源集）的子类覆盖以注入 fake 连接
     * - 边缘：HTTP 传输暂未实现，抛 UnsupportedOperationException
     *
     * @return 传输连接实例
     */
    internal open fun createConnection(): AcpConnection = when (config.transport) {
        is AcpStdioTransport -> createAcpStdioConnection(config.transport)
        is AcpHttpTransport -> throw UnsupportedOperationException(
            "ACP HTTP transport is not yet implemented, use AcpStdioTransport"
        )
    }

    /**
     * 确保已完成 initialize 握手（懒初始化）。
     *
     * - 作用：首次调用时建立连接、启动 dispatcher、执行 initialize 握手
     * - 线程安全：stateMutex + double-check 防止并发重复初始化
     * - 边缘：已关闭时抛 IllegalStateException
     */
    private suspend fun ensureInitialized() {
        check(!closed) { "AcpClient is closed" }
        if (initialized) return
        stateMutex.withLock {
            if (initialized) return
            check(!closed) { "AcpClient is closed" }
            // 1. 建立连接
            connection.connect()
            // 2. 启动 dispatcher（在发送 initialize 请求之前，以便接收响应）
            dispatcherJob = scope.launch { runDispatcher() }
            // 3. 执行 initialize 握手
            doInitialize()
            initialized = true
        }
    }

    /**
     * 执行 ACP initialize 握手。
     *
     * - 作用：发送 initialize 请求协商协议版本与能力，缓存响应结果
     * - 实现：构造 InitializeParams → sendRequest → 解析 InitializeResult → 缓存
     */
    private suspend fun doInitialize() {
        val params = InitializeParams(
            protocolVersion = config.protocolVersion,
            clientCapabilities = config.clientCapabilities,
            clientInfo = config.clientInfo,
        )
        val result = sendRequest(
            "initialize",
            json.encodeToJsonElement(InitializeParams.serializer(), params),
        )
        initResult = json.decodeFromJsonElement(InitializeResult.serializer(), result)
    }

    /**
     * 后台消息分发协程：从 connection.incoming 收集消息并路由。
     *
     * - 作用：持续读取 Agent 发来的 JSON-RPC 消息，按类型分发到对应处理器
     * - 消息类型：
     *   - 响应（有 id + result/error）：匹配 pendingResponses，完成对应 CompletableDeferred
     *   - Agent 请求（有 id + method）：调用 [handleAgentRequest] 处理并回送响应
     *   - 通知（有 method 无 id）：调用 [handleNotification] 转发
     * - 终止：收到空行（子进程退出信号）时停止收集，finally 块失败所有 pending 请求
     * - 错误处理：单条消息解析失败不影响后续消息处理
     */
    private suspend fun runDispatcher() {
        try {
            // takeWhile：遇到空行（终止信号）时停止收集
            connection.incoming
                .takeWhile { line -> line.isNotBlank() }
                .collect { line ->
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        routeMessage(obj)
                    } catch (e: CancellationException) {
                        // 协程取消需传播，不吞没
                        throw e
                    } catch (_: Exception) {
                        // 忽略格式错误的消息，继续处理后续消息
                    }
                }
        } finally {
            // 连接关闭后，失败所有待响应请求
            failAllPending("ACP connection closed")
        }
    }

    /**
     * 路由单条 JSON-RPC 消息到对应处理器。
     *
     * - 判断依据：
     *   - 有 result 或 error 字段 + 有 id → 响应消息
     *   - 有 method 字段 + 有 id → Agent 发起的请求
     *   - 有 method 字段 + 无 id → 通知消息
     *
     * @param obj 已解析的 JSON-RPC 消息对象
     */
    private suspend fun routeMessage(obj: JsonObject) {
        val hasResult = obj.containsKey("result")
        val hasError = obj.containsKey("error")
        val hasMethod = obj.containsKey("method")
        val id = obj["id"]?.jsonPrimitive?.longOrNull

        when {
            // 响应消息：有 result 或 error，且有 id
            (hasResult || hasError) && id != null -> {
                val deferred = pendingMutex.withLock { pendingResponses.remove(id) }
                deferred?.complete(obj)
            }
            // Agent 请求：有 method 且有 id
            hasMethod && id != null -> {
                handleAgentRequest(obj, id)
            }
            // 通知：有 method 但无 id
            hasMethod -> {
                handleNotification(obj)
            }
            // 其他：忽略（不符合 JSON-RPC 2.0 规范的消息）
        }
    }

    /**
     * 处理 Agent 发起的请求（Agent → Client 方向的 JSON-RPC 请求）。
     *
     * - 作用：处理 ACP 协议中 Agent 向 Client 发起的方法调用
     * - 支持的方法：
     *   - session/request_permission：调用 [AcpClientConfig.permissionHandler] 处理权限请求
     *   - 其他方法：回送 method not found 错误
     * - 实现：解析方法名与参数 → 调用对应处理器 → 构造 JsonRpcResponse → 发送
     *
     * @param obj 请求消息对象
     * @param id 请求 id，用于匹配响应
     */
    private suspend fun handleAgentRequest(obj: JsonObject, id: Long) {
        val method = obj["method"]?.jsonPrimitive?.content ?: return
        when (method) {
            "session/request_permission" -> {
                val paramsElement = obj["params"] ?: return
                val params = json.decodeFromJsonElement(RequestPermissionParams.serializer(), paramsElement)
                // 调用用户配置的权限处理器
                val outcome = config.permissionHandler(params.permission)
                val result = RequestPermissionResult(outcome = outcome)
                val response = JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = id,
                    result = json.encodeToJsonElement(RequestPermissionResult.serializer(), result),
                )
                connection.send(json.encodeToString(JsonRpcResponse.serializer(), response))
            }
            else -> {
                // 不支持的方法，回送 method not found 错误
                val response = JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = id,
                    error = JsonRpcError(
                        code = AcpError.METHOD_NOT_FOUND,
                        message = "Method not found: $method",
                    ),
                )
                connection.send(json.encodeToString(JsonRpcResponse.serializer(), response))
            }
        }
    }

    /**
     * 处理 Agent 发来的通知（无 id 的 JSON-RPC 消息）。
     *
     * - 作用：处理 session/update 等通知，转发到当前 update 回调
     * - 支持的通知：
     *   - session/update：解析 SessionUpdate 并调用 updateCallback
     *   - 其他：忽略
     *
     * @param obj 通知消息对象
     */
    private suspend fun handleNotification(obj: JsonObject) {
        val method = obj["method"]?.jsonPrimitive?.content ?: return
        when (method) {
            "session/update" -> {
                val paramsElement = obj["params"] ?: return
                val params = json.decodeFromJsonElement(SessionUpdateParams.serializer(), paramsElement)
                // 读取当前回调并调用（callbackMutex 保证可见性）
                val callback = callbackMutex.withLock { updateCallback }
                callback?.invoke(params.update)
            }
            else -> {
                // 忽略未知通知
            }
        }
    }

    /**
     * 发送 JSON-RPC 请求并返回 result（成功时）或抛异常（失败时）。
     *
     * - 作用：统一的请求发送 + 响应匹配 + 错误处理
     * - 实现：
     *   1. 分配唯一 id（idMutex 保护）
     *   2. 创建 CompletableDeferred 存入 pendingResponses
     *   3. 序列化请求并通过 connection.send 发送
     *   4. 等待 dispatcher 路由响应后完成 deferred
     *   5. 检查 error/result 字段
     * - 错误处理：JSON-RPC error 转为 RACException；连接关闭时 deferred 异常完成
     * - 清理：任何异常情况下从 pendingResponses 移除该 id
     *
     * @param method JSON-RPC 方法名
     * @param params 方法参数 JsonElement
     * @return 响应 result 的 JsonElement
     * @throws RACException Agent 返回 JSON-RPC error 或连接失败
     */
    private suspend fun sendRequest(method: String, params: JsonElement): JsonElement {
        val id = idMutex.withLock { ++requestId }
        val request = JsonRpcRequest.create(id = id, method = method, params = params)
        val requestStr = json.encodeToString(JsonRpcRequest.serializer(), request)
        val deferred = CompletableDeferred<JsonObject>()
        pendingMutex.withLock { pendingResponses[id] = deferred }
        try {
            connection.send(requestStr)
            val response = deferred.await()
            if (response.containsKey("error")) {
                val error = json.decodeFromJsonElement(JsonRpcError.serializer(), response["error"]!!)
                throw RACException(
                    "ACP error [${error.code}]: ${error.message}" +
                        (error.data?.let { ", data: $it" } ?: "")
                )
            }
            return response["result"]
                ?: throw RACException("ACP response missing 'result' for method '$method'")
        } catch (e: CancellationException) {
            pendingMutex.withLock { pendingResponses.remove(id) }
            throw e
        } catch (e: Exception) {
            pendingMutex.withLock { pendingResponses.remove(id) }
            throw if (e is RACException) e else RACException("ACP request '$method' failed: ${e.message}", e)
        }
    }

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * @param method 通知方法名
     * @param params 通知参数；null 表示无参数
     */
    private suspend fun sendNotification(method: String, params: JsonElement? = null) {
        val notification = JsonRpcNotification.create(method = method, params = params)
        connection.send(json.encodeToString(JsonRpcNotification.serializer(), notification))
    }

    /**
     * 失败所有待响应请求，用于连接关闭时。
     *
     * - 作用：将 pendingResponses 中所有 CompletableDeferred 异常完成，
     *   使等待响应的 sendRequest 立即抛出异常
     * - 线程安全：pendingMutex 保护移除操作
     *
     * @param reason 失败原因描述
     */
    private suspend fun failAllPending(reason: String) {
        val pending = pendingMutex.withLock {
            val copy = pendingResponses.values.toList()
            pendingResponses.clear()
            copy
        }
        pending.forEach { it.completeExceptionally(RACException(reason)) }
    }

    // ==================== AcpClient 接口实现 ====================

    /** ACP 协议握手。 */
    override suspend fun initialize(): InitializeResult {
        ensureInitialized()
        return initResult!!
    }

    /** 创建新会话。 */
    override suspend fun sessionNew(
        cwd: String,
        mcpServers: List<AcpMcpServerConfig>,
        additionalDirectories: List<String>,
    ): SessionNewResult {
        ensureInitialized()
        val params = SessionNewParams(
            cwd = cwd,
            mcpServers = mcpServers,
            additionalDirectories = additionalDirectories,
        )
        val result = sendRequest(
            "session/new",
            json.encodeToJsonElement(SessionNewParams.serializer(), params),
        )
        return json.decodeFromJsonElement(SessionNewResult.serializer(), result)
    }

    /** 加载已有会话。 */
    override suspend fun sessionLoad(
        sessionId: String,
        cwd: String,
        mcpServers: List<AcpMcpServerConfig>,
        additionalDirectories: List<String>,
        onUpdate: suspend (SessionUpdate) -> Unit,
    ): SessionLoadResult {
        ensureInitialized()
        // sessionLoad 也需要串行化与 update 回调（Agent 会重放历史 session/update 通知）
        return promptMutex.withLock {
            callbackMutex.withLock { updateCallback = onUpdate }
            try {
                val params = SessionLoadParams(
                    sessionId = sessionId,
                    cwd = cwd,
                    mcpServers = mcpServers,
                    additionalDirectories = additionalDirectories,
                )
                val result = sendRequest(
                    "session/load",
                    json.encodeToJsonElement(SessionLoadParams.serializer(), params),
                )
                json.decodeFromJsonElement(SessionLoadResult.serializer(), result)
            } finally {
                callbackMutex.withLock { updateCallback = null }
            }
        }
    }

    /** 发送提示并执行一轮 Agent 对话。 */
    override suspend fun sessionPrompt(
        sessionId: String,
        prompt: List<AcpContentBlock>,
        onUpdate: suspend (SessionUpdate) -> Unit,
    ): StopReason {
        ensureInitialized()
        // promptMutex 串行化：ACP 规范限制每会话同时只能有一个活跃轮次
        return promptMutex.withLock {
            // 设置 update 回调，dispatcher 收到 session/update 通知时调用
            callbackMutex.withLock { updateCallback = onUpdate }
            try {
                val params = SessionPromptParams(sessionId = sessionId, prompt = prompt)
                val result = sendRequest(
                    "session/prompt",
                    json.encodeToJsonElement(SessionPromptParams.serializer(), params),
                )
                // 响应到达后，所有 session/update 通知已由 dispatcher 处理完毕
                json.decodeFromJsonElement(SessionPromptResult.serializer(), result).stopReason
            } finally {
                // 清除回调，防止后续通知误调用
                callbackMutex.withLock { updateCallback = null }
            }
        }
    }

    /** 取消当前进行中的轮次。 */
    override suspend fun sessionCancel(sessionId: String) {
        ensureInitialized()
        val params = SessionCancelParams(sessionId = sessionId)
        sendNotification(
            "session/cancel",
            json.encodeToJsonElement(SessionCancelParams.serializer(), params),
        )
    }

    /** 关闭客户端连接并释放资源。 */
    override suspend fun close() {
        stateMutex.withLock {
            if (closed) return
            closed = true
        }
        // 取消 dispatcher 协程
        dispatcherJob?.cancel()
        // 失败所有待响应请求
        failAllPending("ACP client closed")
        // 关闭底层连接
        try { connection.close() } catch (_: Exception) { /* 关闭时忽略异常 */ }
    }
}
