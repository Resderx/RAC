**English** | [中文](api-styles.md)

# API Protocol Reference

RAC abstracts 11 LLM providers into 3 API protocols, identified by the `ApiType` enum:

- **Completions API** (OpenAI Chat Completions style) — shared by 10 OpenAI-compatible providers
- **Responses API** (OpenAI Responses style) — currently only supported by OpenAI officially
- **Anthropic API** (Anthropic Messages style) — used natively by Anthropic

All three protocols share a unified `AIMessage` return model and `Flow<StreamEvent>` streaming event model, so callers do not need to worry about protocol differences. This document describes the field mapping, request/response structure, streaming events, and how to choose between `chat { }` / `respond { }` / `anthropicStream { }` for each protocol.

## Protocol Routing Overview

| Llm Method           | Protocol                                       | URL Suffix                                       | Return Type         | Streaming |
|----------------------|------------------------------------------------|--------------------------------------------------|---------------------|-----------|
| `chat { }`           | Routed by default provider's `defaultApiType`  | `/chat/completions` or `/messages` or `/responses` | `AIMessage`         | No        |
| `chatStream { }`     | Completions                                    | `/chat/completions`                              | `Flow<StreamEvent>` | Yes       |
| `anthropicStream { }`| Anthropic                                      | `/messages`                                      | `Flow<StreamEvent>` | Yes       |
| `respond { }`        | Responses                                      | `/responses`                                     | `AIMessage`         | No        |
| `respondStream { }`  | Responses                                      | `/responses`                                     | `Flow<StreamEvent>` | Yes       |

> **Unified Streaming Events**: All three streaming methods (`chatStream` / `anthropicStream` / `respondStream`) return `Flow<StreamEvent>`. The underlying raw events (`CompletionsStreamChunk` / `AnthropicStreamEvent` / `ResponsesStreamEvent`) are aggregated by `StreamAggregator` into a unified `StreamEvent` (`TextDelta` / `ReasoningDelta` / `ToolCallDelta` / `Done`), shielding protocol differences.

`chat { }` is the only auto-routing entry point, dispatching to one of the three clients based on `defaultProvider.defaultApiType`. The other methods explicitly bind to a protocol; a protocol mismatch throws `RACException`.

## Authentication Header Strategy

The auth header is dynamically injected by `Llm.buildHeaders(provider)` based on the provider's `defaultApiType`:

| Protocol    | Auth Header                     | Condition        |
|-------------|---------------------------------|------------------|
| Completions | `Authorization: Bearer <apiKey>`| `apiKey` not null|
| Responses   | `Authorization: Bearer <apiKey>`| `apiKey` not null|
| Anthropic   | `x-api-key: <apiKey>`           | `apiKey` not null|

When `apiKey` is null (e.g., Ollama local mode), no auth header is added. `Content-Type: application/json` is appended uniformly by `buildHeaders`. The provider's `defaultHeaders` (such as Anthropic's `anthropic-version`) are copied before the auth header, and the auth header is written before Content-Type.

---

## I. Completions API (OpenAI Chat Completions)

OpenAI Chat Completions style endpoint at `/chat/completions`. Reused by 10 providers: DeepSeek, OpenAI, Kimi, GLM, MiniMax, Ollama, Doubao, Qwen, MIMO, and Gemini.

### Request Structure (`CompletionsRequest`)

| Field              | Serial Name        | Type                    | Description                                                    |
|--------------------|--------------------|-------------------------|----------------------------------------------------------------|
| `model`            | `model`            | `String`                | Model name; falls back to `provider.defaultModel` when omitted |
| `messages`         | `messages`         | `List<Message>`         | Message list (system / user / assistant / tool)                |
| `temperature`      | `temperature`      | `Double?`               | Sampling temperature; server default when omitted              |
| `topP`             | `top_p`            | `Double?`               | Nucleus sampling                                               |
| `maxTokens`        | `max_tokens`       | `Long?`                 | Maximum generated tokens                                       |
| `stream`           | `stream`           | `Boolean`               | Whether streaming, set automatically by the client             |
| `tools`            | `tools`            | `List<ToolDefinition>?` | Tool definitions; not sent when empty                          |
| `toolChoice`       | `tool_choice`      | `String?`               | Tool selection strategy                                        |
| `reasoningEffort`  | `reasoning_effort` | `String?`               | Reasoning effort (reasoning models only)                       |
| `stop`             | `stop`             | `List<String>?`         | Stop sequences; generation stops when any string is produced   |
| `seed`             | `seed`             | `Long?`                 | Random seed for deterministic output                           |

