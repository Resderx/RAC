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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import top.resderx.rac.acp.*
import top.resderx.rac.dsl.Llm
import top.resderx.rac.messages.FinishReason
import top.resderx.rac.providers.ApiType
import top.resderx.rac.providers.ModelConfig
import top.resderx.rac.providers.ProviderRegistry
import top.resderx.rac.providers.SimpleModelProvider
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * ACP 协议端到端测试（JVM）。
 *
 * - 作用：验证 [DefaultAcpClient]（Client 角色）与 [AcpAgentServer]（Agent 角色）的
 *   JSON-RPC 消息路由、请求-响应匹配、流式更新通知、权限请求处理等核心协议逻辑
 * - 必要性：ACP 是双向 JSON-RPC 协议，涉及 dispatcher 协程、CompletableDeferred 匹配、
 *   SharedFlow 广播等并发机制，需端到端测试验证消息正确路由与状态同步
 * - 设计：
 *   - [FakeAcpConnection]：内存中的 fake 连接，使用 replay=Int.MAX_VALUE 的 SharedFlow
 *     确保 feed 消息在 dispatcher 订阅前不丢失
 *   - [TestAcpClient]：DefaultAcpClient 子类，覆盖 createConnection() 注入 fake 连接
 *   - [FakeAcpAgentHandler]：AcpAgentHandler 的可控实现，记录调用并返回预设结果
 *   - 端到端测试使用 [LinkedConnection] 创建双向连通的连接对
 * - 注意：使用 [runBlocking] 而非 runTest，因为 ACP dispatcher 运行在 Dispatchers.Default
 *   上，与 runTest 的虚拟时间调度器不兼容
 * - 边缘：每个测试结束后调用 client.close()/server.close() 清理协程；
 *   使用 withTimeout 防止死锁
 */
class AcpProtocolTest {

    /** JSON 序列化器，与生产代码配置一致。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 测试期间创建的协程作用域，用于追踪和清理。 */
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 测试期间启动的 Job 列表，AfterTest 中取消。 */
    private val jobs = mutableListOf<Job>()

