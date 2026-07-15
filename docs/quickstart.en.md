**English** | [中文](quickstart.md)

# Quickstart

This guide walks you through installing, configuring, and making your first AI call with RAC in 10 minutes, then introduces streaming, the automated Agent, tool calls, multi-turn conversations, and customization parameters. For the complete API signature reference, read the [API reference](usage.en.md).

## Table of Contents

- [1. Requirements](#1-requirements)
- [2. Installation](#2-installation)
- [3. Your First Call](#3-your-first-call)
- [4. Using Model Presets](#4-using-model-presets)
- [5. Streaming](#5-streaming)
- [6. Agent & Tool Calls](#6-agent--tool-calls)
- [7. Session Multi-turn Conversations](#7-session-multi-turn-conversations)
- [8. Customization Parameters](#8-customization-parameters)
- [9. Multimodal Input](#9-multimodal-input)
- [10. Next Steps](#10-next-steps)

---

## 1. Requirements

| Item                    | Minimum | Recommended |
|-------------------------|---------|-------------|
| Kotlin                  | 2.4.0   | 2.4.0       |
| Gradle                  | 8.5     | 8.7+        |
| JDK (build time)        | 17      | 21          |
| Kotlin Multiplatform    | 2.4.0   | 2.4.0       |

RAC is a KMP library targeting nine platforms: JVM, Android, iOS, mingwX64, linuxX64, linuxArm64, macosArm64, JS, and WasmJs. Your project only needs to pull in the corresponding Ktor engine to gain network capability (RAC's convention plugin already wires per-platform engines).

## 2. Installation

### 2.1 Kotlin Multiplatform project

Add the `core` module to `commonMain` dependencies in `build.gradle.kts`:

```kotlin
kotlin {
    jvm()
    // add other targets as needed
}

dependencies {
    commonMain {
        implementation("top.resderx.rac:rac-core:0.1.0-alpha01")
    }
    // optional protocol modules
    // commonMain { implementation("top.resderx.rac:rac-mcp:0.1.0-alpha01") }
    // commonMain { implementation("top.resderx.rac:rac-acp:0.1.0-alpha01") }
    // commonMain { implementation("top.resderx.rac:rac-a2a:0.1.0-alpha01") }
}
```

Make sure Maven Central is in your repositories:

```kotlin
repositories {
    mavenCentral()
}
```

### 2.2 Pure JVM / Android project

```kotlin
dependencies {
    implementation("top.resderx.rac:rac-core:0.1.0-alpha01")
}
```

## 3. Your First Call

Create an `Llm` instance and make a non-streaming call:

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Create an Llm instance — register the DeepSeek provider
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-your-api-key")
                models {
                    // manually register a model with specific parameters
                    model("deepseek-v4-flash") {
                        maxTokens = 4096
                        temperature = 0.7
                    }
                }
            }
        }
    }

    // 2. Non-streaming chat
    val response = ai.chat {
        user("Explain Kotlin Multiplatform in one sentence")
    }

    // 3. Read the response
    println(response.content)          // body text
    println(response.usage)            // token usage
    println(response.finishReason)     // finish reason (STOP/LENGTH/...)
}
```

**Notes**:

- `llm { }` is the top-level DSL entry that returns an `Llm` instance
- Inside `providers { }` you register providers; each provider has a dedicated extension function (`deepseek` / `openai` / `anthropic`, etc.)
- Inside `models { }` you register models; the first registered model becomes that provider's default
- `chat { }` builds and sends the request, returning a unified `AIMessage`
- All calls are `suspend` functions and must be invoked in a coroutine scope

## 4. Using Model Presets

RAC ships preset enums for 43 mainstream models across 10 providers. Register a model with recommended config in a single line:

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import top.resderx.rac.dsl.openai
import top.resderx.rac.providers.presets.DeepSeekModel
import top.resderx.rac.providers.presets.OpenAIModel

val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models {
                // fully use the preset recommended config (maxTokens=8192, temperature=0.0, reasoningEffort="medium")
                model(DeepSeekModel.V4_FLASH)
            }
        }
        openai {
            apiKey("sk-...")
            models {
                // override some preset fields, keep the rest
                model(OpenAIModel.GPT_5_4) { maxTokens = 4096 }
            }
        }
    }
}
```

**Available preset enums** (all in package `top.resderx.rac.providers.presets`):

| Enum class        | Provider       | # Models | Examples                                          |
|-------------------|----------------|----------|---------------------------------------------------|
| `DeepSeekModel`   | DeepSeek       | 2        | `V4_PRO`, `V4_FLASH`                              |
| `OpenAIModel`     | OpenAI         | 4        | `GPT_5_5`, `GPT_5_4`, `GPT_5_4_MINI`, `GPT_5_4_NANO` |
| `AnthropicModel`  | Anthropic      | 4        | `CLAUDE_OPUS_4_1`, `CLAUDE_SONNET_4_6`            |
| `GeminiModel`     | Google Gemini  | 2        | `PRO_3`, `FLASH_3`                                |
| `QwenModel`       | Tongyi Qianwen | 6        | `MAX_3_7`, `PLUS_3_7`, `FLASH_3_6`                |
| `GlmModel`        | Zhipu GLM      | 4        | `GLM_5_2`, `GLM_4_7_FLASH`                        |
| `KimiModel`       | Moonshot       | 9        | `K2_5`, `K2_THINKING`, `V1_128K`                  |
| `DoubaoModel`     | ByteDance      | 5        | `SEED_2_1_PRO`, `SEED_1_6_THINKING`               |
| `MinimaxModel`    | MiniMax        | 3        | `ABAB7`, `M2_5`                                   |
| `MimoModel`       | Xiaomi MiMo    | 2        | `V2_5_PRO`, `V2_FLASH`                            |

Reasoning/thinking models (e.g. `DeepSeekModel.V4_PRO`, `KimiModel.K2_THINKING`) ship presets that already set `enableThinking = true` and an appropriate `reasoningEffort`.

## 5. Streaming

Streaming calls return a `Flow<StreamEvent>` so you can process incremental chunks of model output in real time:

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import top.resderx.rac.messages.StreamEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-...")
                models { model("deepseek-v4-flash") }
            }
        }
    }

    // chatStream returns Flow<StreamEvent> (Completions API only)
    ai.chatStream {
        user("Write a poem about autumn")
    }.collect { event ->
        when (event) {
            is StreamEvent.TextDelta -> {
                // print each text fragment in real time
                print(event.delta)
            }
            is StreamEvent.ReasoningDelta -> {
                // reasoning model's thinking process (reasoning models only)
                // print("[thinking] ${event.delta}")
            }
            is StreamEvent.Done -> {
                // stream end, carries full info
                println("\n--- done ---")
                println("total tokens: ${event.usage?.totalTokens}")
            }
            is StreamEvent.ToolCallDelta -> {
                // tool call increment (streaming tool call scenario)
            }
        }
    }
}
```

**Four StreamEvent types**:

| Event             | Fields                                                                  | Description                       |
|-------------------|-------------------------------------------------------------------------|-----------------------------------|
| `TextDelta`       | `delta` (new fragment), `accumulated` (accumulated body)                | Body text fragment from the model |
| `ReasoningDelta`  | `delta`, `accumulated`                                                  | Thinking-process fragment (reasoning models only) |
| `ToolCallDelta`   | `index`, `id`, `name`, `argumentsDelta`, `argumentsAccumulated`         | Tool call increment (fragments aggregated) |
| `Done`            | `content`, `reasoningContent`, `toolCalls`, `usage`, `finishReason`     | Stream end, self-contained full info |

**API selection**:

- `chatStream { }` — Completions API streaming (most providers: DeepSeek/OpenAI/Qwen, etc.)
- `anthropicStream { }` — Anthropic API streaming (Anthropic provider only)
- `respondStream { }` — Responses API streaming (OpenAI Responses only)

## 6. Agent & Tool Calls

`Agent` wraps "LLM + system prompt + toolset" and automatically drives the multi-turn tool-call loop. Pair it with `Session` to manage conversation history.

### 6.1 Defining tools

RAC supports two tool definition styles that can be mixed in the same `tools { }` block.

**Style 1: strongly typed (recommended for complex parameters)**

Auto-generates JSON Schema from a `@Serializable data class`:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class WeatherArgs(
    val city: String,           // required
    val date: String? = null,   // optional
)

val agent = agent(ai) {
    prompts("You are a weather assistant that can look up the weather")
    tools {
        tool<WeatherArgs>("get_weather", "Look up weather for a city") { args ->
            // args is already deserialized
            "${args.city} today sunny, 25°C"
        }
    }
}
```

**Style 2: spread parameters (no data class, quick definition)**

```kotlin
val agent = agent(ai) {
    prompts("You are a calculation assistant")
    tools {
        tool("calculate", "Perform a math calculation") {
            param("expression", "string", "Math expression", required = true)
            param("precision", "integer", "Decimal places", required = false)
            execute { expr: String?, precision: Int? ->
                // parameters are nullable; missing ones are null
                "Result: ${eval(expr)}"
            }
        }
    }
}
```

The spread-parameter style provides `execute` overloads for 0–10 parameters, each typed nullable. For 11+ parameters you must use the strongly typed style.

### 6.2 Running an Agent

```kotlin
import top.resderx.rac.agent.Session
import top.resderx.rac.agent.agent
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val session = Session()
    val agent = agent(ai) {
        prompts("You are a weather assistant")
        tools {
            tool<WeatherArgs>("get_weather", "Look up weather") { args ->
                "${args.city} today sunny"
            }
        }
    }

    // run drives the multi-turn tool call loop automatically
    val result = agent.run(session, "How is the weather in Beijing today?")
    println(result.content)  // "Beijing today sunny"
}
```

**Agent.run execution flow**:

1. Append the user input to the Session
2. Dynamically splice the systemPrompt onto the request head (not stored in Session)
3. Inject the full conversation history + tool definitions
4. Call the model; if it returns toolCalls, execute the tools, feed results back, continue the next round
5. When the model no longer requests tools (or maxRounds is reached) return the final result
6. Append the final result to the Session

### 6.3 Streaming Agent

```kotlin
agent.runStream(session, "Write a poem and explain your creative thinking").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.Done -> println("\nDone: ${event.finishReason}")
        else -> {}
    }
}
```

`runStream` has the same semantics as `run` but returns events as an SSE stream. Delta events from intermediate (tool-call) rounds are forwarded in real time; only the final round forwards `Done`.

## 7. Session Multi-turn Conversations

`Session` records the full conversation history (user / assistant / tool messages) and preserves context across multiple rounds.

```kotlin
val session = Session()

// round one
agent.run(session, "My name is Zhang San")
// session now holds: [user("My name is Zhang San"), assistant("Hello Zhang San")]

// round two — the model remembers the context
agent.run(session, "What is my name?")
// session now holds: [user, assistant, user("What is my name?"), assistant("You are Zhang San")]
```

**Core Session design**:

- **No system messages**: system is an Agent property, dynamically spliced per request, never stored in Session
- **Reusable across Agents**: different Agents (with different systemPrompts) can share the same Session
- **Records full history**: includes intermediate tool-call traffic (AssistantMessage + ToolMessage), useful for debugging and persistence

```kotlin
// reuse the same Session across Agents
val session = Session()
val weatherAgent = agent(ai) { prompts("Weather assistant"); tools { ... } }
val translateAgent = agent(ai) { prompts("Translator") }

weatherAgent.run(session, "Beijing weather")       // handled by weather assistant
translateAgent.run(session, "Translate the last utterance")  // translator sees the full history
```

## 8. Customization Parameters

RAC exposes three customization parameters that can be set either inside `chat { }` or in `ModelConfig`:

### 8.1 Stop sequences (stop)

The model stops as soon as it generates any of the listed strings:

```kotlin
ai.chat {
    user("List 3 fruits")
    stop = listOf("\n\n", "END")  // stop on blank line or END
}
```

### 8.2 Random seed (seed)

For reproducible, deterministic output:

```kotlin
ai.chat {
    user("Generate a random number")
    seed = 42L  // same seed + same input → same output
}
```

> Note: some providers/models do not support `seed`; when unsupported it is silently ignored.

### 8.3 Thinking switch (enableThinking)

Controls the model's extended-thinking behavior:

```kotlin
ai.chat {
    user("Prove Goldbach's conjecture")
    enableThinking = true   // enable extended thinking
    // maxTokens = 8192     // consider raising maxTokens at the same time
}
```

**enableThinking behavior across APIs**:

| API         | enableThinking=true                                                | enableThinking=false         |
|-------------|-------------------------------------------------------------------|------------------------------|
| Completions | auto-sets `reasoningEffort="medium"` (when not explicitly set)    | forces `reasoningEffort=null`|
| Anthropic   | builds `thinking={type:"enabled", budget_tokens:maxTokens*4/5}`   | does not send thinking field |
| Responses   | unsupported, silently ignored                                     | unsupported, silently ignored|

### 8.4 Layered configuration

Parameters follow layered fallback: **`chat { }` explicit value → `ModelConfig` default → server default**.

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models {
                model(DeepSeekModel.V4_FLASH) {
                    temperature = 0.0   // ModelConfig default
                    maxTokens = 8192
                }
            }
        }
    }
}

