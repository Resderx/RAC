# API 协议详解

RAC 将 11 家 LLM 供应商归纳为 3 种 API 协议，由 `ApiType` 枚举标识：

- **Completions API**（OpenAI Chat Completions 风格）— 被 10 家 OpenAI 兼容供应商共用
- **Responses API**（OpenAI Responses 风格）— 当前仅 OpenAI 官方支持
- **Anthropic API**（Anthropic Messages 风格）— Anthropic 原生使用

三种协议共用统一的 `AIMessage` 返回模型，调用方无需关心协议差异。本文档说明各协议的字段映射、请求/响应结构、流式事件，以及如何选用 `chat { }` / `respond { }` / `anthropicStream { }`。

## 协议路由总览

| RAC 方法 | 协议 | URL 后缀 | 返回类型 | 流式 |
| --- | --- | --- | --- | --- |
| `chat { }` | 按默认供应商 `defaultApiType` 路由 | `/chat/completions` 或 `/messages` 或 `/responses` | `AIMessage` | 否 |
| `chatStream { }` | Completions | `/chat/completions` | `Flow<CompletionsStreamChunk>` | 是 |
| `anthropicStream { }` | Anthropic | `/messages` | `Flow<AnthropicStreamEvent>` | 是 |
| `respond { }` | Responses | `/responses` | `AIMessage` | 否 |
| `respondStream { }` | Responses | `/responses` | `Flow<ResponsesStreamEvent>` | 是 |

`chat { }` 是唯一的自动路由入口，按 `defaultProvider.defaultApiType` 分发到三种客户端之一。其余方法显式绑定协议，协议不匹配时抛 `RACException`。

## 鉴权头策略

鉴权头由 `RAC.buildHeaders(provider)` 按供应商 `defaultApiType` 动态注入：

| 协议 | 鉴权头 | 条件 |
| --- | --- | --- |
| Completions | `Authorization: Bearer <apiKey>` | `apiKey` 非 null |
| Responses | `Authorization: Bearer <apiKey>` | `apiKey` 非 null |
| Anthropic | `x-api-key: <apiKey>` | `apiKey` 非 null |

`apiKey` 为 null（如 Ollama 本地模式）时不添加任何鉴权头。`Content-Type: application/json` 由 `buildHeaders` 统一追加。供应商的 `defaultHeaders`（如 Anthropic 的 `anthropic-version`）先于鉴权头复制，鉴权头先于 Content-Type 写入。

---

## 一、Completions API（OpenAI Chat Completions）

OpenAI Chat Completions 风格接口，端点为 `/chat/completions`。被 DeepSeek、OpenAI、Kimi、GLM、MiniMax、Ollama、Doubao、Qwen、MIMO、Gemini 共 10 家供应商复用。

### 请求结构（`CompletionsRequest`）

| 字段 | 序列化名 | 类型 | 说明 |
| --- | --- | --- | --- |
| `model` | `model` | `String` | 模型名，未指定时取 `provider.defaultModel` |
| `messages` | `messages` | `List<Message>` | 消息列表（system / user / assistant / tool） |
| `temperature` | `temperature` | `Double?` | 采样温度，省略时服务端默认 |
| `topP` | `top_p` | `Double?` | nucleus sampling |
| `maxTokens` | `max_tokens` | `Long?` | 最大生成 token 数 |
| `stream` | `stream` | `Boolean` | 是否流式，由客户端自动设置 |
| `tools` | `tools` | `List<ToolDefinition>?` | 工具定义，空时不发送 |
| `toolChoice` | `tool_choice` | `String?` | 工具选择策略 |
| `reasoningEffort` | `reasoning_effort` | `String?` | 推理强度（仅推理模型） |

> 序列化时 `encodeDefaults = false`，`null` 字段会被省略，避免发送无意义字段。

### 响应结构（`CompletionsResponse`）

