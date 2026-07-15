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

package top.resderx.rac

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import top.resderx.rac.a2a.A2aAgentHandler
import top.resderx.rac.a2a.A2aAgentServer
import top.resderx.rac.a2a.A2aStreamEvent
import top.resderx.rac.a2a.AgentCard
import top.resderx.rac.a2a.Artifact
import top.resderx.rac.a2a.GetTaskParams
import top.resderx.rac.a2a.GetTaskResult
import top.resderx.rac.a2a.ListTasksParams
import top.resderx.rac.a2a.ListTasksResult
import top.resderx.rac.a2a.Message
import top.resderx.rac.a2a.Role
import top.resderx.rac.a2a.SendMessageParams
import top.resderx.rac.a2a.SendMessageResult
import top.resderx.rac.a2a.SendStreamingMessageParams
import top.resderx.rac.a2a.Task
import top.resderx.rac.a2a.TaskState
import top.resderx.rac.a2a.TaskStatus
import top.resderx.rac.a2a.TaskStatusUpdateEvent
import top.resderx.rac.a2a.TextPart
import top.resderx.rac.a2a.serveAsA2aAgent
import top.resderx.rac.dsl.Llm
import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ProviderRegistry
import top.resderx.rac.providers.SimpleModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A2A 协议端到端测试（JVM）。
 *
 * - 作用：验证 [A2aAgentServer]（协议分发器）、[RacA2aAgent]（Llm→A2A 适配器）以及
 *   Llm DSL 集成（chatWithA2aAgent / serveAsA2aAgent）的核心协议逻辑
 * - 必要性：A2A 协议涉及 JSON-RPC 方法路由、流式事件 Flow、Task 生命周期管理等机制，
 *   需端到端测试验证消息正确路由与状态同步
 * - 设计：
 *   - [FakeA2aAgentHandler]：可控 handler 实现，记录调用并返回预设结果
 *   - Server 测试直接构造 JsonObject 请求，验证 dispatch 返回的 JsonObject 响应
 *   - DSL 测试使用 SseCapableMockEngine 模拟 AI 供应商响应
 * - 注意：使用 [runBlocking] 而非 runTest，避免与 Flow 收集的时序冲突
 * - 边缘：每个测试结束后调用 server.close() 清理资源
 */
class A2aProtocolTest {

    /** JSON 序列化器，与生产代码配置一致。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * 可控的 A2A Agent Handler fake 实现。
     *
     * - 记录所有调用，返回预设结果
     * - 流式方法推送预设的状态更新与产出物
     */
    private class FakeA2aAgentHandler(
        private val agentCard: AgentCard = AgentCard(
            name = "fake-a2a-agent",
            url = "http://localhost",
        ),
    ) : A2aAgentHandler {
        var sendMessageCalled = false
            private set
        var sendStreamingCalled = false
            private set
        var getTaskCalled = false
            private set
        var listTasksCalled = false
            private set
        var cancelTaskCalled = false
            private set
        var lastPromptText: String? = null
            private set

        override fun getAgentCard(): AgentCard = agentCard

        override suspend fun sendMessage(params: SendMessageParams): SendMessageResult {
            sendMessageCalled = true
            lastPromptText = params.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            val task = Task(
                id = params.id ?: "task-1",
                status = TaskStatus(state = TaskState.COMPLETED),
                history = listOf(params.message, Message(
                    role = Role.AGENT,
                    parts = listOf(TextPart(text = "Response from agent")),
                )),
            )
            return SendMessageResult.TaskResult(task = task)
        }

        override suspend fun sendStreamingMessage(
            params: SendStreamingMessageParams,
            context: top.resderx.rac.a2a.A2aAgentContext,
        ): SendMessageResult {
            sendStreamingCalled = true
            lastPromptText = params.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            val taskId = params.id ?: "task-stream-1"
            // 推送 WORKING 状态
            context.sendStatusUpdate(TaskStatusUpdateEvent(
                id = taskId,
                status = TaskStatus(state = TaskState.WORKING),
            ))
            // 推送产出物
            context.sendArtifactUpdate(top.resderx.rac.a2a.TaskArtifactUpdateEvent(
                id = taskId,
                artifact = Artifact(
                    artifactId = "msg-1",
                    parts = listOf(TextPart(text = "Streaming response")),
                    lastChunk = true,
                ),
                lastChunk = true,
            ))
            // 推送最终状态
            context.sendStatusUpdate(TaskStatusUpdateEvent(
                id = taskId,
                status = TaskStatus(state = TaskState.COMPLETED),
                final = true,
            ))
            val task = Task(
                id = taskId,
                status = TaskStatus(state = TaskState.COMPLETED),
                history = listOf(params.message, Message(
                    role = Role.AGENT,
                    parts = listOf(TextPart(text = "Streaming response")),
                )),
            )
            return SendMessageResult.TaskResult(task = task)
        }

        override suspend fun getTask(params: GetTaskParams): GetTaskResult {
            getTaskCalled = true
            return GetTaskResult(task = Task(
                id = params.id,
                status = TaskStatus(state = TaskState.COMPLETED),
            ))
        }

        override suspend fun listTasks(params: ListTasksParams): ListTasksResult {
            listTasksCalled = true
            return ListTasksResult(tasks = emptyList())
        }

        override suspend fun cancelTask(params: top.resderx.rac.a2a.CancelTaskParams): top.resderx.rac.a2a.CancelTaskResult {
            cancelTaskCalled = true
            return top.resderx.rac.a2a.CancelTaskResult(task = Task(
                id = params.id,
                status = TaskStatus(state = TaskState.CANCELED),
            ))
        }

        override suspend fun close() { /* no-op */ }
    }

