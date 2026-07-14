# RAC — Kotlin Multiplatform AI 模型调用库

RAC 是一个基于 Kotlin Multiplatform 与 Ktor 的 AI 模型调用库，通过统一的 `llm { }` DSL 入口接入 11 家 LLM 供应商，覆盖 3 种 API 协议（OpenAI Chat Completions、OpenAI Responses、Anthropic Messages），支持非流式调用、流式调用、工具调用（Tool Calling）与自动化 Agent，并双向支持 Agent Client Protocol（ACP）与 Agent-to-Agent Protocol（A2A）——既能作为 Client 调用外部 Agent，也能将自身 Agent 暴露为 ACP / A2A Server。

### 注意
注意：现在只测试了core的agent和调用模型，提供商只测试了GLM和DeepSeek

## 特性

- **11 家供应商开箱即用**：DeepSeek、OpenAI、Anthropic、Google Gemini、阿里通义千问（Qwen）、智谱 GLM、月之暗面 Kimi、字节豆包（Doubao）、MiniMax、小米 MiMo、Ollama（本地）
- **10 家供应商的模型预设枚举**：43 个主流模型内置推荐配置，`model(DeepSeekModel.V4_FLASH)` 一行注册，免去手写模板代码
- **3 种 API 协议统一抽象**：Completions API、Responses API、Anthropic API，通过 `chat { }` 自动路由
- **流式调用**：`chatStream { }`（Completions）、`anthropicStream { }`（Anthropic）、`respondStream { }`（Responses），基于 SSE 冷流，统一为 `StreamEvent` 语义事件
- **自动化 Agent**：`agent(llm) { prompts(); tools { } }` 声明式配置，`run` / `runStream` 自动完成多轮工具调用循环；`Session` 记录完整对话历史，可跨 Agent 复用
- **双模式工具定义**：强类型 `tool<Args>(name, desc) { }` 自动生成 JSON Schema；散开参数 `tool(name, desc) { param(); execute { } }` 无需 data class，支持 0-10 参数
- **定制化参数**：`stop`（停止序列）、`seed`（随机种子）、`enableThinking`（思考开关），全栈同步并按 API 差异化处理
- **MCP 支持**：原生支持 Model Context Protocol，`chatWithMcp { }` 自动发现 MCP 工具并执行多轮调用，支持 Stdio / HTTP 传输
- **ACP 双向支持**：`chatWithAcpAgent { }` 作为 Client 调用外部 Agent（Claude Code、Codex CLI 等），`serveAsAcpAgent { }` 将 RAC 暴露为 ACP Agent Server
- **A2A 双向支持**：`chatWithA2aAgent` 作为 Client 调用远端 A2A Agent（Google ADK、LangGraph 等），`serveAsA2aAgent` 将 RAC 暴露为 A2A Agent Server
- **网络韧性**：`RetryExecutor` 提供指数退避重试，自动处理 429/5xx 瞬时错误与 `Retry-After` 头
- **跨平台**：同一套通用代码编译到 JVM、Android、iOS、mingwX64、linuxX64、linuxArm64、macosArm64、JS、WasmJs 九大目标平台
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
| JS（browser / nodejs） | ✅ | JS |
| WasmJs（browser / nodejs） | ✅ | JS |

## 模块组成

RAC 分为 4 个 Gradle 模块（依赖 DAG：`core ← mcp ← {acp, a2a}`）：

| 模块 | Maven 坐标                             | 作用 |
| --- |--------------------------------------| --- |
| core | `top.resderx.rac:core:0.1.0-alpha01` | 消息模型、网络层、API 协议客户端、供应商实现、基础 DSL、Agent |
| mcp | `top.resderx.rac:mcp:0.1.0-alpha01`  | MCP 客户端 + `Llm.chatWithMcp` 扩展函数 |
| acp | `top.resderx.rac:acp:0.1.0-alpha01`  | ACP 双向支持 + `Llm.chatWithAcpAgent` / `Llm.serveAsAcpAgent` 扩展函数 |
| a2a | `top.resderx.rac:a2a:0.1.0-alpha01`  | A2A 双向支持 + `Llm.chatWithA2aAgent` / `Llm.serveAsA2aAgent` 扩展函数 |