ai.chat {
    user("hi")
    temperature = 0.5   // overrides ModelConfig's 0.0
    // maxTokens not set → falls back to ModelConfig's 8192
    // seed not set → falls back to server default
}
```

## 9. Multimodal Input

RAC supports multimodal user messages (text + image + audio) via the `user { }` content builder. Each model's supported modalities are declared on its `ModelConfig` through the `modalities { }` DSL block, and presets already fill them in based on each model's known capabilities.

### 9.1 Sending an image

```kotlin
val resp = ai.chat {
    user {
        text("What's in this picture?")
        image(base64 = "<base64-encoded-bytes>", mimeType = "image/png")
    }
}
println(resp.content)
```

You can also pass a publicly reachable URL:

```kotlin
ai.chat {
    user {
        text("Describe this image")
        image(url = "https://example.com/cat.jpg")
    }
}
```

### 9.2 Sending audio

```kotlin
ai.chat {
    user {
        text("Transcribe this audio")
        audio(base64 = "<base64-encoded-bytes>", mimeType = "audio/wav")
    }
}
```

### 9.3 Declaring modalities on a model

Presets prefill `modalities` for every model. To override on a specific registration:

```kotlin
openai {
    models {
        model(OpenAIModel.GPT_5_5) {
            modalities { image(); audio() }  // declare supported input modalities
        }
    }
}
```

**Per-provider modality coverage** (presets):

| Provider                     | Modalities            |
|------------------------------|-----------------------|
| OpenAI GPT-5.5 / 5.4         | TEXT + IMAGE + AUDIO  |
| OpenAI GPT-5.4 mini / nano   | TEXT + IMAGE          |
| Anthropic Claude (all)       | TEXT + IMAGE          |
| Gemini Pro / Flash 3         | TEXT + IMAGE + AUDIO  |
| Zhipu GLM (all)              | TEXT + IMAGE          |
| Tongyi Qianwen (all)         | TEXT + IMAGE          |
| Doubao (except Thinking)     | TEXT + IMAGE          |
| Doubao Thinking              | TEXT                  |
| DeepSeek / Kimi / MiniMax / MiMo | TEXT              |

> Note: audio support depends on the provider's API surface. Even when declared, the underlying vendor endpoint must accept audio input. For Completions-style APIs, audio is currently serialized as a text placeholder when the protocol has no native audio slot.

## 10. Next Steps

- Read the [API reference](usage.en.md) for full signatures of every function and class
- Read [Providers](providers.en.md) to learn each provider's baseUrl and auth method
- Read [API styles](api-styles.en.md) to understand the differences between the three protocols
- Read [ACP integration](acp.en.md) / [A2A integration](a2a.en.md) for bidirectional Agent protocol support