> Serialization uses `encodeDefaults = false`; `null` fields are omitted to avoid sending meaningless fields.

### Differentiated Handling of `enableThinking` on the Completions Protocol

`enableThinking` is not serialized directly into the request body (Completions API has no such field); instead, it is indirectly expressed as `reasoningEffort` via `resolveReasoningEffortForCompletions`:

| `enableThinking` | `reasoningEffort` explicitly set | Final `reasoningEffort`        |
|------------------|----------------------------------|--------------------------------|
| `true`           | Not set                          | Automatically set to `"medium"`|
| `true`           | Already set                      | Preserve the explicit value    |
| `false`          | Any                              | Force `null` (disable reasoning)|
| `null`           | Not set                          | Fall back to ModelConfig or server default |
| `null`           | Already set                      | Preserve the explicit value    |

### Response Structure (`CompletionsResponse`)

```json
{
  "id": "1",
  "model": "deepseek-v4-pro",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello!",
        "reasoning_content": "Thinking process...",
        "tool_calls": [
          {
            "id": "call_1",
            "type": "function",
            "function": {
              "name": "f",
              "arguments": "{}"
            }
          }
        ]
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 5,
    "completion_tokens": 2,
    "total_tokens": 7
  }
}
```

| Field                                   | Type                      | Description                                                          |
|-----------------------------------------|---------------------------|----------------------------------------------------------------------|
| `choices[0].message.content`            | `String?`                 | Main text; null for pure tool calls                                  |
| `choices[0].message.reasoningContent`   | `String?`                 | Reasoning process (DeepSeek extension field)                         |
| `choices[0].message.toolCalls`          | `List<ToolCallResponse>?` | Tool call list                                                       |
| `choices[0].finishReason`               | `String?`                 | Finish reason (`stop` / `length` / `tool_calls` / `content_filter`)  |
| `usage`                                 | `Usage?`                  | Token usage                                                          |

### Streaming Events (`CompletionsStreamChunk`)

Streaming responses are SSE, each line `data: <json>`. The first chunk's `delta` contains `role`, subsequent chunks contain `content` deltas, and the final chunk may contain `finish_reason` and `usage`. The stream ends when `data: [DONE]` is encountered.

```json
{
  "id": "1",
  "model": "deepseek-v4-pro",
  "choices": [
    {
      "index": 0,
      "delta": {
        "role": "assistant",
        "content": "Hi"
      },
      "finish_reason": null
    }
  ]
}
{
  "id": "1",
  "model": "deepseek-v4-pro",
  "choices": [
    {
      "index": 0,
      "delta": {
        "content": "!"
      },
      "finish_reason": "stop"
    }
  ]
}
```

`CompletionsStreamChunk.Delta` fields: `role` / `content` / `reasoningContent` / `toolCalls`, all nullable (servers may send incomplete chunks).

### Mapping to AIMessage

`CompletionsResponse.toAIMessage()` (see `Mappers.kt`):

- `content` ← `choices.firstOrNull()?.message?.content ?: ""`
- `reasoningContent` ← `message?.reasoningContent`
- `toolCalls` ← `message?.toolCalls?.map { ToolCall(id, name, arguments) }` (null-safe)
- `usage` ← `usage` (pass-through)
- `finishReason` ← `choice?.finishReason.toFinishReason()`
- `rawResponse` ← `null`

### Streaming Aggregation (`toCompletionsStreamEvents`)

`CompletionsClient.stream(...)` returns `Flow<CompletionsStreamChunk>`, aggregated by the `toCompletionsStreamEvents` extension function into `Flow<StreamEvent>`:

- `delta.content` → `StreamEvent.TextDelta(delta, accumulated)`
- `delta.reasoningContent` → `StreamEvent.ReasoningDelta(delta, accumulated)`
- `delta.toolCalls` → `StreamEvent.ToolCallDelta(index, id, name, argumentsDelta, argumentsAccumulated)` (auto-aggregates fragments)
- Stream end → `StreamEvent.Done(content, reasoningContent, toolCalls, usage, finishReason, rawResponse)` (fields flattened, self-contained)

