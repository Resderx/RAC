**English** | [中文](a2a.md)

# A2A (Agent-to-Agent Protocol) Support

RAC provides bidirectional support for [Agent-to-Agent Protocol v1.0](https://a2a-protocol.org/) — it can act as a Client to call remote A2A Agents (Google ADK, LangGraph, CrewAI, etc.) and also wrap its own LLM call capabilities as an A2A Agent Server for other Clients to invoke.

## Overview

A2A is an open protocol released by Google in April 2025, based on JSON-RPC 2.0 + HTTP + SSE, defining standardized communication between Agents. Key differences from ACP:

| Dimension           | ACP                          | A2A                                   |
|---------------------|------------------------------|---------------------------------------|
| Transport           | stdio bidirectional JSON-RPC | HTTP request-response + SSE streaming |
| Positioning         | Editor ↔ coding assistant session management | Agent ↔ Agent cross-framework communication |
| Core concepts       | Session, SessionUpdate       | Task, Artifact, Agent Card            |
| Discovery mechanism | None (process-level direct connection) | `/.well-known/agent.json` Agent Card |
| Streaming updates   | `session/update` notification | `tasks/sendSubscribe` SSE event stream |
| Agent → Client request | `session/request_permission` | None (one-way request-response)       |
| State management    | Session-level (implicit)     | Task lifecycle (WORKING/COMPLETED/FAILED, etc.) |

## Architecture

RAC's A2A support consists of the following components (located in the `a2a/` module, package `top.resderx.rac.a2a`):

```
a2a/                                          # Standalone Gradle module (rac-a2a), depends on rac-core
├── A2aTypes.kt              # Protocol types: Role, TaskState, TaskStatus, Task, Message, A2aMetadata
├── A2aPart.kt               # Part polymorphic models (TextPart/FilePart/DataPart) + Artifact
├── A2aAgentCard.kt          # Agent discovery models: AgentCard, AgentProvider, AgentCapabilities, AgentSkill
├── A2aMethods.kt            # JSON-RPC method parameter/result models + A2aError codes
├── A2aClient.kt             # A2aClient interface + A2aStreamEvent + A2aClientConfig + factory functions
├── DefaultA2aClient.kt      # A2aClient default implementation (JSON-RPC over HTTP + SSE)
├── A2aAgentHandler.kt       # Agent business logic handler interface + A2aAgentContext
├── A2aAgentServer.kt        # Protocol-agnostic JSON-RPC dispatcher (dispatch / dispatchStreaming)
├── RacA2aAgent.kt           # Llm → A2A Agent adapter (class name RacA2aAgent)
└── RacA2aExtensions.kt      # Llm.chatWithA2aAgent / Llm.serveAsA2aAgent extension functions
```

## Acting as a Client to Call Remote Agents

### Basic Usage

Connect to a remote A2A Server via `A2aClient`, then initiate a streaming call with the `Llm.chatWithA2aAgent` extension function (the `Llm` instance serves only as a namespace anchor and does not directly use its internal capabilities):

```kotlin
import top.resderx.rac.a2a.A2aClient
import top.resderx.rac.a2a.A2aClientConfig

suspend fun a2aClientExample(ai: Llm) {
    val client = A2aClient(
        A2aClientConfig(
            baseUrl = "https://agent.example.com",
            apiKey = System.getenv("REMOTE_AGENT_API_KEY"), // null when no auth
        ),
    )

    val resp = ai.chatWithA2aAgent(
        client = client,
        prompt = "Help me analyze the trend of this sales data",
    ) { event ->
        // Stream Agent updates
        when (event) {
            is A2aStreamEvent.Initial -> println("Task started: ${event.result}")
            is A2aStreamEvent.StatusUpdate -> println("Status: ${event.event.status.state}")
            is A2aStreamEvent.ArtifactUpdate -> {
                // Incremental artifacts — extract text and print in real time
                event.event.artifact.parts
                    .filterIsInstance<TextPart>()
                    .forEach { print(it.text) }
            }
        }
    }
    println(resp.content) // Accumulated complete Agent response
    client.close()
}
```

### Low-Level API

`chatWithA2aAgent` wraps `sendStreamingMessage`; you can also use the low-level methods directly:

```kotlin
// Non-streaming — send a message and wait for the result
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

// Streaming — subscribe to the SSE event stream
client.sendStreamingMessage(
    SendStreamingMessageParams(
        message = Message(role = Role.USER, parts = listOf(TextPart(text = "Hi"))),
    )
).collect { event ->
    when (event) {
        is A2aStreamEvent.Initial -> { /* Initial Task/Message */
        }
        is A2aStreamEvent.StatusUpdate -> { /* Status change */
        }
        is A2aStreamEvent.ArtifactUpdate -> { /* Artifact increment */
        }
    }
}

// Query task status
val taskResult = client.getTask(GetTaskParams(id = "task-123"))

// List tasks
val listResult = client.listTasks(ListTasksParams(state = TaskState.WORKING))

// Cancel a task
val cancelResult = client.cancelTask(CancelTaskParams(id = "task-123"))

// Get the Agent Card (discovery endpoint)
val card = client.getAgentCard() // Defaults to requesting /.well-known/agent.json
```

### Agent Card Discovery

A2A Agents publish an Agent Card via the `/.well-known/agent.json` endpoint, describing their capabilities, skills, and endpoints:

```kotlin
val card = client.getAgentCard()
println("Agent: ${card.name}")
println("Description: ${card.description}")
println("Capabilities: streaming=${card.capabilities?.streaming}")
card.skills.forEach { skill ->
    println("Skill: ${skill.name} — ${skill.description}")
}
```

## Exposing a Service as an Agent Server

### Using the Llm Built-in Adapter

`serveAsA2aAgent` wraps Llm's `chat` call as an A2A Agent, returning a protocol-agnostic JSON-RPC dispatcher:

```kotlin
val server = ai.serveAsA2aAgent(
    agentCard = AgentCard(
        name = "rac-agent",
        description = "LLM Agent — Kotlin Multiplatform AI Call Library",
        url = "https://my-agent.example.com",
        provider = AgentProvider(organization = "ResDerX"),
        capabilities = AgentCapabilities(streaming = true),
    ),
    systemPrompt = "You are a Kotlin programming assistant",
)

// Get the Agent Card JSON (for the HTTP server to return at /.well-known/agent.json)
val cardJson: JsonElement = server.getAgentCardJson()

// Dispatch non-streaming JSON-RPC requests
val response: JsonObject = server.dispatch(requestJson)

// Dispatch streaming JSON-RPC requests
val events: Flow<A2aStreamEvent> = server.dispatchStreaming(requestJson)

server.close()
```

> **Note**: `A2aAgentServer` is a protocol-agnostic JSON-RPC dispatcher and does not bind to an HTTP server. Callers need to bind `dispatch` / `dispatchStreaming` to HTTP endpoints themselves (e.g., Ktor `embeddedServer`, Spring Boot, etc.). This is a design choice for the KMP library to avoid introducing HTTP server dependencies and maintain cross-platform compatibility.

Inside the `RacA2aAgent` adapter (file `RacA2aAgent.kt`):

1. `sendMessage` extracts text from the A2A Message → calls `Llm.chat` → constructs a Task containing the AI response
2. `sendStreamingMessage` pushes a WORKING status → executes `Llm.chat` → pushes an ArtifactUpdate (AI response text) → pushes a COMPLETED final status
3. `getTask` / `listTasks` / `cancelTask` are managed via an in-memory task store (Mutex-protected for concurrent access)

### Custom Agent Handler

Implement the `A2aAgentHandler` interface to fully customize Agent behavior:

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

        // Push WORKING status
        context.sendStatusUpdate(
            TaskStatusUpdateEvent(
                id = taskId,
                status = TaskStatus(state = TaskState.WORKING),
            )
        )

        // Push artifacts
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

        // Push final status
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

    override suspend fun close() { /* Release resources */
    }
}

