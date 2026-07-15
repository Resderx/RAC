**English** | [中文](usage.md)

# API Reference

This document is the complete signature reference for every public function, class, and property in the RAC library. It is organized by functional module; each API entry includes its signature, parameter description, and a usage example. If you are new to RAC, start with the [Quickstart](quickstart.en.md).

## Table of Contents

- [1. DSL Entry](#1-dsl-entry)
    - [1.1 llm { }](#11-llm--)
    - [1.2 LlmBuilder](#12-llmbuilder)
    - [1.3 RetryPolicy](#13-retrypolicy)
- [2. Provider DSL](#2-provider-dsl)
    - [2.1 providers { } and provider extensions](#21-providers--and-provider-extensions)
    - [2.2 ProviderDsl](#22-providerdsl)
- [3. Model Registration](#3-model-registration)
    - [3.1 ModelsBuilder.model()](#31-modelsbuildermodel)
    - [3.2 ModelBuilder](#32-modelbuilder)
    - [3.3 ModalitiesBuilder](#33-modalitiesbuilder)
    - [3.4 ModelConfig](#34-modelconfig)
    - [3.5 Modality](#35-modality)
- [4. Model Preset Enums](#4-model-preset-enums)
    - [4.1 ModelPreset interface](#41-modelpreset-interface)
    - [4.2 Ten provider enums](#42-ten-provider-enums)
- [5. Llm class](#5-llm-class)
    - [5.1 chat { }](#51-chat--)
    - [5.2 chatWithTools { }](#52-chatwithtools--)
    - [5.3 chatStream { }](#53-chatstream--)
    - [5.4 anthropicStream { }](#54-anthropicstream--)
    - [5.5 respond { }](#55-respond--)
    - [5.6 respondStream { }](#56-respondstream--)
    - [5.7 provider()](#57-provider)
- [6. ChatRequestBuilder (inside chat { })](#6-chatrequestbuilder-inside-chat--)
- [7. RespondRequestBuilder (inside respond { })](#7-respondrequestbuilder-inside-respond--)
- [8. Agent API](#8-agent-api)
    - [8.1 agent { }](#81-agent--)
    - [8.2 AgentBuilder](#82-agentbuilder)
    - [8.3 Agent class](#83-agent-class)
    - [8.4 Session class](#84-session-class)
- [9. Tool Definition](#9-tool-definition)
    - [9.1 tool<Args>() strongly typed](#91-toolargs-strongly-typed)
    - [9.2 tool() spread parameters](#92-tool-spread-parameters)
    - [9.3 param()](#93-param)
    - [9.4 execute() arity overloads](#94-execute-arity-overloads)
- [10. Message Types](#10-message-types)
    - [10.1 Message sealed interface](#101-message-sealed-interface)
    - [10.2 Content sealed interface](#102-content-sealed-interface)
    - [10.3 AIMessage](#103-aimessage)
    - [10.4 ToolCall / ToolDefinition](#104-toolcall--tooldefinition)
    - [10.5 Usage / FinishReason](#105-usage--finishreason)
- [11. StreamEvent](#11-streamevent)
- [12. MCP Extension](#12-mcp-extension)
- [13. ACP Extension](#13-acp-extension)
- [14. A2A Extension](#14-a2a-extension)
- [15. Exception Types](#15-exception-types)

---

## 1. DSL Entry

### 1.1 `llm { }`

Top-level DSL entry function; creates and builds an `Llm` instance.

```kotlin
package top.resderx.rac.dsl

inline fun llm(block: LlmBuilder.() -> Unit): Llm
```

**Parameters**:

- `block: LlmBuilder.() -> Unit` — configuration block; register providers etc. inside the `LlmBuilder` scope

**Returns**: the built `Llm` instance

**Throws**: `RACException` — when no provider is registered inside the block

**Example**:

```kotlin
val ai = llm {
    defaultProviderName = "deepseek"
    timeoutMillis = 30_000L
    providers {
        deepseek {
            apiKey("sk-...")
            models { model("deepseek-v4-flash") }
        }
    }
}
```

### 1.2 LlmBuilder

Receiver of the `llm { }` block; carries global config and provider registration entry points.

```kotlin
@RacDslMarker
class LlmBuilder {
    var defaultProviderName: String? = null
    var timeoutMillis: Long? = null
    var retryPolicy: RetryPolicy? = null

    fun providers(block: ProvidersBuilder.() -> Unit)
    fun build(): Llm
}
```

**Properties**:

| Property               | Type             | Default | Description                                                  |
|------------------------|------------------|---------|--------------------------------------------------------------|
| `defaultProviderName`  | `String?`        | null    | Default provider name; overrides the "first-registered is default" rule |
| `timeoutMillis`        | `Long?`          | null    | HttpClient timeout in milliseconds; null uses the default 60s |
| `retryPolicy`          | `RetryPolicy?`   | null    | Retry policy; null uses the default `RetryPolicy()`          |

**Methods**:

| Method                                              | Description                                                |
|-----------------------------------------------------|------------------------------------------------------------|
| `fun providers(block: ProvidersBuilder.() -> Unit)` | Entry to the providers block; register providers one by one |
| `fun build(): Llm`                                  | Builds the immutable `Llm` instance (usually called by `llm { }` automatically) |

### 1.3 RetryPolicy

Retry policy data class; defines automatic retry behavior for transient network errors.

```kotlin
package top.resderx.rac.network

data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val backoffMultiplier: Double = 2.0,
    val retryableStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
) {
    fun isRetryableStatus(statusCode: Int): Boolean
}
```

**Properties**:

| Property               | Default                  | Description                          |
|------------------------|--------------------------|--------------------------------------|
| `maxRetries`           | 3                        | Max retry count (excluding the first attempt) |
| `initialDelayMillis`   | 1000                     | First retry delay in milliseconds    |
| `maxDelayMillis`       | 30000                    | Max retry delay in milliseconds      |
| `backoffMultiplier`    | 2.0                      | Exponential backoff multiplier       |
| `retryableStatusCodes` | 408/429/500/502/503/504  | Retryable HTTP status codes          |

**Example**:

```kotlin
val ai = llm {
    retryPolicy = RetryPolicy(maxRetries = 5, initialDelayMillis = 500)
    providers { /* ... */ }
}
```

---

## 2. Provider DSL

### 2.1 `providers { }` and provider extensions

The receiver of the `providers { }` block is `ProvidersBuilder`; each provider is registered via its dedicated extension function:

```kotlin
package top.resderx.rac.dsl

fun ProvidersBuilder.deepseek(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.openai(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.anthropic(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.gemini(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.qwen(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.glm(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.kimi(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.doubao(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.minimax(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.mimo(block: ProviderDsl.() -> Unit)
fun ProvidersBuilder.ollama(block: ProviderDsl.() -> Unit)
```

**Note**: Each extension configures the provider's connection info and models inside the `ProviderDsl` scope. The first registered provider becomes the default (override with `defaultProviderName`).

**Example**:

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models { model(DeepSeekModel.V4_FLASH) }
        }
        openai {
            apiKey("sk-...")
            baseUrl("https://api.openai.com/v1")  // optional, overrides default
            models { model(OpenAIModel.GPT_5_4) }
        }
    }
}
```

### 2.2 ProviderDsl

Provider DSL scope; configures connection info and model registration.

```kotlin
package top.resderx.rac.dsl

@RacDslMarker
class ProviderDsl {
    fun apiKey(key: String?)
    fun baseUrl(url: String?)
    fun header(name: String, value: String)
    fun headers(headers: Map<String, String>)
    fun models(block: ModelsBuilder.() -> Unit)
}
```

**Methods**:

| Method                          | Description                                            |
|---------------------------------|--------------------------------------------------------|
| `fun apiKey(key: String?)`      | Set the API key; passing null keeps the provider default |
| `fun baseUrl(url: String?)`     | Set baseUrl, overriding the provider default          |
| `fun header(name, value)`       | Append a single extra request header                  |
| `fun headers(headers)`          | Append a batch of extra request headers               |
| `fun models(block)`             | Entry to the models block; register models inside the lambda |

---

## 3. Model Registration

### 3.1 `ModelsBuilder.model()`

Register a model inside the `models { }` block; two overloads:

```kotlin
package top.resderx.rac.dsl

@RacDslMarker
class ModelsBuilder internal constructor() {
    // overload 1: specify the model name manually
    fun model(name: String, block: ModelBuilder.() -> Unit = {})

    // overload 2: read model name and recommended config from a preset enum
    fun model(preset: ModelPreset, block: ModelBuilder.() -> Unit = {})

    internal fun build(): Map<String, ModelConfig>
}
```

**Overload 1**: `model(name, block)` — specify the model name and config manually. The first registered model becomes the provider's default.

**Overload 2**: `model(preset, block)` — read `modelName` and `recommendedConfig` from the preset enum as initial values; the block can override individual fields.

**Example**:

```kotlin
models {
    // overload 1: manual registration
    model("deepseek-v4-flash") {
        maxTokens = 4096
        temperature = 0.7
    }

    // overload 2: preset registration (fully use the preset)
    model(DeepSeekModel.V4_FLASH)

    // overload 2: preset registration + override some fields
    model(DeepSeekModel.V4_PRO) {
        maxTokens = 4096  // override preset's 8192; keep the rest
    }
}
```

### 3.2 ModelBuilder

DSL builder for model config, used inside `model("xxx") { }` or `model(preset) { }`.

```kotlin
@RacDslMarker
class ModelBuilder {
    var maxTokens: Long? = null
    var temperature: Double? = null
    var topP: Double? = null
    var systemPrompt: String? = null
    var reasoningEffort: String? = null
    var stop: List<String>? = null
    var seed: Long? = null
    var enableThinking: Boolean? = null

    fun modalities(block: ModalitiesBuilder.() -> Unit)

    internal constructor(initial: ModelConfig)  // initialize from preset
    internal constructor()

    internal fun build(): ModelConfig
}
```

**Properties**:

| Property          | Type              | Description                                                         |
|-------------------|-------------------|---------------------------------------------------------------------|
| `maxTokens`       | `Long?`           | Max generation tokens                                               |
| `temperature`     | `Double?`         | Sampling temperature                                                |
| `topP`            | `Double?`         | Nucleus sampling parameter                                          |
| `systemPrompt`    | `String?`         | Model-specific system prompt (overridable via `chat { system() }`)  |
| `reasoningEffort` | `String?`         | Reasoning effort (`"low"` / `"medium"` / `"high"`); reasoning models only |
| `stop`            | `List<String>?`   | Stop sequences                                                      |
| `seed`            | `Long?`           | Random seed                                                         |
| `enableThinking`  | `Boolean?`        | Thinking switch; true enables extended thinking                     |

**Methods**:

| Method                                       | Description                                                       |
|----------------------------------------------|-------------------------------------------------------------------|
| `fun modalities(block: ModalitiesBuilder.() -> Unit)` | Declares supported input modalities via the `modalities { }` DSL block |

### 3.3 ModalitiesBuilder

Builder used inside `model("xxx") { modalities { ... } }` to declare input modalities.

```kotlin
@RacDslMarker
class ModalitiesBuilder internal constructor() {
    fun text()    // declare TEXT modality
    fun image()   // declare IMAGE modality
    fun audio()   // declare AUDIO modality

    internal fun build(): Set<Modality>
}
```

**Example**:

```kotlin
model("gpt-5.5") {
    modalities {
        image()  // supports image input
        audio()  // supports audio input
    }
}
```

### 3.4 ModelConfig

Immutable model config data class, produced by `ModelBuilder.build()`.

```kotlin
package top.resderx.rac.providers

data class ModelConfig(
    val maxTokens: Long? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val systemPrompt: String? = null,
    val reasoningEffort: String? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
    val enableThinking: Boolean? = null,
    val modalities: Set<Modality> = emptySet(),
)
```

**Note**: All `null` fields mean "no model-level override"; server defaults apply. An empty `modalities` set means "undeclared" (caller decides).

### 3.5 Modality

Enum of input modalities a model can accept.

```kotlin
package top.resderx.rac.providers

enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
}
```

---

## 4. Model Preset Enums

### 4.1 ModelPreset interface

Per-provider enum classes implement this interface uniformly.

```kotlin
package top.resderx.rac.providers.presets

interface ModelPreset {
    val modelName: String
    val recommendedConfig: ModelConfig
}
```

**Properties**:

- `modelName: String` — model identifier, corresponds to the `model` field of the API request body
- `recommendedConfig: ModelConfig` — recommended model config with parameters tuned for that model

### 4.2 Ten provider enums

All in package `top.resderx.rac.providers.presets`, totaling 43 models:

#### DeepSeekModel (2)

| Enum item   | modelName           | Recommended config                                                                        |
|-------------|---------------------|-------------------------------------------------------------------------------------------|
| `V4_PRO`    | `deepseek-v4-pro`   | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true, TEXT only   |
| `V4_FLASH`  | `deepseek-v4-flash` | maxTokens=8192, temperature=0.0, reasoningEffort="medium", TEXT only                      |

#### OpenAIModel (4)

| Enum item       | modelName      | Recommended config                                                                              |
|-----------------|----------------|-------------------------------------------------------------------------------------------------|
| `GPT_5_5`       | `gpt-5.5`      | maxTokens=16384, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE+AUDIO |
| `GPT_5_4`       | `gpt-5.4`      | maxTokens=16384, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE+AUDIO |
| `GPT_5_4_MINI`  | `gpt-5.4-mini` | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                     |
| `GPT_5_4_NANO`  | `gpt-5.4-nano` | maxTokens=4096, temperature=0.7, TEXT+IMAGE                                                     |

#### AnthropicModel (4)

| Enum item            | modelName                  | Recommended config                                                       |
|----------------------|----------------------------|--------------------------------------------------------------------------|
| `CLAUDE_OPUS_4_1`    | `claude-opus-4-1`          | maxTokens=16384, temperature=0.0, enableThinking=true, TEXT+IMAGE        |
| `CLAUDE_SONNET_4_6`  | `claude-sonnet-4-6`        | maxTokens=8192, temperature=0.0, enableThinking=true, TEXT+IMAGE         |
| `CLAUDE_OPUS_4`      | `claude-opus-4-20250514`   | maxTokens=8192, temperature=0.0, TEXT+IMAGE                              |
| `CLAUDE_SONNET_4`    | `claude-sonnet-4-20250514` | maxTokens=8192, temperature=0.0, TEXT+IMAGE                              |

#### GeminiModel (2)

| Enum item  | modelName        | Recommended config                                                                              |
|------------|------------------|-------------------------------------------------------------------------------------------------|
| `PRO_3`    | `gemini-3-pro`   | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE+AUDIO  |
| `FLASH_3`  | `gemini-3-flash` | maxTokens=8192, temperature=0.7, TEXT+IMAGE+AUDIO                                               |

#### QwenModel (6)

| Enum item    | modelName              | Recommended config                                                                                |
|--------------|------------------------|---------------------------------------------------------------------------------------------------|
| `MAX_3_7`    | `qwen3.7-max-preview`  | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE          |
| `PLUS_3_7`   | `qwen3.7-plus-preview` | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |
| `MAX_3_6`    | `qwen3.6-max-preview`  | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE          |
| `PLUS_3_6`   | `qwen3.6-plus`         | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |
| `FLASH_3_6`  | `qwen3.6-flash`        | maxTokens=4096, temperature=0.7, TEXT+IMAGE                                                       |
| `MAX_FLASH`  | `qwen-max-flash`       | maxTokens=4096, temperature=0.7, TEXT+IMAGE                                                       |

#### GlmModel (4)

| Enum item        | modelName       | Recommended config                                                                                |
|------------------|-----------------|---------------------------------------------------------------------------------------------------|
| `GLM_5_2`        | `glm-5.2`       | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE          |
| `GLM_5_1`        | `glm-5.1`       | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |
| `GLM_5`          | `glm-5`         | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |
| `GLM_4_7_FLASH`  | `glm-4.7-flash` | maxTokens=4096, temperature=0.7, TEXT+IMAGE                                                       |

#### KimiModel (9)

| Enum item            | modelName                | Recommended config                                                                                |
|----------------------|--------------------------|---------------------------------------------------------------------------------------------------|
| `K2_5`               | `kimi-k2-5`              | maxTokens=8192, temperature=0.7, TEXT                                                             |
| `K2_0905`            | `kimi-k2-0905-preview`   | maxTokens=8192, temperature=0.7, TEXT                                                             |
| `K2_0711`            | `kimi-k2-0711-preview`   | maxTokens=8192, temperature=0.7, TEXT                                                             |
| `K2_TURBO`           | `kimi-k2-turbo-preview`  | maxTokens=8192, temperature=0.7, TEXT                                                             |
| `K2_THINKING`        | `kimi-k2-thinking`       | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true, TEXT               |
| `K2_THINKING_TURBO`  | `kimi-k2-thinking-turbo` | maxTokens=8192, temperature=0.0, reasoningEffort="medium", enableThinking=true, TEXT             |
| `V1_8K`              | `moonshot-v1-8k`         | maxTokens=8000, temperature=0.7, TEXT                                                             |
| `V1_32K`             | `moonshot-v1-32k`        | maxTokens=32000, temperature=0.7, TEXT                                                            |
| `V1_128K`            | `moonshot-v1-128k`       | maxTokens=128000, temperature=0.7, TEXT                                                           |

#### DoubaoModel (5)

| Enum item            | modelName                  | Recommended config                                                                                |
|----------------------|----------------------------|---------------------------------------------------------------------------------------------------|
| `SEED_2_1_PRO`       | `doubao-seed-2.1-pro`      | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT+IMAGE          |
| `SEED_1_6`           | `doubao-seed-1.6`          | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |
| `SEED_1_6_FLASH`     | `doubao-seed-1.6-flash`    | maxTokens=4096, temperature=0.7, TEXT+IMAGE                                                       |
| `SEED_1_6_THINKING`  | `doubao-seed-1.6-thinking` | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true, TEXT                |
| `SEED_1_6_VISION`    | `doubao-seed-1.6-vision`   | maxTokens=8192, temperature=0.7, TEXT+IMAGE                                                       |

#### MinimaxModel (3)

| Enum item  | modelName      | Recommended config                                                                                |
|------------|----------------|---------------------------------------------------------------------------------------------------|
| `ABAB7`    | `abab7`        | maxTokens=8192, temperature=0.7, TEXT                                                             |
| `M2_5`     | `MiniMax-M2.5` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT               |
| `M2`       | `MiniMax-M2`   | maxTokens=8192, temperature=0.7, TEXT                                                             |

#### MimoModel (2)

| Enum item    | modelName       | Recommended config                                                                                |
|--------------|-----------------|---------------------------------------------------------------------------------------------------|
| `V2_5_PRO`   | `MiMo-V2.5-Pro` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true, TEXT               |
| `V2_FLASH`   | `MiMo-V2-Flash` | maxTokens=4096, temperature=0.7, TEXT                                                             |

**Usage example**:

```kotlin
import top.resderx.rac.providers.presets.DeepSeekModel
import top.resderx.rac.providers.presets.OpenAIModel

models {
    model(DeepSeekModel.V4_FLASH)                       // fully use preset
    model(OpenAIModel.GPT_5_4) { maxTokens = 4096 }     // override some fields
}
```

---

## 5. Llm class

Top-level LLM entry class; holds all runtime dependencies and exposes the chat/respond family of methods.

```kotlin
package top.resderx.rac.dsl

class Llm(
    val httpClient: HttpClient,
    val registry: ProviderRegistry,
    val defaultProvider: ModelProvider,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val executor: RetryExecutor = RetryExecutor(RequestExecutor(httpClient), retryPolicy),
    val completionsClient: CompletionsClient = CompletionsClient(executor),
    val anthropicClient: AnthropicClient = AnthropicClient(executor),
    val responsesClient: ResponsesClient = ResponsesClient(executor),
)
```

### 5.1 `chat { }`

Non-streaming Chat call; routes to Completions/Anthropic/Responses based on the provider's `defaultApiType`.

```kotlin
suspend fun chat(block: ChatRequestBuilder.() -> Unit): AIMessage
```

**Parameter**: `block: ChatRequestBuilder.() -> Unit` — request builder block

**Returns**: a unified `AIMessage`

**Example**:

```kotlin
val resp = ai.chat {
    system("You are an assistant")
    user("Hello")
    temperature = 0.7
}
println(resp.content)
```

### 5.2 `chatWithTools { }`

Non-streaming Chat call with a tool-call loop. The framework automatically drives multi-round tool calls until the model no longer requests tools or `maxRounds` is reached.

```kotlin
suspend fun chatWithTools(
    maxRounds: Int = 10,
    toolExecutor: suspend (ToolCall) -> String,
    block: ChatRequestBuilder.() -> Unit,
): AIMessage
```

**Parameters**:

- `maxRounds: Int = 10` — max tool-call loop rounds; must be > 0, otherwise throws `IllegalArgumentException`
- `toolExecutor: suspend (ToolCall) -> String` — tool execution callback; receives a `ToolCall`, returns the result string
- `block: ChatRequestBuilder.() -> Unit` — request builder block (declare tools inside `tools { }`)

**Returns**: the final `AIMessage` (no tool calls or maxRounds reached)

**Example**:

```kotlin
val resp = ai.chatWithTools(
    maxRounds = 5,
    toolExecutor = { call ->
        when (call.name) {
            "get_weather" -> """{"city":"Beijing","temp":25}"""
            else -> "unknown"
        }
    },
) {
    user("Beijing weather")
    tools {
        tool("get_weather", "Look up weather") {
            param("city", "string", "City", required = true)
            // note: chatWithTools's toolExecutor handles execution; here only the schema is declared
        }
    }
}
```

### 5.3 `chatStream { }`

Streaming Chat call (Completions API only). Returns a cold `Flow<StreamEvent>`.

```kotlin
fun chatStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent>
```

**Throws**: `RACException` — when the provider's `defaultApiType` is not `COMPLETIONS`

**Example**:

```kotlin
ai.chatStream {
    user("Write a poem")
}.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.Done -> println("\nDone")
        else -> {}
    }
}
```

### 5.4 `anthropicStream { }`

Streaming Chat call (Anthropic API only).

```kotlin
fun anthropicStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent>
```

**Throws**: `RACException` — when the provider's `defaultApiType` is not `ANTHROPIC`

### 5.5 `respond { }`

Non-streaming Respond call (Responses API).

```kotlin
suspend fun respond(block: RespondRequestBuilder.() -> Unit): AIMessage
```

**Example**:

```kotlin
val resp = ai.respond {
    input("Explain quantum entanglement")
    instructions("You are a physicist")
}
```

### 5.6 `respondStream { }`

Streaming Respond call (Responses API).

```kotlin
fun respondStream(block: RespondRequestBuilder.() -> Unit): Flow<StreamEvent>
```

### 5.7 `provider()`

Get a registered provider by name.

```kotlin
fun provider(name: String): ModelProvider
```

**Parameter**: `name: String` — provider name (the name used at registration inside `providers { }`)

**Returns**: the corresponding `ModelProvider`

**Throws**: `RACException` — when `name` is not registered

---

## 6. ChatRequestBuilder (inside `chat { }`)

Receiver of `chat { }` / `chatStream { }` / `chatWithTools { }` / `anthropicStream { }`.

```kotlin
package top.resderx.rac.dsl

@RacDslMarker
class ChatRequestBuilder {
    // ===== runtime switching =====
    fun provider(name: String)
    fun model(name: String)

    // ===== add messages =====
    fun system(text: String)
    fun user(text: String)
    fun user(block: UserContentBuilder.() -> Unit)
    fun assistant(text: String)
    fun tool(id: String, content: String)

    // ===== tool declaration =====
    fun tools(block: ToolsBuilder.() -> Unit)
    fun addTools(tools: List<ToolDefinition>)

    // ===== customization parameters (var fields) =====
    var temperature: Double? = null
    var topP: Double? = null
    var maxTokens: Long? = null
    var reasoningEffort: String? = null
    var stop: List<String>? = null
    var seed: Long? = null
    var enableThinking: Boolean? = null
}
```

**Methods**:

| Method                                               | Description                                                       |
|------------------------------------------------------|-------------------------------------------------------------------|
| `fun provider(name: String)`                         | Switch to the specified provider at runtime                       |
| `fun model(name: String)`                            | Switch to the specified model at runtime                          |
| `fun system(text: String)`                           | Add a system message                                              |
| `fun user(text: String)`                             | Add a plain-text user message                                     |
| `fun user(block: UserContentBuilder.() -> Unit)`     | Add a user message built by `UserContentBuilder` (multimodal)     |
| `fun assistant(text: String)`                        | Add an assistant message                                          |
| `fun tool(id: String, content: String)`              | Add a tool result message (id is `ToolCall.id`)                   |
| `fun tools(block: ToolsBuilder.() -> Unit)`          | Declare the available toolset                                     |
| `fun addTools(tools: List<ToolDefinition>)`          | Append a batch of tool definitions (for MCP auto-injection)       |

**var fields** (override `ModelConfig` defaults; null means inherit):

| Field             | Type              | Description                                                       |
|-------------------|-------------------|-------------------------------------------------------------------|
| `temperature`     | `Double?`         | Sampling temperature                                              |
| `topP`            | `Double?`         | Nucleus sampling parameter                                        |
| `maxTokens`       | `Long?`           | Max generation tokens                                             |
| `reasoningEffort` | `String?`         | Reasoning effort                                                  |
| `stop`            | `List<String>?`   | Stop sequences                                                    |
| `seed`            | `Long?`           | Random seed                                                       |
| `enableThinking`  | `Boolean?`        | Thinking switch (see [enableThinking behavior](#enablethinking-behavior)) |

#### `enableThinking` behavior

| API         | enableThinking=true                                                | enableThinking=false         | null |
|-------------|-------------------------------------------------------------------|------------------------------|------|
| Completions | auto-sets `reasoningEffort="medium"` (when not explicitly set)    | forces `reasoningEffort=null`| noop |
| Anthropic   | builds `thinking={type:"enabled", budget_tokens:maxTokens*4/5}`   | does not send thinking field | not sent |
| Responses   | silently ignored                                                  | silently ignored             | noop |

#### UserContentBuilder (multimodal)

```kotlin
@RacDslMarker
class UserContentBuilder {
    fun text(text: String)
    fun image(url: String? = null, base64: String? = null, mimeType: String = "image/jpeg")
    fun audio(base64: String, mimeType: String = "audio/wav")
    internal fun build(): List<Content>
}
```

**Example**:

```kotlin
ai.chat {
    user {
        text("What's in this image?")
        image(url = "https://example.com/cat.jpg")
    }
}
```

**Full example**:

```kotlin
ai.chat {
    provider("openai")           // switch to OpenAI at runtime
    model("gpt-5.4")             // switch model
    system("You are a translator")
    user("Translate: Hello World")
    temperature = 0.3
    maxTokens = 1000
    stop = listOf("\n\n")
    seed = 42L
    enableThinking = true
}
```

---

## 7. RespondRequestBuilder (inside `respond { }`)

Receiver of `respond { }` / `respondStream { }` (Responses API only).

```kotlin
package top.resderx.rac.dsl

@RacDslMarker
class RespondRequestBuilder {
    var input: String = ""
    var instructions: String? = null
    var temperature: Double? = null
    var maxOutputTokens: Long? = null

    fun model(name: String)
    fun tools(block: ToolsBuilder.() -> Unit)
}
```

**Properties**:

| Property          | Type        | Description                       |
|-------------------|-------------|-----------------------------------|
| `input`           | `String`    | User input text                   |
| `instructions`    | `String?`   | System instructions               |
| `temperature`     | `Double?`   | Sampling temperature              |
| `maxOutputTokens` | `Long?`     | Max output tokens                 |

**Example**:

```kotlin
val resp = ai.respond {
    input("Summarize the following article: ...")
    instructions("You are a summarization assistant; output at most 100 words")
    model("gpt-5.4")
    temperature = 0.5
}
```

---

## 8. Agent API

### 8.1 `agent { }`

Agent DSL entry; declaratively configures the "LLM + prompts + tools" triad.

```kotlin
package top.resderx.rac.agent

inline fun agent(llm: Llm, block: AgentBuilder.() -> Unit): Agent
```

**Parameters**:

- `llm: Llm` — underlying LLM call entry
- `block: AgentBuilder.() -> Unit` — configuration block

**Returns**: the built `Agent` instance

### 8.2 AgentBuilder

```kotlin
@RacDslMarker
class AgentBuilder(val llm: Llm) {
    fun prompts(text: String)
    inline fun tools(block: ToolsScope.() -> Unit)
    fun maxRounds(n: Int)

    @PublishedApi
    internal fun build(): Agent
}
```

**Methods**:

| Method                                               | Description                                                                    |
|------------------------------------------------------|--------------------------------------------------------------------------------|
| `fun prompts(text: String)`                          | Set the system prompt (uses `prompts` instead of `system` to decouple from the underlying SystemMessage) |
| `inline fun tools(block: ToolsScope.() -> Unit)`     | Declare the toolset                                                            |
| `fun maxRounds(n: Int)`                              | Set the max tool-call loop rounds (must be > 0, otherwise throws `IllegalArgumentException`) |

**Example**:

```kotlin
val agent = agent(ai) {
    prompts("You are a weather assistant")
    maxRounds(5)
    tools {
        tool<WeatherArgs>("get_weather", "Look up weather") { args ->
            "${args.city} today sunny"
        }
    }
}
```

### 8.3 Agent class

Wraps LLM + system prompt + toolset; provides automatic multi-turn tool calls.

```kotlin
class Agent(
    val llm: Llm,
    val systemPrompt: String?,
    val tools: ToolRegistry,
    val maxRounds: Int = 10,
) {
    suspend fun run(session: Session, input: String): AIMessage
    fun runStream(session: Session, input: String): Flow<StreamEvent>
}
```

**Properties**:

| Property        | Type             | Description                                                   |
|-----------------|------------------|---------------------------------------------------------------|
| `llm`           | `Llm`            | Underlying LLM call entry                                     |
| `systemPrompt`  | `String?`        | System prompt, dynamically spliced per request (not stored in Session) |
| `tools`         | `ToolRegistry`   | Tool registry                                                 |
| `maxRounds`     | `Int`            | Max tool-call loop rounds; default 10                         |

**Methods**:

#### `run(session, input)`

Execute one Agent call — dynamically splice system, inject history, drive multi-turn tool calls, and record everything to the Session.

```kotlin
suspend fun run(session: Session, input: String): AIMessage
```

**Parameters**:

- `session: Session` — conversation history container (no system)
- `input: String` — the user input text for this round

**Returns**: the final `AIMessage`

**Flow**:

1. `session.addUser(input)` — append user input
2. Build request: dynamically splice system (not stored in session) + inject full history + inject tool definitions
3. Call the model; if it returns toolCalls, execute the tools, feed results back, continue the next round
4. Append the final result to the session

#### `runStream(session, input)`

Streaming Agent call; same semantics as `run` but returns events as an SSE stream.

```kotlin
fun runStream(session: Session, input: String): Flow<StreamEvent>
```

**Returns**: a cold `Flow<StreamEvent>` whose final event is `Done`

**Note**: Delta events from intermediate (tool-call) rounds are forwarded in real time; only the final round forwards `Done`.

### 8.4 Session class

Conversation history container; records full history (no system messages).

```kotlin
package top.resderx.rac.agent

class Session {
    val messages: List<Message>

    fun isEmpty(): Boolean
    fun addUser(text: String)
    fun addAssistant(aiMessage: AIMessage)
    fun addAssistantMessage(assistantMessage: AssistantMessage)
    fun addToolResult(toolCallId: String, content: String)
    fun clear()
}
```

**Properties**:

| Property    | Type              | Description                                                                  |
|-------------|-------------------|------------------------------------------------------------------------------|
| `messages`  | `List<Message>`   | Read-only snapshot of the current history (returns a new list each call); contains only user/assistant/tool messages |

**Methods**:

| Method                                                            | Description                                                       |
|-------------------------------------------------------------------|-------------------------------------------------------------------|
| `fun isEmpty(): Boolean`                                          | Whether the history is empty                                      |
| `fun addUser(text: String)`                                       | Append a plain-text user message                                  |
| `fun addAssistant(aiMessage: AIMessage)`                          | Append an assistant message mapped from AIMessage (empty content → null) |
| `fun addAssistantMessage(assistantMessage: AssistantMessage)`     | Append an AssistantMessage directly (for recording intermediate tool-call messages) |
| `fun addToolResult(toolCallId: String, content: String)`          | Append a tool result message                                      |
| `fun clear()`                                                     | Clear the conversation history                                    |

**Key design**:

- **No SystemMessage**: system is an Agent property; no `addSystem()` method is provided
- **Reusable across Agents**: different Agents (with different systemPrompts) can share the same Session
- **Records full history**: includes intermediate tool-call traffic (AssistantMessage + ToolMessage)

**Example**:

```kotlin
val session = Session()
agent.run(session, "Hello")
agent.run(session, "Another question")
println("Conversation rounds: ${session.messages.size / 2}")
```

---

## 9. Tool Definition

Tools are declared inside the `agent { tools { } }` block; two styles can be mixed.

### 9.1 `tool<Args>()` strongly typed

Auto-generates JSON Schema from a `@Serializable data class`.

```kotlin
inline fun <reified Args : Any> ToolsScope.tool(
    name: String,
    description: String,
    noinline handler: suspend (Args) -> String,
)
```

**Parameters**:

- `name: String` — tool name (used when the model calls it)
- `description: String` — tool description (helps the model decide when to call)
- `handler: suspend (Args) -> String` — tool execution callback; receives the auto-deserialized arguments and returns a result string

**Type parameter**: `Args : Any` — must be a `@Serializable` data class; the framework auto-generates JSON Schema from `KSerializer<Args>`

**Example**:

```kotlin
@Serializable
data class SearchArgs(
    val query: String,
    val limit: Int? = null,
)

tools {
    tool<SearchArgs>("search", "Search documents") { args ->
        // args.query is always present; args.limit may be null
        "Found ${args.limit ?: 10} results for ${args.query}"
    }
}
```

### 9.2 `tool()` spread parameters

No data class needed; declare params with `param()` and provide logic via `execute { }`.

```kotlin
inline fun ToolsScope.tool(
    name: String,
    description: String,
    block: ToolScope.() -> Unit,
)
```

**Example**:

```kotlin
tools {
    tool("calculate", "Perform a calculation") {
        param("expression", "string", "Math expression", required = true)
        param("precision", "integer", "Decimal places", required = false)
        execute { expr: String?, precision: Int? ->
            // parameters are nullable; missing or JsonNull → null
            "Result: ${eval(expr)}"
        }
    }
}
```

### 9.3 `param()`

Declare a parameter in the spread-parameter style.

```kotlin
fun param(
    name: String,
    type: String,
    description: String? = null,
    required: Boolean = true,
    enumValues: List<String>? = null,
)
```

**Parameters**:

| Parameter      | Type              | Default | Description                                                                                          |
|----------------|-------------------|---------|------------------------------------------------------------------------------------------------------|
| `name`         | `String`          | —       | Parameter name; the key in JSON Schema properties                                                    |
| `type`         | `String`          | —       | JSON Schema type (`"string"` / `"integer"` / `"number"` / `"boolean"` / `"array"` / `"object"`)     |
| `description`  | `String?`         | null    | Parameter description                                                                                |
| `required`     | `Boolean`         | true    | Whether the parameter is required                                                                    |
| `enumValues`   | `List<String>?`   | null    | Optional enum value list                                                                             |

### 9.4 `execute()` arity overloads

Provide execution logic in the spread-parameter style; supports 0–10 parameter overloads:

```kotlin
// 0 parameters
fun execute(handler: suspend () -> String)

// 1 parameter
inline fun <reified A> execute(noinline handler: suspend (A?) -> String)

// 2 parameters
inline fun <reified A, reified B> execute(noinline handler: suspend (A?, B?) -> String)

// ... 3–10 parameters likewise, up to 10
inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G, reified H, reified I, reified J>
    execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?, J?) -> String)
```

**Notes**:

- Handler parameters are uniformly nullable (`A?`); missing or `JsonNull` values are passed as null
- Parameter order must match the `param()` declaration order
- Parameter types are inferred via `reified`; the framework deserializes JSON values accordingly
- **11+ parameters have no overload** — use the strongly typed `tool<Args>` style

**Example (0 parameters)**:

```kotlin
tool("get_time", "Get current time") {
    execute {
        "2026-07-14 12:00:00"
    }
}
```

**Example (3 parameters)**:

```kotlin
tool("create_user", "Create a user") {
    param("name", "string", "Username", required = true)
    param("age", "integer", "Age", required = true)
    param("email", "string", "Email", required = false)
    execute { name: String?, age: Int?, email: String? ->
        "Created user: $name, $age, ${email ?: "no email"}"
    }
}
```

---

## 10. Message Types

### 10.1 Message sealed interface

Root interface for all message types; uses `@JsonClassDiscriminator("role")` to auto-generate the `role` field.

```kotlin
package top.resderx.rac.messages

@JsonClassDiscriminator("role")
@Serializable
sealed interface Message

@Serializable
@SerialName("system")
data class SystemMessage(val content: String) : Message

@Serializable
@SerialName("user")
data class UserMessage(val content: List<Content>) : Message {
    constructor(text: String) : this(listOf(TextContent(text)))
}

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
) : Message

@Serializable
@SerialName("tool")
data class ToolMessage(
    @SerialName("tool_call_id") val toolCallId: String,
    val content: String,
) : Message
```

**Notes**:

- `UserMessage` has a convenience constructor `UserMessage(text: String)` that wraps it as `TextContent`
- `AssistantMessage.content` is null for pure tool calls (no body)
- `AssistantMessage.toolCalls` is an empty list when there are no tool calls
- Subclasses must not declare a property named `role` (auto-managed by `@JsonClassDiscriminator`)

### 10.2 Content sealed interface

Content blocks of user messages; supports multimodal.

```kotlin
@JsonClassDiscriminator("type")
@Serializable
sealed interface Content

@Serializable
@SerialName("text")
data class TextContent(val text: String) : Content

@Serializable
@SerialName("image")
data class ImageContent(
    val url: String? = null,
    val base64: String? = null,
    val mimeType: String = "image/jpeg",
) : Content  // init block requires at least one of url/base64 to be non-null

@Serializable
@SerialName("audio")
data class AudioContent(
    val base64: String,
    val mimeType: String = "audio/wav",
) : Content
```

### 10.3 AIMessage

The unified response message returned by the model.

```kotlin
@Serializable
data class AIMessage(
    val content: String,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null,
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val rawResponse: String? = null,
)
```

**Properties**:

| Property           | Type               | Description                                                |
|--------------------|--------------------|------------------------------------------------------------|
| `content`          | `String`           | Body text; empty string for pure tool calls                |
| `reasoningContent` | `String?`          | Reasoning-process text; only returned by reasoning models  |
| `toolCalls`        | `List<ToolCall>`   | Tool calls requested by the model; empty by default        |
| `usage`            | `Usage?`           | Token usage stats; some providers only return it at stream end |
| `finishReason`     | `FinishReason`     | Generation finish reason                                   |
| `rawResponse`      | `String?`          | Raw response string; usually null in streaming scenarios   |

### 10.4 ToolCall / ToolDefinition

```kotlin
@Serializable(with = ToolCallSerializer::class)
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
```

**Note**: The custom serializer `ToolCallSerializer` converts to the OpenAI/DeepSeek nested format
`{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`.

```kotlin
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String,  // JSON Schema string
)
```

### 10.5 Usage / FinishReason

```kotlin
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)

@Serializable
enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("tool_calls") TOOL_CALLS,
    @SerialName("content_filter") CONTENT_FILTER,
    @SerialName("unknown") UNKNOWN,
}
```

---

## 11. StreamEvent

Unified semantic event for streaming responses; a sealed interface with four event types.

```kotlin
package top.resderx.rac.messages

sealed interface StreamEvent {

    data class TextDelta(
        val delta: String,
        val accumulated: String,
    ) : StreamEvent

    data class ReasoningDelta(
        val delta: String,
        val accumulated: String,
    ) : StreamEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String,
        val name: String,
        val argumentsDelta: String,
        val argumentsAccumulated: String,
    ) : StreamEvent

    data class Done(
        val content: String,
        val reasoningContent: String?,
        val toolCalls: List<ToolCall>,
        val usage: Usage?,
        val finishReason: FinishReason,
        val rawResponse: String? = null,
    ) : StreamEvent

    fun Done.toAIMessage(): AIMessage
}
```

**Event types**:

| Event             | Fields                                                                          | Trigger                                                                                       |
|-------------------|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `TextDelta`       | `delta` (new fragment), `accumulated` (accumulated body)                        | Completions delta.content / Anthropic text_delta / Responses output_text.delta                |
| `ReasoningDelta`  | `delta`, `accumulated`                                                          | Completions delta.reasoningContent / Anthropic thinking_delta                                 |
| `ToolCallDelta`   | `index`, `id` (first non-null), `name` (first non-null), `argumentsDelta`, `argumentsAccumulated` | Completions delta.toolCalls / Anthropic tool_use / Responses function_call                    |
| `Done`            | `content`, `reasoningContent`, `toolCalls`, `usage`, `finishReason`, `rawResponse` | Stream end                                                                                    |

**`Done.toAIMessage()`**: Converts a `Done` event to an `AIMessage` for scenarios needing a unified type.

**Handling example**:

```kotlin
ai.chatStream { user("hi") }.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.ReasoningDelta -> print("[thinking] ${event.delta}")
        is StreamEvent.ToolCallDelta -> println("Tool call: ${event.name}")
        is StreamEvent.Done -> {
            val aiMessage = event.toAIMessage()  // convert to AIMessage
            println("Done: ${aiMessage.content}")
        }
    }
}
```

---

## 12. MCP Extension

> Requires the `top.resderx.rac:mcp` module.

### `chatWithMcp()`

Automatically discovers tools from an MCP server and injects them into the conversation, reusing the `Llm.chatWithTools` multi-turn tool-call loop.

```kotlin
package top.resderx.rac.mcp

suspend fun Llm.chatWithMcp(
    mcpClient: McpClient,
    maxRounds: Int = 10,
    block: ChatRequestBuilder.() -> Unit,
): AIMessage
```

**Parameters**:

- `mcpClient: McpClient` — MCP client instance
- `maxRounds: Int = 10` — max tool-call loop rounds
- `block: ChatRequestBuilder.() -> Unit` — request builder block

**Example**:

```kotlin
import top.resderx.rac.mcp.chatWithMcp

val mcpClient = McpClient(McpClientConfig(transport = StdioMcpTransport(...)))
ai.chatWithMcp(mcpClient) {
    user("Read README.md and summarize it")
}
```

---

## 13. ACP Extension

> Requires the `top.resderx.rac:acp` module.

### `chatWithAcpAgent()`

Call a remote ACP Agent as an ACP Client.

```kotlin
package top.resderx.rac.acp

suspend fun Llm.chatWithAcpAgent(
    client: AcpClient,
    prompt: String,
    cwd: String = "",
    onUpdate: suspend (SessionUpdate) -> Unit = {},
): AIMessage
```

**Parameters**:

- `client: AcpClient` — ACP client instance
- `prompt: String` — user prompt
- `cwd: String = ""` — working directory
- `onUpdate: suspend (SessionUpdate) -> Unit = {}` — streaming update callback

### `serveAsAcpAgent()`

Start the Llm as an ACP Agent Server (communicating with ACP Clients over stdio).

```kotlin
@Throws(UnsupportedOperationException::class)
fun Llm.serveAsAcpAgent(
    agentInfo: ImplementationInfo = ImplementationInfo(
        name = "rac-agent",
        title = "LLM Agent",
        version = "0.2.0",
    ),
    agentCapabilities: AgentCapabilities = AgentCapabilities(),
    systemPrompt: String? = null,
): AcpAgentServer
```

**Returns**: a configured but not-yet-started `AcpAgentServer` (caller must call `start()`)

---

## 14. A2A Extension

> Requires the `top.resderx.rac:a2a` module.

### `chatWithA2aAgent()`

Call a remote Agent via the A2A protocol (HTTP + SSE streaming).

```kotlin
package top.resderx.rac.a2a

suspend fun Llm.chatWithA2aAgent(
    client: A2aClient,
    prompt: String,
    onUpdate: suspend (A2aStreamEvent) -> Unit = {},
): AIMessage
```

**Parameters**:

- `client: A2aClient` — A2A client instance
- `prompt: String` — user prompt
- `onUpdate: suspend (A2aStreamEvent) -> Unit = {}` — streaming update callback

### `serveAsA2aAgent()`

Start the Llm as an A2A Agent Server; returns a protocol-agnostic JSON-RPC dispatcher.

```kotlin
fun Llm.serveAsA2aAgent(
    agentCard: AgentCard = AgentCard(
        name = "rac-agent",
        description = "LLM Agent — Kotlin Multiplatform AI Call Library",
        url = "",
        provider = AgentProvider(organization = "ResDerX"),
    ),
    systemPrompt: String? = null,
): A2aAgentServer
```

**Note**: The returned `A2aAgentServer` is a protocol-agnostic dispatcher (not bound to an HTTP server); the caller must bind it to an HTTP server themselves.

---

## 15. Exception Types

All exceptions live in package `top.resderx.rac.exceptions` and extend `RACException`.

```kotlin
package top.resderx.rac.exceptions

open class RACException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RACNetworkException(message: String, cause: Throwable? = null) : RACException(message, cause)

class RACApiException(
    val statusCode: Int,
    val errorBody: String,
    val headers: Map<String, String> = emptyMap(),
    message: String = "API error $statusCode: $errorBody",
) : RACException(message)

class RACSerializationException(message: String, cause: Throwable? = null) : RACException(message, cause)

class RACTimeoutException(message: String = "Request timed out", cause: Throwable? = null) :
    RACException(message, cause)
```

**Exception classes**:

| Exception class              | Parent class     | Purpose                                                  |
|------------------------------|------------------|----------------------------------------------------------|
| `RACException`               | `Exception`      | Base class for all RAC library exceptions; open for subclassing |
| `RACNetworkException`        | `RACException`   | Network layer errors (connection failure, DNS failure, TLS handshake failure) |
| `RACApiException`            | `RACException`   | Provider returned a non-success HTTP status (4xx/5xx)    |
| `RACSerializationException`  | `RACException`   | JSON ↔ domain model conversion failures                  |
| `RACTimeoutException`        | `RACException`   | Request timeout (connect/read timeout)                   |

**RACApiException extra properties**:

| Property     | Type                | Description                                                |
|--------------|---------------------|------------------------------------------------------------|
| `statusCode` | `Int`               | HTTP status code                                           |
| `errorBody`  | `String`            | Raw error response body returned by the provider           |
| `headers`    | `Map<String, String>` | Response headers, including `Retry-After` and other hints |

**Exception handling example**:

```kotlin
try {
    ai.chat { user("hi") }
} catch (e: RACApiException) {
    when (e.statusCode) {
        401 -> println("API Key invalid")
        429 -> println("Rate limit exceeded; retry later")
        else -> println("API error ${e.statusCode}: ${e.errorBody}")
    }
} catch (e: RACTimeoutException) {
    println("Request timed out")
} catch (e: RACNetworkException) {
    println("Network error: ${e.message}")
} catch (e: RACException) {
    println("Other error: ${e.message}")
}
```
