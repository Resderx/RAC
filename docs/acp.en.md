**English** | [中文](acp.md)

# ACP (Agent Client Protocol) Support

RAC provides bidirectional support for [Agent Client Protocol v1](https://agentclientprotocol.com/) — it can act as a Client to call external Agents (Claude Code, Codex CLI, Gemini CLI, etc.) and also wrap its own LLM call capabilities as an ACP Agent Server for Editors to invoke.

## Overview

ACP is a standardized AI coding assistant communication protocol jointly introduced by Zed and JetBrains, based on JSON-RPC 2.0, defining bidirectional communication between an Editor (Client) and an Agent. Key differences from MCP:

| Dimension           | MCP                                          | ACP                                                            |
|---------------------|----------------------------------------------|----------------------------------------------------------------|
| Communication       | Client → Server one-way requests             | Client ↔ Agent bidirectional requests                          |
| Positioning         | Tool/resource/prompt discovery and invocation| Coding assistant session management                            |
| Core methods        | `tools/list`, `tools/call`, `resources/*`    | `initialize`, `session/new`, `session/prompt`, `session/update`|
| Streaming updates   | No native streaming                          | `session/update` notification streaming                        |
| Agent → Client req  | None                                         | `session/request_permission` (permission request)              |

## Architecture

RAC's ACP support consists of the following components (located in the `acp/` module, package `top.resderx.rac.acp`):

```
acp/                                          # Standalone Gradle module (rac-acp), depends on rac-core
├── AcpTypes.kt              # Protocol types: InitializeParams/Result, SessionNewParams, etc.
├── AcpContent.kt            # Content block models: AcpTextBlock, AcpResourceBlock, AcpImageBlock, etc.
├── AcpSession.kt            # Session update models: SessionUpdate sealed interface and subtypes
├── AcpMcpConfig.kt          # MCP configuration models (ACP embedded MCP servers)
├── AcpMethods.kt            # Method parameter/result serialization models
├── AcpTransport.kt          # Transport config: AcpStdioTransport, AcpHttpTransport
├── AcpConnection.kt         # Connection abstraction interface
├── StdioAcpConnection.kt    # stdio connection implementation (platform-specific)
├── StdioAcpServerConnection.kt # stdio server-side connection implementation
├── AcpClient.kt             # AcpClient interface + factory functions
├── DefaultAcpClient.kt      # AcpClient default implementation (dispatcher routing + request-response matching)
├── AcpAgentHandler.kt       # Agent business logic handler interface
├── AcpAgentServer.kt        # Agent server (routes Client requests to handler)
├── RacAcpAgent.kt           # Llm → ACP Agent adapter (class name RacAcpAgent)
└── RacAcpExtensions.kt      # Llm.chatWithAcpAgent / Llm.serveAsAcpAgent extension functions
```

## Acting as a Client to Call External Agents

### Basic Usage

Connect to an external Agent via `AcpClient`, then initiate a session with the `Llm.chatWithAcpAgent` extension function (the `Llm` instance serves only as a namespace anchor and does not directly use its internal capabilities):

```kotlin
import top.resderx.rac.acp.AcpClient
import top.resderx.rac.acp.AcpClientConfig
import top.resderx.rac.acp.AcpStdioTransport
import top.resderx.rac.acp.ImplementationInfo

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
    prompt = "Refactor src/Main.kt",
    cwd = "/project",
) { update ->
    // Stream Agent updates
    when (update) {
        is AgentMessageChunk -> print((update.content as AcpTextBlock).text)
        is ToolCallUpdate -> println("[Tool] ${update.title} — ${update.status}")
        is PlanUpdate -> update.entries.forEach {
            println("[Plan] ${it.content}")
        }
        is UsageUpdate -> println("[Usage] ${update.used}/${update.size} tokens")
        is UserMessageChunk -> Unit // History replay, usually ignored
    }
}
client.close()
```

### Low-Level API

`chatWithAcpAgent` wraps the following low-level calls, which can also be used directly:

```kotlin
client.initialize()                                    // Protocol handshake
val session = client.sessionNew(cwd = "/project")      // Create a session
val stopReason = client.sessionPrompt(
    // Initiate a prompt turn
    sessionId = session.sessionId,
    prompt = listOf(AcpTextBlock(text = "Hello")),
    onUpdate = { update -> /* ... */ },
)
client.sessionCancel(session.sessionId)                // Cancel the session
```

### Permission Handling

When an Agent performs file edits, command execution, and other operations, it sends a permission request to the Client via `session/request_permission`. Configure the handling policy via `permissionHandler`:

```kotlin
val client = AcpClient(
    AcpClientConfig(
        transport = AcpStdioTransport(command = "claude", args = emptyList()),
        clientInfo = ImplementationInfo(name = "my-editor", version = "1.0.0"),
        permissionHandler = { request ->
            // request.type: "edit_file" / "execute" / "write_file", etc.
            // request.options: List<PermissionOption>, each with id and title
            println("Agent requests permission: ${request.title}")
            // Return the user-selected option id
            PermissionOutcome(selected = PermissionOutcomeValue.ALLOW)
        },
    ),
)
```

## Exposing a Service as an Agent Server

### Using the Llm Built-in Adapter

`serveAsAcpAgent` wraps Llm's `chat` call as an ACP Agent, automatically handling protocol handshake, session management, and streaming update push:

```kotlin
val server = ai.serveAsAcpAgent(
    agentInfo = ImplementationInfo(
        name = "rac-agent",
        title = "LLM Agent",
        version = "0.2.0",
    ),
    agentCapabilities = AgentCapabilities(), // Declare Agent capabilities
    systemPrompt = "You are a Kotlin programming assistant",
)
server.start().join() // Block the current coroutine until the Editor disconnects
server.close()
```

Inside the `RacAcpAgent` adapter (file `RacAcpAgent.kt`):

1. `sessionPrompt` receives a user prompt and converts the `AcpContentBlock` list to text
2. Calls `Llm.chat` to perform LLM inference
3. Pushes `AgentMessageChunk` (containing the LLM response content) via `AcpAgentContext.sendUpdate`
4. Returns `SessionPromptResult` (`stopReason` is mapped from `FinishReason`)

### Custom Agent Handler

Implement the `AcpAgentHandler` interface to fully customize Agent behavior:

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
        // Push a plan
        context.sendUpdate(
            PlanUpdate(
                entries = listOf(
                    PlanEntry(content = "Analyze code", priority = "high", status = "in_progress"),
                )
            )
        )
        // Push a message
        context.sendUpdate(
            AgentMessageChunk(
                messageId = "msg-1",
                content = AcpTextBlock(text = "Analyzing..."),
            )
        )
        // Request permission
        val outcome = context.requestPermission(
            PermissionRequest(
                type = "execute",
                title = "Run tests",
                options = listOf(
                    PermissionOption(id = "allow", title = "Allow"),
                    PermissionOption(id = "deny", title = "Deny"),
                ),
            )
        )
        return SessionPromptResult(stopReason = StopReason.END_TURN)
    }

    override suspend fun sessionCancel(sessionId: String) { /* Cancel handling */
    }
    override suspend fun close() { /* Release resources */
    }
}

