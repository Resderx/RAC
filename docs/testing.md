# 测试指南

RAC 采用分层测试策略，覆盖通用逻辑、JVM 端到端、原生平台（mingwX64）序列化与 DSL 验证，以及真实 API 集成测试。本文档说明各层测试的组织方式、运行命令与常见问题。

## 测试策略

RAC 的测试代码分布在三套源集，各司其职：

| 源集 | 平台 | 内容 | 依赖 |
| --- | --- | --- | --- |
| `commonTest` | 全平台共享 | 通用逻辑测试（占位，当前基本为空） | `kotlin-test` |
| `jvmTest` | JVM | MockEngine 端到端测试 + 集成测试 | `kotlin-test`、`ktor-client-mock` |
| `mingwX64Test` | Windows 原生 | 原生序列化、SSE 解析、DSL 构建测试 | `kotlin-test` |

### 分层职责

1. **commonTest（共享）**：存放可跨平台运行的纯逻辑测试。当前含 `CompletionsApiTest`（占位）与 `EngineTest`，后续可扩展不依赖 MockEngine 的纯模型测试。
2. **jvmTest（JVM + MockEngine）**：JVM 平台 MockEngine 可靠，是主力测试层。覆盖 `RequestExecutor` → API 客户端 → `Mappers` → `AIMessage` 的完整链路，无需真实 API Key。同时承载集成测试（默认跳过）。
3. **mingwX64Test（原生）**：验证原生平台的 kotlinx.serialization 解析、SSE 分块解析、DSL 构建在无 JVM 的环境下正常工作。因原生平台无 MockEngine，聚焦于序列化与纯逻辑。
4. **集成测试（可选）**：对 DeepSeek/OpenAI/Anthropic 发起真实 API 调用，验证 Mock 无法覆盖的网络与协议差异，默认跳过。

### 测试依赖

```toml
# gradle/libs.versions.toml
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", ... }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", ... }
```

`commonTest` 依赖 `kotlin-test` 与 `ktor-client-mock`；`jvmTest` 继承 `commonTest` 依赖并可直接使用。

## 运行各平台测试

开发机 Gradle 用户主目录设为 `D:\AppData\Gradle`，运行前需设置环境变量。

### JVM 测试

JVM 测试是主力层，包含 MockEngine 端到端测试与集成测试：

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest
```

仅运行集成测试（需先启用，见下文）：

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "com.resderx.rac.IntegrationTest"
```

### mingwX64 原生测试

验证原生平台序列化与 SSE 解析：

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:mingwX64Test
```

### 运行全部测试

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:allTests
```

## 启用集成测试

集成测试位于 `jvmTest/.../IntegrationTest.kt`，默认跳过。需同时设置以下环境变量：

| 环境变量 | 作用 | 必填 |
| --- | --- | --- |
| `RAC_INTEGRATION_TEST=true` | 总开关，启用集成测试 | 是 |
| `RAC_DEEPSEEK_API_KEY` | DeepSeek 真实调用密钥 | 调用 DeepSeek 用例时 |
| `RAC_OPENAI_API_KEY` | OpenAI 真实调用密钥 | 调用 OpenAI 用例时 |
| `RAC_ANTHROPIC_API_KEY` | Anthropic 真实调用密钥 | 调用 Anthropic 用例时 |

集成测试在 `runTest` 内通过 `System.getenv` 读取环境变量。总开关未启用或对应 Key 未设置时，用例早期 `return`（静默跳过，不报失败）。集成测试覆盖：

- `deepseekChatCompletion` — DeepSeek 非流式
- `deepseekChatStream` — DeepSeek 流式
- `openaiChatCompletion` — OpenAI 非流式
- `anthropicChatCompletion` — Anthropic 非流式

### 运行示例

```powershell
# 启用 DeepSeek + OpenAI 集成测试
$env:RAC_INTEGRATION_TEST='true'
$env:RAC_DEEPSEEK_API_KEY='sk-...'
$env:RAC_OPENAI_API_KEY='sk-...'
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "com.resderx.rac.IntegrationTest"
```

> 集成测试不 mock 网络，网络不可用时用例会失败（不跳过）。请确保网络可达且 API Key 有效。

## MockEngine 用法示例

RAC 的 JVM 测试通过 `SseCapableMockEngine`（Ktor `MockEngine` 子类）注入预设 HTTP/SSE 响应，端到端验证调用链路。

### 为什么需要 SseCapableMockEngine

Ktor 3.x 的 SSE 插件要求引擎声明 `SSECapability`，否则抛 `IllegalArgumentException`；原生 `MockEngine` 仅支持 `HttpTimeout` / `WebSocket` 能力。此外，原生 `MockEngine.execute` 不会调用 `ResponseAdapterAttributeKey` 对应的适配器，导致 SSE 插件的 Transform 阶段收到 `ByteReadChannel` 而非 `SSESession`，抛 "Expected SSESession content" 异常。

