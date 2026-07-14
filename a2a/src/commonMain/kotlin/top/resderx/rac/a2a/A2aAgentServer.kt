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

package top.resderx.rac.a2a

import top.resderx.rac.exceptions.RACException
import top.resderx.rac.mcp.JsonRpcError
import top.resderx.rac.mcp.JsonRpcResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile

/**
 * A2A Agent 服务器——协议无关的 JSON-RPC 请求分发器。
 *
 * - 作用：接收已解析的 A2A JSON-RPC 请求，路由到 [top.resderx.rac.a2a.A2aAgentHandler] 的对应方法，
 *   返回 JSON-RPC 响应或流式事件流
 * - 必要性：A2A Server 需处理 tasks/send、tasks/sendSubscribe、tasks/get 等方法，
 *   将 JSON-RPC 协议解析与业务逻辑解耦；此分发器可在任何 HTTP 服务器框架中复用
 * - 设计思路（与 ACP [AcpAgentServer] 的差异）：
 *   1. ACP 基于 stdio 双向 JSON-RPC，需 dispatcher 协程与 CompletableDeferred 匹配
 *   2. A2A 基于 HTTP 请求-响应，每个请求独立处理，无需 dispatcher 与 pending 映射
 *   3. 流式方法通过 [Flow] 返回事件，由调用者通过 SSE 推送给 Client
 * - 协议无关：本类不处理 HTTP 传输，仅做 JSON-RPC 方法路由；调用者负责：
 *   1. 从 HTTP 请求体解析 JSON-RPC 请求
 *   2. 调用 [dispatch] 或 [dispatchStreaming]
 *   3. 将响应通过 HTTP 响应体或 SSE 流返回
 * - 线程安全：无共享可变状态，天然线程安全
 * - 边缘：
 *   - 未知方法回送 METHOD_NOT_FOUND 错误
 *   - handler 抛 RACException 时提取 message，其他异常包装为 INTERNAL_ERROR
 *   - close 幂等
 * - 时间复杂度：单次请求 O(n) 解析 + handler 执行
 * - 空间复杂度：O(1) 无状态
 *
 * @property handler Agent 业务逻辑处理器
 */
