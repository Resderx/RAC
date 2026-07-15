[English](a2a.en.md) | **中文**

# A2A（Agent-to-Agent Protocol）支持

RAC 双向支持 [Agent-to-Agent Protocol v1.0](https://a2a-protocol.org/)——既能作为 Client 调用远端 A2A Agent（Google
ADK、LangGraph、CrewAI 等），也能将自身 LLM 调用能力封装为 A2A Agent Server 供其他 Client 调用。

## 概述

A2A 是 Google 于 2025 年 4 月发布的开放协议，基于 JSON-RPC 2.0 + HTTP + SSE，定义 Agent 间的标准化通信。与 ACP 的关键差异：

| 维度                | ACP                          | A2A                                   |
|-------------------|------------------------------|---------------------------------------|
| 传输方式              | stdio 双向 JSON-RPC            | HTTP 请求-响应 + SSE 流式                   |
| 定位                | Editor ↔ 编码助手会话管理            | Agent ↔ Agent 跨框架通信                   |
| 核心概念              | Session、SessionUpdate        | Task、Artifact、Agent Card              |
| 发现机制              | 无（进程级直连）                     | `/.well-known/agent.json` Agent Card  |
| 流式更新              | `session/update` 通知          | `tasks/sendSubscribe` SSE 事件流         |
| Agent → Client 请求 | `session/request_permission` | 无（单向请求-响应）                            |
| 状态管理              | Session 级（隐式）                | Task 生命周期（WORKING/COMPLETED/FAILED 等） |

## 架构

RAC 的 A2A 支持分为以下组件（位于 `a2a/` 模块，`top.resderx.rac.a2a` 包）：

```
a2a/                                          # 独立 Gradle 模块（rac-a2a），依赖 rac-core
├── A2aTypes.kt              # 协议类型：Role、TaskState、TaskStatus、Task、Message、A2aMetadata
├── A2aPart.kt               # Part 多态模型（TextPart/FilePart/DataPart）+ Artifact
├── A2aAgentCard.kt          # Agent 发现模型：AgentCard、AgentProvider、AgentCapabilities、AgentSkill
├── A2aMethods.kt            # JSON-RPC 方法参数/结果模型 + A2aError 错误码
├── A2aClient.kt             # A2aClient 接口 + A2aStreamEvent + A2aClientConfig + 工厂函数
├── DefaultA2aClient.kt      # A2aClient 默认实现（JSON-RPC over HTTP + SSE）
├── A2aAgentHandler.kt       # Agent 业务逻辑处理器接口 + A2aAgentContext
├── A2aAgentServer.kt        # 协议无关 JSON-RPC 分发器（dispatch / dispatchStreaming）
├── RacA2aAgent.kt           # Llm → A2A Agent 适配器（类名 RacA2aAgent）
└── RacA2aExtensions.kt      # Llm.chatWithA2aAgent / Llm.serveAsA2aAgent 扩展函数
```

## 作为 Client 调用远端 Agent

### 基本用法

通过 `A2aClient` 连接远端 A2A Server，再用 `Llm.chatWithA2aAgent` 扩展函数发起流式调用（`Llm` 实例仅作为命名空间锚点，不直接使用其内部能力）：

```kotlin
import top.resderx.rac.a2a.A2aClient
import top.resderx.rac.a2a.A2aClientConfig

suspend fun a2aClientExample(ai: Llm) {
    val client = A2aClient(
        A2aClientConfig(
            baseUrl = "https://agent.example.com",
            apiKey = System.getenv("REMOTE_AGENT_API_KEY"), // 无鉴权时为 null
        ),
    )

    val resp = ai.chatWithA2aAgent(
        client = client,
        prompt = "帮我分析这份销售数据的趋势",
    ) { event ->
        // 流式接收 Agent 更新
        when (event) {
            is A2aStreamEvent.Initial -> println("Task started: ${event.result}")
            is A2aStreamEvent.StatusUpdate -> println("Status: ${event.event.status.state}")
            is A2aStreamEvent.ArtifactUpdate -> {
                // 增量产出物——提取文本并实时打印
                event.event.artifact.parts
                    .filterIsInstance<TextPart>()
                    .forEach { print(it.text) }
            }
        }
    }
    println(resp.content) // 累积的完整 Agent 响应
    client.close()
}
```

### 底层 API

`chatWithA2aAgent` 封装了 `sendStreamingMessage`，也可直接使用底层方法：

```kotlin
// 非流式——发送消息并等待结果
val result = client.sendMessage(
    SendMessageParams(
        message = Message(
            role = Role.USER,
            parts = listOf(TextPart(text = "Hello")),
        ),
    )
)
when (result) {
    is SendMessageResult.TaskResult -> println("Task: ${result.task.id}, state: ${result.task.status.state}")
    is SendMessageResult.MessageResult -> println("Direct message: ${result.message}")
}

// 流式——订阅 SSE 事件流
client.sendStreamingMessage(
    SendStreamingMessageParams(
        message = Message(role = Role.USER, parts = listOf(TextPart(text = "Hi"))),
    )
).collect { event ->
    when (event) {
        is A2aStreamEvent.Initial -> { /* 初始 Task/Message */
        }
        is A2aStreamEvent.StatusUpdate -> { /* 状态变化 */
        }
        is A2aStreamEvent.ArtifactUpdate -> { /* 产出物增量 */
        }
    }
}

// 查询任务状态
val taskResult = client.getTask(GetTaskParams(id = "task-123"))

// 列出任务
val listResult = client.listTasks(ListTasksParams(state = TaskState.WORKING))

// 取消任务
val cancelResult = client.cancelTask(CancelTaskParams(id = "task-123"))

// 获取 Agent Card（发现端点）
val card = client.getAgentCard() // 默认请求 /.well-known/agent.json
```

### Agent Card 发现

A2A Agent 通过 `/.well-known/agent.json` 端点发布 Agent Card，描述自身能力、技能与端点：

```kotlin
val card = client.getAgentCard()
println("Agent: ${card.name}")
println("Description: ${card.description}")
println("Capabilities: streaming=${card.capabilities?.streaming}")
card.skills.forEach { skill ->
    println("Skill: ${skill.name} — ${skill.description}")
}
```

## 作为 Agent Server 暴露服务

### 使用 Llm 内置适配器

`serveAsA2aAgent` 将 Llm 的 `chat` 调用封装为 A2A Agent，返回协议无关的 JSON-RPC 分发器：

```kotlin
val server = ai.serveAsA2aAgent(
    agentCard = AgentCard(
        name = "rac-agent",
        description = "LLM Agent — Kotlin Multiplatform AI Call Library",
        url = "https://my-agent.example.com",
        provider = AgentProvider(organization = "ResDerX"),
        capabilities = AgentCapabilities(streaming = true),
    ),
    systemPrompt = "你是一个 Kotlin 编程助手",
)

// 获取 Agent Card JSON（供 HTTP 服务器在 /.well-known/agent.json 返回）
val cardJson: JsonElement = server.getAgentCardJson()

// 分发非流式 JSON-RPC 请求
val response: JsonObject = server.dispatch(requestJson)

// 分发流式 JSON-RPC 请求
val events: Flow<A2aStreamEvent> = server.dispatchStreaming(requestJson)

server.close()
```

> **注意**：`A2aAgentServer` 是协议无关的 JSON-RPC 分发器，不绑定 HTTP 服务器。调用方需自行将 `dispatch` /
`dispatchStreaming` 绑定到 HTTP 端点（如 Ktor `embeddedServer`、Spring Boot 等）。这是 KMP 库不引入 HTTP 服务器依赖的设计选择，保持跨平台兼容。

`RacA2aAgent` 适配器内部（文件 `RacA2aAgent.kt`）：

1. `sendMessage` 提取 A2A Message 中的文本 → 调用 `Llm.chat` → 构造含 AI 响应的 Task
2. `sendStreamingMessage` 推送 WORKING 状态 → 执行 `Llm.chat` → 推送 ArtifactUpdate（AI 响应文本）→ 推送 COMPLETED 最终状态
3. `getTask` / `listTasks` / `cancelTask` 通过内存任务存储管理（Mutex 保护并发访问）

### 自定义 Agent Handler

实现 `A2aAgentHandler` 接口完全自定义 Agent 行为：

```kotlin
class MyAgentHandler : A2aAgentHandler {
    override fun getAgentCard(): AgentCard = AgentCard(
        name = "my-agent",
        url = "https://my-agent.example.com",
    )

    override suspend fun sendMessage(params: SendMessageParams): SendMessageResult {
        val taskId = params.id ?: "task-${Random.nextLong(1, Long.MAX_VALUE)}"
        val task = Task(
            id = taskId,
            status = TaskStatus(state = TaskState.COMPLETED),
            history = listOf(
                params.message, Message(
                    role = Role.AGENT,
                    parts = listOf(TextPart(text = "Done!")),
                )
            ),
        )
        return SendMessageResult.TaskResult(task = task)
    }

    override suspend fun sendStreamingMessage(
        params: SendStreamingMessageParams,
        context: A2aAgentContext,
    ): SendMessageResult {
        val taskId = params.id ?: "task-stream"

        // 推送 WORKING 状态
        context.sendStatusUpdate(
            TaskStatusUpdateEvent(
                id = taskId,
                status = TaskStatus(state = TaskState.WORKING),
            )
        )

        // 推送产出物
        context.sendArtifactUpdate(
            TaskArtifactUpdateEvent(
                id = taskId,
                artifact = Artifact(
                    artifactId = "result-1",
                    parts = listOf(TextPart(text = "Processing complete")),
                    lastChunk = true,
                ),
                lastChunk = true,
            )
        )

        // 推送最终状态
        context.sendStatusUpdate(
            TaskStatusUpdateEvent(
                id = taskId,
                status = TaskStatus(state = TaskState.COMPLETED),
                final = true,
            )
        )

        return SendMessageResult.TaskResult(
            task = Task(
                id = taskId,
                status = TaskStatus(state = TaskState.COMPLETED),
            )
        )
    }

    override suspend fun getTask(params: GetTaskParams): GetTaskResult =
        GetTaskResult(task = Task(id = params.id, status = TaskStatus(state = TaskState.COMPLETED)))

    override suspend fun listTasks(params: ListTasksParams): ListTasksResult =
        ListTasksResult(tasks = emptyList())

    override suspend fun cancelTask(params: CancelTaskParams): CancelTaskResult =
        CancelTaskResult(task = Task(id = params.id, status = TaskStatus(state = TaskState.CANCELED)))

    override suspend fun close() { /* 释放资源 */
    }
}

val server = A2aAgentServer(MyAgentHandler())
```

## 协议方法映射

### Client → Server 请求

| JSON-RPC 方法           | 说明          | RAC Client 方法                         |
|-----------------------|-------------|---------------------------------------|
| `tasks/send`          | 发送消息（非流式）   | `client.sendMessage(params)`          |
| `tasks/sendSubscribe` | 发送消息并订阅流式更新 | `client.sendStreamingMessage(params)` |
| `tasks/get`           | 查询任务状态      | `client.getTask(params)`              |
| `tasks/list`          | 列出任务        | `client.listTasks(params)`            |
| `tasks/cancel`        | 取消任务        | `client.cancelTask(params)`           |

### 流式事件类型

| 事件                              | 说明                                 |
|---------------------------------|------------------------------------|
| `A2aStreamEvent.Initial`        | 流的首条事件，携带 Task 或 Message           |
| `A2aStreamEvent.StatusUpdate`   | 任务状态变化（WORKING/COMPLETED/FAILED 等） |
| `A2aStreamEvent.ArtifactUpdate` | 产出物增量推送                            |

### Task 生命周期

```
                    ┌─────────────┐
                    │   WORKING   │ ← 任务开始
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┬──────────────┐
           ▼               ▼               ▼              ▼
  ┌─────────────────┐ ┌──────────┐ ┌─────────────┐ ┌──────────┐
  │ INPUT_REQUIRED  │ │ COMPLETED│ │   FAILED    │ │ CANCELED │
  └─────────────────┘ └──────────┘ └─────────────┘ └──────────┘
   需用户提供输入       正常完成      执行失败        用户取消
```

| TaskState        | 说明      | FinishReason 映射    |
|------------------|---------|--------------------|
| `WORKING`        | 进行中     | `UNKNOWN`（不应出现在终态） |
| `COMPLETED`      | 正常完成    | `STOP`             |
| `INPUT_REQUIRED` | 需用户提供输入 | `TOOL_CALLS`       |
| `FAILED`         | 执行失败    | `UNKNOWN`          |
| `CANCELED`       | 用户取消    | `STOP`             |
| `REJECTED`       | 请求被拒绝   | `CONTENT_FILTER`   |
| `AUTH_REQUIRED`  | 需认证     | `UNKNOWN`          |

### Part 类型

A2A Message 的 `parts` 字段为多态 `Part` 密封接口（`@JsonClassDiscriminator("kind")`）：

| @SerialName | 类型         | 说明                 |
|-------------|------------|--------------------|
| `text`      | `TextPart` | 文本内容               |
| `file`      | `FilePart` | 文件（URI 或内联 Base64） |
| `data`      | `DataPart` | 结构化 JSON 数据        |

## 与 ACP 的架构差异

| 维度        | ACP（`acp/` 包）                                 | A2A（`a2a/` 包）                         |
|-----------|-----------------------------------------------|---------------------------------------|
| 传输        | stdio 双向 JSON-RPC                             | HTTP 请求-响应 + SSE                      |
| Server 实现 | `AcpAgentServer` 绑定 stdio 连接                  | `A2aAgentServer` 协议无关分发器              |
| 并发模型      | dispatcher 协程 + `CompletableDeferred` 请求-响应匹配 | 每请求独立处理，无需 dispatcher                 |
| 流式更新      | `SharedFlow` 广播 `SessionUpdate`               | `Channel` 缓冲 + `Flow<A2aStreamEvent>` |
| 状态管理      | Session 级（隐式，由 `StopReason` 表达终态）             | Task 生命周期（显式 7 种状态）                   |
| 发现机制      | 无                                             | Agent Card（`/.well-known/agent.json`） |

### dispatchStreaming 事件顺序保证

`A2aAgentServer.dispatchStreaming` 使用 `Channel` 缓冲 handler 推送的更新，确保 `Initial` 事件始终先于 `StatusUpdate` /
`ArtifactUpdate` 发射：

1. 创建 `Channel<A2aStreamEvent>(UNLIMITED)` 缓冲更新
2. 调用 `handler.sendStreamingMessage`——更新被缓冲到 channel，返回值为初始结果
3. 先发射 `Initial`（初始结果）
4. 关闭 channel 并排空缓冲的更新事件

这保证了客户端始终先收到 `Initial` 事件获取 Task ID 与初始状态，再接收后续更新。

## 测试

A2A 协议测试位于 `core/src/jvmTest/kotlin/com/resderx/rac/A2aProtocolTest.kt`，覆盖：

- Server 路由非流式请求（tasks/send、tasks/get、tasks/cancel）
- Server 对未知方法返回 METHOD_NOT_FOUND 错误
- Server 流式分发推送正确的事件序列（Initial → StatusUpdate → ArtifactUpdate → final StatusUpdate）
- Server 返回 Agent Card JSON
- RacA2aAgent 适配器（prompt → chat → Task 映射）
- RacA2aAgent 流式调用推送更新
- DSL 集成（serveAsA2aAgent 返回配置正确的 Server）

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "top.resderx.rac.A2aProtocolTest"
```

测试使用 `FakeA2aAgentHandler`（可控 handler 实现）和 `SseCapableMockEngine`（模拟 AI 供应商 HTTP 响应），无需真实 API Key。