---

## II. Responses API (OpenAI Responses)

OpenAI's new protocol at the `/responses` endpoint, the successor to Chat Completions. Currently only supported by OpenAI officially. RAC invokes it explicitly via `respond { }` / `respondStream { }`, independent of the provider's `defaultApiType`.

### Request Structure (`ResponsesRequest`)

| Field             | Serial Name         | Type                    | Description                                                              |
|-------------------|---------------------|-------------------------|--------------------------------------------------------------------------|
| `model`           | `model`             | `String`                | Model name                                                               |
| `input`           | `input`             | `String`                | User input text (string form, auto-wrapped as a single user message)     |
| `instructions`    | `instructions`      | `String?`               | System instructions, corresponding to the Responses API `instructions`   |
| `stream`          | `stream`            | `Boolean`               | Whether streaming, set automatically by the client                       |
| `tools`           | `tools`             | `List<ToolDefinition>?` | Tool definitions                                                         |
| `temperature`     | `temperature`       | `Double?`               | Sampling temperature                                                     |
| `maxOutputTokens` | `max_output_tokens` | `Long?`                 | Max output tokens (note the field name differs from Completions)         |
| `seed`            | `seed`              | `Long?`                 | Random seed                                                              |

> The Responses API `input` is a string, not a message list. `chat { }` in the RESPONSES branch calls `ChatRequestBuilder.buildResponses()`, preferring the last `UserMessage` text as `input`.
>
> **`stop` and `enableThinking` are unsupported**: The Responses protocol has no corresponding fields; `buildResponses` silently ignores these two parameters.

### Response Structure (`ResponsesResponse`)

```json
{
  "id": "resp_1",
  "model": "gpt-5.5",
  "output": [
    {
      "type": "message",
      "id": "msg_1",
      "role": "assistant",
      "content": [
        {
          "type": "output_text",
          "text": "Answer"
        }
      ],
      "status": "completed"
    },
    {
      "type": "function_call",
      "id": "fc_1",
      "call_id": "call_1",
      "name": "get_weather",
      "arguments": "{\"city\":\"Beijing\"}"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 5,
    "total_tokens": 15
  }
}
```

`output` is a list of the sealed class `OutputItem`, with two subtypes:

| Subtype              | Serial Name    | Key Fields                                          |
|----------------------|----------------|-----------------------------------------------------|
| `MessageOutput`      | `message`      | `content: List<ResponseContent>` (contains `text`)  |
| `FunctionCallOutput` | `function_call`| `callId` / `name` / `arguments`                     |

### Streaming Events (`ResponsesStreamEvent`)

The Responses API streaming protocol has 11 event types (sealed class); commonly used events:

| Event Subclass       | Serial Name                    | Description                        |
|----------------------|--------------------------------|------------------------------------|
| `ResponseCreated`    | `response.created`             | Response created                   |
| `OutputItemAdded`    | `response.output_item.added`   | Output item started                |
| `OutputTextDelta`    | `response.output_text.delta`   | Text delta (`delta` field)         |
| `OutputTextDone`     | `response.output_text.done`    | Text output completed              |
| `OutputItemDone`     | `response.output_item.done`    | Output item completed              |
| `ResponseCompleted`  | `response.completed`           | Response completed                 |
| `Error Event`        | `error`                        | Error                              |

### Mapping to AIMessage

`ResponsesResponse.toAIMessage()` (see `Mappers.kt`):

- `content` ← concatenated text from all `MessageOutput` content in `output`
- `toolCalls` ← all `FunctionCallOutput` mapped to `ToolCall` (`id` takes `callId` or `id`)
- `usage` ← `usage` (pass-through)
- `finishReason` ← fixed `STOP` (Responses API has no explicit finish reason)

### Streaming Aggregation (`toResponsesStreamEvents`)

`ResponsesClient.stream(...)` returns `Flow<ResponsesStreamEvent>`, aggregated by the `toResponsesStreamEvents` extension function into `Flow<StreamEvent>`, with the same format as Completions streaming events.

