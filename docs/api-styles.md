# API 协议详解

RAC 将 11 家 LLM 供应商归纳为 3 种 API 协议，由 `ApiType` 枚举标识：

- **Completions API**（OpenAI Chat Completions 风格）— 被 10 家 OpenAI 兼容供应商共用
- **Responses API**（OpenAI Responses 风格）— 当前仅 OpenAI 官方支持
- **Anthropic API**（Anthropic Messages 风格）— Anthropic 原生使用

三种协议共用统一的 `AIMessage` 返回模型与 `Flow<StreamEvent>` 流式事件模型，调用方无需关心协议差异。本文档说明各协议的字段映射、请求/响应结构、流式事件，以及如何选用 `chat { }` / `respond { }` / `anthropicStream { }`。

## 协议路由总览

| Llm 方法 | 协议 | URL 后缀 | 返回类型 | 流式 |
| --- | --- | --- | --- | --- |
| `chat { }` | 按默认供应商 `defaultApiType` 路由 | `/chat/completions` 或 `/messages` 或 `/responses` | `AIMessage` | 否 |
| `chatStream { }` | Completions | `/chat/completions` | `Flow<StreamEvent>` | 是 |
| `anthropicStream { }` | Anthropic | `/messages` | `Flow<StreamEvent>` | 是 |
| `respond { }` | Responses | `/responses` | `AIMessage` | 否 |
| `respondStream { }` | Responses | `/responses` | `Flow<StreamEvent>` | 是 |

> **统一流式事件**：三个流式方法（`chatStream` / `anthropicStream` / `respondStream`）均返回 `Flow<StreamEvent>`。底层原始事件（`CompletionsStreamChunk` / `AnthropicStreamEvent` / `ResponsesStreamEvent`）经 `StreamAggregator` 聚合为统一的 `StreamEvent`（`TextDelta` / `ReasoningDelta` / `ToolCallDelta` / `Done`），屏蔽协议差异。

`chat { }` 是唯一的自动路由入口，按 `defaultProvider.defaultApiType` 分发到三种客户端之一。其余方法显式绑定协议，协议不匹配时抛 `RACException`。

## 鉴权头策略

鉴权头由 `Llm.buildHeaders(provider)` 按供应商 `defaultApiType` 动态注入：

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
| `stop` | `stop` | `List<String>?` | 停止序列，模型生成到任一字符串时停止 |
| `seed` | `seed` | `Long?` | 随机种子，用于确定性输出 |

> 序列化时 `encodeDefaults = false`，`null` 字段会被省略，避免发送无意义字段。

### `enableThinking` 在 Completions 协议上的差异化处理

`enableThinking` 不直接序列化到请求体（Completions API 无此字段），而是通过 `resolveReasoningEffortForCompletions` 间接体现为 `reasoningEffort`：

| `enableThinking` | `reasoningEffort` 显式设置 | 最终 `reasoningEffort` |
| --- | --- | --- |
| `true` | 未设置 | 自动设为 `"medium"` |
| `true` | 已设置 | 保留显式值 |
| `false` | 任意 | 强制 `null`（禁用推理） |
| `null` | 未设置 | 沿用 ModelConfig 或服务端默认 |
| `null` | 已设置 | 保留显式值 |

### 响应结构（`CompletionsResponse`）

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
| `choices[0].message.reasoningContent` | `String?` | 推理过程（DeepSeek 扩展字段） |
| `choices[0].message.toolCalls` | `List<ToolCallResponse>?` | 工具调用列表 |
| `choices[0].finishReason` | `String?` | 结束原因（`stop` / `length` / `tool_calls` / `content_filter`） |
| `usage` | `Usage?` | token 用量 |

### 流式事件（`CompletionsStreamChunk`）

流式响应为 SSE，每行 `data: <json>`。首个 chunk 的 `delta` 含 `role`，后续 chunk 含 `content` 增量，最后 chunk 可能含 `finish_reason` 与 `usage`。遇到 `data: [DONE]` 时流结束。

