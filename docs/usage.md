# API 参考

本文档是 RAC 库每个公开函数、类、属性的完整签名参考。按功能模块组织，每条 API 含签名、参数说明与使用示例。刚接触 RAC 建议先阅读 [入门指南](quickstart.md)。

## 目录

- [1. DSL 入口](#1-dsl-入口)
  - [1.1 llm { }](#11-llm--)
  - [1.2 LlmBuilder](#12-llmbuilder)
  - [1.3 RetryPolicy](#13-retrypolicy)
- [2. 供应商 DSL](#2-供应商-dsl)
  - [2.1 providers { } 与供应商扩展函数](#21-providers--与供应商扩展函数)
  - [2.2 ProviderDsl](#22-providerdsl)
- [3. 模型注册](#3-模型注册)
  - [3.1 ModelsBuilder.model()](#31-modelsbuildermodel)
  - [3.2 ModelBuilder](#32-modelbuilder)
  - [3.3 ModelConfig](#33-modelconfig)
- [4. 模型预设枚举](#4-模型预设枚举)
  - [4.1 ModelPreset 接口](#41-modelpreset-接口)
  - [4.2 10 个供应商枚举](#42-10-个供应商枚举)
- [5. Llm 类](#5-llm-类)
  - [5.1 chat { }](#51-chat--)
  - [5.2 chatWithTools { }](#52-chatwithtools--)
  - [5.3 chatStream { }](#53-chatstream--)
  - [5.4 anthropicStream { }](#54-anthropicstream--)
  - [5.5 respond { }](#55-respond--)
  - [5.6 respondStream { }](#56-respondstream--)
  - [5.7 provider()](#57-provider)
- [6. ChatRequestBuilder（chat { } 块内）](#6-chatrequestbuilderchat--块内)
- [7. RespondRequestBuilder（respond { } 块内）](#7-respondrequestbuilderrespond--块内)
- [8. Agent API](#8-agent-api)
  - [8.1 agent { }](#81-agent--)
  - [8.2 AgentBuilder](#82-agentbuilder)
  - [8.3 Agent 类](#83-agent-类)
  - [8.4 Session 类](#84-session-类)
- [9. 工具定义](#9-工具定义)
  - [9.1 tool<Args>() 强类型模式](#91-toolargs-强类型模式)
  - [9.2 tool() 散开参数模式](#92-tool-散开参数模式)
  - [9.3 param()](#93-param)
  - [9.4 execute() arity 重载](#94-execute-arity-重载)
- [10. 消息类型](#10-消息类型)
  - [10.1 Message 密封接口](#101-message-密封接口)
  - [10.2 Content 密封接口](#102-content-密封接口)
  - [10.3 AIMessage](#103-aimessage)
  - [10.4 ToolCall / ToolDefinition](#104-toolcall--tooldefinition)
  - [10.5 Usage / FinishReason](#105-usage--finishreason)
- [11. StreamEvent](#11-streamevent)
- [12. MCP 扩展](#12-mcp-扩展)
- [13. ACP 扩展](#13-acp-扩展)
- [14. A2A 扩展](#14-a2a-扩展)
- [15. 异常类型](#15-异常类型)

---

## 1. DSL 入口

### 1.1 `llm { }`

顶层 DSL 入口函数，创建并构建 `Llm` 实例。

```kotlin
package com.resderx.rac.dsl

inline fun llm(block: LlmBuilder.() -> Unit): Llm
```

**参数**：
- `block: LlmBuilder.() -> Unit` — 配置块，在 `LlmBuilder` 作用域内注册供应商等

**返回**：构建完成的 `Llm` 实例

**抛出**：`RACException` — 当 block 内未注册任何供应商时

**示例**：

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

`llm { }` 块的接收者，承载全局配置与供应商注册入口。

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

**属性**：

| 属性 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `defaultProviderName` | `String?` | null | 默认供应商名称，覆盖"首个注册即默认"规则 |
| `timeoutMillis` | `Long?` | null | HttpClient 超时毫秒数，null 使用默认 60s |
| `retryPolicy` | `RetryPolicy?` | null | 重试策略，null 使用默认 `RetryPolicy()` |

**方法**：

| 方法 | 说明 |
| --- | --- |
| `fun providers(block: ProvidersBuilder.() -> Unit)` | providers 块入口，在 lambda 内逐个注册供应商 |
| `fun build(): Llm` | 构建不可变的 `Llm` 实例（通常由 `llm { }` 自动调用） |

### 1.3 RetryPolicy

重试策略数据类，定义网络瞬时错误的自动重试行为。

```kotlin
package com.resderx.rac.network

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

**属性**：

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `maxRetries` | 3 | 最大重试次数（不含首次请求） |
| `initialDelayMillis` | 1000 | 首次重试延迟毫秒 |
| `maxDelayMillis` | 30000 | 最大重试延迟毫秒 |
| `backoffMultiplier` | 2.0 | 指数退避倍数 |
| `retryableStatusCodes` | 408/429/500/502/503/504 | 可重试的 HTTP 状态码 |

**示例**：

```kotlin
val ai = llm {
    retryPolicy = RetryPolicy(maxRetries = 5, initialDelayMillis = 500)
    providers { /* ... */ }
}
```

---

## 2. 供应商 DSL

### 2.1 `providers { }` 与供应商扩展函数

`providers { }` 块的接收者是 `ProvidersBuilder`，每个供应商通过对应的扩展函数注册：

```kotlin
package com.resderx.rac.dsl

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

**说明**：每个扩展函数在 `ProviderDsl` 作用域内配置该供应商的连接信息与模型。首个注册的供应商自动成为默认供应商（可用 `defaultProviderName` 覆盖）。

**示例**：

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models { model(DeepSeekModel.V4_FLASH) }
        }
        openai {
            apiKey("sk-...")
            baseUrl("https://api.openai.com/v1")  // 可选，覆盖默认
            models { model(OpenAIModel.GPT_5_4) }
        }
    }
}
```

### 2.2 ProviderDsl

供应商 DSL 作用域，配置连接信息与模型注册。

```kotlin
package com.resderx.rac.dsl

@RacDslMarker
class ProviderDsl {
    fun apiKey(key: String?)
    fun baseUrl(url: String?)
    fun header(name: String, value: String)
    fun headers(headers: Map<String, String>)
    fun models(block: ModelsBuilder.() -> Unit)
}
```

**方法**：

| 方法 | 说明 |
| --- | --- |
| `fun apiKey(key: String?)` | 设置 API 密钥，传 null 沿用供应商默认 |
| `fun baseUrl(url: String?)` | 设置 baseUrl，覆盖供应商默认 |
| `fun header(name, value)` | 追加单个额外请求头 |
| `fun headers(headers)` | 批量追加额外请求头 |
| `fun models(block)` | models 块入口，在 lambda 内逐个注册模型 |

---

## 3. 模型注册

### 3.1 `ModelsBuilder.model()`

在 `models { }` 块内注册模型，有两种重载：

```kotlin
package com.resderx.rac.dsl

@RacDslMarker
class ModelsBuilder internal constructor() {
    // 重载一：手动指定模型名
    fun model(name: String, block: ModelBuilder.() -> Unit = {})

    // 重载二：从预设枚举读取模型名与推荐配置
    fun model(preset: ModelPreset, block: ModelBuilder.() -> Unit = {})

    internal fun build(): Map<String, ModelConfig>
}
```

**重载一**：`model(name, block)` — 手动指定模型名与配置。首个注册的模型自动成为该供应商的默认模型。

**重载二**：`model(preset, block)` — 从预设枚举读取 `modelName` 与 `recommendedConfig` 作为初始值，block 可覆盖部分字段。

**示例**：

```kotlin
models {
    // 重载一：手动注册
    model("deepseek-v4-flash") {
        maxTokens = 4096
        temperature = 0.7
    }

    // 重载二：预设注册（完全使用预设）
    model(DeepSeekModel.V4_FLASH)

    // 重载二：预设注册 + 覆盖部分字段
    model(DeepSeekModel.V4_PRO) {
        maxTokens = 4096  // 覆盖预设的 8192，其余沿用预设
    }
}
```

### 3.2 ModelBuilder

模型配置的 DSL 构建器，在 `model("xxx") { }` 或 `model(preset) { }` 内使用。

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

    internal constructor(initial: ModelConfig)  // 从预设初始化
    internal constructor()
    internal fun build(): ModelConfig
}
```

**属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `maxTokens` | `Long?` | 最大生成 token 数 |
| `temperature` | `Double?` | 采样温度 |
| `topP` | `Double?` | nucleus sampling 参数 |
| `systemPrompt` | `String?` | 模型专属系统提示词（调用时可在 `chat { system() }` 覆盖） |
| `reasoningEffort` | `String?` | 推理强度（`"low"` / `"medium"` / `"high"`），仅推理模型支持 |
| `stop` | `List<String>?` | 停止序列 |
| `seed` | `Long?` | 随机种子 |
| `enableThinking` | `Boolean?` | 思考开关，true 启用扩展思考 |

### 3.3 ModelConfig

不可变的模型配置数据类，由 `ModelBuilder.build()` 产出。

```kotlin
package com.resderx.rac.providers

data class ModelConfig(
    val maxTokens: Long? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val systemPrompt: String? = null,
    val reasoningEffort: String? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
    val enableThinking: Boolean? = null,
)
```

**说明**：所有字段为 null 表示"无模型级默认覆盖"，完全沿用服务端默认。

---

## 4. 模型预设枚举

### 4.1 ModelPreset 接口

按供应商区分的枚举类统一实现此接口。

```kotlin
package com.resderx.rac.providers.presets

interface ModelPreset {
    val modelName: String
    val recommendedConfig: ModelConfig
}
```

**属性**：
- `modelName: String` — 模型标识符，与 API 请求体 `model` 字段对应
- `recommendedConfig: ModelConfig` — 推荐的模型配置，含针对该模型特性调优过的参数

### 4.2 10 个供应商枚举

均位于 `com.resderx.rac.providers.presets` 包下，共 43 个模型：

#### DeepSeekModel（2 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `V4_PRO` | `deepseek-v4-pro` | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true |
| `V4_FLASH` | `deepseek-v4-flash` | maxTokens=8192, temperature=0.0, reasoningEffort="medium" |

#### OpenAIModel（4 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `GPT_5_5` | `gpt-5.5` | maxTokens=16384, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `GPT_5_4` | `gpt-5.4` | maxTokens=16384, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `GPT_5_4_MINI` | `gpt-5.4-mini` | maxTokens=8192, temperature=0.7 |
| `GPT_5_4_NANO` | `gpt-5.4-nano` | maxTokens=4096, temperature=0.7 |

#### AnthropicModel（4 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `CLAUDE_OPUS_4_1` | `claude-opus-4-1` | maxTokens=16384, temperature=0.0, enableThinking=true |
| `CLAUDE_SONNET_4_6` | `claude-sonnet-4-6` | maxTokens=8192, temperature=0.0, enableThinking=true |
| `CLAUDE_OPUS_4` | `claude-opus-4-20250514` | maxTokens=8192, temperature=0.0 |
| `CLAUDE_SONNET_4` | `claude-sonnet-4-20250514` | maxTokens=8192, temperature=0.0 |

#### GeminiModel（2 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `PRO_3` | `gemini-3-pro` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `FLASH_3` | `gemini-3-flash` | maxTokens=8192, temperature=0.7 |

#### QwenModel（6 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `MAX_3_7` | `qwen3.7-max-preview` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `PLUS_3_7` | `qwen3.7-plus-preview` | maxTokens=8192, temperature=0.7 |
| `MAX_3_6` | `qwen3.6-max-preview` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `PLUS_3_6` | `qwen3.6-plus` | maxTokens=8192, temperature=0.7 |
| `FLASH_3_6` | `qwen3.6-flash` | maxTokens=4096, temperature=0.7 |
| `MAX_FLASH` | `qwen-max-flash` | maxTokens=4096, temperature=0.7 |

#### GlmModel（4 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `GLM_5_2` | `glm-5.2` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `GLM_5_1` | `glm-5.1` | maxTokens=8192, temperature=0.7 |
| `GLM_5` | `glm-5` | maxTokens=8192, temperature=0.7 |
| `GLM_4_7_FLASH` | `glm-4.7-flash` | maxTokens=4096, temperature=0.7 |

#### KimiModel（9 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `K2_5` | `kimi-k2.5` | maxTokens=8192, temperature=0.7 |
| `K2_0905` | `kimi-k2-0905-preview` | maxTokens=8192, temperature=0.7 |
| `K2_0711` | `kimi-k2-0711-preview` | maxTokens=8192, temperature=0.7 |
| `K2_TURBO` | `kimi-k2-turbo-preview` | maxTokens=8192, temperature=0.7 |
| `K2_THINKING` | `kimi-k2-thinking` | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true |
| `K2_THINKING_TURBO` | `kimi-k2-thinking-turbo` | maxTokens=8192, temperature=0.0, reasoningEffort="medium", enableThinking=true |
| `V1_8K` | `moonshot-v1-8k` | maxTokens=8000, temperature=0.7 |
| `V1_32K` | `moonshot-v1-32k` | maxTokens=32000, temperature=0.7 |
| `V1_128K` | `moonshot-v1-128k` | maxTokens=128000, temperature=0.7 |

#### DoubaoModel（5 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `SEED_2_1_PRO` | `doubao-seed-2.1-pro` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `SEED_1_6` | `doubao-seed-1.6` | maxTokens=8192, temperature=0.7 |
| `SEED_1_6_FLASH` | `doubao-seed-1.6-flash` | maxTokens=4096, temperature=0.7 |
| `SEED_1_6_THINKING` | `doubao-seed-1.6-thinking` | maxTokens=8192, temperature=0.0, reasoningEffort="high", enableThinking=true |
| `SEED_1_6_VISION` | `doubao-seed-1.6-vision` | maxTokens=8192, temperature=0.7 |

#### MinimaxModel（3 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `ABAB7` | `abab7` | maxTokens=8192, temperature=0.7 |
| `M2_5` | `MiniMax-M2.5` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `M2` | `MiniMax-M2` | maxTokens=8192, temperature=0.7 |

#### MimoModel（2 个）

| 枚举项 | modelName | 推荐配置 |
| --- | --- | --- |
| `V2_5_PRO` | `MiMo-V2.5-Pro` | maxTokens=8192, temperature=0.7, reasoningEffort="high", enableThinking=true |
| `V2_FLASH` | `MiMo-V2-Flash` | maxTokens=4096, temperature=0.7 |

**使用示例**：

```kotlin
import com.resderx.rac.providers.presets.DeepSeekModel
import com.resderx.rac.providers.presets.OpenAIModel

models {
    model(DeepSeekModel.V4_FLASH)                      // 完全使用预设
    model(OpenAIModel.GPT_5_4) { maxTokens = 4096 }    // 覆盖部分字段
}
```

---

## 5. Llm 类

LLM 顶层入口类，持有所有运行时依赖并提供 chat/respond 系列调用方法。

```kotlin
package com.resderx.rac.dsl

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

非流式 Chat 调用，按 provider 的 defaultApiType 路由到 Completions/Anthropic/Responses 三种协议。

```kotlin
suspend fun chat(block: ChatRequestBuilder.() -> Unit): AIMessage
```

**参数**：`block: ChatRequestBuilder.() -> Unit` — 请求构建块

**返回**：统一的 `AIMessage`

**示例**：

```kotlin
val resp = ai.chat {
    system("你是助手")
    user("你好")
    temperature = 0.7
}
println(resp.content)
```

### 5.2 `chatWithTools { }`

带工具调用循环的非流式 Chat 调用。框架自动执行多轮工具调用直到模型不再请求工具或达到 maxRounds。

```kotlin
suspend fun chatWithTools(
    maxRounds: Int = 10,
    toolExecutor: suspend (ToolCall) -> String,
    block: ChatRequestBuilder.() -> Unit,
): AIMessage
```

**参数**：
- `maxRounds: Int = 10` — 最大工具调用循环轮数，需 > 0，否则抛 `IllegalArgumentException`
- `toolExecutor: suspend (ToolCall) -> String` — 工具执行回调，接收 `ToolCall`，返回执行结果字符串
- `block: ChatRequestBuilder.() -> Unit` — 请求构建块（需在 `tools { }` 内声明工具）

**返回**：最终的 `AIMessage`（无工具调用或达 maxRounds）

**示例**：

```kotlin
val resp = ai.chatWithTools(
    maxRounds = 5,
    toolExecutor = { call ->
        when (call.name) {
            "get_weather" -> """{"city":"北京","temp":25}"""
            else -> "unknown"
        }
    },
) {
    user("北京天气")
    tools {
        tool("get_weather", "查询天气") {
            param("city", "string", "城市", required = true)
            // 注意：chatWithTools 的 toolExecutor 负责执行，此处只声明 schema
        }
    }
}
```

### 5.3 `chatStream { }`

流式 Chat 调用（仅 Completions API）。返回 `Flow<StreamEvent>` 冷流。

```kotlin
fun chatStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent>
```

**抛出**：`RACException` — 当供应商的 defaultApiType 不是 `COMPLETIONS` 时

**示例**：

```kotlin
ai.chatStream {
    user("写一首诗")
}.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.Done -> println("\n完成")
        else -> {}
    }
}
```

### 5.4 `anthropicStream { }`

流式 Chat 调用（仅 Anthropic API）。

```kotlin
fun anthropicStream(block: ChatRequestBuilder.() -> Unit): Flow<StreamEvent>
```

**抛出**：`RACException` — 当供应商的 defaultApiType 不是 `ANTHROPIC` 时

### 5.5 `respond { }`

非流式 Respond 调用（Responses API）。

```kotlin
suspend fun respond(block: RespondRequestBuilder.() -> Unit): AIMessage
```

**示例**：

```kotlin
val resp = ai.respond {
    input("解释量子纠缠")
    instructions("你是物理学家")
}
```

### 5.6 `respondStream { }`

流式 Respond 调用（Responses API）。

```kotlin
fun respondStream(block: RespondRequestBuilder.() -> Unit): Flow<StreamEvent>
```

### 5.7 `provider()`

按名称获取已注册的供应商。

```kotlin
fun provider(name: String): ModelProvider
```

**参数**：`name: String` — 供应商名称（`providers { }` 块内注册时使用的名称）

**返回**：对应的 `ModelProvider`

**抛出**：`RACException` — 当 name 未注册时

---

## 6. ChatRequestBuilder（`chat { }` 块内）

`chat { }` / `chatStream { }` / `chatWithTools { }` / `anthropicStream { }` 块的接收者。

```kotlin
package com.resderx.rac.dsl

@RacDslMarker
class ChatRequestBuilder {
    // ===== 运行时切换 =====
    fun provider(name: String)
    fun model(name: String)

    // ===== 添加消息 =====
    fun system(text: String)
    fun user(text: String)
    fun user(block: UserContentBuilder.() -> Unit)
    fun assistant(text: String)
    fun tool(id: String, content: String)

    // ===== 工具声明 =====
    fun tools(block: ToolsBuilder.() -> Unit)
    fun addTools(tools: List<ToolDefinition>)

    // ===== 定制化参数（var 字段）=====
    var temperature: Double? = null
    var topP: Double? = null
    var maxTokens: Long? = null
    var reasoningEffort: String? = null
    var stop: List<String>? = null
    var seed: Long? = null
    var enableThinking: Boolean? = null
}
```

**方法**：

| 方法 | 说明 |
| --- | --- |
| `fun provider(name: String)` | 运行时切换到指定 provider |
| `fun model(name: String)` | 运行时切换到指定 model |
| `fun system(text: String)` | 添加系统消息 |
| `fun user(text: String)` | 添加纯文本用户消息 |
| `fun user(block: UserContentBuilder.() -> Unit)` | 添加用户消息（内容由 UserContentBuilder 构建） |
| `fun assistant(text: String)` | 添加助手消息 |
| `fun tool(id: String, content: String)` | 添加工具回执消息（id 为 ToolCall.id） |
| `fun tools(block: ToolsBuilder.() -> Unit)` | 声明可用工具集 |
| `fun addTools(tools: List<ToolDefinition>)` | 追加一组工具定义（用于 MCP 工具自动注入） |

**var 字段**（覆盖 ModelConfig 默认值，null 表示沿用）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `temperature` | `Double?` | 采样温度 |
| `topP` | `Double?` | nucleus sampling 参数 |
| `maxTokens` | `Long?` | 最大生成 token 数 |
| `reasoningEffort` | `String?` | 推理强度 |
| `stop` | `List<String>?` | 停止序列 |
| `seed` | `Long?` | 随机种子 |
| `enableThinking` | `Boolean?` | 思考开关（详见 [enableThinking 行为](#enablethinking-行为)） |

#### `enableThinking` 行为

| API | enableThinking=true | enableThinking=false | null |
| --- | --- | --- | --- |
| Completions | 自动设 `reasoningEffort="medium"`（未显式设置时） | 强制 `reasoningEffort=null` | 不干预 |
| Anthropic | 构造 `thinking={type:"enabled", budget_tokens:maxTokens*4/5}` | 不发送 thinking 字段 | 不发送 |
| Responses | 静默忽略 | 静默忽略 | 不干预 |

**完整示例**：

```kotlin
ai.chat {
    provider("openai")           // 运行时切换到 OpenAI
    model("gpt-5.4")             // 切换模型
    system("你是翻译助手")
    user("翻译：Hello World")
    temperature = 0.3
    maxTokens = 1000
    stop = listOf("\n\n")
    seed = 42L
    enableThinking = true
}
```

---

## 7. RespondRequestBuilder（`respond { }` 块内）

`respond { }` / `respondStream { }` 块的接收者（Responses API 专用）。

```kotlin
package com.resderx.rac.dsl

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

**属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `input` | `String` | 用户输入文本 |
| `instructions` | `String?` | 系统指令 |
| `temperature` | `Double?` | 采样温度 |
| `maxOutputTokens` | `Long?` | 最大输出 token 数 |

**示例**：

```kotlin
val resp = ai.respond {
    input("总结以下文章：...")
    instructions("你是摘要助手，输出不超过 100 字")
    model("gpt-5.4")
    temperature = 0.5
}
```

---

## 8. Agent API

### 8.1 `agent { }`

Agent DSL 入口，声明式配置「LLM + prompts + tools」三要素。

```kotlin
package com.resderx.rac.agent

inline fun agent(llm: Llm, block: AgentBuilder.() -> Unit): Agent
```

**参数**：
- `llm: Llm` — 底层 LLM 调用入口
- `block: AgentBuilder.() -> Unit` — 配置块

**返回**：构建完成的 `Agent` 实例

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

**方法**：

| 方法 | 说明 |
| --- | --- |
| `fun prompts(text: String)` | 设置系统提示词（用 `prompts` 而非 `system`，与底层 SystemMessage 解耦） |
| `inline fun tools(block: ToolsScope.() -> Unit)` | 声明工具集 |
| `fun maxRounds(n: Int)` | 设置最大工具调用循环轮数（需 > 0，否则抛 `IllegalArgumentException`） |

**示例**：

```kotlin
val agent = agent(ai) {
    prompts("你是天气助手")
    maxRounds(5)
    tools {
        tool<WeatherArgs>("get_weather", "查询天气") { args ->
            "${args.city} 今天晴"
        }
    }
}
```

### 8.3 Agent 类

封装 LLM + 系统提示词 + 工具集，提供自动多轮工具调用。

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

**属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `llm` | `Llm` | 底层 LLM 调用入口 |
| `systemPrompt` | `String?` | 系统提示词，每次请求时动态拼接（不存入 Session） |
| `tools` | `ToolRegistry` | 工具注册表 |
| `maxRounds` | `Int` | 最大工具调用循环轮数，默认 10 |

**方法**：

#### `run(session, input)`

执行一轮 Agent 调用——动态拼接 system、注入历史、多轮工具调用、完整记录到 Session。

```kotlin
suspend fun run(session: Session, input: String): AIMessage
```

**参数**：
- `session: Session` — 对话历史容器（不含 system）
- `input: String` — 本轮用户输入文本

**返回**：最终的 `AIMessage`

**流程**：
1. `session.addUser(input)` — 追加用户输入
2. 构造请求：动态拼接 system（不存 session）+ 注入完整历史 + 注入工具定义
3. 调用模型，若返回 toolCalls 则执行工具、回填结果、继续下一轮
4. 最终结果追加到 session

#### `runStream(session, input)`

流式执行 Agent 调用，与 `run` 语义一致但以 SSE 流式返回事件流。

```kotlin
fun runStream(session: Session, input: String): Flow<StreamEvent>
```

**返回**：`Flow<StreamEvent>` 冷流，最终事件为 `Done`

**说明**：中间轮（工具调用轮）的 delta 事件实时转发，只有最终轮才转发 `Done`。

### 8.4 Session 类

对话历史容器，记录完整对话历史（不含 system 消息）。

```kotlin
package com.resderx.rac.agent

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

**属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `messages` | `List<Message>` | 当前对话历史只读快照（每次返回新列表），仅含 user/assistant/tool 消息 |

**方法**：

| 方法 | 说明 |
| --- | --- |
| `fun isEmpty(): Boolean` | 对话历史是否为空 |
| `fun addUser(text: String)` | 追加纯文本用户消息 |
| `fun addAssistant(aiMessage: AIMessage)` | 从 AIMessage 映射追加助手消息（content 为空字符串时转为 null） |
| `fun addAssistantMessage(assistantMessage: AssistantMessage)` | 直接接收 AssistantMessage 追加（用于记录中间工具调用消息） |
| `fun addToolResult(toolCallId: String, content: String)` | 追加工具回执消息 |
| `fun clear()` | 清空对话历史 |

**关键设计**：
- **不含 SystemMessage**：system 是 Agent 的属性，不提供 `addSystem()` 方法
- **可跨 Agent 复用**：不同 Agent（不同 systemPrompt）可共用同一 Session
- **记录完整历史**：含中间工具调用过程（AssistantMessage + ToolMessage）

**示例**：

```kotlin
val session = Session()
agent.run(session, "你好")
agent.run(session, "再问一个问题")
println("对话轮数: ${session.messages.size / 2}")
```

---

## 9. 工具定义

工具定义在 `agent { tools { } }` 块内，支持两种模式混合使用。

### 9.1 `tool<Args>()` 强类型模式

通过 `@Serializable data class` 自动生成 JSON Schema。

```kotlin
inline fun <reified Args : Any> ToolsScope.tool(
    name: String,
    description: String,
    noinline handler: suspend (Args) -> String,
)
```

**参数**：
- `name: String` — 工具名称（模型调用时使用）
- `description: String` — 工具描述（帮助模型理解何时调用）
- `handler: suspend (Args) -> String` — 工具执行回调，接收自动反序列化的参数，返回结果字符串

**类型参数**：`Args : Any` — 需为 `@Serializable` 的 data class，框架自动从 `KSerializer<Args>` 生成 JSON Schema

**示例**：

```kotlin
@Serializable
data class SearchArgs(
    val query: String,
    val limit: Int? = null,
}

tools {
    tool<SearchArgs>("search", "搜索文档") { args ->
        // args.query 必有，args.limit 可能为 null
        "找到 ${args.limit ?: 10} 条关于 ${args.query} 的结果"
    }
}
```

### 9.2 `tool()` 散开参数模式

无需 data class，直接用 `param()` 声明参数，用 `execute { }` 提供执行逻辑。

```kotlin
inline fun ToolsScope.tool(
    name: String,
    description: String,
    block: ToolScope.() -> Unit,
)
```

**示例**：

```kotlin
tools {
    tool("calculate", "执行计算") {
        param("expression", "string", "数学表达式", required = true)
        param("precision", "integer", "小数位数", required = false)
        execute { expr: String?, precision: Int? ->
            // 参数可空，缺失或 JsonNull 时为 null
            "结果: ${eval(expr)}"
        }
    }
}
```

### 9.3 `param()`

在散开参数模式下声明一个参数。

```kotlin
fun param(
    name: String,
    type: String,
    description: String? = null,
    required: Boolean = true,
    enumValues: List<String>? = null,
)
```

**参数**：

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `name` | `String` | — | 参数名，对应 JSON Schema properties 的 key |
| `type` | `String` | — | JSON Schema 类型（`"string"` / `"integer"` / `"number"` / `"boolean"` / `"array"` / `"object"`） |
| `description` | `String?` | null | 参数描述 |
| `required` | `Boolean` | true | 是否必填 |
| `enumValues` | `List<String>?` | null | 可选枚举值列表 |

### 9.4 `execute()` arity 重载

散开参数模式下提供执行逻辑，支持 0-10 个参数的重载：

```kotlin
// 0 参数
fun execute(handler: suspend () -> String)

// 1 参数
inline fun <reified A> execute(noinline handler: suspend (A?) -> String)

// 2 参数
inline fun <reified A, reified B> execute(noinline handler: suspend (A?, B?) -> String)

// ... 3-10 参数同理，最大 10 参数
inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G, reified H, reified I, reified J>
    execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?, J?) -> String)
```

**说明**：
- handler 入参统一为可空（`A?`），缺失或 `JsonNull` 传 null
- 参数顺序需与 `param()` 声明顺序一致
- 参数类型通过 `reified` 自动推断，框架据此反序列化 JSON 值
- **11+ 参数无对应重载**，需改用 `tool<Args>` 强类型模式

**示例（0 参数）**：

```kotlin
tool("get_time", "获取当前时间") {
    execute {
        "2026-07-14 12:00:00"
    }
}
```

**示例（3 参数）**：

```kotlin
tool("create_user", "创建用户") {
    param("name", "string", "用户名", required = true)
    param("age", "integer", "年龄", required = true)
    param("email", "string", "邮箱", required = false)
    execute { name: String?, age: Int?, email: String? ->
        "创建用户: $name, $age, ${email ?: "无邮箱"}"
    }
}
```

---

## 10. 消息类型

### 10.1 Message 密封接口

所有消息类型的根接口，使用 `@JsonClassDiscriminator("role")` 自动生成 `role` 字段。

```kotlin
package com.resderx.rac.messages

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

**说明**：
- `UserMessage` 有便捷构造 `UserMessage(text: String)`，内部包装为 `TextContent`
- `AssistantMessage.content` 为 null 表示纯工具调用（无正文）
- `AssistantMessage.toolCalls` 为空列表表示无工具调用
- 子类不得再声明名为 `role` 的属性（`@JsonClassDiscriminator` 自动管理）

### 10.2 Content 密封接口

用户消息的内容块，支持多模态。

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
) : Content  // init 块校验 url/base64 至少一个非 null

@Serializable
@SerialName("audio")
data class AudioContent(
    val base64: String,
    val mimeType: String = "audio/wav",
) : Content
```

### 10.3 AIMessage

模型返回的统一响应消息。

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

**属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `content` | `String` | 正文文本，纯工具调用时为空字符串 |
| `reasoningContent` | `String?` | 推理过程文本，仅推理模型返回，非推理模型为 null |
| `toolCalls` | `List<ToolCall>` | 模型请求的工具调用列表，默认空 |
| `usage` | `Usage?` | token 用量统计，部分供应商流式末尾才返回 |
| `finishReason` | `FinishReason` | 生成结束原因 |
| `rawResponse` | `String?` | 原始响应字符串，流式场景通常为 null |

### 10.4 ToolCall / ToolDefinition

```kotlin
@Serializable(with = ToolCallSerializer::class)
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
```

**说明**：自定义序列化器 `ToolCallSerializer` 转换为 OpenAI/DeepSeek 嵌套格式 `{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}`。

```kotlin
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String,  // JSON Schema 字符串
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

流式响应的统一语义事件，密封接口含四种事件类型。

```kotlin
package com.resderx.rac.messages

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

**事件类型**：

| 事件 | 字段 | 触发 |
| --- | --- | --- |
| `TextDelta` | `delta`（新增片段）、`accumulated`（累积正文） | Completions delta.content / Anthropic text_delta / Responses output_text.delta |
| `ReasoningDelta` | `delta`、`accumulated` | Completions delta.reasoningContent / Anthropic thinking_delta |
| `ToolCallDelta` | `index`、`id`（首次非空）、`name`（首次非空）、`argumentsDelta`、`argumentsAccumulated` | Completions delta.toolCalls / Anthropic tool_use / Responses function_call |
| `Done` | `content`、`reasoningContent`、`toolCalls`、`usage`、`finishReason`、`rawResponse` | 流结束 |

**`Done.toAIMessage()`**：将 `Done` 事件转换为 `AIMessage`，便于需要统一类型的场景。

**处理示例**：

```kotlin
ai.chatStream { user("hi") }.collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.ReasoningDelta -> print("[思考] ${event.delta}")
        is StreamEvent.ToolCallDelta -> println("工具调用: ${event.name}")
        is StreamEvent.Done -> {
            val aiMessage = event.toAIMessage()  // 转为 AIMessage
            println("完成: ${aiMessage.content}")
        }
    }
}
```

---

## 12. MCP 扩展

> 需引入 `com.resderx.rac:mcp` 模块。

### `chatWithMcp()`

自动从 MCP 服务器发现工具并注入到对话，复用 `Llm.chatWithTools` 的多轮工具调用循环。

```kotlin
package com.resderx.rac.mcp

suspend fun Llm.chatWithMcp(
    mcpClient: McpClient,
    maxRounds: Int = 10,
    block: ChatRequestBuilder.() -> Unit,
): AIMessage
```

**参数**：
- `mcpClient: McpClient` — MCP 客户端实例
- `maxRounds: Int = 10` — 最大工具调用循环轮数
- `block: ChatRequestBuilder.() -> Unit` — 请求构建块

**示例**：

```kotlin
import com.resderx.rac.mcp.chatWithMcp

val mcpClient = McpClient(McpClientConfig(transport = StdioMcpTransport(...)))
ai.chatWithMcp(mcpClient) {
    user("读取 README.md 并总结")
}
```

---

## 13. ACP 扩展

> 需引入 `com.resderx.rac:acp` 模块。

### `chatWithAcpAgent()`

以 ACP Client 身份调用远程 ACP Agent。

```kotlin
package com.resderx.rac.acp

suspend fun Llm.chatWithAcpAgent(
    client: AcpClient,
    prompt: String,
    cwd: String = "",
    onUpdate: suspend (SessionUpdate) -> Unit = {},
): AIMessage
```

**参数**：
- `client: AcpClient` — ACP 客户端实例
- `prompt: String` — 用户提示词
- `cwd: String = ""` — 工作目录
- `onUpdate: suspend (SessionUpdate) -> Unit = {}` — 流式更新回调

### `serveAsAcpAgent()`

将 Llm 作为 ACP Agent Server 启动（通过 stdio 与 ACP Client 通信）。

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

**返回**：已配置但未启动的 `AcpAgentServer`（调用方需调用 `start()`）

---

## 14. A2A 扩展

> 需引入 `com.resderx.rac:a2a` 模块。

### `chatWithA2aAgent()`

通过 A2A 协议调用远端 Agent（HTTP + SSE 流式）。

```kotlin
package com.resderx.rac.a2a

suspend fun Llm.chatWithA2aAgent(
    client: A2aClient,
    prompt: String,
    onUpdate: suspend (A2aStreamEvent) -> Unit = {},
): AIMessage
```

**参数**：
- `client: A2aClient` — A2A 客户端实例
- `prompt: String` — 用户提示词
- `onUpdate: suspend (A2aStreamEvent) -> Unit = {}` — 流式更新回调

### `serveAsA2aAgent()`

将 Llm 作为 A2A Agent Server 启动，返回协议无关的 JSON-RPC 分发器。

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

**说明**：返回的 `A2aAgentServer` 是协议无关的分发器（不绑定 HTTP 服务器），调用方需自行绑定 HTTP 服务器。

---

## 15. 异常类型

所有异常位于 `com.resderx.rac.exceptions` 包，均继承自 `RACException`。

```kotlin
package com.resderx.rac.exceptions

open class RACException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RACNetworkException(message: String, cause: Throwable? = null) : RACException(message, cause)

class RACApiException(
    val statusCode: Int,
    val errorBody: String,
    val headers: Map<String, String> = emptyMap(),
    message: String = "API error $statusCode: $errorBody",
) : RACException(message)

class RACSerializationException(message: String, cause: Throwable? = null) : RACException(message, cause)

class RACTimeoutException(message: String = "Request timed out", cause: Throwable? = null) : RACException(message, cause)
```

**异常类**：

| 异常类 | 父类 | 用途 |
| --- | --- | --- |
| `RACException` | `Exception` | 所有 RAC 库异常的基类，open 允许子类化 |
| `RACNetworkException` | `RACException` | 网络层错误（连接失败、DNS 解析失败、TLS 握手失败） |
| `RACApiException` | `RACException` | 供应商返回非成功 HTTP 状态码（4xx/5xx） |
| `RACSerializationException` | `RACException` | JSON 与领域模型互转失败 |
| `RACTimeoutException` | `RACException` | 请求超时（连接超时/读取超时） |

**RACApiException 额外属性**：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `statusCode` | `Int` | HTTP 状态码 |
| `errorBody` | `String` | 供应商返回的原始错误响应体 |
| `headers` | `Map<String, String>` | 响应头，含 `Retry-After` 等重试指导字段 |

**异常处理示例**：

```kotlin
try {
    ai.chat { user("hi") }
} catch (e: RACApiException) {
    when (e.statusCode) {
        401 -> println("API Key 无效")
        429 -> println("请求频率超限，请稍后重试")
        else -> println("API 错误 ${e.statusCode}: ${e.errorBody}")
    }
} catch (e: RACTimeoutException) {
    println("请求超时")
} catch (e: RACNetworkException) {
    println("网络错误: ${e.message}")
} catch (e: RACException) {
    println("其他错误: ${e.message}")
}
```