---

## III. Anthropic API (Anthropic Messages)

Anthropic Messages style endpoint at `/messages`. Used natively only by Anthropic. The protocol differs significantly from OpenAI: system is a top-level field, `max_tokens` is required, responses use a content blocks structure, and thinking is controlled via a `thinking` object.

### Request Structure (`AnthropicRequest`)

| Field            | Serial Name       | Type                    | Description                                                          |
|------------------|-------------------|-------------------------|----------------------------------------------------------------------|
| `model`          | `model`           | `String`                | Model name                                                           |
| `messages`       | `messages`        | `List<Message>`         | Non-system message list                                              |
| `system`         | `system`          | `String?`               | Top-level system field, extracted and concatenated from SystemMessage|
| `maxTokens`      | `max_tokens`      | `Long`                  | **Required**; `buildAnthropic` defaults to 4096 when null            |
| `temperature`    | `temperature`     | `Double?`               | Sampling temperature                                                 |
| `topP`           | `top_p`           | `Double?`               | Nucleus sampling                                                     |
| `tools`          | `tools`           | `List<ToolDefinition>?` | Tool definitions                                                     |
| `stream`         | `stream`          | `Boolean`               | Whether streaming                                                    |
| `stopSequences`  | `stop_sequences`  | `List<String>?`         | Stop sequences                                                       |
| `thinking`       | `thinking`        | `AnthropicThinking?`    | Thinking object, constructed when `enableThinking=true`              |

> `ChatRequestBuilder.buildAnthropic()` extracts system messages via `filterIsInstance<SystemMessage>` and joins them with newlines into the `system` string; non-system messages keep their original order as `messages`. `reasoningEffort` is ignored (the Anthropic protocol has no such field; thinking behavior is controlled via the `thinking` object).

### `AnthropicThinking` Data Class

```kotlin
@Serializable
data class AnthropicThinking(
    val type: String,                          // "enabled"
    @SerialName("budget_tokens") val budgetTokens: Long,
)
```

Constructed when `enableThinking = true` and `maxTokens > 1`:

```
AnthropicThinking(
    type = "enabled",
    budgetTokens = (maxTokens * 4 / 5).coerceIn(1L, maxTokens - 1)
)
```

`budget_tokens` is 4/5 of `maxTokens`, ensuring `1 <= budget < maxTokens` (API constraint `budget_tokens < max_tokens`), while reserving 20% for the main text output.

| `enableThinking`              | Final `thinking` field                                    |
|-------------------------------|-----------------------------------------------------------|
| `true` (and maxTokens > 1)    | `{type:"enabled", budget_tokens: maxTokens*4/5}`          |
| `true` (and maxTokens ≤ 1)    | `null` (cannot satisfy budget < maxTokens constraint)     |
| `false`                       | `null` (no thinking field sent, equivalent to disabled)   |
| `null`                        | `null` (default behavior, no thinking sent)               |

### Required Request Headers

- `anthropic-version: 2023-06-01` (auto-injected into `defaultHeaders` by the `AnthropicProvider` factory)
- `x-api-key: <apiKey>` (injected by `Llm.buildHeaders` in the `ANTHROPIC` branch, **not** Bearer)

### Response Structure (`AnthropicResponse`)

```json
{
  "id": "msg_1",
  "model": "claude-opus-4-1",
  "content": [
    {
      "type": "text",
      "text": "Answer"
    },
    {
      "type": "tool_use",
      "id": "toolu_1",
      "name": "get_weather",
      "input": "{\"city\":\"Beijing\"}"
    }
  ],
  "stop_reason": "end_turn",
  "usage": {
    "input_tokens": 10,
    "output_tokens": 5
  }
}
```

`content` is a list of the sealed class `ContentBlock`, with two subtypes:

| Subtype    | Serial Name | Key Fields             |
|------------|-------------|------------------------|
| `Text`     | `text`      | `text`                 |
| `ToolUse`  | `tool_use`  | `id` / `name` / `input`|

### Streaming Events (`AnthropicStreamEvent`)

The Anthropic streaming protocol has 6 event types (sealed class), triggered in order:

