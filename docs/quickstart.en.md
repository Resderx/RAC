**English** | [中文](quickstart.md)

# Quick Start

This guide helps you install, configure, and make your first AI call with RAC in 10 minutes, then walks through streaming, automated agents, tool calling, multi-turn conversations, and customization parameters. For complete API signature reference, read the [API Reference](usage.en.md).

## Table of Contents

- [1. Requirements](#1-requirements)
- [2. Installation](#2-installation)
- [3. Your First Call](#3-your-first-call)
- [4. Using Model Presets](#4-using-model-presets)
- [5. Streaming](#5-streaming)
- [6. Agents and Tool Calling](#6-agents-and-tool-calling)
- [7. Multi-turn Sessions](#7-multi-turn-sessions)
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

RAC is a KMP library supporting 9 targets: JVM, Android, iOS, mingwX64, linuxX64, linuxArm64, macosArm64, JS, and WasmJs. Your project only needs to add the corresponding Ktor engine to gain networking (RAC auto-configures platform engines via its convention plugin).

## 2. Installation

### 2.1 Kotlin Multiplatform Project

Add the `core` module to `commonMain` dependencies in `build.gradle.kts`:

```kotlin
kotlin {
    jvm()
    // add other targets as needed
}

dependencies {
    commonMain {
        implementation("top.resderx.rac:rac-core:0.1.0-alpha02")
    }
    // optional protocol modules
    // commonMain { implementation("top.resderx.rac:rac-mcp:0.1.0-alpha02") }
    // commonMain { implementation("top.resderx.rac:rac-acp:0.1.0-alpha02") }
    // commonMain { implementation("top.resderx.rac:rac-a2a:0.1.0-alpha02") }
}
```

Ensure your repositories include Maven Central:

```kotlin
repositories {
    mavenCentral()
}
```

### 2.2 Pure JVM / Android Project

```kotlin
dependencies {
    implementation("top.resderx.rac:rac-core:0.1.0-alpha02")
}
```

## 3. Your First Call

Create an `Llm` instance and make a non-streaming call:

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Create Llm instance — register the DeepSeek provider
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-your-api-key")
                models {
                    // register a model manually with custom parameters
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
    println(response.finishReason)     // stop reason (STOP/LENGTH/...)
}
```

**Notes**:

- `llm { }` is the top-level DSL entry point, returns an `Llm` instance
- `providers { }` block registers providers, each with a corresponding extension function (`deepseek` / `openai` / `anthropic`, etc.)
- `models { }` block registers models; the first registered model becomes that provider's default
- `chat { }` builds and sends a request, returns a unified `AIMessage`
- All calls are `suspend` functions — invoke them inside a coroutine scope

## 4. Using Model Presets

RAC provides preset enums for 43 mainstream models across 10 vendors. Register a model with recommended config in one line:

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
                // use preset recommended config entirely
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

**Available preset enums** (all in `top.resderx.rac.providers.presets`):

| Enum              | Vendor        | Models | Examples                                           |
|-------------------|---------------|--------|----------------------------------------------------|
| `DeepSeekModel`   | DeepSeek      | 2      | `V4_PRO`, `V4_FLASH`                               |
| `OpenAIModel`     | OpenAI        | 4      | `GPT_5_5`, `GPT_5_4`, `GPT_5_4_MINI`, `GPT_5_4_NANO` |
| `AnthropicModel`  | Anthropic     | 4      | `CLAUDE_OPUS_4_1`, `CLAUDE_SONNET_4_6`             |
| `GeminiModel`     | Google Gemini | 2      | `PRO_3`, `FLASH_3`                                 |
| `QwenModel`       | Tongyi Qianwen| 6      | `MAX_3_7`, `PLUS_3_7`, `FLASH_3_6`                 |
| `GlmModel`        | Zhipu GLM     | 4      | `GLM_5_2`, `GLM_4_7_FLASH`                         |
| `KimiModel`       | Moonshot      | 9      | `K2_5`, `K2_THINKING`, `V1_128K`                   |
| `DoubaoModel`     | ByteDance Doubao | 5   | `SEED_2_1_PRO`, `SEED_1_6_THINKING`                |
| `MinimaxModel`    | MiniMax       | 3      | `ABAB7`, `M2_5`                                    |
| `MimoModel`       | Xiaomi MiMo   | 2      | `V2_5_PRO`, `V2_FLASH`                             |

Reasoning/thinking models (e.g. `DeepSeekModel.V4_PRO`, `KimiModel.K2_THINKING`) have presets that auto-set `enableThinking = true` and an appropriate `reasoningEffort`.

## 5. Streaming

Streaming calls return `Flow<StreamEvent>`, letting you process incremental chunks of model output in real time:

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
                // print each text chunk in real time
                print(event.delta)
            }
            is StreamEvent.ReasoningDelta -> {
                // reasoning process (only produced by reasoning models)
                // print("[thinking] ${event.delta}")
            }
            is StreamEvent.Done -> {
                // stream ended, carries full information
                println("\n--- done ---")
                println("total tokens: ${event.usage?.totalTokens}")
            }
            is StreamEvent.ToolCallDelta -> {
                // tool call increment (streaming tool calls)
            }
        }
    }
}
```

**Four StreamEvent types**:

| Event              | Fields                                                                  | Description                          |
|--------------------|-------------------------------------------------------------------------|--------------------------------------|
| `TextDelta`        | `delta` (new chunk), `accumulated` (accumulated body)                   | Body text chunk produced by model    |
| `ReasoningDelta`   | `delta`, `accumulated`                                                  | Thinking process chunk (reasoning models only) |
| `ToolCallDelta`    | `index`, `id`, `name`, `argumentsDelta`, `argumentsAccumulated`         | Tool call increment (fragments aggregated) |
| `Done`             | `content`, `reasoningContent`, `toolCalls`, `usage`, `finishReason`     | Stream end, self-contained full info |

**API selection**:

- `chatStream { }` — Completions API streaming (DeepSeek/OpenAI/Qwen and most providers)
- `anthropicStream { }` — Anthropic API streaming (Anthropic provider only)
- `respondStream { }` — Responses API streaming (OpenAI Responses only)

## 6. Agents and Tool Calling

`Agent` encapsulates "LLM + system prompt + toolset" and automatically runs the multi-turn tool-call loop. Pair it with `Session` to manage conversation history.

### 6.1 Defining Tools

RAC supports two tool definition modes that can be mixed in the same `tools { }` block.

**Mode 1: Strongly typed (recommended for complex parameters)**

Auto-generate JSON Schema via `@Serializable data class`:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class WeatherArgs(
    val city: String,           // required
    val date: String? = null,   // optional
)

val agent = agent(ai) {
    prompts("You are a weather assistant that can query weather")
    tools {
        tool<WeatherArgs>("get_weather", "Query weather for a city") { args ->
            // args is auto-deserialized
            "${args.city} today sunny, 25°C"
        }
    }
}
```

**Mode 2: Spread params (no data class needed, quick definition)**

```kotlin
val agent = agent(ai) {
    prompts("You are a calculator assistant")
    tools {
        tool("calculate", "Execute a math calculation") {
            param("expression", "string", "Math expression", required = true)
            param("precision", "integer", "Decimal places", required = false)
            execute { expr: String?, precision: Int? ->
                // parameters are nullable, null when missing
                "result: ${eval(expr)}"
            }
        }
    }
}
```

The spread-params mode supports `execute` overloads for 0–10 parameters; each parameter type is nullable. For 11+ parameters use the strongly-typed mode.

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
            tool<WeatherArgs>("get_weather", "Query weather") { args ->
                "${args.city} today sunny"
            }
        }
    }

    // run automatically completes the multi-turn tool-call loop
    val result = agent.run(session, "What's the weather in Beijing today?")
    println(result.content)  // "Beijing today sunny"
}
```

**Agent.run execution flow**:

1. Append user input to the Session
2. Dynamically splice `systemPrompt` to the request header (not stored in Session)
3. Inject full conversation history + tool definitions
4. Call the model; if it returns toolCalls, execute the tools, feed results back, continue the next round
5. Return the final result when the model no longer requests tool calls (or `maxRounds` is reached)
6. Append the final result to the Session

### 6.3 Streaming Agent

```kotlin
agent.runStream(session, "Write a poem and explain the creative thinking").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.Done -> println("\nDone: ${event.finishReason}")
        else -> {}
    }
}
```

`runStream` has the same semantics as `run` but returns events as an SSE stream. Intermediate (tool-call) rounds forward delta events in real time; only the final round forwards `Done`.

## 7. Multi-turn Sessions

`Session` records the full conversation history (user / assistant / tool messages) and preserves context across multi-turn calls.

```kotlin
val session = Session()

// first turn
agent.run(session, "My name is Zhang San")
// session now contains: [user("My name is Zhang San"), assistant("Hello Zhang San")]

// second turn — the model remembers the context
agent.run(session, "What's my name?")
// session now contains: [user, assistant, user("What's my name"), assistant("You are Zhang San")]
```

**Key Session design**:

- **No system messages**: system is an Agent property, dynamically spliced per request, never stored in Session
- **Reusable across Agents**: different Agents (with different systemPrompt) can share the same Session
- **Records full history**: includes intermediate tool-call process (AssistantMessage + ToolMessage) for debugging and persistence

```kotlin
// reuse the same Session across Agents
val session = Session()
val weatherAgent = agent(ai) { prompts("Weather assistant"); tools { ... } }
val translateAgent = agent(ai) { prompts("Translator") }

weatherAgent.run(session, "Beijing weather")      // handled by weather assistant
translateAgent.run(session, "Translate the previous sentence")  // translator sees full history
```

## 8. Customization Parameters

RAC provides three customization parameters that can be set in the `chat { }` block or in `ModelConfig`:

### 8.1 Stop sequences (`stop`)

The model stops immediately upon generating any of the strings:

```kotlin
ai.chat {
    user("List 3 fruits")
    stop = listOf("\n\n", "END")  // stop at blank line or END
}
```

### 8.2 Random seed (`seed`)

For reproducible deterministic output:

```kotlin
ai.chat {
    user("Generate a random number")
    seed = 42L  // same seed + same input → same output
}
```

> Note: some providers/models don't support `seed`; when set it is silently ignored.

### 8.3 Thinking switch (`enableThinking`)

Controls the model's extended-thinking behavior:

```kotlin
ai.chat {
    user("Prove Goldbach's conjecture")
    enableThinking = true   // enable extended thinking
    // maxTokens = 8192     // consider raising maxTokens as well
}
```

**enableThinking behavior across APIs**:

| API         | enableThinking=true                                          | enableThinking=false      |
|-------------|--------------------------------------------------------------|---------------------------|
| Completions | Auto-set `reasoningEffort="medium"` (when not explicitly set) | Force `reasoningEffort=null` |
| Anthropic   | Construct `thinking={type:"enabled", budget_tokens:maxTokens*4/5}` | Omit thinking field      |
| Responses   | Not supported, silently ignored                              | Not supported, silently ignored |

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
    // maxTokens not set, falls back to ModelConfig's 8192
    // seed not set, falls back to server default
}
```

## 9. Multimodal Input

RAC supports multimodal user messages (text + image + audio) via the `user { }` content builder. Each model declares the modalities it accepts through `ModelConfig.modalities` (auto-populated by presets).

```kotlin
val ai = llm {
    providers {
        openai {
            apiKey("sk-...")
            models {
                // GPT-5.5 preset declares TEXT + IMAGE + AUDIO
                model(OpenAIModel.GPT_5_5)
            }
        }
    }
}

// send an image along with text
val resp = ai.chat {
    user {
        text("What's in this image?")
        image(base64 = "iVBORw0KG...", mimeType = "image/png")
        // or: image(url = "https://example.com/photo.jpg")
    }
}
println(resp.content)
```

**`user { }` content builder methods**:

| Method | Description |
|--------|-------------|
| `text(s)` | Append a text chunk |
| `image(url=null, base64=null, mimeType="image/jpeg")` | Append an image (at least one of url/base64 required) |
| `audio(base64, mimeType="audio/wav")` | Append an audio chunk |

**Per-protocol serialization** (handled automatically by RAC):

- Completions: `{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}`
- Anthropic: `{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}`
- Responses: `{"type":"input_image","image_url":"..."}` (flat string)

Audio content is currently serialized as a text placeholder (no native Completions audio field); Anthropic/Responses serialization follows the same pattern as images.

**Checking model capabilities**:

```kotlin
val provider = ai.provider("openai")
val config = provider.models["gpt-5.5"]!!
if (Modality.IMAGE in config.modalities) {
    // safe to send ImageContent
}
```

## 10. Next Steps

- Read the [API Reference](usage.en.md) for full signatures of every function and class
- Read [Providers](providers.en.md) for baseUrl and auth details of each provider
- Read [API Styles](api-styles.en.md) for the differences between the three protocols
- Read [ACP Integration](acp.en.md) / [A2A Integration](a2a.en.md) for bidirectional agent protocols
