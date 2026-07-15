**English** | [中文](README.md)

# RAC — Kotlin Multiplatform AI Model Call Library

RAC is an AI model call library based on Kotlin Multiplatform and Ktor. It connects to 11 LLM providers through a
unified `llm { }` DSL entry point, covering 3 API protocols (OpenAI Chat Completions, OpenAI Responses, Anthropic
Messages). It supports non-streaming calls, streaming calls, Tool Calling, and automated Agents, with bidirectional
support for Agent Client Protocol (ACP) and Agent-to-Agent Protocol (A2A) — it can act as a Client to call external
Agents and also expose its own Agent as an ACP / A2A Server.

### Notes

- Currently only the core module's agent and model calls have been tested; only GLM and DeepSeek providers have been
  tested.
- This library is primarily intended for lightweight cross-platform model calls. If you need to build a JVM backend,
  please use [JetBrains/Koog](https://github.com/Jetbrains/koog).

## Features

- **11 providers out of the box**: DeepSeek, OpenAI, Anthropic, Google Gemini, Alibaba Qwen, Zhipu GLM, Moonshot Kimi,
  ByteDance Doubao, MiniMax, Xiaomi MiMo, Ollama (local)
- **Model preset enums for 10 providers**: 43 mainstream models with built-in recommended configurations; register with
  a single line `model(DeepSeekModel.V4_FLASH)`, no boilerplate needed
- **Unified abstraction for 3 API protocols**: Completions API, Responses API, Anthropic API, auto-routed via `chat { }`
- **Streaming calls**: `chatStream { }` (Completions), `anthropicStream { }` (Anthropic), `respondStream { }` (
  Responses), based on SSE cold flows, unified into `StreamEvent` semantic events
- **Automated Agent**: `agent(llm) { prompts(); tools { } }` declarative configuration; `run` / `runStream`
  automatically complete multi-turn tool call loops; `Session` records complete conversation history and can be reused
  across Agents
- **Dual-mode tool definition**: Strongly-typed `tool<Args>(name, desc) { }` auto-generates JSON Schema; spread
  parameters `tool(name, desc) { param(); execute { } }` requires no data class, supports 0-10 parameters
- **Customization parameters**: `stop` (stop sequences), `seed` (random seed), `enableThinking` (thinking toggle),
  synchronized across the stack and handled differently per API
- **MCP support**: Native support for Model Context Protocol; `chatWithMcp { }` auto-discovers MCP tools and performs
  multi-turn calls; supports Stdio / HTTP transport
- **ACP bidirectional support**: `chatWithAcpAgent { }` acts as a Client to call external Agents (Claude Code, Codex
  CLI, etc.); `serveAsAcpAgent { }` exposes RAC as an ACP Agent Server
- **A2A bidirectional support**: `chatWithA2aAgent` acts as a Client to call remote A2A Agents (Google ADK, LangGraph,
  etc.); `serveAsA2aAgent` exposes RAC as an A2A Agent Server
- **Network resilience**: `RetryExecutor` provides exponential backoff retry, automatically handling 429/5xx transient
  errors and `Retry-After` headers
- **Cross-platform**: The same common code compiles to 9 target platforms — JVM, Android, iOS, mingwX64, linuxX64,
  linuxArm64, macosArm64, JS, WasmJs
- **Testable**: Dependency-injected HttpClient; JVM supports end-to-end testing with MockEngine

## Platform Support Matrix

| Platform                           | Supported | Ktor Engine    |
|------------------------------------|-----------|----------------|
| JVM                                | ✅         | OkHttp         |
| Android (minSdk 24)                | ✅         | OkHttp         |
| iOS (iosArm64 / iosSimulatorArm64) | ✅         | Darwin         |
| mingwX64 (Windows native)          | ✅         | WinHttp + Curl |
| linuxX64                           | ✅         | Curl           |
| linuxArm64                         | ✅         | Curl           |
| macosArm64                         | ✅         | Darwin         |
| JS (browser / nodejs)              | ✅         | JS             |
| WasmJs (browser / nodejs)          | ✅         | JS             |

## Module Composition

RAC is divided into 4 Gradle modules (dependency DAG: `core ← mcp ← {acp, a2a}`):

| Module | Maven Coordinate                         | Purpose                                                                                        |
|--------|------------------------------------------|------------------------------------------------------------------------------------------------|
| core   | `top.resderx.rac:rac-core:0.1.0-alpha01` | Message models, network layer, API protocol clients, provider implementations, base DSL, Agent |
| mcp    | `top.resderx.rac:rac-mcp:0.1.0-alpha01`  | MCP client + `Llm.chatWithMcp` extension function                                              |
| acp    | `top.resderx.rac:rac-acp:0.1.0-alpha01`  | ACP bidirectional support + `Llm.chatWithAcpAgent` / `Llm.serveAsAcpAgent` extension functions |
| a2a    | `top.resderx.rac:rac-a2a:0.1.0-alpha01`  | A2A bidirectional support + `Llm.chatWithA2aAgent` / `Llm.serveAsA2aAgent` extension functions |

To use only the basic chat capabilities, just include `core`; include the corresponding module (along with `core`) when
you need MCP / ACP / A2A protocol integration.

## Installation

### Gradle (Kotlin Multiplatform)

Add the following to the `commonMain` dependencies in your `build.gradle.kts`:

```kotlin
kotlin {
    // Configure your target platforms, e.g.:
    jvm()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()
}

dependencies {
    commonMain {
        implementation("top.resderx.rac:rac-core:0.1.0-alpha01")
        // Include protocol modules as needed
        implementation("top.resderx.rac:rac-mcp:0.1.0-alpha01")
        implementation("top.resderx.rac:rac-acp:0.1.0-alpha01")
        implementation("top.resderx.rac:rac-a2a:0.1.0-alpha01")
    }
}
```

### Pure JVM / Android Projects

```kotlin
dependencies {
    implementation("top.resderx.rac:rac-core:0.1.0-alpha01")
}
```

## Quick Example

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import top.resderx.rac.providers.presets.DeepSeekModel
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Create an Llm instance — register the DeepSeek provider and use a preset model
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-...")
                models {
                    // Register a preset model in one line, auto-applying recommended config
                    model(DeepSeekModel.V4_FLASH)
                }
            }
        }
    }

    // 2. Non-streaming chat
    val response = ai.chat {
        user("Explain Kotlin Multiplatform in one sentence")
    }
    println(response.content)
}
```

See the documentation links below for more examples.

## Documentation

- **[Quick Start](docs/quickstart.en.md)** — From installation to your first call, a complete tutorial covering
  streaming, Agent, tool calls, and customization parameters
- **[API Reference](docs/usage.en.md)** — Complete signatures, parameter descriptions, and usage examples for every
  public function, class, and property
- **[Provider Configuration](docs/providers.en.md)** — baseUrl, authentication methods, and model preset enums for 11
  providers
- **[API Protocol Styles](docs/api-styles.en.md)** — Differences and choices between the Completions / Anthropic /
  Responses protocols
- **[ACP Integration](docs/acp.en.md)** — Agent Client Protocol bidirectional support in detail
- **[A2A Integration](docs/a2a.en.md)** — Agent-to-Agent Protocol bidirectional support in detail

## Core Design

- **DSL entry**: `llm { }` creates an `Llm` instance, `providers { }` registers providers, `models { }` registers
  models, `chat { }` builds requests
- **Layered configuration**: `ModelConfig` (defaults at model registration) → `ChatRequestBuilder` (override at call
  time) → server defaults. Fields not explicitly set in the builder fall back to ModelConfig, and if still null, fall
  back to server defaults
- **Unified returns**: All protocol responses are normalized to `AIMessage` (content / reasoningContent / toolCalls /
  usage / finishReason); streaming responses are normalized to the `StreamEvent` sealed interface
- **Agent decoupling**: `Agent` holds the systemPrompt and tool set; `Session` only records user/assistant/tool
  messages (no system); different Agents can share the same Session
- **Dual tool modes**: Strongly-typed `tool<Args>` auto-generates JSON Schema from `@Serializable data class`; spread
  parameters `tool { param(); execute { } }` requires no class definition, supports 0-10 parameters; 11+ parameters
  require the strongly-typed mode

## License

See [LICENSE](LICENSE).