| Event Subclass        | Serial Name           | Description                                                                |
|-----------------------|-----------------------|----------------------------------------------------------------------------|
| `MessageStart`        | `message_start`       | Message start, contains full `AnthropicResponse` (no content)              |
| `ContentBlockStart`   | `content_block_start` | Content block start, contains `index` and `contentBlock`                   |
| `ContentBlockDelta`   | `content_block_delta` | Content block delta; `delta` is `TextDelta` or `InputJsonDelta`            |
| `ContentBlockStop`    | `content_block_stop`  | Content block end                                                          |
| `MessageDelta`        | `message_delta`       | Message-level delta, contains `stopReason` and `usage`                     |
| `MessageStop`         | `message_stop`        | Message end                                                                |

`Delta` sealed class subtypes: `TextDelta` (`text_delta`, contains `text`), `ThinkingDelta` (`thinking_delta`, contains `thinking`, used for extended thinking), `InputJsonDelta` (`input_json_delta`, contains `partialJson`, used for tool call argument deltas).

### Mapping to AIMessage

`AnthropicResponse.toAIMessage()` (see `Mappers.kt`):

- `content` ← concatenated `text` from all `Text` blocks in `content`
- `toolCalls` ← all `ToolUse` blocks mapped to `ToolCall(id, name, arguments=input)`
- `usage` ← `AnthropicUsage` mapped to `Usage` (`promptTokens = inputTokens`, `completionTokens = outputTokens`, `totalTokens = inputTokens + outputTokens`)
- `finishReason` ← `stopReason.toFinishReason()` (`end_turn` → `STOP`, `max_tokens` → `LENGTH`, `tool_use` → `TOOL_CALLS`)

### Streaming Aggregation (`toAnthropicStreamEvents`)

`AnthropicClient.stream(...)` returns `Flow<AnthropicStreamEvent>`, aggregated by the `toAnthropicStreamEvents` extension function into `Flow<StreamEvent>`, with the same format as Completions streaming events. `thinking_delta` events are aggregated into `StreamEvent.ReasoningDelta`.

---

## Unified AIMessage Model

Responses from all three protocols are normalized to `AIMessage` via the extension functions in `Mappers.kt`:

```kotlin
@Serializable
data class AIMessage(
    val content: String,                        // Main text
    val reasoningContent: String? = null,       // Reasoning process (reasoning models only)
    val toolCalls: List<ToolCall> = emptyList(),// Tool calls
    val usage: Usage? = null,                   // Token usage
    val finishReason: FinishReason = UNKNOWN,   // Finish reason enum
    val rawResponse: String? = null,            // Raw response string (currently null)
)
```

| Protocol    | content Source                   | toolCalls Source      | reasoningContent           | finishReason        |
|-------------|----------------------------------|-----------------------|----------------------------|---------------------|
| Completions | `message.content`                | `message.toolCalls`   | `message.reasoningContent` | `finishReason` mapped|
| Responses   | `MessageOutput.content` joined   | `FunctionCallOutput`  | None                       | Fixed `STOP`        |
| Anthropic   | `Text` blocks joined             | `ToolUse` blocks      | `thinking_delta` aggregated (streaming) | `stopReason` mapped |

The `FinishReason` enum normalizes each provider's finish reason string (see `String?.toFinishReason()`):

| Protocol Raw Value                     | FinishReason     |
|----------------------------------------|------------------|
| `stop` / `end_turn` / `stop_sequence`  | `STOP`           |
| `length` / `max_tokens`                | `LENGTH`         |
| `tool_calls` / `tool_use`              | `TOOL_CALLS`     |
| `content_filter`                       | `CONTENT_FILTER` |
| `null` / unknown value                 | `UNKNOWN`        |

---

## Unified StreamEvent Model

Streaming responses from all three protocols are aggregated by `StreamAggregator` into a unified `StreamEvent` sealed interface:

```kotlin
sealed interface StreamEvent {
    data class TextDelta(val delta: String, val accumulated: String) : StreamEvent
    data class ReasoningDelta(val delta: String, val accumulated: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int, val id: String, val name: String,
        val argumentsDelta: String, val argumentsAccumulated: String,
    ) : StreamEvent
    data class Done(
        val content: String,
        val reasoningContent: String?,
        val toolCalls: List<ToolCall>,
        val usage: Usage?,
        val finishReason: FinishReason,
        val rawResponse: String? = null,
    ) : StreamEvent
}
```