val server = AcpAgentServer(MyAgentHandler())
server.start().join()
```

## Transport

### Stdio Transport (`AcpStdioTransport`)

Exchanges JSON-RPC messages (one JSON per line) via subprocess stdin/stdout.

- **Client mode**: RAC is the parent process, spawning an external Agent subprocess
- **Server mode**: RAC is the subprocess, reading Editor requests from stdin and writing responses to stdout
- **Platform support**: JVM fully implemented; other platforms throw `UnsupportedOperationException`

### HTTP Transport (`AcpHttpTransport`)

Exchanges JSON-RPC messages via HTTP/SSE. Planned; currently throws `UnsupportedOperationException`.

## Protocol Method Mapping

### Client → Agent Requests

| Method           | Description                | RAC Client Method                                       |
|------------------|----------------------------|---------------------------------------------------------|
| `initialize`     | Protocol handshake         | `client.initialize()`                                   |
| `session/new`    | Create a new session       | `client.sessionNew(cwd)`                                |
| `session/load`   | Load a historical session  | `client.sessionLoad(sessionId, cwd)`                    |
| `session/prompt` | Initiate a prompt turn     | `client.sessionPrompt(sessionId, prompt, onUpdate)`     |
| `session/cancel` | Cancel an in-progress turn | `client.sessionCancel(sessionId)`                       |

### Agent → Client Requests

| Method                        | Description                       | Handling                           |
|-------------------------------|-----------------------------------|------------------------------------|
| `session/request_permission`  | Request file edit/command exec permission | `AcpClientConfig.permissionHandler` |

### Agent → Client Notifications

| Method           | Description                                          | Callback                          |
|------------------|-----------------------------------------------------|-----------------------------------|
| `session/update` | Push session updates (message chunks/tool calls/plan/usage) | `sessionPrompt`'s `onUpdate` param |

### SessionUpdate Subtypes

| `@SerialName`         | Type                | Description               |
|-----------------------|---------------------|---------------------------|
| `agent_message_chunk` | `AgentMessageChunk` | Agent incremental message |
| `user_message_chunk`  | `UserMessageChunk`  | User message (history replay) |
| `plan`                | `PlanUpdate`        | Agent work plan           |
| `tool_call`           | `ToolCallUpdate`    | Tool call status update   |
| `usage_update`        | `UsageUpdate`       | Token usage and cost      |

## StopReason Mapping

The mapping between ACP `StopReason` and RAC `FinishReason` (`FinishReason` is defined in rac-core's `messages/` package):

| ACP StopReason       | RAC FinishReason | Description           |
|----------------------|------------------|-----------------------|
| `end_turn`           | `STOP`           | Agent finished normally |
| `max_tokens`         | `LENGTH`         | Reached token limit   |
| `max_turn_requests`  | `LENGTH`         | Reached turn limit    |
| `refusal`            | `CONTENT_FILTER` | Agent refused         |
| `cancelled`          | `STOP`           | User cancelled        |

## Testing

ACP protocol tests are located in `core/src/jvmTest/kotlin/com/resderx/rac/AcpProtocolTest.kt`, covering:

- Client request format validation (initialize, session/new, session/prompt)
- Client handling of session/update notifications
- Client handling of session/request_permission requests
- AgentServer routing Client requests to handler
- AgentServer handling session/cancel notifications
- RacAcpAgent adapter (prompt → chat → update push)
- DSL integration (chatWithAcpAgent, serveAsAcpAgent)
- Client ↔ Server end-to-end communication

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "top.resderx.rac.AcpProtocolTest"
```

Tests use an in-memory FakeAcpConnection (`SharedFlow` replay=MAX_VALUE) instead of a real stdio connection to avoid subprocess dependencies.