    @AfterTest
    fun cleanup() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        testScope.cancel("Test cleanup")
    }

    // ==================== Fake 组件 ====================

    /**
     * 内存中的 fake ACP 连接，用于测试。
     *
     * - 使用 replay=Int.MAX_VALUE 的 SharedFlow 确保 feed 消息在 dispatcher 订阅前不丢失
     * - send() 捕获消息到 sent 列表供断言
     * - feed() 向 incoming 注入消息模拟远端发送
     * - close() 发送空行作为终止信号
     */
    private class FakeAcpConnection : AcpConnection {
        // replay=Int.MAX_VALUE: 所有 feed 的消息都会被新订阅者重放，不丢失
        private val _incoming = MutableSharedFlow<String>(replay = Int.MAX_VALUE)
        override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

        /** 已发送的消息列表（供测试断言）。 */
        val sent = mutableListOf<String>()

        /** 向 incoming 流注入一条消息（模拟远端发送）。 */
        fun feed(message: String) {
            _incoming.tryEmit(message)
        }

        /** 向 incoming 流注入终止信号（空行）。 */
        fun feedClose() {
            _incoming.tryEmit("")
        }

        override suspend fun connect() { /* no-op */ }
        override suspend fun send(message: String) { sent.add(message) }
        override suspend fun close() { _incoming.tryEmit("") }
    }

    /**
     * DefaultAcpClient 的测试子类，覆盖 createConnection() 注入 fake 连接。
     */
    private class TestAcpClient(
        config: AcpClientConfig,
        private val fakeConnection: FakeAcpConnection,
    ) : DefaultAcpClient(config) {
        override fun createConnection() = fakeConnection
    }

    /**
     * AcpAgentHandler 的可控 fake 实现，记录调用并返回预设结果。
     */
    private class FakeAcpAgentHandler(
        private val initResult: InitializeResult = InitializeResult(
            protocolVersion = 1,
            agentInfo = ImplementationInfo(name = "fake-agent", version = "0.1.0"),
            agentCapabilities = AgentCapabilities(),
        ),
        private val sessionNewResult: SessionNewResult = SessionNewResult(sessionId = "test-session-1"),
        private val promptResult: SessionPromptResult = SessionPromptResult(stopReason = StopReason.END_TURN),
    ) : AcpAgentHandler {
        var initializeCalled = false
            private set
        var sessionNewCalled = false
            private set
        var sessionNewCwd: String? = null
            private set
        var promptText: String? = null
            private set
        var promptSessionId: String? = null
            private set
        var cancelSessionId: String? = null
            private set

        override suspend fun initialize(params: InitializeParams): InitializeResult {
            initializeCalled = true
            return initResult
        }

        override suspend fun sessionNew(params: SessionNewParams): SessionNewResult {
            sessionNewCalled = true
            sessionNewCwd = params.cwd
            return sessionNewResult
        }

        override suspend fun sessionLoad(params: SessionLoadParams, context: AcpAgentContext): SessionLoadResult {
            return SessionLoadResult()
        }

        override suspend fun sessionPrompt(
            params: SessionPromptParams,
            context: AcpAgentContext,
        ): SessionPromptResult {
            promptSessionId = params.sessionId
            promptText = params.prompt.joinToString("") { (it as? AcpTextBlock)?.text ?: "" }
            return promptResult
        }

        override suspend fun sessionCancel(sessionId: String) {
            cancelSessionId = sessionId
        }

        override suspend fun close() { /* no-op */ }
    }

    // ==================== 辅助函数 ====================

    /**
     * 创建 TestAcpClient，使用 fake 连接与默认配置。
     */
    private fun createTestClient(
        connection: FakeAcpConnection,
        permissionHandler: suspend (top.resderx.rac.acp.PermissionRequest) -> PermissionOutcome = {
            PermissionOutcome(selected = PermissionOutcomeValue.ALLOW)
        },
    ): TestAcpClient {
        val config = AcpClientConfig(
            transport = AcpHttpTransport(serverUrl = "http://localhost"),
            clientInfo = ImplementationInfo(name = "test-client", version = "0.1.0"),
            permissionHandler = permissionHandler,
        )
        return TestAcpClient(config, connection)
    }

    /** 构造 JSON-RPC 响应字符串。 */
    private fun rpcResponse(id: Long, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    /** 构造 JSON-RPC 通知字符串（无 id）。 */
    private fun rpcNotification(method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }.toString()

    /** 构造 JSON-RPC 请求字符串（有 id，模拟 Agent → Client 请求）。 */
    private fun rpcRequest(id: Long, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

    /**
     * 等待条件满足，使用 CompletableDeferred 实现高效同步。
     * 超时 10 秒后抛出 TimeoutCancellationException。
     */
    private suspend fun waitForCondition(
        timeoutMs: Long = 10_000,
        check: () -> Boolean,
    ) {
        withTimeout(timeoutMs.milliseconds) {
            while (!check()) delay(10.milliseconds)
        }
    }

    // ==================== Client 测试 ====================

    /**
     * 验证 Client 的 initialize → sessionNew 流程发送正确的 JSON-RPC 请求。
     *
     * - 在后台协程中等待 Client 发送请求后，注入预设响应
     * - 验证 Client 发送的 JSON-RPC 请求格式正确
     */
    @Test
    fun acpClientSendsCorrectInitializeAndSessionNewRequests() = runBlocking {
        val connection = FakeAcpConnection()
        val client = createTestClient(connection)

        // 预设 Agent 响应
        val initResultJson = buildJsonObject {
            put("protocolVersion", 1)
            put("agentInfo", buildJsonObject {
                put("name", "fake-agent")
                put("version", "0.1.0")
            })
        }
        val sessionNewResultJson = buildJsonObject {
            put("sessionId", "test-session-1")
        }

        // 后台 responder：等待 Client 请求后注入响应
        val responderJob = testScope.launch {
            // 等待 initialize 请求
            waitForCondition { connection.sent.isNotEmpty() }
            connection.feed(rpcResponse(1, initResultJson))

            // 等待 session/new 请求
            waitForCondition { connection.sent.size >= 2 }
            connection.feed(rpcResponse(2, sessionNewResultJson))
        }
        jobs.add(responderJob)

        // 执行 Client 调用
        val initResult = client.initialize()
        assertEquals(1, initResult.protocolVersion)

        val sessionResult = client.sessionNew(cwd = "/test")
        assertEquals("test-session-1", sessionResult.sessionId)

        // 验证发送的请求格式
        val initRequest = json.parseToJsonElement(connection.sent[0]) as JsonObject
        assertEquals("initialize", initRequest["method"]!!.toString().trim('"'))
        assertEquals(1, initRequest["id"]!!.toString().toInt())

        val sessionNewRequest = json.parseToJsonElement(connection.sent[1]) as JsonObject
        assertEquals("session/new", sessionNewRequest["method"]!!.toString().trim('"'))

        client.close()
    }

    /**
     * 验证 Client 的 sessionPrompt 正确处理 session/update 通知并返回 StopReason。
     */
    @Test
    fun acpClientHandlesSessionPromptWithUpdateNotifications() = runBlocking {
        val connection = FakeAcpConnection()
        val client = createTestClient(connection)

        val initResultJson = buildJsonObject {
            put("protocolVersion", 1)
            put("agentInfo", buildJsonObject { put("name", "agent") })
        }
        val sessionNewResultJson = buildJsonObject { put("sessionId", "s1") }

        val receivedUpdates = mutableListOf<SessionUpdate>()

        val responderJob = testScope.launch {
            waitForCondition { connection.sent.isNotEmpty() }
            connection.feed(rpcResponse(1, initResultJson))

            waitForCondition { connection.sent.size >= 2 }
            connection.feed(rpcResponse(2, sessionNewResultJson))

            // 等待 session/prompt 请求
            waitForCondition { connection.sent.size >= 3 }

            // 发送 session/update 通知
            val updateParams = buildJsonObject {
                put("sessionId", "s1")
                put("update", buildJsonObject {
                    put("sessionUpdate", "agent_message_chunk")
                    put("messageId", "msg-1")
                    put("content", buildJsonObject {
                        put("type", "text")
                        put("text", "Hello from agent!")
                    })
                })
            }
            connection.feed(rpcNotification("session/update", updateParams))

            // 发送 session/prompt 响应
            connection.feed(rpcResponse(3, buildJsonObject { put("stopReason", "end_turn") }))
        }
        jobs.add(responderJob)

        client.initialize()
        client.sessionNew(cwd = "/test")

        val stopReason = client.sessionPrompt(
            sessionId = "s1",
            prompt = listOf(AcpTextBlock(text = "Hello")),
            onUpdate = { update -> receivedUpdates.add(update) },
        )

        assertEquals(StopReason.END_TURN, stopReason)
        assertEquals(1, receivedUpdates.size)
        assertTrue(receivedUpdates[0] is AgentMessageChunk)
        val chunk = receivedUpdates[0] as AgentMessageChunk
        assertEquals("Hello from agent!", (chunk.content as AcpTextBlock).text)

        client.close()
    }

    /**
     * 验证 Client 正确处理 Agent 发来的 session/request_permission 请求。
     */
    @Test
    fun acpClientHandlesPermissionRequestFromAgent() = runBlocking {
        val connection = FakeAcpConnection()
        var permissionRequested: String? = null
        val client = createTestClient(connection) { req ->
            permissionRequested = req.type
            PermissionOutcome(selected = PermissionOutcomeValue.ALLOW)
        }

        val initResultJson = buildJsonObject {
            put("protocolVersion", 1)
            put("agentInfo", buildJsonObject { put("name", "agent") })
        }
        val sessionNewResultJson = buildJsonObject { put("sessionId", "s1") }

        val responderJob = testScope.launch {
            waitForCondition { connection.sent.isNotEmpty() }
            connection.feed(rpcResponse(1, initResultJson))

            waitForCondition { connection.sent.size >= 2 }
            connection.feed(rpcResponse(2, sessionNewResultJson))

            // 等待 session/prompt 请求
            waitForCondition { connection.sent.size >= 3 }

            // 发送 session/request_permission 请求（Agent → Client）
            val permParams = buildJsonObject {
                put("sessionId", "s1")
                put("permission", buildJsonObject {
                    put("type", "edit_file")
                    put("title", "Edit test.txt")
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("id", "allow"); put("title", "Allow") })
                        add(buildJsonObject { put("id", "deny"); put("title", "Deny") })
                    })
                })
            }
            connection.feed(rpcRequest(1, "session/request_permission", permParams))

            // 等待 Client 响应权限请求
            waitForCondition { connection.sent.size >= 4 }
            // 发送 session/prompt 响应
            delay(100.milliseconds)
            connection.feed(rpcResponse(3, buildJsonObject { put("stopReason", "end_turn") }))
        }
        jobs.add(responderJob)

        client.initialize()
        client.sessionNew(cwd = "/test")
        val stopReason = client.sessionPrompt(
            sessionId = "s1",
            prompt = listOf(AcpTextBlock(text = "edit test.txt")),
        )

        assertEquals(StopReason.END_TURN, stopReason)
        assertEquals("edit_file", permissionRequested)

        // 验证 Client 发送了权限响应
        val permResponse = connection.sent.find { msg ->
            val obj = json.parseToJsonElement(msg) as JsonObject
            obj.containsKey("result") && obj["id"]?.toString()?.toInt() == 1
        }
        assertNotNull(permResponse, "Client should send a response to the permission request")

        client.close()
    }

    // ==================== AgentServer 测试 ====================

    /**
     * 验证 AgentServer 正确路由 initialize 请求到 handler。
     */
    @Test
    fun acpAgentServerRoutesInitializeRequest() = runBlocking {
        val connection = FakeAcpConnection()
        val handler = FakeAcpAgentHandler()
        val server = AcpAgentServer(connection, handler)

        val job = server.start()
        jobs.add(job)

        // 发送 initialize 请求
        connection.feed(rpcRequest(1, "initialize", buildJsonObject {
            put("protocolVersion", 1)
            put("clientInfo", buildJsonObject {
                put("name", "test-client")
                put("version", "0.1.0")
            })
        }))

        // 等待 handler 被调用
        waitForCondition { handler.initializeCalled }

        // 等待 server 发送响应（handler 返回后仍需序列化+send 的时间）
        waitForCondition { connection.sent.isNotEmpty() }

        // 验证 server 发送了响应
        val response = connection.sent.find { msg ->
            val obj = json.parseToJsonElement(msg) as JsonObject
            obj["id"]?.toString()?.toInt() == 1 && obj.containsKey("result")
        }
        assertNotNull(response, "Server should send initialize response")

        server.close()
    }

    /**
     * 验证 AgentServer 正确路由 session/new 请求并返回 sessionId。
     */
    @Test
    fun acpAgentServerRoutesSessionNewRequest() = runBlocking {
        val connection = FakeAcpConnection()
        val handler = FakeAcpAgentHandler()
        val server = AcpAgentServer(connection, handler)

        val job = server.start()
        jobs.add(job)

        // 发送 initialize
        connection.feed(rpcRequest(1, "initialize", buildJsonObject {
            put("protocolVersion", 1)
        }))

        // 发送 session/new
        connection.feed(rpcRequest(2, "session/new", buildJsonObject {
            put("cwd", "/project")
        }))

        // 等待 handler 被调用
        waitForCondition { handler.sessionNewCalled }

        assertEquals("/project", handler.sessionNewCwd)

        // 验证响应包含 sessionId
        val response = connection.sent.find { msg ->
            val obj = json.parseToJsonElement(msg) as JsonObject
            obj["id"]?.toString()?.toInt() == 2 && obj.containsKey("result")
        }
        assertNotNull(response, "Server should send session/new response")
        val responseObj = json.parseToJsonElement(response) as JsonObject
        val result = responseObj["result"]!! as JsonObject
        assertEquals("test-session-1", result["sessionId"]!!.toString().trim('"'))

        server.close()
    }

    /**
     * 验证 AgentServer 正确路由 session/cancel 通知（无响应）。
     */
    @Test
    fun acpAgentServerHandlesSessionCancelNotification() = runBlocking {
        val connection = FakeAcpConnection()
        val handler = FakeAcpAgentHandler()
        val server = AcpAgentServer(connection, handler)

        val job = server.start()
        jobs.add(job)

        // 先发送 initialize 以启动 dispatcher 处理
        connection.feed(rpcRequest(1, "initialize", buildJsonObject {
            put("protocolVersion", 1)
        }))
        waitForCondition { handler.initializeCalled }

        // 发送 session/cancel 通知
        connection.feed(rpcNotification("session/cancel", buildJsonObject {
            put("sessionId", "cancel-target")
        }))

        // 等待 handler.sessionCancel 被调用
        waitForCondition { handler.cancelSessionId != null }

        assertEquals("cancel-target", handler.cancelSessionId)

        server.close()
    }

    // ==================== RacAcpAgent 测试 ====================

    /**
     * 验证 RacAcpAgent 将 ACP prompt 映射为 Llm chat 调用并推送 AgentMessageChunk。
     */
    @Test
    fun racAcpAgentMapsPromptToChatAndPushesUpdates() = runBlocking {
        // 创建 MockEngine 支撑的 Llm
        val mockHttpClient = HttpClient(SseCapableMockEngine { _ ->
            respond(
                """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hello from RAC!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":5,"total_tokens":10}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }) {
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
        val rac = Llm(httpClient = mockHttpClient, registry = registry, defaultProvider = provider)

        // 创建 RacAcpAgent
        val agent = RacAcpAgent(llm = rac, systemPrompt = "You are helpful")

        // 初始化
        val initResult = agent.initialize(
            InitializeParams(protocolVersion = 1, clientInfo = ImplementationInfo(name = "test"))
        )
        assertEquals(1, initResult.protocolVersion)

        // 创建会话
        val sessionResult = agent.sessionNew(SessionNewParams(cwd = "/test"))
        val sessionId = sessionResult.sessionId

        // 收集推送的更新
        val updates = mutableListOf<SessionUpdate>()
        val fakeContext = object : AcpAgentContext {
            override suspend fun sendUpdate(update: SessionUpdate) {
                updates.add(update)
            }
            override suspend fun requestPermission(permission: top.resderx.rac.acp.PermissionRequest): PermissionOutcome {
                return PermissionOutcome(selected = PermissionOutcomeValue.ALLOW)
            }
        }

        // 执行 prompt
        val promptResult = agent.sessionPrompt(
            params = SessionPromptParams(
                sessionId = sessionId,
                prompt = listOf(AcpTextBlock(text = "Hello")),
            ),
            context = fakeContext,
        )

        // 验证停止原因
        assertEquals(StopReason.END_TURN, promptResult.stopReason)

        // 验证推送了 AgentMessageChunk
        val messageChunks = updates.filterIsInstance<AgentMessageChunk>()
        assertTrue(messageChunks.isNotEmpty(), "Should push at least one AgentMessageChunk")
        assertEquals("Hello from RAC!", (messageChunks[0].content as AcpTextBlock).text)

        agent.close()
        mockHttpClient.close()
    }

    // ==================== Llm DSL 集成测试 ====================

    /**
     * 验证 Llm.chatWithAcpAgent 正确累积 Agent 消息并映射 StopReason。
     */
    @Test
    fun racChatWithAcpAgentAccumulatesAgentMessage() = runBlocking {
        val connection = FakeAcpConnection()
        val client = createTestClient(connection)

        val initResultJson = buildJsonObject {
            put("protocolVersion", 1)
            put("agentInfo", buildJsonObject { put("name", "agent") })
        }
        val sessionNewResultJson = buildJsonObject { put("sessionId", "s1") }

        val responderJob = testScope.launch {
            waitForCondition { connection.sent.isNotEmpty() }
            connection.feed(rpcResponse(1, initResultJson))
            waitForCondition { connection.sent.size >= 2 }
            connection.feed(rpcResponse(2, sessionNewResultJson))
            waitForCondition { connection.sent.size >= 3 }

            // 推送两个 Agent 消息块
            for (text in listOf("Hello ", "from ACP!")) {
                connection.feed(rpcNotification("session/update", buildJsonObject {
                    put("sessionId", "s1")
                    put("update", buildJsonObject {
                        put("sessionUpdate", "agent_message_chunk")
                        put("content", buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                }))
            }

            // 推送 prompt 响应
            connection.feed(rpcResponse(3, buildJsonObject { put("stopReason", "end_turn") }))
        }
        jobs.add(responderJob)

        // 创建 Llm 实例（chatWithAcpAgent 不直接使用 Llm 的 HTTP 客户端，但需要实例）
        val rac = Llm(
            httpClient = HttpClient { install(HttpTimeout) },
            registry = ProviderRegistry(),
            defaultProvider = SimpleModelProvider(
                name = "mock", baseUrl = "http://localhost", apiKey = null,
                defaultApiType = ApiType.COMPLETIONS, models = mapOf("gpt-4" to ModelConfig()),
            ),
        )

        val result = rac.chatWithAcpAgent(
            client = client,
            prompt = "Hello",
            cwd = "/test",
        )

        // 验证累积的文本
        assertEquals("Hello from ACP!", result.content)
        assertEquals(FinishReason.STOP, result.finishReason)

        client.close()
        rac.httpClient.close()
    }

    /**
     * 验证 Llm.serveAsAcpAgent 返回未启动的 AcpAgentServer。
     */
    @Test
    fun racServeAsAcpAgentReturnsConfiguredServer() = runBlocking {
        val rac = Llm(
            httpClient = HttpClient { install(HttpTimeout) },
            registry = ProviderRegistry(),
            defaultProvider = SimpleModelProvider(
                name = "mock", baseUrl = "http://localhost", apiKey = null,
                defaultApiType = ApiType.COMPLETIONS, models = mapOf("gpt-4" to ModelConfig()),
            ),
        )

        val server = rac.serveAsAcpAgent(systemPrompt = "You are helpful")
        assertNotNull(server)
        // 不调用 start()（会尝试创建 stdio 连接），仅验证返回类型
        rac.httpClient.close()
    }

    // ==================== 端到端 Client ↔ Server 测试 ====================

    /**
     * 验证 Client ↔ Server 端到端通信：initialize → sessionNew → sessionPrompt。
     *
     * - 创建一对 linked 连接，Client 发送的消息出现在 Server 的 incoming，反之亦然
     * - Client 调用 sessionPrompt 时，Server handler 推送 AgentMessageChunk 并返回 StopReason
     */
    @Test
    fun acpEndToEndClientServerCommunication() = runBlocking {
        // 创建 linked 连接对（replay=Int.MAX_VALUE 确保消息不丢失）
        val clientFlow = MutableSharedFlow<String>(replay = Int.MAX_VALUE)
        val serverFlow = MutableSharedFlow<String>(replay = Int.MAX_VALUE)

        val clientConn = LinkedConnection(clientFlow, serverFlow)
        val serverConn = LinkedConnection(serverFlow, clientFlow)

        // 创建 handler：sessionPrompt 时推送一条消息并返回 end_turn
        val handler = object : AcpAgentHandler {
            override suspend fun initialize(params: InitializeParams) = InitializeResult(
                protocolVersion = 1,
                agentInfo = ImplementationInfo(name = "e2e-agent", version = "0.1.0"),
                agentCapabilities = AgentCapabilities(),
            )

            override suspend fun sessionNew(params: SessionNewParams) =
                SessionNewResult(sessionId = "e2e-session")

            override suspend fun sessionLoad(params: SessionLoadParams, context: AcpAgentContext) =
                SessionLoadResult()

            override suspend fun sessionPrompt(
                params: SessionPromptParams,
                context: AcpAgentContext,
            ): SessionPromptResult {
                // 推送一条 Agent 消息
                context.sendUpdate(
                    AgentMessageChunk(content = AcpTextBlock(text = "E2E response"))
                )
                return SessionPromptResult(stopReason = StopReason.END_TURN)
            }

            override suspend fun sessionCancel(sessionId: String) { /* no-op */ }
            override suspend fun close() { /* no-op */ }
        }

        // 创建并启动 server
        val server = AcpAgentServer(serverConn, handler)
        val serverJob = server.start()
        jobs.add(serverJob)

        // 创建 client
        val clientConfig = AcpClientConfig(
            transport = AcpHttpTransport(serverUrl = "http://localhost"),
            clientInfo = ImplementationInfo(name = "e2e-client", version = "0.1.0"),
        )
        val client = object : DefaultAcpClient(clientConfig) {
            override fun createConnection() = clientConn
        }

        // 执行端到端流程
        val initResult = client.initialize()
        assertEquals(1, initResult.protocolVersion)

        val sessionResult = client.sessionNew(cwd = "/e2e")
        assertEquals("e2e-session", sessionResult.sessionId)

        // 收集更新
        val updates = mutableListOf<SessionUpdate>()
        val stopReason = withTimeout(15_000.milliseconds) {
            client.sessionPrompt(
                sessionId = sessionResult.sessionId,
                prompt = listOf(AcpTextBlock(text = "Hello E2E")),
                onUpdate = { update -> updates.add(update) },
            )
        }

        assertEquals(StopReason.END_TURN, stopReason)
        assertEquals(1, updates.size)
        assertTrue(updates[0] is AgentMessageChunk)
        assertEquals("E2E response", ((updates[0] as AgentMessageChunk).content as AcpTextBlock).text)

        client.close()
        server.close()
    }

    /**
     * 双向连通的 ACP 连接，用于端到端测试。
     *
     * - send() 将消息写入 outgoing 流（对方会收到）
     * - incoming 为对方的 outgoing 流
     * - close() 向对方发送终止信号
     */
    private class LinkedConnection(
        incoming: MutableSharedFlow<String>,
        private val outgoing: MutableSharedFlow<String>,
    ) : AcpConnection {
        override val incoming: SharedFlow<String> = incoming

        override suspend fun connect() { /* no-op */ }
        override suspend fun send(message: String) {
            outgoing.emit(message)
        }
        override suspend fun close() {
            outgoing.tryEmit("")
        }
    }
}