val server = A2aAgentServer(MyAgentHandler())
```

## Protocol Method Mapping

### Client → Server Requests

| JSON-RPC Method        | Description                    | RAC Client Method                         |
|------------------------|--------------------------------|-------------------------------------------|
| `tasks/send`           | Send message (non-streaming)   | `client.sendMessage(params)`              |
| `tasks/sendSubscribe`  | Send message and subscribe to streaming updates | `client.sendStreamingMessage(params)` |
| `tasks/get`            | Query task status              | `client.getTask(params)`                  |
| `tasks/list`           | List tasks                     | `client.listTasks(params)`                |
| `tasks/cancel`         | Cancel a task                  | `client.cancelTask(params)`               |

### Streaming Event Types

| Event                              | Description                                            |
|------------------------------------|--------------------------------------------------------|
| `A2aStreamEvent.Initial`           | First event of the stream, carries Task or Message     |
| `A2aStreamEvent.StatusUpdate`      | Task status change (WORKING/COMPLETED/FAILED, etc.)    |
| `A2aStreamEvent.ArtifactUpdate`    | Incremental artifact push                              |

### Task Lifecycle

```
                    ┌─────────────┐
                    │   WORKING   │ ← Task starts
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┬──────────────┐
           ▼               ▼               ▼              ▼
  ┌─────────────────┐ ┌──────────┐ ┌─────────────┐ ┌──────────┐
  │ INPUT_REQUIRED  │ │ COMPLETED│ │   FAILED    │ │ CANCELED │
  └─────────────────┘ └──────────┘ └─────────────┘ └──────────┘
   Needs user input    Completed   Execution failed  User cancelled