```json
{
  "id": "1",
  "model": "gpt-4",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello!",
        "reasoning_content": "思考过程...",
        "tool_calls": [{ "id": "call_1", "type": "function", "function": { "name": "f", "arguments": "{}" } }]
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7 }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `choices[0].message.content` | `String?` | 正文，纯工具调用时为 null |
| `choices[0].message.reasoningContent` | `String?` | 推理过程（DeepSeek-R1 扩展字段） |
| `choices[0].message.toolCalls` | `List<ToolCallResponse>?` | 工具调用列表 |
| `choices[0].finishReason` | `String?` | 结束原因（`stop` / `length` / `tool_calls` / `content_filter`） |
| `usage` | `Usage?` | token 用量 |

### 流式事件（`CompletionsStreamChunk`）

流式响应为 SSE，每行 `data: <json>`。首个 chunk 的 `delta` 含 `role`，后续 chunk 含 `content` 增量，最后 chunk 可能含 `finish_reason` 与 `usage`。遇到 `data: [DONE]` 时流结束。

```json
{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}
{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}
```

`CompletionsStreamChunk.Delta` 字段：`role` / `content` / `reasoningContent` / `toolCalls`，全部可空（服务端可能发送不完整 chunk）。

### 映射到 AIMessage

`CompletionsResponse.toAIMessage()`（见 `Mappers.kt`）：

- `content` ← `choices.firstOrNull()?.message?.content ?: ""`
- `reasoningContent` ← `message?.reasoningContent`
- `toolCalls` ← `message?.toolCalls?.map { ToolCall(id, name, arguments) }`（空安全）
- `usage` ← `usage`（直接透传）
- `finishReason` ← `choice?.finishReason.toFinishReason()`
- `rawResponse` ← `null`

---

## 二、Responses API（OpenAI Responses）

OpenAI 新协议，端点为 `/responses`，是 Chat Completions 的后继。当前仅 OpenAI 官方支持。RAC 通过 `respond { }` / `respondStream { }` 显式调用，不依赖供应商 `defaultApiType`。

### 请求结构（`ResponsesRequest`）

| 字段 | 序列化名 | 类型 | 说明 |
| --- | --- | --- | --- |
| `model` | `model` | `String` | 模型名 |
| `input` | `input` | `String` | 用户输入文本（字符串形式，自动包装为单条 user 消息） |
| `instructions` | `instructions` | `String?` | 系统指令，对应 Responses API 的 `instructions` 字段 |
| `stream` | `stream` | `Boolean` | 是否流式，由客户端自动设置 |
| `tools` | `tools` | `List<ToolDefinition>?` | 工具定义 |
| `temperature` | `temperature` | `Double?` | 采样温度 |
| `maxOutputTokens` | `max_output_tokens` | `Long?` | 最大输出 token 数（注意字段名与 Completions 不同） |

> Responses API 的 `input` 为字符串而非消息列表。`chat { }` 在 RESPONSES 分支会调用 `ChatRequestBuilder.buildResponses()`，优先取最后一条 `UserMessage` 文本作为 `input`。

### 响应结构（`ResponsesResponse`）

```json
{
  "id": "resp_1",
  "model": "gpt-4o",
  "output": [
    { "type": "message", "id": "msg_1", "role": "assistant", "content": [{ "type": "output_text", "text": "答案" }], "status": "completed" },
    { "type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "get_weather", "arguments": "{\"city\":\"北京\"}" }
  ],
  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
}
```

`output` 为密封类 `OutputItem` 列表，两种子类型：

| 子类型 | 序列化名 | 关键字段 |
| --- | --- | --- |
| `MessageOutput` | `message` | `content: List<ResponseContent>`（含 `text`） |
| `FunctionCallOutput` | `function_call` | `callId` / `name` / `arguments` |

### 流式事件（`ResponsesStreamEvent`）

Responses API 流式协议有 11 种事件类型（密封类），常用事件：

| 事件子类 | 序列化名 | 说明 |
| --- | --- | --- |
| `ResponseCreated` | `response.created` | 响应创建 |
| `OutputItemAdded` | `response.output_item.added` | 输出项开始 |
| `OutputTextDelta` | `response.output_text.delta` | 文本增量（`delta` 字段） |
| `OutputTextDone` | `response.output_text.done` | 文本输出完成 |
| `OutputItemDone` | `response.output_item.done` | 输出项完成 |
| `ResponseCompleted` | `response.completed` | 响应完成 |
| `ErrorEvent` | `error` | 错误 |

### 映射到 AIMessage

`ResponsesResponse.toAIMessage()`（见 `Mappers.kt`）：

- `content` ← `output` 中所有 `MessageOutput` 的 `content` 文本拼接
- `toolCalls` ← `output` 中所有 `FunctionCallOutput` 映射为 `ToolCall`（`id` 取 `callId` 或 `id`）
- `usage` ← `usage`（直接透传）
- `finishReason` ← 固定 `STOP`（Responses API 无显式结束原因）

---

## 三、Anthropic API（Anthropic Messages）

Anthropic Messages 风格接口，端点为 `/messages`。仅 Anthropic 原生使用。协议与 OpenAI 差异较大：system 为顶层字段、`max_tokens` 必填、响应为 content blocks 结构。

### 请求结构（`AnthropicRequest`）

| 字段 | 序列化名 | 类型 | 说明 |
| --- | --- | --- | --- |
| `model` | `model` | `String` | 模型名 |
| `messages` | `messages` | `List<Message>` | 非 system 消息列表 |
| `system` | `system` | `String?` | 顶层 system 字段，由 `SystemMessage` 抽取拼接 |
| `maxTokens` | `max_tokens` | `Long` | **必填**，`buildAnthropic` 在 null 时默认 4096 |
| `temperature` | `temperature` | `Double?` | 采样温度 |
| `topP` | `top_p` | `Double?` | nucleus sampling |
| `tools` | `tools` | `List<ToolDefinition>?` | 工具定义 |
| `stream` | `stream` | `Boolean` | 是否流式 |

> `ChatRequestBuilder.buildAnthropic()` 会用 `filterIsInstance<SystemMessage>` 抽取系统消息并以换行拼接为 `system` 字符串；非 system 消息保留原顺序作为 `messages`。`reasoningEffort` 被忽略（Anthropic 协议无此字段）。

### 必填请求头

- `anthropic-version: 2023-06-01`（由 `AnthropicProvider` 工厂自动注入到 `defaultHeaders`）
- `x-api-key: <apiKey>`（由 `RAC.buildHeaders` 在 `ANTHROPIC` 分支注入，**非** Bearer）

### 响应结构（`AnthropicResponse`）

```json
{
  "id": "msg_1",
  "model": "claude-3-5-sonnet-20241022",
  "content": [
    { "type": "text", "text": "答案" },
    { "type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": "{\"city\":\"北京\"}" }
  ],
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 10, "output_tokens": 5 }
}
```

`content` 为密封类 `ContentBlock` 列表，两种子类型：

| 子类型 | 序列化名 | 关键字段 |
| --- | --- | --- |
| `Text` | `text` | `text` |
| `ToolUse` | `tool_use` | `id` / `name` / `input` |

### 流式事件（`AnthropicStreamEvent`）

Anthropic 流式协议有 6 种事件类型（密封类），按顺序触发：

| 事件子类 | 序列化名 | 说明 |
| --- | --- | --- |
| `MessageStart` | `message_start` | 消息开始，含完整 `AnthropicResponse`（无 content） |
| `ContentBlockStart` | `content_block_start` | 内容块开始，含 `index` 与 `contentBlock` |
| `ContentBlockDelta` | `content_block_delta` | 内容块增量，`delta` 为 `TextDelta` 或 `InputJsonDelta` |
| `ContentBlockStop` | `content_block_stop` | 内容块结束 |
| `MessageDelta` | `message_delta` | 消息级增量，含 `stopReason` 与 `usage` |
| `MessageStop` | `message_stop` | 消息结束 |

`Delta` 密封类子类型：`TextDelta`（`text_delta`，含 `text`）与 `InputJsonDelta`（`input_json_delta`，含 `partialJson`，用于工具调用参数增量）。

### 映射到 AIMessage

`AnthropicResponse.toAIMessage()`（见 `Mappers.kt`）：

- `content` ← `content` 中所有 `Text` 块的 `text` 拼接
- `toolCalls` ← `content` 中所有 `ToolUse` 块映射为 `ToolCall(id, name, arguments=input)`
- `usage` ← `AnthropicUsage` 映射为 `Usage`（`promptTokens = inputTokens`，`completionTokens = outputTokens`，`totalTokens = inputTokens + outputTokens`）
- `finishReason` ← `stopReason.toFinishReason()`（`end_turn` → `STOP`，`max_tokens` → `LENGTH`，`tool_use` → `TOOL_CALLS`）

---

## 统一 AIMessage 模型

三种协议响应经 `Mappers.kt` 中的扩展函数归一化为 `AIMessage`：

```kotlin
data class AIMessage(
    val content: String,                       // 正文文本
    val reasoningContent: String? = null,      // 推理过程（仅 Completions 推理模型）
    val toolCalls: List<ToolCall> = emptyList(),// 工具调用
    val usage: Usage? = null,                  // token 用量
    val finishReason: FinishReason = UNKNOWN,  // 结束原因枚举
    val rawResponse: String? = null,           // 原始响应字符串（当前为 null）
)
```

| 协议 | content 来源 | toolCalls 来源 | reasoningContent | finishReason |
| --- | --- | --- | --- | --- |
| Completions | `message.content` | `message.toolCalls` | `message.reasoningContent` | `finishReason` 映射 |
| Responses | `MessageOutput.content` 文本拼接 | `FunctionCallOutput` | 无 | 固定 `STOP` |
| Anthropic | `Text` 块拼接 | `ToolUse` 块 | 无 | `stopReason` 映射 |

`FinishReason` 枚举统一归一化各供应商的结束原因字符串（见 `String?.toFinishReason()`）：

| 协议原值 | FinishReason |
| --- | --- |
| `stop` / `end_turn` / `stop_sequence` | `STOP` |
| `length` / `max_tokens` | `LENGTH` |
| `tool_calls` / `tool_use` | `TOOL_CALLS` |
| `content_filter` | `CONTENT_FILTER` |
| `null` / 未知值 | `UNKNOWN` |

---

## 何时用 chat { } / respond { } / anthropicStream { }

### chat { } — 通用非流式入口

适用场景：希望按供应商默认协议自动路由，不关心协议差异。RAC 根据 `defaultProvider.defaultApiType` 分发：

- Completions 供应商（DeepSeek/Kimi/GLM 等）→ `/chat/completions`
- Anthropic 供应商 → `/messages`
- Responses 供应商 → `/responses`

**推荐作为默认非流式调用方式**，除非需要 Responses API 的 `instructions` 等专属特性。

### respond { } — 显式 Responses API

适用场景：需要 OpenAI Responses API 的 `instructions` 系统指令字段、`max_output_tokens` 命名，或 Responses 专属输出结构。**仅 OpenAI 官方供应商可用**，其他供应商调用会因端点不存在而失败。

`respond { }` 使用独立的 `RespondRequestBuilder`，提供 `input` / `instructions` / `model` / `temperature` / `maxOutputTokens` / `tools` 字段，不维护消息列表。

### anthropicStream { } — Anthropic 流式

适用场景：Anthropic 供应商需要流式输出。由于 Anthropic 流式事件类型（`message_start` / `content_block_delta` 等）与 Completions/Responses 互不兼容，需独立流式入口保证类型安全。

- Completions 流式 → `chatStream { }`，返回 `Flow<CompletionsStreamChunk>`
- Anthropic 流式 → `anthropicStream { }`，返回 `Flow<AnthropicStreamEvent>`
- Responses 流式 → `respondStream { }`，返回 `Flow<ResponsesStreamEvent>`

> **协议不匹配时的快速失败**：在 Anthropic 供应商上调用 `chatStream { }` 会抛 `RACException("chatStream requires a Completions API provider; use anthropicStream for Anthropic")`，反之亦然。这是运行期类型安全保护，调用方应按供应商协议选择正确的流式方法。