```json
{"id":"1","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}
{"id":"1","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}
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

### 流式聚合（`toCompletionsStreamEvents`）

`CompletionsClient.stream(...)` 返回 `Flow<CompletionsStreamChunk>`，经 `toCompletionsStreamEvents` 扩展函数聚合为 `Flow<StreamEvent>`：

- `delta.content` → `StreamEvent.TextDelta(delta, accumulated)`
- `delta.reasoningContent` → `StreamEvent.ReasoningDelta(delta, accumulated)`
- `delta.toolCalls` → `StreamEvent.ToolCallDelta(index, id, name, argumentsDelta, argumentsAccumulated)`（自动聚合碎片）
- 流结束 → `StreamEvent.Done(content, reasoningContent, toolCalls, usage, finishReason, rawResponse)`（字段平铺，自包含）

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
| `seed` | `seed` | `Long?` | 随机种子 |

> Responses API 的 `input` 为字符串而非消息列表。`chat { }` 在 RESPONSES 分支会调用 `ChatRequestBuilder.buildResponses()`，优先取最后一条 `UserMessage` 文本作为 `input`。
>
> **`stop` 与 `enableThinking` 不支持**：Responses 协议无对应字段，`buildResponses` 静默忽略这两个参数。

### 响应结构（`ResponsesResponse`）

```json
{
  "id": "resp_1",
  "model": "gpt-5.5",
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
| `Error Event` | `error` | 错误 |

### 映射到 AIMessage

`ResponsesResponse.toAIMessage()`（见 `Mappers.kt`）：

- `content` ← `output` 中所有 `MessageOutput` 的 `content` 文本拼接
- `toolCalls` ← `output` 中所有 `FunctionCallOutput` 映射为 `ToolCall`（`id` 取 `callId` 或 `id`）
- `usage` ← `usage`（直接透传）
- `finishReason` ← 固定 `STOP`（Responses API 无显式结束原因）

### 流式聚合（`toResponsesStreamEvents`）

`ResponsesClient.stream(...)` 返回 `Flow<ResponsesStreamEvent>`，经 `toResponsesStreamEvents` 扩展函数聚合为 `Flow<StreamEvent>`，与 Completions 流式事件格式一致。

---

## 三、Anthropic API（Anthropic Messages）

Anthropic Messages 风格接口，端点为 `/messages`。仅 Anthropic 原生使用。协议与 OpenAI 差异较大：system 为顶层字段、`max_tokens` 必填、响应为 content blocks 结构、思考通过 `thinking` 对象控制。

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
| `stopSequences` | `stop_sequences` | `List<String>?` | 停止序列 |
| `thinking` | `thinking` | `AnthropicThinking?` | 思考对象，`enableThinking=true` 时构造 |

> `ChatRequestBuilder.buildAnthropic()` 会用 `filterIsInstance<SystemMessage>` 抽取系统消息并以换行拼接为 `system` 字符串；非 system 消息保留原顺序作为 `messages`。`reasoningEffort` 被忽略（Anthropic 协议无此字段，思考行为通过 `thinking` 对象控制）。

### `AnthropicThinking` 数据类

```kotlin
@Serializable
data class AnthropicThinking(
    val type: String,                          // "enabled"
    @SerialName("budget_tokens") val budgetTokens: Long,
)
```

`enableThinking = true` 且 `maxTokens > 1` 时构造：

```
AnthropicThinking(
    type = "enabled",
    budgetTokens = (maxTokens * 4 / 5).coerceIn(1L, maxTokens - 1)
)
```

`budget_tokens` 取 `maxTokens` 的 4/5，确保 `1 <= budget < maxTokens`（API 约束 `budget_tokens < max_tokens`），同时留 20% 给正文输出。

| `enableThinking` | 最终 `thinking` 字段 |
| --- | --- |
| `true`（且 maxTokens > 1） | `{type:"enabled", budget_tokens: maxTokens*4/5}` |
| `true`（且 maxTokens ≤ 1） | `null`（无法满足 budget < maxTokens 约束） |
| `false` | `null`（不发送 thinking 字段，等价于禁用） |
| `null` | `null`（沿用默认行为，不发送 thinking） |

### 必填请求头

- `anthropic-version: 2023-06-01`（由 `AnthropicProvider` 工厂自动注入到 `defaultHeaders`）
- `x-api-key: <apiKey>`（由 `Llm.buildHeaders` 在 `ANTHROPIC` 分支注入，**非** Bearer）

### 响应结构（`AnthropicResponse`）

```json
{
  "id": "msg_1",
  "model": "claude-opus-4-1",
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

`Delta` 密封类子类型：`TextDelta`（`text_delta`，含 `text`）、`ThinkingDelta`（`thinking_delta`，含 `thinking`，用于扩展思考过程）、`InputJsonDelta`（`input_json_delta`，含 `partialJson`，用于工具调用参数增量）。

### 映射到 AIMessage

`AnthropicResponse.toAIMessage()`（见 `Mappers.kt`）：

- `content` ← `content` 中所有 `Text` 块的 `text` 拼接
- `toolCalls` ← `content` 中所有 `ToolUse` 块映射为 `ToolCall(id, name, arguments=input)`
- `usage` ← `AnthropicUsage` 映射为 `Usage`（`promptTokens = inputTokens`，`completionTokens = outputTokens`，`totalTokens = inputTokens + outputTokens`）
- `finishReason` ← `stopReason.toFinishReason()`（`end_turn` → `STOP`，`max_tokens` → `LENGTH`，`tool_use` → `TOOL_CALLS`）

### 流式聚合（`toAnthropicStreamEvents`）

`AnthropicClient.stream(...)` 返回 `Flow<AnthropicStreamEvent>`，经 `toAnthropicStreamEvents` 扩展函数聚合为 `Flow<StreamEvent>`，与 Completions 流式事件格式一致。`thinking_delta` 事件聚合为 `StreamEvent.ReasoningDelta`。

---

## 统一 AIMessage 模型

三种协议响应经 `Mappers.kt` 中的扩展函数归一化为 `AIMessage`：

```kotlin
@Serializable
data class AIMessage(
    val content: String,                        // 正文文本
    val reasoningContent: String? = null,       // 推理过程（仅推理模型）
    val toolCalls: List<ToolCall> = emptyList(),// 工具调用
    val usage: Usage? = null,                   // token 用量
    val finishReason: FinishReason = UNKNOWN,   // 结束原因枚举
    val rawResponse: String? = null,            // 原始响应字符串（当前为 null）
)
```

| 协议 | content 来源 | toolCalls 来源 | reasoningContent | finishReason |
| --- | --- | --- | --- | --- |
| Completions | `message.content` | `message.toolCalls` | `message.reasoningContent` | `finishReason` 映射 |
| Responses | `MessageOutput.content` 文本拼接 | `FunctionCallOutput` | 无 | 固定 `STOP` |
| Anthropic | `Text` 块拼接 | `ToolUse` 块 | `thinking_delta` 聚合（流式） | `stopReason` 映射 |

`FinishReason` 枚举统一归一化各供应商的结束原因字符串（见 `String?.toFinishReason()`）：

| 协议原值 | FinishReason |
| --- | --- |
| `stop` / `end_turn` / `stop_sequence` | `STOP` |
| `length` / `max_tokens` | `LENGTH` |
| `tool_calls` / `tool_use` | `TOOL_CALLS` |
| `content_filter` | `CONTENT_FILTER` |
| `null` / 未知值 | `UNKNOWN` |

---

## 统一 StreamEvent 模型

三种协议的流式响应经 `StreamAggregator` 聚合为统一的 `StreamEvent` 密封接口：

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

`Done` 事件是**自包含**的——所有字段平铺在顶层，调用方无需嵌套访问或拼接增量：

```kotlin
ai.chatStream { user("你好") }.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)      // 增量文本
        is StreamEvent.ReasoningDelta -> print(event.delta) // 增量推理
        is StreamEvent.ToolCallDelta -> { /* 工具调用碎片 */ }
        is StreamEvent.Done -> {
            // 流结束——直接访问完整结果
            println(event.content)
            println(event.finishReason)
            event.toolCalls.forEach { /* ... */ }
            // 也可转为 AIMessage：event.toAIMessage()
        }
    }
}
```

三个聚合函数位于 `StreamAggregator.kt`，函数名反映 API 来源（避免 JVM 泛型擦除签名冲突，且 KMP 全平台兼容）：

| 函数 | 输入 | 输出 |
| --- | --- | --- |
| `Flow<CompletionsStreamChunk>.toCompletionsStreamEvents()` | Completions 原始 chunk | `Flow<StreamEvent>` |
| `Flow<AnthropicStreamEvent>.toAnthropicStreamEvents()` | Anthropic 原始事件 | `Flow<StreamEvent>` |
| `Flow<ResponsesStreamEvent>.toResponsesStreamEvents()` | Responses 原始事件 | `Flow<StreamEvent>` |

---

## 定制化参数的协议适配

`stop` / `seed` / `enableThinking` 三个参数在三种协议上的处理方式不同：

| 参数 | Completions | Anthropic | Responses |
| --- | --- | --- | --- |
| `stop` | 序列化为 `stop` | 序列化为 `stop_sequences` | **不支持**，静默忽略 |
| `seed` | 序列化为 `seed` | **不支持**，静默忽略 | 序列化为 `seed` |
| `enableThinking` | 与 `reasoningEffort` 互斥（true 且未显式设 → `"medium"`；false → `null`） | 构造 `thinking={type:"enabled", budget_tokens:maxTokens*4/5}` | **不支持**，静默忽略 |

参数解析采用分层回退：**builder 显式值 → ModelConfig 默认值 → 服务端默认**。这意味着：

- 在 `models { model("xxx") { enableThinking = true } }` 注册的模型，每次调用自动启用思考
- 在 `chat { enableThinking = false }` 显式覆盖时，禁用思考
- 既不在 builder 也不在 ModelConfig 设置时，沿用服务端默认行为

---

## 何时用 chat { } / respond { } / anthropicStream { }

### chat { } — 通用非流式入口

适用场景：希望按供应商默认协议自动路由，不关心协议差异。Llm 根据 `defaultProvider.defaultApiType` 分发：

- Completions 供应商（DeepSeek/Kimi/GLM 等）→ `/chat/completions`
- Anthropic 供应商 → `/messages`
- Responses 供应商 → `/responses`

**推荐作为默认非流式调用方式**，除非需要 Responses API 的 `instructions` 等专属特性。

### respond { } — 显式 Responses API

适用场景：需要 OpenAI Responses API 的 `instructions` 系统指令字段、`max_output_tokens` 命名，或 Responses 专属输出结构。**仅 OpenAI 官方供应商可用**，其他供应商调用会因端点不存在而失败。

`respond { }` 使用独立的 `RespondRequestBuilder`，提供 `input` / `instructions` / `model` / `temperature` / `maxOutputTokens` / `tools` / `seed` 字段，不维护消息列表。

### chatStream { } / anthropicStream { } / respondStream { } — 协议显式流式

三种流式方法均返回 `Flow<StreamEvent>`，但分别绑定不同协议：

- Completions 流式 → `chatStream { }`
- Anthropic 流式 → `anthropicStream { }`
- Responses 流式 → `respondStream { }`

> **协议不匹配时的快速失败**：在 Anthropic 供应商上调用 `chatStream { }` 会抛 `RACException("chatStream requires a Completions API provider; use anthropicStream for Anthropic")`，反之亦然。这是运行期类型安全保护，调用方应按供应商协议选择正确的流式方法。