```

| TaskState        | Description           | FinishReason Mapping              |
|------------------|-----------------------|-----------------------------------|
| `WORKING`        | In progress           | `UNKNOWN` (should not appear in terminal state) |
| `COMPLETED`      | Completed normally    | `STOP`                            |
| `INPUT_REQUIRED` | Needs user input      | `TOOL_CALLS`                      |
| `FAILED`         | Execution failed      | `UNKNOWN`                         |
| `CANCELED`       | User cancelled        | `STOP`                            |
| `REJECTED`       | Request rejected      | `CONTENT_FILTER`                  |
| `AUTH_REQUIRED`  | Authentication required | `UNKNOWN`                       |

### Part Types

The A2A Message `parts` field is a polymorphic `Part` sealed interface (`@JsonClassDiscriminator("kind")`):

| @SerialName | Type       | Description                          |
|-------------|------------|--------------------------------------|
| `text`      | `TextPart` | Text content                         |
| `file`      | `FilePart` | File (URI or inline Base64)          |
| `data`      | `DataPart` | Structured JSON data                 |

## Architectural Differences from ACP

| Dimension      | ACP (`acp/` package)                                  | A2A (`a2a/` package)                  |
|----------------|-------------------------------------------------------|---------------------------------------|
| Transport      | stdio bidirectional JSON-RPC                          | HTTP request-response + SSE           |
| Server impl    | `AcpAgentServer` bound to stdio connection            | `A2aAgentServer` protocol-agnostic dispatcher |
| Concurrency    | dispatcher coroutine + `CompletableDeferred` request-response matching | Per-request independent handling, no dispatcher |
| Streaming      | `SharedFlow` broadcasts `SessionUpdate`               | `Channel` buffer + `Flow<A2aStreamEvent>` |
| State management | Session-level (implicit, expressed via `StopReason`) | Task lifecycle (explicit 7 states)    |
| Discovery      | None                                                  | Agent Card (`/.well-known/agent.json`)|

### dispatchStreaming Event Ordering Guarantee

`A2aAgentServer.dispatchStreaming` uses a `Channel` to buffer handler-pushed updates, ensuring the `Initial` event is always emitted before `StatusUpdate` / `ArtifactUpdate`:

1. Create a `Channel<A2aStreamEvent>(UNLIMITED)` to buffer updates
2. Call `handler.sendStreamingMessage` — updates are buffered to the channel, the return value is the initial result
3. Emit `Initial` (the initial result) first
4. Close the channel and drain the buffered update events

This guarantees the client always receives the `Initial` event first to get the Task ID and initial status, before receiving subsequent updates.

## Testing

A2A protocol tests are located in `core/src/jvmTest/kotlin/com/resderx/rac/A2aProtocolTest.kt`, covering:

- Server routing non-streaming requests (tasks/send, tasks/get, tasks/cancel)
- Server returning METHOD_NOT_FOUND error for unknown methods
- Server streaming dispatch pushing the correct event sequence (Initial → StatusUpdate → ArtifactUpdate → final StatusUpdate)
- Server returning Agent Card JSON
- RacA2aAgent adapter (prompt → chat → Task mapping)
- RacA2aAgent streaming call pushing updates
- DSL integration (serveAsA2aAgent returns a correctly configured Server)

```powershell
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest --tests "top.resderx.rac.A2aProtocolTest"
```

Tests use `FakeA2aAgentHandler` (a controllable handler implementation) and `SseCapableMockEngine` (simulates AI provider HTTP responses), requiring no real API Key.
