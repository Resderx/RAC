# RAC（ResDerX AI Call）— Kotlin Multiplatform AI 模型调用库

RAC 是一个基于 Kotlin Multiplatform 与 Ktor 的 AI 模型调用库，通过统一的 DSL 入口接入 11 家 LLM 供应商，覆盖 3 种 API 协议（OpenAI Chat Completions、OpenAI Responses、Anthropic Messages），支持非流式调用、流式调用与工具调用（Tool Calling），并双向支持 Agent Client Protocol（ACP）与 Agent-to-Agent Protocol（A2A）——既能作为 Client 调用外部 Agent，也能将自身 Agent 暴露为 ACP / A2A Server。

## 特性

- **11 家供应商开箱即用**：DeepSeek、OpenAI、Anthropic、Kimi、GLM（智谱）、MiniMax、Ollama、Doubao（火山方舟）、Qwen（通义千问）、MIMO（小米）、Gemini
- **3 种 API 协议统一抽象**：Completions API、Responses API、Anthropic API，通过 `chat { }` 自动路由
- **流式调用**：`chatStream { }`（Completions）、`anthropicStream { }`（Anthropic）、`respondStream { }`（Responses），基于 SSE 冷流
- **工具调用**：通过 `tools { }` DSL 声明可用工具，统一返回 `AIMessage.toolCalls`；`chatWithTools { }` 自动执行多轮工具调用循环
- **MCP 支持**：原生支持 Model Context Protocol，`chatWithMcp { }` 自动发现 MCP 工具并执行多轮调用，支持 Stdio / HTTP 传输
- **ACP 支持**：双向支持 Agent Client Protocol v1——`chatWithAcpAgent { }` 作为 Client 调用外部 Agent（Claude Code、Codex CLI 等），`serveAsAcpAgent { }` 将 RAC 自身暴露为 ACP Agent Server
- **A2A 支持**：双向支持 Agent-to-Agent Protocol v1.0——`chatWithA2aAgent` 作为 Client 调用远端 A2A Agent（Google ADK、LangGraph 等），`serveAsA2aAgent` 将 RAC 暴露为 A2A Agent Server（协议无关 JSON-RPC 分发器）
- **网络韧性**：`RetryExecutor` 提供指数退避重试，自动处理 429/5xx 瞬时错误与 `Retry-After` 头
- **跨平台**：同一套通用代码编译到 JVM、Android、iOS、mingwX64、linuxX64、linuxArm64、macosArm64、JS、WasmJs
- **声明式 DSL**：`rac { }` 入口注册供应商，`chat { }` / `respond { }` 构建请求，`@DslMarker` 保证作用域清洁
- **统一返回模型**：所有协议响应经映射归一化为 `AIMessage`（content / reasoningContent / toolCalls / usage / finishReason）
- **可测试**：依赖注入 HttpClient，JVM 可用 MockEngine 端到端测试

## 平台支持矩阵

| 平台 | 支持 | Ktor 引擎 |
| --- | --- | --- |
| JVM | ✅ | OkHttp |
| Android（minSdk 24） | ✅ | OkHttp |
| iOS（iosArm64 / iosSimulatorArm64） | ✅ | Darwin |
| mingwX64（Windows 原生） | ✅ | WinHttp + Curl |
| linuxX64 | ✅ | Curl |
| linuxArm64 | ✅ | Curl |
| macosArm64 | ✅ | Darwin |
| JS（浏览器 / Node.js） | ✅ | Js |
| WasmJs（浏览器 / Node.js） | ✅ | Js |

## Gradle 配置

> **开发机 Gradle 配置**：本项目开发机 Gradle 用户主目录设为 `D:\AppData\Gradle`。请在终端执行
> `$env:GRADLE_USER_HOME='D:\AppData\Gradle'`，或在系统环境变量中永久设置 `GRADLE_USER_HOME=D:\AppData\Gradle`。

### 添加依赖

RAC 已配置 `com.vanniktech.maven.publish` 插件，发布坐标为 `com.resderx.rac:core:0.0.1-alpha`。

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.resderx.rac:core:0.0.1-alpha")
        }
    }
}
```

### 技术栈版本

| 依赖 | 版本 |
| --- | --- |
| Kotlin | 2.4.0 |
| Ktor | 3.5.1 |
| kotlinx-coroutines | 1.11.0 |
| kotlinx-serialization | 1.11.0 |
| kotlinx-schema | 0.4.4 |

## 快速开始

通过 `rac { }` DSL 注册供应商并获取 `RAC` 实例，再调用 `chat { }` 发起对话：

```kotlin
import com.resderx.rac.dsl.rac
import com.resderx.rac.dsl.deepseek

