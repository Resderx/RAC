package com.resderx.rac.acp

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.mcp.JsonRpcError
import com.resderx.rac.mcp.JsonRpcNotification
import com.resderx.rac.mcp.JsonRpcRequest
import com.resderx.rac.mcp.JsonRpcResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * ACP Agent 服务器，接收 Client 的 JSON-RPC 请求并路由到 [AcpAgentHandler]。
 *
 * - 作用：作为 ACP Agent 端的 JSON-RPC 分发器，从 [AcpConnection] 读取 Client 请求，
 *   调用 [handler] 的对应方法，并将响应/通知回送给 Client
 * - 必要性：ACP Agent 需处理双向 JSON-RPC——既接收 Client 请求（initialize/session/new/prompt），
 *   也向 Client 发送通知（session/update）与请求（session/request_permission）；
 *   AcpAgentServer 统一管理消息路由与请求-响应匹配
 * - 设计思路：
 *   1. dispatcher 协程：从 connection.incoming 收集消息，按类型路由
 *   2. Client→Agent 请求（有 method+id）：调用 handler 方法，回送 JSON-RPC 响应
 *   3. Client→Agent 通知（有 method 无 id）：调用 handler 方法（如 sessionCancel），无响应
 *   4. Agent→Client 请求（由 [AcpAgentContext] 发起）：分配 id，存入 pendingRequests，等待 Client 响应
 *   5. 错误处理：handler 抛异常时回送 JSON-RPC error 响应
 * - 与 [DefaultAcpClient] 的对称性：
 *   - Client 的 dispatcher 路由 Agent→Client 消息，Server 的 dispatcher 路由 Client→Agent 消息
 *   - 都使用 CompletableDeferred 进行请求-响应匹配
 * - 线程安全：pendingResponses 与 requestId 各自由 Mutex 保护
 * - 边缘：
 *   - 连接关闭时（空行终止信号），dispatcher 停止并失败所有 pending 请求
 *   - handler 抛 RACException 时提取 message，其他异常包装为 INTERNAL_ERROR
 *   - close 幂等
 * - 时间复杂度：单条消息 O(n) 解析 + handler 执行
 * - 空间复杂度：O(m) pendingResponses（m 为并发 Agent→Client 请求数）
 *
 * @property connection ACP 传输连接
 * @property handler Agent 业务逻辑处理器
 */
