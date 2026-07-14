# 入门指南

本文档帮助你在 10 分钟内完成 RAC 的安装、配置与第一次 AI 调用，并逐步介绍流式输出、自动化 Agent、工具调用、多轮对话与定制化参数。如需完整的 API 签名参考，请阅读 [API 参考](usage.md)。

## 目录

- [1. 环境要求](#1-环境要求)
- [2. 安装](#2-安装)
- [3. 第一个调用](#3-第一个调用)
- [4. 使用模型预设](#4-使用模型预设)
- [5. 流式输出](#5-流式输出)
- [6. Agent 与工具调用](#6-agent-与工具调用)
- [7. Session 多轮对话](#7-session-多轮对话)
- [8. 定制化参数](#8-定制化参数)
- [9. 下一步](#9-下一步)

---

## 1. 环境要求

| 项 | 最低版本 | 推荐 |
| --- | --- | --- |
| Kotlin | 2.4.0 | 2.4.0 |
| Gradle | 8.5 | 8.7+ |
| JDK（构建期） | 17 | 21 |
| Kotlin Multiplatform | 2.4.0 | 2.4.0 |

RAC 是 KMP 库，支持 JVM、Android、iOS、mingwX64、linuxX64、linuxArm64、macosArm64、JS、WasmJs 九大目标平台。你的项目只需引入对应 Ktor 引擎即可获得网络能力（RAC 已通过约定插件自动配置各平台引擎）。

## 2. 安装

### 2.1 Kotlin Multiplatform 项目

在 `build.gradle.kts` 的 `commonMain` 依赖中添加 `core` 模块：

```kotlin
kotlin {
    jvm()
    // 按需添加其他目标平台
}

dependencies {
    commonMain {
        implementation("com.resderx.rac:core:0.1.0-alpha")
    }
    // 按需引入协议模块
    // commonMain { implementation("com.resderx.rac:mcp:0.1.0-alpha") }
    // commonMain { implementation("com.resderx.rac:acp:0.1.0-alpha") }
    // commonMain { implementation("com.resderx.rac:a2a:0.1.0-alpha") }
}
```

确保仓库配置包含 Maven Central：

```kotlin
repositories {
    mavenCentral()
}
```

### 2.2 纯 JVM / Android 项目

```kotlin
dependencies {
    implementation("com.resderx.rac:core:0.1.0-alpha")
}
```

## 3. 第一个调用

创建 `Llm` 实例并发起一次非流式对话：

```kotlin
import com.resderx.rac.dsl.llm
import com.resderx.rac.dsl.deepseek
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. 创建 Llm 实例——注册 DeepSeek 供应商
    val ai = llm {
        providers {
            deepseek {
                apiKey("sk-你的-api-key")
                models {
                    // 手动注册模型，指定参数
                    model("deepseek-v4-flash") {
                        maxTokens = 4096
                        temperature = 0.7
                    }
                }
            }
        }
    }

    // 2. 非流式对话
    val response = ai.chat {
        user("用一句话解释 Kotlin Multiplatform")
    }

    // 3. 读取响应
    println(response.content)          // 正文文本
    println(response.usage)            // token 用量
    println(response.finishReason)     // 结束原因（STOP/LENGTH/...）
}
```

**说明**：
- `llm { }` 是顶层 DSL 入口，返回 `Llm` 实例
- `providers { }` 块内注册供应商，每个供应商有对应的扩展函数（`deepseek` / `openai` / `anthropic` 等）
- `models { }` 块内注册模型，首个注册的模型自动成为该供应商的默认模型
- `chat { }` 构建并发送请求，返回统一的 `AIMessage`
- 所有调用都是 `suspend` 函数，需在协程作用域内调用

## 4. 使用模型预设

RAC 为 10 家供应商提供了 43 个主流模型的预设枚举，一行代码即可注册带推荐配置的模型：

```kotlin
import com.resderx.rac.dsl.llm
import com.resderx.rac.dsl.deepseek
import com.resderx.rac.dsl.openai
import com.resderx.rac.providers.presets.DeepSeekModel
import com.resderx.rac.providers.presets.OpenAIModel

val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models {
                // 完全使用预设推荐配置（maxTokens=8192, temperature=0.0, reasoningEffort="medium"）
                model(DeepSeekModel.V4_FLASH)
            }
        }
        openai {
            apiKey("sk-...")
            models {
                // 覆盖部分预设字段，其余沿用预设
                model(OpenAIModel.GPT_5_4) { maxTokens = 4096 }
            }
        }
    }
}
```

**可用的预设枚举**（均位于 `com.resderx.rac.providers.presets` 包）：

| 枚举类              | 供应商           | 模型数量 | 示例                                                |
|------------------|---------------|------|---------------------------------------------------|
| `DeepSeekModel`  | DeepSeek      | 2    | `V4_PRO`、`V4_FLASH`                               |
| `OpenAIModel`    | OpenAI        | 4    | `GPT_5_5`、`GPT_5_4`、`GPT_5_4_MINI`、`GPT_5_4_NANO` |
| `AnthropicModel` | Anthropic     | 4    | `CLAUDE_OPUS_4_1`、`CLAUDE_SONNET_4_6`             |
| `GeminiModel`    | Google Gemini | 2    | `PRO_3`、`FLASH_3`                                 |
| `QwenModel`      | 通义千问          | 6    | `MAX_3_7`、`PLUS_3_7`、`FLASH_3_6`                  |
| `GlmModel`       | 智谱 GLM        | 4    | `GLM_5_2`、`GLM_4_7_FLASH`                         |
| `KimiModel`      | 月之暗面          | 9    | `K2_5`、`K2_THINKING`、`V1_128K`                    |
| `DoubaoModel`    | 字节豆包          | 5    | `SEED_2_1_PRO`、`SEED_1_6_THINKING`                |
| `MinimaxModel`   | MiniMax       | 3    | `ABAB7`、`M2_5`                                    |
| `MimoModel`      | 小米 MiMo       | 2    | `V2_5_PRO`、`V2_FLASH`                             |

推理/思考类模型（如 `DeepSeekModel.V4_PRO`、`KimiModel.K2_THINKING`）的预设已自动设置 `enableThinking = true` 和合适的 `reasoningEffort`。

## 5. 流式输出

流式调用返回 `Flow<StreamEvent>`，你可以实时处理模型输出的增量片段：

```kotlin
import com.resderx.rac.dsl.llm
import com.resderx.rac.dsl.deepseek
import com.resderx.rac.messages.StreamEvent
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

    // chatStream 返回 Flow<StreamEvent>（仅 Completions API）
    ai.chatStream {
        user("写一首关于秋天的诗")
    }.collect { event ->
        when (event) {
            is StreamEvent.TextDelta -> {
                // 实时打印每个文本片段
                print(event.delta)
            }
            is StreamEvent.ReasoningDelta -> {
                // 推理模型的思考过程（仅推理模型产生）
                // print("[思考] ${event.delta}")
            }
            is StreamEvent.Done -> {
                // 流结束，携带完整信息
                println("\n--- 完成 ---")
                println("总 token: ${event.usage?.totalTokens}")
            }
            is StreamEvent.ToolCallDelta -> {
                // 工具调用增量（流式工具调用场景）
            }
        }
    }
}
```

**StreamEvent 四种事件类型**：

| 事件 | 字段 | 说明 |
| --- | --- | --- |
| `TextDelta` | `delta`（新增片段）、`accumulated`（累积正文） | 模型生成的正文片段 |
| `ReasoningDelta` | `delta`、`accumulated` | 思考过程片段（仅推理模型） |
| `ToolCallDelta` | `index`、`id`、`name`、`argumentsDelta`、`argumentsAccumulated` | 工具调用增量（已聚合碎片） |
| `Done` | `content`、`reasoningContent`、`toolCalls`、`usage`、`finishReason` | 流结束，自包含完整信息 |

**API 选择**：
- `chatStream { }` —— Completions API 的流式（DeepSeek/OpenAI/Qwen 等大部分供应商）
- `anthropicStream { }` —— Anthropic API 的流式（仅 Anthropic 供应商）
- `respondStream { }` —— Responses API 的流式（仅 OpenAI Responses）

## 6. Agent 与工具调用

`Agent` 封装了「LLM + 系统提示词 + 工具集」，自动完成多轮工具调用循环。配合 `Session` 管理对话历史。

### 6.1 定义工具

RAC 支持两种工具定义模式，可在同一个 `tools { }` 块内混合使用。

**模式一：强类型（推荐，参数复杂时）**

通过 `@Serializable data class` 自动生成 JSON Schema：

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class WeatherArgs(
    val city: String,           // required
    val date: String? = null,   // optional
)

val agent = agent(ai) {
    prompts("你是天气助手，可以查询天气")
    tools {
        tool<WeatherArgs>("get_weather", "查询指定城市天气") { args ->
            // args 已自动反序列化
            "${args.city} 今天晴，25°C"
        }
    }
}
```

**模式二：散开参数（无需 data class，快速定义）**

```kotlin
val agent = agent(ai) {
    prompts("你是计算助手")
    tools {
        tool("calculate", "执行数学计算") {
            param("expression", "string", "数学表达式", required = true)
            param("precision", "integer", "小数位数", required = false)
            execute { expr: String?, precision: Int? ->
                // 参数可空，缺失时为 null
                "结果: ${eval(expr)}"
            }
        }
    }
}
```

散开参数模式支持 0-10 个参数的 `execute` 重载，每个参数类型可空。11+ 参数须用强类型模式。

### 6.2 运行 Agent

```kotlin
import com.resderx.rac.agent.Session
import com.resderx.rac.agent.agent
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val session = Session()
    val agent = agent(ai) {
        prompts("你是天气助手")
        tools {
            tool<WeatherArgs>("get_weather", "查询天气") { args ->
                "${args.city} 今天晴"
            }
        }
    }

    // run 自动完成多轮工具调用
    val result = agent.run(session, "北京今天天气怎么样？")
    println(result.content)  // "北京今天晴"
}
```

**Agent.run 的执行流程**：
1. 将用户输入追加到 Session
2. 动态拼接 systemPrompt 到请求头部（不存入 Session）
3. 注入完整对话历史 + 工具定义
4. 调用模型，若返回 toolCalls 则执行工具、将结果回填，继续下一轮
5. 模型不再请求工具调用（或达到 maxRounds）时返回最终结果
6. 最终结果追加到 Session

### 6.3 流式 Agent

```kotlin
agent.runStream(session, "写一首诗并解释创作思路").collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.delta)
        is StreamEvent.Done -> println("\n完成: ${event.finishReason}")
        else -> {}
    }
}
```

`runStream` 与 `run` 语义一致，但以 SSE 流式返回事件。中间轮（工具调用轮）的 delta 事件会实时转发，只有最终轮才转发 `Done`。

## 7. Session 多轮对话

`Session` 记录完整的对话历史（user / assistant / tool 消息），支持跨多轮调用保持上下文。

```kotlin
val session = Session()

// 第一轮
agent.run(session, "我叫张三")
// session 现在含: [user("我叫张三"), assistant("你好张三")]

// 第二轮——模型能记住上下文
agent.run(session, "我叫什么名字？")
// session 现在含: [user, assistant, user("我叫什么"), assistant("你叫张三")]
```

**Session 的核心设计**：
- **不含 system 消息**：system 是 Agent 的属性，每次请求时动态拼接，不存入 Session
- **可跨 Agent 复用**：不同 Agent（不同 systemPrompt）可共用同一 Session
- **记录完整历史**：含中间工具调用过程（AssistantMessage + ToolMessage），便于调试与持久化

```kotlin
// 跨 Agent 复用同一 Session
val session = Session()
val weatherAgent = agent(ai) { prompts("天气助手"); tools { ... } }
val translateAgent = agent(ai) { prompts("翻译助手") }

weatherAgent.run(session, "北京天气")     // 用天气助手处理
translateAgent.run(session, "翻译上一句")  // 翻译助手能看到完整历史
```

## 8. 定制化参数

RAC 提供三个定制化参数，可在 `chat { }` 块内或 `ModelConfig` 中设置：

### 8.1 停止序列（stop）

模型生成到任一字符串时立即停止：

```kotlin
ai.chat {
    user("列出 3 个水果")
    stop = listOf("\n\n", "END")  // 遇到空行或 END 时停止
}
```

### 8.2 随机种子（seed）

用于可重现的确定性输出：

```kotlin
ai.chat {
    user("生成一个随机数")
    seed = 42L  // 相同 seed + 相同输入 → 相同输出
}
```

> 注意：部分供应商/模型不支持 seed，设置后会被静默忽略。

### 8.3 思考开关（enableThinking）

控制模型的扩展思考行为：

```kotlin
ai.chat {
    user("证明哥德巴赫猜想")
    enableThinking = true   // 启用扩展思考
    // maxTokens = 8192     // 建议同时调高 maxTokens
}
```

**enableThinking 在不同 API 上的行为**：

| API | enableThinking=true | enableThinking=false |
| --- | --- | --- |
| Completions | 自动设 `reasoningEffort="medium"`（未显式设置时） | 强制 `reasoningEffort=null` |
| Anthropic | 构造 `thinking={type:"enabled", budget_tokens:maxTokens*4/5}` | 不发送 thinking 字段 |
| Responses | 不支持，静默忽略 | 不支持，静默忽略 |

### 8.4 分层配置

参数遵循分层回退：**`chat { }` 显式值 → `ModelConfig` 默认值 → 服务端默认**。

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey("sk-...")
            models {
                model(DeepSeekModel.V4_FLASH) {
                    temperature = 0.0   // ModelConfig 默认值
                    maxTokens = 8192
                }
            }
        }
    }
}

ai.chat {
    user("hi")
    temperature = 0.5   // 覆盖 ModelConfig 的 0.0
    // maxTokens 未设，回退到 ModelConfig 的 8192
    // seed 未设，回退到服务端默认
}
```

## 9. 下一步

- 阅读 [API 参考](usage.md) 了解每个函数、类的完整签名
- 阅读 [供应商配置](providers.md) 了解各供应商的 baseUrl 与鉴权方式
- 阅读 [API 协议风格](api-styles.md) 了解三种协议的差异与选择
- 阅读 [ACP 集成](acp.md) / [A2A 集成](a2a.md) 了解 Agent 协议双向支持
- 阅读 [测试](testing.md) 了解如何用 MockEngine 进行端到端测试