suspend fun main() {
    val ai = rac {
        deepseek {
            apiKey(System.getenv("DEEPSEEK_API_KEY"))
        }
    }
    val resp = ai.chat {
        system("你是一个简洁的助手")
        user("用一句话介绍 Kotlin Multiplatform")
    }
    println(resp.content)
    ai.httpClient.close()
}
```

`chat { }` 会根据默认供应商的协议类型自动路由：Completions 走 `/chat/completions`，Anthropic 走 `/messages`，Responses 走 `/responses`。

## 供应商使用

所有供应商通过 `rac { }` 块内同名的 DSL 扩展函数注册，首个注册的供应商自动成为默认。每个 DSL 接收 `ProviderConfigBuilder` 作用域，可覆盖 `apiKey` / `model` / `baseUrl` / `timeoutMillis` / 自定义 `header`。详细说明见 [docs/providers.md](docs/providers.md)。

### DeepSeek

```kotlin
val ai = rac {
    deepseek {
        apiKey(System.getenv("DEEPSEEK_API_KEY"))
        // 默认模型为 deepseek-v4-flash；切换到推理模型：
        // model("deepseek-v4-pro")
    }
}
```

> **迁移提示**：旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026-07-24 停用，自 v0.2.0 起默认模型已切换为 `deepseek-v4-flash`。

### OpenAI

```kotlin
val ai = rac {
    openai {
        apiKey(System.getenv("OPENAI_API_KEY"))
        model("gpt-4o")
    }
}
```

### Anthropic

```kotlin
val ai = rac {
    anthropic {
        apiKey(System.getenv("ANTHROPIC_API_KEY"))
        // anthropic-version 头由工厂自动注入
    }
}
```

### Kimi（Moonshot AI）

```kotlin
val ai = rac {
    kimi {
        apiKey(System.getenv("KIMI_API_KEY"))
        model("moonshot-v1-32k") // 长上下文
    }
}
```

### GLM（智谱 AI）

```kotlin
val ai = rac {
    glm {
        apiKey(System.getenv("GLM_API_KEY"))
        model("glm-4-plus")
    }
}
```

### MiniMax

```kotlin
val ai = rac {
    minimax {
        apiKey(System.getenv("MINIMAX_API_KEY"))
    }
}
```

### Ollama（本地）

```kotlin
val ai = rac {
    ollama {
        // 本地模式：无需 apiKey，默认连接 http://localhost:11434/v1
        model("llama3.1")
        // 云端模式：同时覆盖 baseUrl 与 apiKey
        // baseUrl("https://your-ollama-host/v1")
        // apiKey(System.getenv("OLLAMA_API_KEY"))
    }
}
```

### Doubao（火山引擎方舟）

```kotlin
val ai = rac {
    doubao {
        apiKey(System.getenv("DOUBAO_API_KEY"))
        // 生产环境通常需覆盖为接入点 ID：
        // model("ep-xxxxxxxx")
    }
}
```

### Qwen（阿里 DashScope）

```kotlin
val ai = rac {
    qwen {
        apiKey(System.getenv("QWEN_API_KEY"))
        model("qwen-max")
    }
}
```

### MIMO（小米）

```kotlin
val ai = rac {
    mimo {
        apiKey(System.getenv("MIMO_API_KEY"))
    }
}
```

### Gemini

```kotlin
val ai = rac {
    gemini {
        apiKey(System.getenv("GEMINI_API_KEY"))
        model("gemini-2.0-flash")
    }
}
```

## 流式调用

RAC 为三种协议分别提供流式入口（流元素类型与协议强耦合，故拆分方法以保证类型安全）。

### chatStream { }（Completions 协议）

返回 `Flow<CompletionsStreamChunk>`，仅 Completions 协议供应商可用：

```kotlin
import kotlinx.coroutines.flow.collect