`SseCapableMockEngine`（`jvmTest/.../SseCapableMockEngine.kt`）：
1. 在 `supportedCapabilities` 中声明 `SSECapability`
2. 重写 `execute`，在 `super.execute` 返回后检查 `ResponseAdapterAttributeKey`，若存在且响应体为 `ByteReadChannel`，调用 `adapter.adapt()` 转换为 `DefaultClientSSESession`

### 示例 1：CompletionsClient 非流式 Mock

```kotlin
@Test
fun completeReturnsResponse() = runTest {
    val json = """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""
    val client = HttpClient(SseCapableMockEngine { _ ->
        respond(json, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }) { install(SSE); install(HttpTimeout) }
    val executor = RequestExecutor(client)
    val completions = CompletionsClient(executor)
    val request = CompletionsRequest(model = "gpt-4", messages = emptyList())
    val resp = completions.complete("http://localhost/v1/chat/completions", emptyMap(), request)
    assertEquals("Hello!", resp.choices[0].message.content)
}
```

### 示例 2：流式 SSE Mock

```kotlin
@Test
fun streamParsesChunksAndStopsAtDone() = runTest {
    val chunk1 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}"""
    val chunk2 = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}"""
    val sseBody = "data: $chunk1\n\ndata: $chunk2\n\ndata: [DONE]\n\n"
    val client = HttpClient(SseCapableMockEngine { _ ->
        respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
    }) { install(SSE); install(HttpTimeout) }
    val completions = CompletionsClient(RequestExecutor(client))
    val chunks = completions.stream("http://localhost/v1/chat/completions", emptyMap(),
        CompletionsRequest(model = "gpt-4", messages = emptyList())).toList()
    assertEquals(2, chunks.size)
    assertEquals("Hi", chunks[0].choices[0].delta.content)
}
```

### 示例 3：Llm DSL 端到端 Mock

通过构造函数注入 Mock HttpClient 到 `Llm` 实例，验证 `chat { }` / `chatStream { }` 的完整链路：

```kotlin
private fun racWithMock(handler: MockRequestHandler): Llm {
    val client = HttpClient(SseCapableMockEngine(handler)) {
        install(SSE); install(HttpTimeout)
    }
    val provider = SimpleModelProvider(
        name = "mock", baseUrl = "http://localhost", apiKey = null,
        defaultApiType = ApiType.COMPLETIONS, defaultModel = "gpt-4",
    )
    val registry = ProviderRegistry().apply { register(provider) }
    return Llm(httpClient = client, registry = registry, defaultProvider = provider)
}

@Test
fun chatReturnsAiMessage() = runTest {
    val json = """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
    val rac = racWithMock { _ ->
        respond(json, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }
    val ai: AIMessage = rac.chat { user("ping") }
    assertEquals("Hi there", ai.content)
    assertEquals(FinishReason.STOP, ai.finishReason)
    rac.httpClient.close()
}
```

> 注意：Mock 场景下 `apiKey` 可为 `null`，`buildHeaders` 不会添加鉴权头，不影响 Mock 响应。测试结束应调用 `httpClient.close()` 释放资源。

### 示例 5：ACP 协议测试（FakeAcpConnection）

ACP 测试不依赖 MockEngine，而是通过 `FakeAcpConnection`（内存 SharedFlow）模拟双向 JSON-RPC 通信：

- **`runBlocking` 而非 `runTest`**：ACP dispatcher 运行在 `Dispatchers.Default`，与 `runTest` 的虚拟时间调度器不兼容
- **`SharedFlow(replay = Int.MAX_VALUE)`**：确保 feed 消息在 dispatcher 订阅前不丢失
- **`waitForCondition`**：基于 `withTimeout` 的轮询等待，避免固定 sleep 导致的竞态
- **`LinkedConnection`**：端到端测试中创建双向连通的连接对

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "com.resderx.rac.AcpProtocolTest"
```

详见 [docs/acp.md](acp.md#测试) 的测试章节。

### 示例 6：A2A 协议测试（FakeA2aAgentHandler + MockEngine）

A2A 测试使用两种 fake 策略：

- **`FakeA2aAgentHandler`**：可控 handler 实现，记录调用并返回预设结果，用于测试 `A2aAgentServer` 的 JSON-RPC 路由
- **`SseCapableMockEngine`**：模拟 AI 供应商 HTTP 响应，用于测试 `LlmA2aAgent` 的 Llm chat 集成

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "com.resderx.rac.A2aProtocolTest"
```

A2A 测试使用 `runBlocking`（非 `runTest`），因为 `dispatchStreaming` 返回的 `Flow` 收集涉及时序。`A2aAgentServer.dispatchStreaming` 内部使用 `Channel` 缓冲 handler 推送的更新，确保 `Initial` 事件始终先于 `StatusUpdate` / `ArtifactUpdate` 发射。

