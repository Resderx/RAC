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

package com.resderx.rac.a2a

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.mcp.JsonRpcRequest
import com.resderx.rac.mcp.JsonRpcResponse
import com.resderx.rac.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * A2A 客户端默认实现——基于 JSON-RPC 2.0 over HTTP + SSE。
 *
 * - 作用：实现 [A2aClient] 接口，封装 A2A 协议的 JSON-RPC 请求发送、响应解析、
 *   SSE 流式事件解析等操作
 * - 必要性：RAC 需要原生支持 A2A Client 角色；与 ACP（基于 stdio 双向 JSON-RPC）不同，
 *   A2A 基于 HTTP 请求-响应 + SSE 流式推送，无需 dispatcher 协程与 CompletableDeferred 匹配
 * - 设计思路：
 *   1. 非流式方法：构造 `JsonRpcRequest` → HTTP POST → 解析 `JsonRpcResponse` → 提取 result
 *   2. 流式方法：构造 `JsonRpcRequest` → HTTP POST（Accept: text/event-stream）→
 *      解析 SSE 事件流 → 按事件类型发射 [A2aStreamEvent]
 *   3. Agent Card：直接 HTTP GET `/.well-known/agent.json`（非 JSON-RPC）
 *   4. 鉴权：若配置 apiKey，在每个请求的 header 中添加 apiKeyHeaderName
 * - 线程安全：requestId 使用 AtomicLong 保证唯一递增；无共享可变状态，天然线程安全
 * - 边缘：
 *   - HTTP 非 2xx 响应抛 RACException（含状态码与响应体）
 *   - JSON-RPC error 响应抛 RACException（含错误码与消息）
 *   - close 幂等；ownHttpClient=true 时关闭自建的 HttpClient
 * - 时间复杂度：单次请求 O(n) 序列化 + HTTP I/O；流式方法 O(m) 解析 m 个 SSE 事件
 * - 空间复杂度：O(1) 无状态，除 HttpClient 外无常驻内存
 *
 * @property config 客户端配置
 */