suspend fun streamExample(ai: RAC) {
    ai.chatStream {
        user("讲一个关于 Kotlin 的冷笑话")
    }.collect { chunk ->
        // chunk.choices.firstOrNull()?.delta?.content 为增量文本
        print(chunk.choices.firstOrNull()?.delta?.content ?: "")
    }
}
```

### anthropicStream { }（Anthropic 协议）

返回 `Flow<AnthropicStreamEvent>`，仅 Anthropic 协议供应商可用：

```kotlin
suspend fun anthropicStreamExample(ai: RAC) {
    ai.anthropicStream {
        user("逐步推导 1+1=2")
    }.collect { event ->
        when (event) {
            is AnthropicStreamEvent.ContentBlockDelta -> {
                val delta = event.delta
                if (delta is Delta.TextDelta) print(delta.text ?: "")
            }
            is AnthropicStreamEvent.MessageStop -> println("\n[完成]")
            else -> Unit
        }
    }
}
```

在非 Completions 供应商上调用 `chatStream { }` 或在非 Anthropic 供应商上调用 `anthropicStream { }` 会抛 `RACException`，提示改用对应方法。

## 工具调用

通过 `tools { }` DSL 声明可用工具集，模型返回的工具调用统一映射到 `AIMessage.toolCalls`：

```kotlin
suspend fun toolExample(ai: RAC) {
    val resp = ai.chat {
        user("北京今天天气如何？")
        tools {
            tool(
                name = "get_weather",
                description = "查询指定城市的当前天气",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "city": { "type": "string", "description": "城市名称" }
                      },
                      "required": ["city"]
                    }
                """.trimIndent()
            )
        }
    }
    if (resp.finishReason == FinishReason.TOOL_CALLS) {
        val call = resp.toolCalls.first()
        println("模型请求调用工具：${call.name}，参数：${call.arguments}")
        // 执行工具后将结果回传：
        // ai.chat {
        //     assistant(resp.content)
        //     tool(call.id, """{"city":"北京","temp":26,"cond":"晴"}""")
        // }
    }
}
```

`tool(id, content)` 用于向对话追加工具回执消息，`id` 需对应上一次 `ToolCall.id`。

### 自动多轮工具调用：chatWithTools { }

手动管理工具调用循环（执行工具 → 回写 ToolMessage → 再次调用模型）较繁琐。`chatWithTools { }` 封装了完整的闭环：模型返回 `toolCalls` 时自动调用你提供的 `toolExecutor`，将结果回写对话历史后再次调用模型，循环直至模型不再请求工具或达到 `maxRounds` 上限。

```kotlin
suspend fun toolLoopExample(ai: RAC) {
    val resp = ai.chatWithTools(
        maxRounds = 10,
        toolExecutor = { call ->
            // call.name / call.arguments 由模型生成
            when (call.name) {
                "get_weather" -> """{"city":"北京","temp":26,"cond":"晴"}"""
                else -> """{"error":"unknown tool"}"""
            }
        },
    ) {
        user("北京今天天气如何？")
        tools {
            tool("get_weather", "查询指定城市的当前天气", """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""")
        }
    }
    println(resp.content) // 模型基于工具结果生成的最终答案
}
```

`maxRounds` 控制最大循环轮数（不含首轮调用），达到上限时返回最后一次响应（此时 `finishReason` 仍为 `TOOL_CALLS`，调用方可据此判断是否未完成）。`toolExecutor` 为 `suspend` lambda，支持异步工具实现。

### MCP 工具集成：chatWithMcp { }

[MCP（Model Context Protocol）](https://modelcontextprotocol.io/) 是 Anthropic 提出的标准化 AI 工具协议。RAC 原生支持 MCP，通过 `chatWithMcp { }` 自动从 MCP 服务器发现工具并注入对话，复用 `chatWithTools` 的多轮调用循环：

```kotlin
import com.resderx.rac.mcp.McpClient
import com.resderx.rac.mcp.McpClientConfig
import com.resderx.rac.mcp.HttpTransport

suspend fun mcpExample(ai: RAC) {
    // 连接 MCP 服务器（HTTP 传输，全平台支持）
    val mcp: McpClient = McpClient(
        McpClientConfig(
            transport = HttpTransport(serverUrl = "http://localhost:3000/mcp"),
            timeoutMillis = 30_000,
        ),
    )
    val resp = ai.chatWithMcp(
        mcpClient = mcp,
        maxRounds = 10,
    ) {
        user("帮我搜索 Kotlin 协程的最新资料")
    }
    println(resp.content)
    mcp.close() // 调用方负责关闭 MCP 客户端
}
```

支持的传输方式：
- **HTTP 传输**（`HttpTransport`）：全平台支持，复用 `RetryExecutor` 自动重试
- **Stdio 传输**（`StdioTransport`）：通过子进程 stdin/stdout 交换 JSON-RPC，当前仅 JVM 平台完整实现，其他平台抛 `UnsupportedOperationException`

`McpClient` 接口也可手动实现以集成自定义工具源（如本地函数、数据库查询等），`listTools()` 返回的工具会与 `block` 内通过 `tools { }` 声明的本地工具合并。

## ACP（Agent Client Protocol）

[ACP（Agent Client Protocol）](https://agentclientprotocol.com/) 是 Zed 与 JetBrains 联合推出的标准化 AI 编码助手通信协议，基于 JSON-RPC 2.0，定义 Editor（Client）与 Agent 之间的双向通信。RAC 双向支持 ACP v1——既能作为 Client 调用任何兼容 ACP 的 Agent，也能将自身暴露为 ACP Agent Server 供其他 Editor 调用。

### 作为 Client：chatWithAcpAgent { }

`chatWithAcpAgent` 让 RAC 通过 ACP 协议调用外部 Agent（如 Claude Code、Codex CLI、Gemini CLI），将 Agent 的响应归一化为 `AIMessage`：

```kotlin
import com.resderx.rac.acp.AcpClient
import com.resderx.rac.acp.AcpClientConfig
import com.resderx.rac.acp.AcpStdioTransport
import com.resderx.rac.acp.ImplementationInfo

suspend fun acpClientExample(ai: RAC) {
    // 通过 stdio 连接外部 Agent 进程
    val client: AcpClient = AcpClient(
        AcpClientConfig(
            transport = AcpStdioTransport(
                command = "claude",
                args = listOf("agent"),
                cwd = "/project",
            ),
            clientInfo = ImplementationInfo(name = "my-editor", version = "1.0.0"),
        ),
    )
    val resp = ai.chatWithAcpAgent(
        client = client,
        prompt = "重构 src/Main.kt 中的重复代码",
        cwd = "/project",
    ) { update ->
        // 流式接收 Agent 更新（AgentMessageChunk / ToolCallUpdate / PlanUpdate 等）
        println(update)
    }
    println(resp.content)
    client.close()
}
```

### 作为 Agent Server：serveAsAcpAgent { }

`serveAsAcpAgent` 将 RAC 自身的 LLM 调用能力封装为 ACP Agent Server，供任何兼容 ACP 的 Editor 调用：

```kotlin
import com.resderx.rac.acp.AcpAgentServer

suspend fun acpServerExample(ai: RAC) {
    val server: AcpAgentServer = ai.serveAsAcpAgent(
        agentInfo = ImplementationInfo(
            name = "rac-agent",
            title = "RAC Agent",
            version = "0.2.0",
        ),
        systemPrompt = "你是一个 Kotlin 编程助手",
    )
    // 启动 stdio 服务端，阻塞当前协程直到 Editor 断开
    server.start().join()
    server.close()
}
```

ACP 详细协议映射、传输方式、权限处理等说明见 [docs/acp.md](docs/acp.md)。

## A2A（Agent-to-Agent Protocol）

[A2A（Agent-to-Agent Protocol）](https://a2a-protocol.org/) 是 Google 发布的开放协议，基于 JSON-RPC 2.0 + HTTP + SSE，定义 Agent 间的标准化通信。RAC 双向支持 A2A v1.0——既能作为 Client 调用任何兼容 A2A 的远端 Agent，也能将自身暴露为 A2A Agent Server。

### 作为 Client：chatWithA2aAgent

`chatWithA2aAgent` 让 RAC 通过 A2A 协议调用远端 Agent（如 Google ADK、LangGraph、CrewAI 等），将 Agent 的流式响应归一化为 `AIMessage`：

```kotlin
import com.resderx.rac.a2a.A2aClient
import com.resderx.rac.a2a.A2aClientConfig

suspend fun a2aClientExample(ai: RAC) {
    val client = A2aClient(
        A2aClientConfig(
            baseUrl = "https://agent.example.com",
            apiKey = System.getenv("REMOTE_AGENT_API_KEY"),
        ),
    )
    val resp = ai.chatWithA2aAgent(
        client = client,
        prompt = "帮我分析这份销售数据的趋势",
    ) { event ->
        // 流式接收 Agent 更新（Initial / StatusUpdate / ArtifactUpdate）
        when (event) {
            is A2aStreamEvent.ArtifactUpdate -> {
                event.event.artifact.parts
                    .filterIsInstance<TextPart>()
                    .forEach { print(it.text) }
            }
            else -> Unit
        }
    }
    println(resp.content)
    client.close()
}
```

### 作为 Agent Server：serveAsA2aAgent

`serveAsA2aAgent` 将 RAC 的 LLM 调用能力封装为 A2A Agent Server，返回协议无关的 JSON-RPC 分发器：

```kotlin
val server = ai.serveAsA2aAgent(
    agentCard = AgentCard(
        name = "rac-agent",
        description = "RAC Agent — Kotlin Multiplatform AI Call Library",
        url = "https://my-agent.example.com",
        provider = AgentProvider(organization = "ResDerX"),
    ),
    systemPrompt = "你是一个 Kotlin 编程助手",
)

// 获取 Agent Card JSON（供 HTTP 服务器在 /.well-known/agent.json 返回）
val cardJson = server.getAgentCardJson()

// 分发 JSON-RPC 请求（调用方绑定到 HTTP 端点）
val response = server.dispatch(requestJson)
val events = server.dispatchStreaming(requestJson) // 流式

server.close()
```

> `A2aAgentServer` 是协议无关分发器，不绑定 HTTP 服务器——调用方需自行将 `dispatch` / `dispatchStreaming` 绑定到 HTTP 端点。这是 KMP 库不引入 HTTP 服务器依赖的设计选择。

A2A 详细协议映射、Task 生命周期、Part 类型等说明见 [docs/a2a.md](docs/a2a.md)。

## Responses API

`respond { }` 显式走 OpenAI Responses API（`/responses`），适合需要 `instructions` 系统指令或 Responses 专属特性的场景。当前仅 OpenAI 官方支持。

```kotlin
suspend fun respondExample(ai: RAC) {
    val resp = ai.respond {
        input("用三句话解释量子纠缠")
        instructions("你是面向儿童的科普助手，语言要简单易懂")
        model("gpt-4o")
        temperature = 0.7
    }
    println(resp.content)
}
```

流式版本 `respondStream { }` 返回 `Flow<ResponsesStreamEvent>`。

## 测试

RAC 采用分层测试策略，详见 [docs/testing.md](docs/testing.md)。

### 运行平台测试

```powershell
# JVM 测试（含 MockEngine 端到端）
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest

# mingwX64 原生测试
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:mingwX64Test
```

### 启用集成测试

集成测试默认跳过，需同时设置以下环境变量：

| 环境变量 | 作用 |
| --- | --- |
| `RAC_INTEGRATION_TEST=true` | 总开关，启用集成测试 |
| `RAC_DEEPSEEK_API_KEY` | DeepSeek 真实调用密钥 |
| `RAC_OPENAI_API_KEY` | OpenAI 真实调用密钥 |
| `RAC_ANTHROPIC_API_KEY` | Anthropic 真实调用密钥 |

```powershell
$env:RAC_INTEGRATION_TEST='true'
$env:RAC_DEEPSEEK_API_KEY='sk-...'
$env:GRADLE_USER_HOME='D:\AppData\Gradle'; .\gradlew.bat :core:jvmTest
```

## 文档

- [供应商详解](docs/providers.md) — 11 家供应商的 baseUrl、默认模型、鉴权方式、已知限制
- [API 协议](docs/api-styles.md) — Completions / Responses / Anthropic 三种协议的字段映射与选用指南
- [ACP 协议](docs/acp.md) — Agent Client Protocol 双向支持：Client 调用外部 Agent、Server 暴露 RAC 为 Agent
- [A2A 协议](docs/a2a.md) — Agent-to-Agent Protocol 双向支持：Client 调用远端 Agent、Server 暴露 RAC 为 Agent
- [测试指南](docs/testing.md) — 测试策略、运行命令、MockEngine 用法与常见问题

## 许可证

Apache License 2.0