详见 [docs/a2a.md](a2a.md#测试) 的测试章节。

### 示例 4：原生平台序列化测试（mingwX64）

mingwX64 无 MockEngine，聚焦序列化与纯逻辑验证：

```kotlin
class NativeSseParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseStreamChunkWithReasoningContent() {
        val data = """{"id":"1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"answer","reasoning_content":"thinking"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals("thinking", chunk.choices[0].delta.reasoningContent)
    }
}
```

## 测试文件清单

### jvmTest

| 文件 | 说明 |
| --- | --- |
| `SseCapableMockEngine.kt` | 支持 SSE 的 MockEngine 子类，所有 SSE 测试的基础设施 |
| `CompletionsClientMockTest.kt` | CompletionsClient 非流式/流式/错误处理 |
| `RacDslMockTest.kt` | Llm DSL 端到端（chat / chatStream） |
| `SSEClientMockTest.kt` | SSEClient 分块解析 |
| `ToolLoopTest.kt` | 多轮工具调用循环（chatWithTools / chatWithMcp） |
| `RetryExecutorTest.kt` | 重试执行器（429/5xx 退避、Retry-After） |
| `AcpProtocolTest.kt` | ACP 协议端到端（Client/AgentServer/LlmAcpAgent/DSL 集成） |
| `A2aProtocolTest.kt` | A2A 协议端到端（AgentServer/LlmA2aAgent/DSL 集成） |
| `IntegrationTest.kt` | 真实 API 集成测试（默认跳过） |

### mingwX64Test

| 文件 | 说明 |
| --- | --- |
| `NativeSseParseTest.kt` | CompletionsStreamChunk 序列化解析（含 reasoning_content、未知字段忽略） |
| `NativeSerializationTest.kt` | 请求/响应模型序列化 |
| `NativeDslBuildTest.kt` | DSL 构建器在原生平台的行为 |

### commonTest

| 文件 | 说明 |
| --- | --- |
| `CompletionsApiTest.kt` | 占位，可扩展纯逻辑测试 |
| `EngineTest.kt` | 引擎相关 |

## 常见问题与解决方案

### 1. SSE 测试报 "Expected SSESession content"

**原因**：使用原生 `MockEngine` 而非 `SseCapableMockEngine`，或未在 `HttpClient` 配置中 `install(SSE)`。

**解决**：统一使用 `SseCapableMockEngine`，并确保 `HttpClient(...) { install(SSE); install(HttpTimeout) }`。

### 2. 测试报 "No provider registered in LLM"

**原因**：`llm { }` 块内未注册任何供应商，或 `defaultProviderName` 指向未注册的供应商。

**解决**：在 `llm { providers { } }` 块内至少注册一个供应商（如 `deepseek { apiKey(...) }`）；若手动构造 `Llm`，确保 `registry` 中已 `register(provider)` 且 `defaultProvider` 来自同一 registry。

### 3. chatStream 抛 RACException "requires a Completions API provider"

**原因**：默认供应商的 `defaultApiType` 不是 `COMPLETIONS`（如 Anthropic 供应商）。

**解决**：Anthropic 供应商改用 `anthropicStream { }`；或切换默认供应商为 Completions 类型。

### 4. 集成测试静默跳过（用例不计入失败）

**原因**：`RAC_INTEGRATION_TEST` 未设为 `true`，或对应供应商的 `RAC_<PROVIDER>_API_KEY` 未设置。

**解决**：确认环境变量正确设置。`System.getenv` 在 Gradle 实验室中可能需通过 IDE 运行配置注入，或在终端 `$env:RAC_INTEGRATION_TEST='true'` 后运行。

### 5. mingwX64 测试因网络相关用例失败

**原因**：mingwX64 原生平台无 MockEngine，且测试不应依赖真实网络。

**解决**：mingwX64 测试仅覆盖序列化与纯逻辑，不写网络调用用例。需要网络验证的用例放在 `jvmTest` 的 MockEngine 测试或 `IntegrationTest`。

### 6. GRADLE_USER_HOME 未设置导致构建异常

**原因**：开发机 Gradle 用户主目录定制为 `D:\AppData\Gradle`，未设置时 Gradle 会使用默认目录导致依赖缓存缺失或路径问题。

**解决**：每次运行前 `$env:GRADLE_USER_HOME='D:\AppData\Gradle'`，或在系统环境变量中永久设置 `GRADLE_USER_HOME=D:\AppData\Gradle`。

### 7. HttpClient 资源未释放

**原因**：测试中创建的 `HttpClient` 未调用 `close()`，可能导致连接泄漏。

**解决**：测试结束前调用 `rac.httpClient.close()` 或 `client.close()`。`Llm` 持有 HttpClient 生命周期，调用方负责关闭。