The `Done` event is **self-contained** — all fields are flattened at the top level, so callers need no nested access or delta concatenation:

```kotlin
ai.chatStream { user("Hello") }.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)      // Text delta
        is StreamEvent.ReasoningDelta -> print(event.delta) // Reasoning delta
        is StreamEvent.ToolCallDelta -> { /* Tool call fragment */
        }
        is StreamEvent.Done -> {
            // Stream ended — access the complete result directly
            println(event.content)
            println(event.finishReason)
            event.toolCalls.forEach { /* ... */ }
            // Or convert to AIMessage: event.toAIMessage()
        }
    }
}
```

The three aggregation functions are located in `StreamAggregator.kt`. The function names reflect the API source (avoiding JVM generic erasure signature conflicts and ensuring KMP cross-platform compatibility):

| Function                                                    | Input                    | Output               |
|-------------------------------------------------------------|--------------------------|----------------------|
| `Flow<CompletionsStreamChunk>.toCompletionsStreamEvents()`  | Completions raw chunk    | `Flow<StreamEvent>`  |
| `Flow<AnthropicStreamEvent>.toAnthropicStreamEvents()`      | Anthropic raw event      | `Flow<StreamEvent>`  |
| `Flow<ResponsesStreamEvent>.toResponsesStreamEvents()`      | Responses raw event      | `Flow<StreamEvent>`  |

---

## Protocol Adaptation of Customization Parameters

The `stop` / `seed` / `enableThinking` parameters are handled differently across the three protocols:

| Parameter        | Completions                                                  | Anthropic                                                   | Responses             |
|------------------|--------------------------------------------------------------|-------------------------------------------------------------|-----------------------|
| `stop`           | Serialized as `stop`                                         | Serialized as `stop_sequences`                              | **Unsupported**, silently ignored |
| `seed`           | Serialized as `seed`                                         | **Unsupported**, silently ignored                           | Serialized as `seed`  |
| `enableThinking` | Mutually exclusive with `reasoningEffort` (true and not explicitly set → `"medium"`; false → `null`) | Constructs `thinking={type:"enabled", budget_tokens:maxTokens*4/5}` | **Unsupported**, silently ignored |

Parameter resolution uses layered fallback: **builder explicit value → ModelConfig default → server default**. This means:

- A model registered in `models { model("xxx") { enableThinking = true } }` automatically enables thinking on every call
- Explicitly overriding with `chat { enableThinking = false }` disables thinking
- If neither the builder nor ModelConfig sets it, the server default behavior applies

---

## When to Use chat { } / respond { } / anthropicStream { }

### chat { } — Universal Non-Streaming Entry

Use case: You want automatic routing by the provider's default protocol and do not care about protocol differences. Llm dispatches based on `defaultProvider.defaultApiType`:

- Completions providers (DeepSeek/Kimi/GLM, etc.) → `/chat/completions`
- Anthropic providers → `/messages`
- Responses providers → `/responses`

**Recommended as the default non-streaming call method**, unless you need Responses API-specific features like `instructions`.

### respond { } — Explicit Responses API

Use case: You need the OpenAI Responses API's `instructions` system directive field, `max_output_tokens` naming, or Responses-specific output structure. **Only available for the official OpenAI provider**; calling it on other providers will fail because the endpoint does not exist.

`respond { }` uses a separate `RespondRequestBuilder` that provides `input` / `instructions` / `model` / `temperature` / `maxOutputTokens` / `tools` / `seed` fields, without maintaining a message list.

### chatStream { } / anthropicStream { } / respondStream { } — Protocol-Explicit Streaming

All three streaming methods return `Flow<StreamEvent>`, but bind to different protocols:

- Completions streaming → `chatStream { }`
- Anthropic streaming → `anthropicStream { }`
- Responses streaming → `respondStream { }`

> **Fast-fail on protocol mismatch**: Calling `chatStream { }` on an Anthropic provider throws `RACException("chatStream requires a Completions API provider; use anthropicStream for Anthropic")`, and vice versa. This is a runtime type-safety guard; callers should choose the correct streaming method based on the provider's protocol.
