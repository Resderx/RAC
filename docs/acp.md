# ACP（Agent Client Protocol）支持

RAC 双向支持 [Agent Client Protocol v1](https://agentclientprotocol.com/)——既能作为 Client 调用外部 Agent（Claude Code、Codex CLI、Gemini CLI 等），也能将自身 LLM 调用能力封装为 ACP Agent Server 供 Editor 调用。

## 概述

ACP 是 Zed 与 JetBrains 联合推出的标准化 AI 编码助手通信协议，基于 JSON-RPC 2.0，定义 Editor（Client）与 Agent 之间的双向通信。与 MCP 的关键差异：

| 维度 | MCP | ACP |
| --- | --- | --- |
| 通信方向 | Client → Server 单向请求 | Client ↔ Agent 双向请求 |
| 定位 | 工具/资源/提示 发现与调用 | 编码助手会话管理 |
| 核心方法 | `tools/list`、`tools/call`、`resources/*` | `initialize`、`session/new`、`session/prompt`、`session/update` |
| 流式更新 | 无原生流式 | `session/update` 通知流式推送 |
| Agent → Client 请求 | 无 | `session/request_permission`（权限请求） |

## 架构

RAC 的 ACP 支持分为以下组件（位于 `acp/` 包）：

```
acp/
├── AcpTypes.kt              # 协议类型：InitializeParams/Result、SessionNewParams 等
├── AcpContent.kt            # 内容块模型：AcpTextBlock、AcpResourceBlock、AcpImageBlock 等
├── AcpSession.kt            # 会话更新模型：SessionUpdate 密封接口及子类型
├── AcpMcpConfig.kt          # MCP 配置模型（ACP 内嵌 MCP 服务器）
├── AcpMethods.kt            # 方法参数/结果序列化模型
├── AcpTransport.kt          # 传输配置：AcpStdioTransport、AcpHttpTransport
├── AcpConnection.kt         # 连接抽象接口
├── StdioAcpConnection.kt    # stdio 连接实现（平台特定）
├── StdioAcpServerConnection.kt # stdio 服务端连接实现
├── AcpClient.kt             # AcpClient 接口 + 工厂函数
├── DefaultAcpClient.kt      # AcpClient 默认实现（dispatcher 路由 + 请求-响应匹配）
├── AcpAgentHandler.kt       # Agent 业务逻辑处理器接口
├── AcpAgentServer.kt        # Agent 服务器（路由 Client 请求到 handler）
└── RacAcpAgent.kt           # RAC → ACP Agent 适配器
```

## 作为 Client 调用外部 Agent

### 基本用法

通过 `AcpClient` 连接外部 Agent，再用 `RAC.chatWithAcpAgent` 发起会话：

```kotlin
import com.resderx.rac.acp.AcpClient
import com.resderx.rac.acp.AcpClientConfig
import com.resderx.rac.acp.AcpStdioTransport
import com.resderx.rac.acp.ImplementationInfo

val client = AcpClient(
    AcpClientConfig(
        transport = AcpStdioTransport(
            command = "claude",
            args = listOf("agent"),
            cwd = "/project",
            env = mapOf("ANTHROPIC_API_KEY" to System.getenv("ANTHROPIC_API_KEY")),
        ),
        clientInfo = ImplementationInfo(name = "my-editor", version = "1.0.0"),
    ),
)

val resp = ai.chatWithAcpAgent(
    client = client,
    prompt = "重构 src/Main.kt",
    cwd = "/project",
) { update ->
    // 流式接收 Agent 更新
    when (update) {
        is AgentMessageChunk -> print((update.content as AcpTextBlock).text)
        is ToolCallUpdate -> println("[Tool] ${update.title} — ${update.status}")
        is PlanUpdate -> update.entries.forEach { println("[Plan] ${it.content}")
        is UsageUpdate -> println("[Usage] ${update.used}/${update.size} tokens")
        is UserMessageChunk -> Unit // 历史回放，通常忽略
    }
}
client.close()
```

### 底层 API

`chatWithAcpAgent` 封装了以下底层调用，也可直接使用：

```kotlin
client.initialize()                                    // 协议握手
val session = client.sessionNew(cwd = "/project")      // 创建会话
val stopReason = client.sessionPrompt(                 // 发起提示轮次
    sessionId = session.sessionId,
    prompt = listOf(AcpTextBlock(text = "Hello")),
    onUpdate = { update -> /* ... */ },
)
client.sessionCancel(session.sessionId)                // 取消会话
```

### 权限处理

Agent 在执行文件编辑、命令运行等操作时，会通过 `session/request_permission` 向 Client 发起权限请求。通过 `permissionHandler` 配置处理策略：

```kotlin
val client = AcpClient(
    AcpClientConfig(
        transport = AcpStdioTransport(command = "claude", args = emptyList()),
        clientInfo = ImplementationInfo(name = "my-editor", version = "1.0.0"),
        permissionHandler = { request ->
            // request.type: "edit_file" / "execute" / "write_file" 等
            // request.options: List<PermissionOption>，每个含 id 和 title
            println("Agent 请求权限：${request.title}")
            // 返回用户选择的 option id
            PermissionOutcome(selected = PermissionOutcomeValue.ALLOW)
        },
    ),
)
```

## 作为 Agent Server 暴露服务

### 使用 RAC 内置适配器

`serveAsAcpAgent` 将 RAC 的 `chat` 调用封装为 ACP Agent，自动处理协议握手、会话管理、流式更新推送：

```kotlin
val server = ai.serveAsAcpAgent(
    agentInfo = ImplementationInfo(
        name = "rac-agent",
        title = "RAC Agent",
        version = "0.2.0",
    ),
    agentCapabilities = AgentCapabilities(), // 声明 Agent 能力
    systemPrompt = "你是一个 Kotlin 编程助手",
)
server.start().join() // 阻塞当前协程，直到 Editor 断开
server.close()
```

`RacAcpAgent` 适配器内部：
1. `sessionPrompt` 收到用户提示后，将 `AcpContentBlock` 列表转为文本
2. 调用 `RAC.chat` 执行 LLM 推理
3. 通过 `AcpAgentContext.sendUpdate` 推送 `AgentMessageChunk`（含 LLM 响应内容）
4. 返回 `SessionPromptResult`（`stopReason` 由 `FinishReason` 映射）

### 自定义 Agent Handler

实现 `AcpAgentHandler` 接口完全自定义 Agent 行为：

```kotlin
class MyAgentHandler : AcpAgentHandler {
    override suspend fun initialize(params: InitializeParams): InitializeResult {
        return InitializeResult(
            protocolVersion = 1,
            agentInfo = ImplementationInfo(name = "my-agent", version = "1.0.0"),
            agentCapabilities = AgentCapabilities(),
        )
    }

    override suspend fun sessionNew(params: SessionNewParams): SessionNewResult {
        return SessionNewResult(sessionId = UUID.randomUUID().toString())
    }

    override suspend fun sessionPrompt(
        params: SessionPromptParams,
        context: AcpAgentContext,
    ): SessionPromptResult {
        // 推送计划
        context.sendUpdate(PlanUpdate(entries = listOf(
            PlanEntry(content = "分析代码", priority = "high", status = "in_progress"),
        )))
        // 推送消息
        context.sendUpdate(AgentMessageChunk(
            messageId = "msg-1",
            content = AcpTextBlock(text = "正在分析..."),
        ))
        // 请求权限
        val outcome = context.requestPermission(PermissionRequest(
            type = "execute",
            title = "运行测试",
            options = listOf(
                PermissionOption(id = "allow", title = "允许"),
                PermissionOption(id = "deny", title = "拒绝"),
            ),
        ))
        return SessionPromptResult(stopReason = StopReason.END_TURN)
    }

    override suspend fun sessionCancel(sessionId: String) { /* 取消处理 */ }
    override suspend fun close() { /* 释放资源 */ }
}

val server = AcpAgentServer(MyAgentHandler())
server.start().join()
```

## 传输方式

### Stdio 传输（`AcpStdioTransport`）

通过子进程 stdin/stdout 交换 JSON-RPC 消息（每行一条 JSON）。

- **Client 模式**：RAC 作为父进程，spawn 外部 Agent 子进程
- **Server 模式**：RAC 作为子进程，从 stdin 读取 Editor 请求，向 stdout 写入响应
- **平台支持**：JVM 完整实现；其他平台抛 `UnsupportedOperationException`

### HTTP 传输（`AcpHttpTransport`）

通过 HTTP/SSE 交换 JSON-RPC 消息。规划中，当前抛 `UnsupportedOperationException`。

## 协议方法映射

### Client → Agent 请求

| 方法 | 说明 | RAC Client 方法 |
| --- | --- | --- |
| `initialize` | 协议握手，协商版本与能力 | `client.initialize()` |
| `session/new` | 创建新会话 | `client.sessionNew(cwd)` |
| `session/load` | 加载历史会话 | `client.sessionLoad(sessionId, cwd)` |
| `session/prompt` | 发起提示轮次 | `client.sessionPrompt(sessionId, prompt, onUpdate)` |
| `session/cancel` | 取消进行中的轮次 | `client.sessionCancel(sessionId)` |

### Agent → Client 请求

| 方法 | 说明 | 处理方式 |
| --- | --- | --- |
| `session/request_permission` | 请求文件编辑/命令执行权限 | `AcpClientConfig.permissionHandler` |

### Agent → Client 通知

| 方法 | 说明 | 回调 |
| --- | --- | --- |
| `session/update` | 推送会话更新（消息块/工具调用/计划/用量） | `sessionPrompt` 的 `onUpdate` 参数 |

### SessionUpdate 子类型

| `@SerialName` | 类型 | 说明 |
| --- | --- | --- |
| `agent_message_chunk` | `AgentMessageChunk` | Agent 增量消息 |
| `user_message_chunk` | `UserMessageChunk` | 用户消息（历史回放） |
| `plan` | `PlanUpdate` | Agent 工作计划 |
| `tool_call` | `ToolCallUpdate` | 工具调用状态更新 |
| `usage_update` | `UsageUpdate` | Token 用量与成本 |

## StopReason 映射

ACP `StopReason` 与 RAC `FinishReason` 的映射关系：

| ACP StopReason | RAC FinishReason | 说明 |
| --- | --- | --- |
| `end_turn` | `STOP` | Agent 正常结束 |
| `max_tokens` | `LENGTH` | 达到 token 上限 |
| `max_turn_requests` | `LENGTH` | 达到轮次上限 |
| `refusal` | `CONTENT_FILTER` | Agent 拒绝 |
| `cancelled` | `STOP` | 用户取消 |

## 测试

ACP 协议测试位于 `core/src/jvmTest/kotlin/com/resderx/rac/AcpProtocolTest.kt`，覆盖：

- Client 请求格式验证（initialize、session/new、session/prompt）
- Client 处理 session/update 通知
- Client 处理 session/request_permission 请求
- AgentServer 路由 Client 请求到 handler
- AgentServer 处理 session/cancel 通知
- RacAcpAgent 适配器（prompt → chat → update 推送）
- DSL 集成（chatWithAcpAgent、serveAsAcpAgent）
- Client ↔ Server 端到端通信

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "com.resderx.rac.AcpProtocolTest"
```

测试使用内存 FakeAcpConnection（`SharedFlow` replay=MAX_VALUE）替代真实 stdio 连接，避免子进程依赖。