class AcpAgentServer internal constructor(
    private val connection: AcpConnection,
    private val handler: AcpAgentHandler,
) {

    /** JSON 序列化器。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 协程作用域，管理 dispatcher 生命周期。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 待响应的 Agent→Client 请求映射：requestId → CompletableDeferred。由 [pendingMutex] 保护。 */
    private val pendingResponses = mutableMapOf<Long, CompletableDeferred<JsonObject>>()

    /** pendingResponses 访问互斥锁。 */
    private val pendingMutex = Mutex()

    /** Agent→Client 请求 id 自增计数器。由 [idMutex] 保护。 */
    private var requestId = 0L

    /** requestId 自增互斥锁。 */
    private val idMutex = Mutex()

    /** dispatcher 协程 Job。 */
    private var dispatcherJob: Job? = null

    /** 是否已关闭。 */
    private var closed = false

    /**
     * 启动 Agent 服务器，开始接收并处理 Client 请求。
     *
     * - 作用：建立连接并启动 dispatcher 协程
     * - 实现：connection.connect() → launch { runDispatcher() }
     * - 返回：dispatcher 的 Job，调用方可 join 或 cancel
     *
     * @return dispatcher 协程 Job
     */
    fun start(): Job {
        check(!closed) { "AcpAgentServer is already closed" }
        // 启动 dispatcher 协程
        dispatcherJob = scope.launch { runDispatcher() }
        return dispatcherJob!!
    }

    /**
     * 后台消息分发协程：从 connection.incoming 收集并路由 Client 消息。
     *
     * - 流程：connect → takeWhile（空行终止）→ collect → routeMessage
     * - 终止：收到空行（Client 关闭连接信号）时停止，finally 块失败所有 pending 请求
     */
    private suspend fun runDispatcher() {
        connection.connect()
        try {
            connection.incoming
                .takeWhile { it.isNotBlank() }
                .collect { line ->
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        routeMessage(obj)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 忽略格式错误的消息
                    }
                }
        } finally {
            failAllPending("ACP connection closed")
            handler.close()
        }
    }

    /**
     * 路由单条 JSON-RPC 消息。
     *
     * - 响应（有 result/error + id）：匹配 pendingResponses，完成 Agent→Client 请求
     * - 请求（有 method + id）：调用 [handleClientRequest]
     * - 通知（有 method 无 id）：调用 [handleClientNotification]
     *
     * @param obj 已解析的 JSON-RPC 消息对象
     */
    private suspend fun routeMessage(obj: JsonObject) {
        val hasResult = obj.containsKey("result")
        val hasError = obj.containsKey("error")
        val hasMethod = obj.containsKey("method")
        val id = obj["id"]?.jsonPrimitive?.longOrNull

        when {
            // Client 响应我们的 Agent→Client 请求
            (hasResult || hasError) && id != null -> {
                val deferred = pendingMutex.withLock { pendingResponses.remove(id) }
                deferred?.complete(obj)
            }
            // Client→Agent 请求
            hasMethod && id != null -> {
                handleClientRequest(obj, id)
            }
            // Client→Agent 通知
            hasMethod -> {
                handleClientNotification(obj)
            }
        }
    }

    /**
     * 处理 Client→Agent 请求，调用 handler 并回送响应。
     *
     * - 流程：解析 method → 调用 handler 对应方法 → 构造 JsonRpcResponse → send
     * - 错误处理：handler 抛异常时回送 JSON-RPC error
     *
     * @param obj 请求消息对象
     * @param id 请求 id（用于匹配响应）
     */
    private suspend fun handleClientRequest(obj: JsonObject, id: Long) {
        val method = obj["method"]?.jsonPrimitive?.content ?: return
        val paramsElement = obj["params"]
        try {
            val result: JsonElement = when (method) {
                "initialize" -> {
                    val params = decodeParams<InitializeParams>(paramsElement, InitializeParams.serializer())
                    json.encodeToJsonElement(InitializeResult.serializer(), handler.initialize(params))
                }
                "session/new" -> {
                    val params = decodeParams<SessionNewParams>(paramsElement, SessionNewParams.serializer())
                    json.encodeToJsonElement(SessionNewResult.serializer(), handler.sessionNew(params))
                }
                "session/load" -> {
                    val params = decodeParams<SessionLoadParams>(paramsElement, SessionLoadParams.serializer())
                    val context = AcpAgentContextImpl(params.sessionId)
                    handler.sessionLoad(params, context)
                    json.encodeToJsonElement(SessionLoadResult.serializer(), SessionLoadResult())
                }
                "session/prompt" -> {
                    val params = decodeParams<SessionPromptParams>(paramsElement, SessionPromptParams.serializer())
                    val context = AcpAgentContextImpl(params.sessionId)
                    val result = handler.sessionPrompt(params, context)
                    json.encodeToJsonElement(SessionPromptResult.serializer(), result)
                }
                else -> {
                    // 未知方法，回送 method not found
                    sendErrorResponse(id, AcpError.METHOD_NOT_FOUND, "Method not found: $method")
                    return
                }
            }
            sendResponse(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RACException) {
            sendErrorResponse(id, AcpError.INTERNAL_ERROR, e.message ?: "Internal error")
        } catch (e: Exception) {
            sendErrorResponse(id, AcpError.INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    /**
     * 处理 Client→Agent 通知（无 id，无响应）。
     *
     * @param obj 通知消息对象
     */
    private suspend fun handleClientNotification(obj: JsonObject) {
        val method = obj["method"]?.jsonPrimitive?.content ?: return
        when (method) {
            "session/cancel" -> {
                val params = decodeParams<SessionCancelParams>(obj["params"], SessionCancelParams.serializer())
                handler.sessionCancel(params.sessionId)
            }
            else -> {
                // 忽略未知通知
            }
        }
    }

    /**
     * 发送 JSON-RPC 响应到 Client。
     *
     * @param id 请求 id
     * @param result 响应结果 JsonElement
     */
    private suspend fun sendResponse(id: Long, result: JsonElement) {
        val response = JsonRpcResponse(jsonrpc = "2.0", id = id, result = result)
        connection.send(json.encodeToString(JsonRpcResponse.serializer(), response))
    }

    /**
     * 发送 JSON-RPC 错误响应到 Client。
     *
     * @param id 请求 id
     * @param code 错误码
     * @param message 错误描述
     */
    private suspend fun sendErrorResponse(id: Long, code: Int, message: String) {
        val response = JsonRpcResponse(
            jsonrpc = "2.0",
            id = id,
            error = JsonRpcError(code = code, message = message),
        )
        connection.send(json.encodeToString(JsonRpcResponse.serializer(), response))
    }

    /**
     * 发送 JSON-RPC 通知到 Client（无 id，无响应）。
     *
     * @param method 通知方法名
     * @param params 通知参数
     */
    private suspend fun sendNotification(method: String, params: JsonElement) {
        val notification = JsonRpcNotification.create(method = method, params = params)
        connection.send(json.encodeToString(JsonRpcNotification.serializer(), notification))
    }

    /**
     * 发送 Agent→Client 请求并等待响应。
     *
     * - 作用：用于 session/request_permission 等 Agent 发起的请求
     * - 实现：分配 id → 存入 pendingResponses → 发送 → 等待 Client 响应
     *
     * @param method 请求方法名
     * @param params 请求参数
     * @return 响应 result 的 JsonElement
     * @throws RACException Client 返回错误或连接关闭
     */
    private suspend fun sendRequestToClient(method: String, params: JsonElement): JsonElement {
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
                throw RACException("ACP client error [${error.code}]: ${error.message}")
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
     * 失败所有待响应的 Agent→Client 请求。
     *
     * @param reason 失败原因
     */
    private suspend fun failAllPending(reason: String) {
        val pending = pendingMutex.withLock {
            val copy = pendingResponses.values.toList()
            pendingResponses.clear()
            copy
        }
        pending.forEach { it.completeExceptionally(RACException(reason)) }
    }

    /**
     * 解码 JSON-RPC 参数为指定类型。
     *
     * - 作用：统一参数反序列化，params 为 null 时使用类型的默认构造
     */
    private inline fun <reified T> decodeParams(
        params: JsonElement?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        return if (params != null) {
            json.decodeFromJsonElement(serializer, params)
        } else {
            // params 为 null 时通过 JSON 对象反序列化（利用默认值）
            json.decodeFromString(serializer, "{}")
        }
    }

    /**
     * AcpAgentContext 实现，提供 Agent→Client 通信能力。
     *
     * - 作用：在 sessionPrompt/sessionLoad 期间，handler 通过此上下文推送更新与请求权限
     * - 实现：sendUpdate 构造 session/update 通知发送；requestPermission 构造请求发送并等待响应
     *
     * @property sessionId 当前会话 ID（用于 session/update 通知的 sessionId 字段）
     */
    private inner class AcpAgentContextImpl(
        private val sessionId: String,
    ) : AcpAgentContext {

        /** 推送会话更新通知。 */
        override suspend fun sendUpdate(update: SessionUpdate) {
            val params = SessionUpdateParams(sessionId = sessionId, update = update)
            sendNotification(
                "session/update",
                json.encodeToJsonElement(SessionUpdateParams.serializer(), params),
            )
        }

        /** 请求权限并等待 Client 响应。 */
        override suspend fun requestPermission(permission: PermissionRequest): PermissionOutcome {
            val params = RequestPermissionParams(sessionId = sessionId, permission = permission)
            val result = sendRequestToClient(
                "session/request_permission",
                json.encodeToJsonElement(RequestPermissionParams.serializer(), params),
            )
            val permissionResult = json.decodeFromJsonElement(RequestPermissionResult.serializer(), result)
            return permissionResult.outcome
        }
    }

    /**
     * 关闭 Agent 服务器并释放资源。
     *
     * - 作用：停止 dispatcher，关闭连接，调用 handler.close()
     * - 边缘：幂等，多次调用不报错
     */
    suspend fun close() {
        if (closed) return
        closed = true
        dispatcherJob?.cancel()
        failAllPending("AcpAgentServer closed")
        try { connection.close() } catch (_: Exception) { /* 忽略关闭异常 */ }
        try { handler.close() } catch (_: Exception) { /* 忽略关闭异常 */ }
    }
}

/**
 * 创建 ACP Agent 服务器的顶层工厂函数（stdio 传输）。
 *
 * - 作用：提供简洁的 ACP Agent 服务器创建入口，封装底层 stdio 连接构造
 * - 必要性：[AcpAgentServer] 的主构造接受 [AcpConnection]（internal 类型），
 *   外部无法直接构造；本工厂函数隐藏传输细节，仅暴露公开的 [AcpAgentHandler] 参数
 * - 用法：`val server = AcpAgentServer(myHandler); server.start().join()`
 * - 平台支持：JVM 完整实现；其他平台抛 [UnsupportedOperationException]
 *
 * @param handler Agent 业务逻辑处理器
 * @return 已配置 stdio 连接的 AcpAgentServer 实例（尚未启动，需调用 [AcpAgentServer.start]）
 * @throws UnsupportedOperationException 当前平台不支持 stdio 服务端传输时抛出
 */
@Throws(UnsupportedOperationException::class)
fun AcpAgentServer(handler: AcpAgentHandler): AcpAgentServer =
    AcpAgentServer(createAcpStdioServerConnection(), handler)