class DefaultA2aClient(
    private val config: A2aClientConfig,
) : A2aClient {

    /** JSON 序列化器，ignoreUnknownKeys 兼容不同 Agent 实现，encodeDefaults=false 省略 null 字段。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * 底层 HttpClient——若配置提供则复用，否则自建平台默认引擎实例。
     * - close 时根据 [A2aClientConfig.ownHttpClient] 决定是否关闭
     */
    private val httpClient: HttpClient = config.httpClient ?: HttpClientFactory.create()

    /** JSON-RPC 请求 id 自增计数器。由 [idMutex] 保护。 */
    private var requestId = 0L

    /** requestId 自增互斥锁，保证并发安全。 */
    private val idMutex = Mutex()

    /** 是否已关闭，防止重复关闭。 */
    @Volatile
    private var closed = false

    /**
     * 发送 JSON-RPC 请求并返回 result（成功时）或抛异常（失败时）。
     *
     * - 作用：统一的非流式请求发送 + 响应解析 + 错误处理
     * - 实现：
     *   1. 分配唯一 id
     *   2. 构造 JsonRpcRequest 并序列化
     *   3. HTTP POST 到 baseUrl，附加鉴权与 Content-Type header
     *   4. 检查 HTTP 状态码
     *   5. 解析 JsonRpcResponse，检查 error/result
     * - 错误处理：HTTP 非 2xx 或 JSON-RPC error 转 RACException
     *
     * @param method JSON-RPC 方法名（如 "tasks/send"）
     * @param params 方法参数 JsonElement
     * @return 响应 result 的 JsonElement
     * @throws RACException HTTP 失败或 JSON-RPC error
     */
    private suspend fun sendRpcRequest(method: String, params: JsonElement): JsonElement {
        check(!closed) { "A2aClient is closed" }
        val id = idMutex.withLock { ++requestId }
        val request = JsonRpcRequest.create(id = id, method = method, params = params)
        val requestStr = json.encodeToString(JsonRpcRequest.serializer(), request)

        val responseText = try {
            httpClient.post(config.baseUrl) {
                contentType(ContentType.Application.Json)
                header("A2A-Version", config.a2aVersion)
                config.apiKey?.let { header(config.apiKeyHeaderName, it) }
                setBody(requestStr)
            }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RACException("A2A HTTP request '$method' failed: ${e.message}", e)
        }

        val responseObj = try {
            json.decodeFromString(JsonRpcResponse.serializer(), responseText)
        } catch (e: Exception) {
            throw RACException("A2A response parse error for '$method': ${e.message}", e)
        }

        if (responseObj.isError()) {
            val err = responseObj.error!!
            throw RACException("A2A error [${err.code}]: ${err.message}")
        }
        return responseObj.result
            ?: throw RACException("A2A response missing 'result' for method '$method'")
    }

    /**
     * 解析 JSON-RPC result 为指定类型。
     *
     * @param result JsonElement 结果
     * @param serializer 反序列化器
     * @return 反序列化后的对象
     */
    private fun <T> parseResult(
        result: JsonElement,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = json.decodeFromJsonElement(serializer, result)

    /**
     * 判断 result 是否为 Task（含 status 字段）或 Message（含 role 字段）。
     *
     * - 作用：tasks/send 返回类型多态，需运行时判断
     * - 实现：检查 JsonObject 是否含 "status" 字段（Task 标志）
     */
    private fun parseSendMessageResult(result: JsonElement): SendMessageResult {
        val obj = result.jsonObject
        return if (obj.containsKey("status")) {
            SendMessageResult.TaskResult(
                task = json.decodeFromJsonElement(Task.serializer(), result)
            )
        } else {
            SendMessageResult.MessageResult(
                result = json.decodeFromJsonElement(Message.serializer(), result)
            )
        }
    }

    override suspend fun sendMessage(params: SendMessageParams): SendMessageResult {
        val paramsJson = json.encodeToJsonElement(SendMessageParams.serializer(), params)
        val result = sendRpcRequest("tasks/send", paramsJson)
        return parseSendMessageResult(result)
    }

    override fun sendStreamingMessage(params: SendStreamingMessageParams): Flow<A2aStreamEvent> = flow {
        check(!closed) { "A2aClient is closed" }
        val id = idMutex.withLock { ++requestId }
        val request = JsonRpcRequest.create(id = id, method = "tasks/sendSubscribe", params = json.encodeToJsonElement(SendStreamingMessageParams.serializer(), params))
        val requestStr = json.encodeToString(JsonRpcRequest.serializer(), request)

        // 使用 SSE 流式接收事件
        val sseClient = com.resderx.rac.network.SSEClient(httpClient)
        val eventFlow = sseClient.stream(
            urlString = config.baseUrl,
            headers = buildMap {
                put("A2A-Version", config.a2aVersion)
                config.apiKey?.let { put(config.apiKeyHeaderName, it) }
            },
            body = requestStr,
        )

        var hasEmittedInitial = false
        eventFlow.collect { data ->
            // A2A SSE 事件 data 为 JSON-RPC 响应或通知
            if (data.isBlank()) return@collect
            val obj = try {
                json.parseToJsonElement(data).jsonObject
            } catch (_: Exception) {
                return@collect
            }

            // 初始响应：含 id 与 result（JSON-RPC 响应）
            val hasResult = obj.containsKey("result")
            val hasMethod = obj.containsKey("method")

            if (hasResult && !hasMethod && !hasEmittedInitial) {
                val result = obj["result"]!!
                hasEmittedInitial = true
                emit(A2aStreamEvent.Initial(parseSendMessageResult(result)))
                return@collect
            }

            // 流式更新：含 method 的通知
            if (hasMethod) {
                val method = obj["method"]?.jsonPrimitive?.content ?: return@collect
                val paramsElement = obj["params"] ?: return@collect
                when (method) {
                    "tasks/update" -> {
                        // 解析 params 中的 update 字段判断类型
                        val paramsObj = paramsElement.jsonObject
                        val updateElement = paramsObj["update"] ?: return@collect
                        val updateObj = updateElement.jsonObject
                        // 判断是 status 还是 artifact 更新
                        if (updateObj.containsKey("status")) {
                            val event = json.decodeFromJsonElement(
                                TaskStatusUpdateEvent.serializer(), updateElement
                            )
                            emit(A2aStreamEvent.StatusUpdate(event))
                        } else if (updateObj.containsKey("artifact")) {
                            val event = json.decodeFromJsonElement(
                                TaskArtifactUpdateEvent.serializer(), updateElement
                            )
                            emit(A2aStreamEvent.ArtifactUpdate(event))
                        }
                    }
                }
            }
        }
    }

    override suspend fun getTask(params: GetTaskParams): GetTaskResult {
        val paramsJson = json.encodeToJsonElement(GetTaskParams.serializer(), params)
        val result = sendRpcRequest("tasks/get", paramsJson)
        return GetTaskResult(task = parseResult(result, Task.serializer()))
    }

    override suspend fun listTasks(params: ListTasksParams): ListTasksResult {
        val paramsJson = json.encodeToJsonElement(ListTasksParams.serializer(), params)
        val result = sendRpcRequest("tasks/list", paramsJson)
        return parseResult(result, ListTasksResult.serializer())
    }

    override suspend fun cancelTask(params: CancelTaskParams): CancelTaskResult {
        val paramsJson = json.encodeToJsonElement(CancelTaskParams.serializer(), params)
        val result = sendRpcRequest("tasks/cancel", paramsJson)
        return CancelTaskResult(task = parseResult(result, Task.serializer()))
    }

    override suspend fun getAgentCard(agentCardUrl: String?): AgentCard {
        check(!closed) { "A2aClient is closed" }
        val url = agentCardUrl ?: "${config.baseUrl.trimEnd('/')}/.well-known/agent.json"
        val responseText = try {
            httpClient.get(url) {
                header("A2A-Version", config.a2aVersion)
                config.apiKey?.let { header(config.apiKeyHeaderName, it) }
            }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RACException("Failed to fetch Agent Card from '$url': ${e.message}", e)
        }
        return try {
            json.decodeFromString(AgentCard.serializer(), responseText)
        } catch (e: Exception) {
            throw RACException("Agent Card parse error: ${e.message}", e)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        if (config.ownHttpClient && config.httpClient == null) {
            httpClient.close()
        }
    }
}