    // ==================== 辅助函数 ====================

    /** 构造 JSON-RPC 请求字符串。 */
    private fun rpcRequest(id: Long, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    // ==================== A2aAgentServer 测试 ====================

    /**
     * 验证 Server 正确路由 tasks/send 请求并返回响应。
     */
    @Test
    fun a2aServerRoutesSendMessageRequest() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val params = buildJsonObject {
            put("message", buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "text")
                        put("text", "Hello A2A")
                    })
                })
            })
        }
        val request = rpcRequest(1, "tasks/send", params)
        val response = server.dispatch(request)

        assertTrue(handler.sendMessageCalled, "handler.sendMessage should be called")
        assertEquals("Hello A2A", handler.lastPromptText)

        // 验证响应结构
        assertTrue(response.containsKey("result"))
        val result = response["result"]!!.jsonObject
        assertTrue(result.containsKey("task"))
        val task = result["task"]!!.jsonObject
        assertEquals("task-1", task["id"]!!.jsonPrimitive.content)
        assertEquals("completed", task["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        server.close()
    }

    /**
     * 验证 Server 正确路由 tasks/get 请求。
     */
    @Test
    fun a2aServerRoutesGetTaskRequest() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val params = buildJsonObject { put("id", "task-123") }
        val request = rpcRequest(2, "tasks/get", params)
        val response = server.dispatch(request)

        assertTrue(handler.getTaskCalled)
        val result = response["result"]!!.jsonObject
        assertEquals("task-123", result["task"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        server.close()
    }

    /**
     * 验证 Server 正确路由 tasks/cancel 请求。
     */
    @Test
    fun a2aServerRoutesCancelTaskRequest() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val params = buildJsonObject { put("id", "task-to-cancel") }
        val request = rpcRequest(3, "tasks/cancel", params)
        val response = server.dispatch(request)

        assertTrue(handler.cancelTaskCalled)
        val result = response["result"]!!.jsonObject
        assertEquals("canceled", result["task"]!!.jsonObject["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        server.close()
    }

    /**
     * 验证 Server 对未知方法返回 METHOD_NOT_FOUND 错误。
     */
    @Test
    fun a2aServerReturnsErrorForUnknownMethod() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val params = buildJsonObject { put("id", "x") }
        val request = rpcRequest(4, "tasks/unknown", params)
        val response = server.dispatch(request)

        assertTrue(response.containsKey("error"))
        val error = response["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.long.toInt())

        server.close()
    }

    /**
     * 验证 Server 流式分发推送正确的事件序列。
     */
    @Test
    fun a2aServerDispatchesStreamingEvents() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val params = buildJsonObject {
            put("message", buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "text")
                        put("text", "Stream test")
                    })
                })
            })
        }
        val request = rpcRequest(5, "tasks/sendSubscribe", params)

        val events = server.dispatchStreaming(request).toList()

        // 预期事件序列：Initial(TaskResult) + StatusUpdate(WORKING) + ArtifactUpdate + StatusUpdate(COMPLETED, final)
        assertEquals(4, events.size)

        // 第一个：Initial
        assertTrue(events[0] is A2aStreamEvent.Initial)
        val initial = events[0] as A2aStreamEvent.Initial
        assertTrue(initial.result is SendMessageResult.TaskResult)

        // 第二个：StatusUpdate (WORKING)
        assertTrue(events[1] is A2aStreamEvent.StatusUpdate)
        val workingUpdate = events[1] as A2aStreamEvent.StatusUpdate
        assertEquals(TaskState.WORKING, workingUpdate.event.status.state)

        // 第三个：ArtifactUpdate
        assertTrue(events[2] is A2aStreamEvent.ArtifactUpdate)
        val artifactUpdate = events[2] as A2aStreamEvent.ArtifactUpdate
        assertEquals("Streaming response", (artifactUpdate.event.artifact.parts[0] as TextPart).text)

        // 第四个：StatusUpdate (COMPLETED, final=true)
        assertTrue(events[3] is A2aStreamEvent.StatusUpdate)
        val finalUpdate = events[3] as A2aStreamEvent.StatusUpdate
        assertEquals(TaskState.COMPLETED, finalUpdate.event.status.state)
        assertTrue(finalUpdate.event.final)

        assertTrue(handler.sendStreamingCalled)
        server.close()
    }

    /**
     * 验证 Server 的 getAgentCardJson 返回正确的 Agent Card JSON。
     */
    @Test
    fun a2aServerReturnsAgentCardJson() = runBlocking {
        val handler = FakeA2aAgentHandler()
        val server = A2aAgentServer(handler)

        val cardJson = server.getAgentCardJson().jsonObject
        assertEquals("fake-a2a-agent", cardJson["name"]!!.jsonPrimitive.content)
        assertEquals("http://localhost", cardJson["url"]!!.jsonPrimitive.content)

        server.close()
    }

    // ==================== RacA2aAgent + DSL 测试 ====================

    /**
     * 创建由 SseCapableMockEngine 支撑的 Llm 实例，handler 决定 HTTP 响应。
     */
    private fun racWithMock(handler: io.ktor.client.engine.mock.MockRequestHandler): Llm {
        val client = HttpClient(SseCapableMockEngine(handler)) {
            install(SSE)
            install(HttpTimeout)
        }
        val provider = SimpleModelProvider(
            name = "mock",
            baseUrl = "http://localhost",
            apiKey = null,
            defaultApiType = ApiType.COMPLETIONS,
            models = mapOf("gpt-4" to ModelConfig()),
        )
        val registry = ProviderRegistry().apply { register(provider) }
        return Llm(
            httpClient = client,
            registry = registry,
            defaultProvider = provider,
        )
    }

    /** 构造 Completions API stop 响应 JSON。 */
    private fun stopResponseJson(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
    }

    /**
     * 验证 RacA2aAgent 正确将 A2A 消息转为 Llm chat 调用并构造 Task。
     */
    @Test
    fun racA2aAgentMapsPromptToChatAndReturnsTask() = runBlocking {
        val rac = racWithMock { _ ->
            respond(
                stopResponseJson("Hello from RAC"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val server = rac.serveAsA2aAgent(
            systemPrompt = "You are helpful",
        )

        val params = SendMessageParams(
            message = Message(
                role = Role.USER,
                parts = listOf(TextPart(text = "Hi")),
            ),
        )
        val result = server.dispatch(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tasks/send")
            put("params", json.encodeToJsonElement(
                SendMessageParams.serializer(), params
            ))
        })

        val task = result["result"]!!.jsonObject["task"]!!.jsonObject
        assertEquals("completed", task["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)

        // 验证 history 含 user + agent 消息（history 是 List<Message>，序列化为 JsonArray）
        val history = task["history"]!!.jsonArray
        assertNotNull(history)
        assertEquals(2, history.size)

        server.close()
        rac.httpClient.close()
    }

    /**
     * 验证 serveAsA2aAgent 返回配置正确的 A2aAgentServer。
     */
    @Test
    fun racServeAsA2aAgentReturnsConfiguredServer() = runBlocking {
        val rac = racWithMock { _ ->
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }

        val server = rac.serveAsA2aAgent(
            systemPrompt = "Test system prompt",
        )

        // 验证 Agent Card
        val cardJson = server.getAgentCardJson().jsonObject
        assertEquals("rac-agent", cardJson["name"]!!.jsonPrimitive.content)

        server.close()
        rac.httpClient.close()
    }

    /**
     * 验证 RacA2aAgent 流式调用推送正确的事件序列。
     */
    @Test
    fun racA2aAgentStreamingPushesUpdates() = runBlocking {
        val rac = racWithMock { _ ->
            respond(
                stopResponseJson("Streaming AI response"),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }

        val server = rac.serveAsA2aAgent()

        val params = SendStreamingMessageParams(
            message = Message(
                role = Role.USER,
                parts = listOf(TextPart(text = "Tell me a story")),
            ),
        )

        val events = server.dispatchStreaming(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tasks/sendSubscribe")
            put("params", json.encodeToJsonElement(
                SendStreamingMessageParams.serializer(), params
            ))
        }).toList()

        // 预期：Initial(TaskResult, WORKING) + StatusUpdate(WORKING) + ArtifactUpdate + StatusUpdate(COMPLETED, final)
        assertTrue(events.size >= 3, "Should have at least 3 events: initial + working + completed")

        // 验证最终事件为 COMPLETED + final
        val lastEvent = events.last()
        assertTrue(lastEvent is A2aStreamEvent.StatusUpdate)
        val finalStatus = lastEvent
        assertEquals(TaskState.COMPLETED, finalStatus.event.status.state)
        assertTrue(finalStatus.event.final)

        // 验证产出物含 AI 响应文本
        val artifactEvent = events.filterIsInstance<A2aStreamEvent.ArtifactUpdate>().firstOrNull()
        assertNotNull(artifactEvent, "Should have at least one artifact update")
        val textPart = artifactEvent.event.artifact.parts.filterIsInstance<TextPart>().first()
        assertEquals("Streaming AI response", textPart.text)

        server.close()
        rac.httpClient.close()
    }
}