仅使用基础对话能力只需引入 `core`；需要 MCP / ACP / A2A 协议集成时再引入对应模块（需同时引入 `core`）。

## 安装

### Gradle（Kotlin Multiplatform）

在 `build.gradle.kts` 的 `commonMain` 依赖中添加：

```kotlin
kotlin {
    // 配置你的目标平台，例如：
    jvm()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()
}

dependencies {
    commonMain {
        implementation("top.resderx.rac:core:0.1.0-alpha01")
        // 按需引入协议模块
        implementation("top.resderx.rac:mcp:0.1.0-alpha01")
        implementation("top.resderx.rac:acp:0.1.0-alpha01")
        implementation("top.resderx.rac:a2a:0.1.0-alpha01")
    }
}
```

> 发布到 Maven Central（Central Portal），确保仓库配置包含 `mavenCentral()`。

### 纯 JVM / Android 项目

```kotlin
dependencies {
    implementation("top.resderx.rac:core:0.1.0-alpha01")
}
```

## 快速示例

```kotlin
import top.resderx.rac.dsl.llm
import top.resderx.rac.dsl.deepseek
import top.resderx.rac.providers.presets.DeepSeekModel
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. 创建 Llm 实例——注册 DeepSeek 供应商并使用预设模型
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-...")
                models {
                    // 一行注册预设模型，自动应用推荐配置
                    model(DeepSeekModel.V4_FLASH)
                }
            }
        }
    }

    // 2. 非流式对话
    val response = ai.chat {
        user("用一句话解释 Kotlin Multiplatform")
    }
    println(response.content)
}
```

更多示例见下方文档链接。

## 文档

- **[入门指南](docs/quickstart.md)** — 从安装到第一个调用，覆盖流式、Agent、工具调用、定制化参数的完整上手教程
- **[API 参考](docs/usage.md)** — 每个公开函数、类、属性的完整签名、参数说明与使用示例
- **[供应商配置](docs/providers.md)** — 11 家供应商的 baseUrl、鉴权方式与模型预设枚举
- **[API 协议风格](docs/api-styles.md)** — Completions / Anthropic / Responses 三种协议的差异与选择
- **[ACP 集成](docs/acp.md)** — Agent Client Protocol 双向支持详解
- **[A2A 集成](docs/a2a.md)** — Agent-to-Agent Protocol 双向支持详解
- **[测试](docs/testing.md)** — MockEngine 端到端测试方法

## 核心设计

- **DSL 入口**：`llm { }` 创建 `Llm` 实例，`providers { }` 注册供应商，`models { }` 注册模型，`chat { }` 构建请求
- **分层配置**：`ModelConfig`（模型注册时的默认值）→ `ChatRequestBuilder`（调用时覆盖）→ 服务端默认。未在 builder 显式设置的字段回退到 ModelConfig，仍为 null 时回退到服务端默认
- **统一返回**：所有协议响应归一化为 `AIMessage`（content / reasoningContent / toolCalls / usage / finishReason），流式响应归一化为 `StreamEvent` 密封接口
- **Agent 解耦**：`Agent` 持有 systemPrompt 与工具集，`Session` 仅记录 user/assistant/tool 消息（不含 system），不同 Agent 可共用同一 Session
- **工具双模式**：强类型 `tool<Args>` 自动从 `@Serializable data class` 生成 JSON Schema；散开参数 `tool { param(); execute { } }` 无需类定义，支持 0-10 参数；11+ 参数须用强类型模式

## 许可证

详见 [LICENSE](LICENSE)。