class A2aAgentServer(
    private val handler: top.resderx.rac.a2a.A2aAgentHandler,
) {

    /** JSON 序列化器。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 是否已关闭。 */
    @Volatile
    private var closed = false

    /**
     * 获取 Agent Card 的 JSON 表示。
     *
     * - 作用：供 HTTP 服务器在 `/.well-known/agent.json` 端点返回
     *
     * @return Agent Card 的 JsonElement
     */
    fun getAgentCardJson(): JsonElement = json.encodeToJsonElement(
        _root_ide_package_.top.resderx.rac.a2a.AgentCard.serializer(), handler.getAgentCard()
    )

    /**
     * 分发非流式 JSON-RPC 请求到 handler，返回 JSON-RPC 响应。
     *
     * - 作用：处理 tasks/send、tasks/get、tasks/list、tasks/cancel 等非流式方法
     * - 实现：
     *   1. 从请求中提取 method 与 params
     *   2. 路由到 handler 对应方法
     *   3. 将结果序列化为 JsonRpcResponse
     *   4. 异常时构造 error 响应
     *
     * @param requestJson JSON-RPC 请求的 JsonObject
     * @return JSON-RPC 响应的 JsonObject（含 result 或 error）
     */
    suspend fun dispatch(requestJson: JsonObject): JsonObject {
        check(!closed) { "A2aAgentServer is closed" }
        val id = requestJson["id"]?.jsonPrimitive?.longOrNull
        val method = requestJson["method"]?.jsonPrimitive?.content
        val params = requestJson["params"]

        if (method == null) {
            return errorResponse(id, _root_ide_package_.top.resderx.rac.a2a.A2aError.INVALID_PARAMS, "Missing 'method' field")
        }
        if (params == null) {
            return errorResponse(id, _root_ide_package_.top.resderx.rac.a2a.A2aError.INVALID_PARAMS, "Missing 'params' field")
        }

        return try {
            val result = when (method) {
                "tasks/send" -> {
                    val p = json.decodeFromJsonElement(_root_ide_package_.top.resderx.rac.a2a.SendMessageParams.serializer(), params)
                    json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.a2a.SendMessageResult.serializer(), handler.sendMessage(p))
                }
                "tasks/get" -> {
                    val p = json.decodeFromJsonElement(_root_ide_package_.top.resderx.rac.a2a.GetTaskParams.serializer(), params)
                    json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.a2a.GetTaskResult.serializer(), handler.getTask(p))
                }
                "tasks/list" -> {
                    val p = json.decodeFromJsonElement(_root_ide_package_.top.resderx.rac.a2a.ListTasksParams.serializer(), params)
                    json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.a2a.ListTasksResult.serializer(), handler.listTasks(p))
                }
                "tasks/cancel" -> {
                    val p = json.decodeFromJsonElement(_root_ide_package_.top.resderx.rac.a2a.CancelTaskParams.serializer(), params)
                    json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.a2a.CancelTaskResult.serializer(), handler.cancelTask(p))
                }
                else -> {
                    return errorResponse(id, _root_ide_package_.top.resderx.rac.a2a.A2aError.METHOD_NOT_FOUND, "Method not found: $method")
                }
            }
            successResponse(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: top.resderx.rac.exceptions.RACException) {
            errorResponse(id, _root_ide_package_.top.resderx.rac.a2a.A2aError.INTERNAL_ERROR, e.message ?: "Unknown error")
        } catch (e: Exception) {
            errorResponse(id, _root_ide_package_.top.resderx.rac.a2a.A2aError.INTERNAL_ERROR, e.message ?: "Unknown error")
        }
    }

    /**
     * 分发流式 JSON-RPC 请求，返回流式事件 Flow。
     *
     * - 作用：处理 tasks/sendSubscribe 流式方法
     * - 实现：
     *   1. 解析请求参数
     *   2. 创建 [Channel] 缓冲 handler 通过 context 推送的更新
     *   3. 调用 handler.sendStreamingMessage——更新被缓冲到 channel，返回值为初始结果
     *   4. 先发射 [top.resderx.rac.a2a.A2aStreamEvent.Initial]（初始结果），再排空 channel 发射缓冲的更新事件
     *   5. 流结束由 handler 通过 `final=true` 的状态更新标识
     * - 设计思路：handler 在返回前通过 context 推送 WORKING/Artifact/COMPLETED 等更新，
     *   若直接 emit 会导致 Initial 事件排在更新之后；使用 Channel 缓冲可保证事件顺序为
     *   [Initial, StatusUpdate..., ArtifactUpdate..., StatusUpdate(final)]
     * - 边缘：Channel 容量为 UNLIMITED，不会因 handler 推送过快而阻塞
     *
     * @param requestJson JSON-RPC 请求的 JsonObject
     * @return 流式事件 Flow
     */
    fun dispatchStreaming(requestJson: JsonObject): Flow<top.resderx.rac.a2a.A2aStreamEvent> = flow {
        check(!closed) { "A2aAgentServer is closed" }
        val method = requestJson["method"]?.jsonPrimitive?.content
        val params = requestJson["params"]

        if (method != "tasks/sendSubscribe") {
            throw _root_ide_package_.top.resderx.rac.exceptions.RACException("Streaming dispatch only supports 'tasks/sendSubscribe', got: $method")
        }
        if (params == null) {
            throw _root_ide_package_.top.resderx.rac.exceptions.RACException("Missing 'params' field in streaming request")
        }

        val p = json.decodeFromJsonElement(_root_ide_package_.top.resderx.rac.a2a.SendStreamingMessageParams.serializer(), params)

        // 使用 Channel 缓冲 handler 推送的更新，确保 Initial 事件先于更新事件发射
        val channel = Channel<top.resderx.rac.a2a.A2aStreamEvent>(Channel.UNLIMITED)

        val context = object : top.resderx.rac.a2a.A2aAgentContext {
            override suspend fun sendStatusUpdate(event: top.resderx.rac.a2a.TaskStatusUpdateEvent) {
                channel.send(_root_ide_package_.top.resderx.rac.a2a.A2aStreamEvent.StatusUpdate(event))
            }
            override suspend fun sendArtifactUpdate(event: top.resderx.rac.a2a.TaskArtifactUpdateEvent) {
                channel.send(_root_ide_package_.top.resderx.rac.a2a.A2aStreamEvent.ArtifactUpdate(event))
            }
        }

        // 调用 handler——更新被缓冲到 channel，返回值为初始结果
        val initialResult = handler.sendStreamingMessage(p, context)

        // 先发射 Initial，再排空 channel 中的缓冲更新，保证事件顺序正确
        emit(_root_ide_package_.top.resderx.rac.a2a.A2aStreamEvent.Initial(initialResult))
        channel.close()
        for (event in channel) {
            emit(event)
        }
    }

    /**
     * 构造 JSON-RPC 成功响应。
     *
     * @param id 请求 id（可空）
     * @param result 结果 JsonElement
     * @return 响应 JsonObject
     */
    private fun successResponse(id: Long?, result: JsonElement): JsonObject {
        val response = _root_ide_package_.top.resderx.rac.mcp.JsonRpcResponse(
            jsonrpc = "2.0",
            id = id,
            result = result,
        )
        return json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.mcp.JsonRpcResponse.serializer(), response).jsonObject
    }

    /**
     * 构造 JSON-RPC 错误响应。
     *
     * @param id 请求 id（可空）
     * @param code 错误码
     * @param message 错误消息
     * @return 响应 JsonObject
     */
    private fun errorResponse(id: Long?, code: Int, message: String): JsonObject {
        val response = _root_ide_package_.top.resderx.rac.mcp.JsonRpcResponse(
            jsonrpc = "2.0",
            id = id,
            error = _root_ide_package_.top.resderx.rac.mcp.JsonRpcError(code = code, message = message),
        )
        return json.encodeToJsonElement(_root_ide_package_.top.resderx.rac.mcp.JsonRpcResponse.serializer(), response).jsonObject
    }

    /**
     * 关闭服务器并释放 handler 资源。
     *
     * - 幂等：多次调用不报错
     */
    suspend fun close() {
        if (closed) return
        closed = true
        handler.close()
    }
}
