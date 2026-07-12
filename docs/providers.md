# 供应商详解

RAC 通过统一的 `ModelProvider` 接口抽象 11 家 LLM 供应商。每家供应商提供一个工厂函数（如 `DeepSeekProvider(config)`）与一个 `rac { }` 块内的 DSL 扩展（如 `deepseek { }`）。本文档汇总所有供应商的连接元数据、默认配置与已知限制。

所有供应商配置均通过 `ProviderConfig` 覆盖，DSL 作用域 `ProviderConfigBuilder` 提供以下方法：

| 方法 | 作用 |
| --- | --- |
| `apiKey(key)` | 覆盖 API 密钥，`null` 沿用供应商默认 |
| `model(name)` | 覆盖默认模型 |
| `baseUrl(url)` | 覆盖 API 基础地址 |
| `timeoutMillis(ms)` | 覆盖请求超时（毫秒） |
| `header(name, value)` | 追加单个自定义请求头 |
| `headers(map)` | 批量追加自定义请求头 |

> 鉴权头（`Authorization: Bearer` 或 `x-api-key`）不由供应商工厂硬编码，而是由 `RAC.buildHeaders` 按供应商 `defaultApiType` 在每次请求时动态注入，`apiKey` 为 `null` 时不添加鉴权头。

## 供应商总览

| 供应商 | baseUrl | 默认 API 协议 | 默认模型 | 鉴权方式 | 特殊字段 | 已知限制 |
| --- | --- | --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | Completions | `deepseek-v4-flash` | Bearer Token | `reasoning_content`（V4-Pro） | 不支持 Responses / Anthropic 协议 |
| OpenAI | `https://api.openai.com/v1` | Completions | `gpt-4o-mini` | Bearer Token | `reasoning_effort` | Responses API 仅 OpenAI 官方支持 |
| Anthropic | `https://api.anthropic.com/v1` | Anthropic | `claude-3-5-sonnet-20241022` | `x-api-key` | `anthropic-version` 头、顶层 `system`、`max_tokens` 必填 | 不支持 Completions / Responses；`chatStream` 会抛异常 |
| Kimi | `https://api.moonshot.cn/v1` | Completions | `moonshot-v1-8k` | Bearer Token | — | 默认 8k 上下文，长文本需切换模型 |
| GLM | `https://open.bigmodel.cn/api/paas/v4` | Completions | `glm-4-flash` | Bearer Token | — | 免费档有速率限制 |
| MiniMax | `https://api.minimaxi.chat/v1` | Completions | `abab6.5-chat` | Bearer Token | — | 上下文窗口受限 |
| Ollama | `http://localhost:11434/v1` | Completions | `llama3.1` | 无（本地）/ Bearer（云端） | — | 本地模式需 Ollama 实例运行 |
| Doubao | `https://ark.cn-beijing.volces.com/api/v3` | Completions | `doubao-seed-1-6` | Bearer Token | — | 生产需用接入点 ID 覆盖 model |
| Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Completions | `qwen-plus` | Bearer Token | — | 仅 OpenAI 兼容模式端点 |
| MIMO | `https://api.mimo.xiaomi.com/v1` | Completions | `mimo-7b` | Bearer Token | — | 7B 轻量模型，上下文有限 |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | Completions | `gemini-1.5-flash` | Bearer Token | — | 走 OpenAI 兼容端点，不支持原生特性 |

---

## DeepSeek

- **工厂函数**：`DeepSeekProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { deepseek { ... } }`
- **注册名**：`deepseek`
- **baseUrl**：`https://api.deepseek.com`（不含 `/v1` 后缀，DeepSeek 官方约定）
- **默认模型**：`deepseek-v4-flash`
- **鉴权方式**：Bearer Token，由 `RAC.buildHeaders` 注入 `Authorization: Bearer <apiKey>`

### 模型迁移提示（2026-07）

旧模型名 `deepseek-chat` 与 `deepseek-reasoner` 将于 **2026-07-24** 停用。迁移映射：

| 旧模型名 | 新模型名 | 说明 |
| --- | --- | --- |
| `deepseek-chat` | `deepseek-v4-flash` | 非思考模式（默认） |
| `deepseek-reasoner` | `deepseek-v4-flash` 思考模式 / `deepseek-v4-pro` | 推理模型，V4-Pro 能力更强 |

自 v0.2.0 起，`DeepSeekProvider` 默认模型已从 `deepseek-chat` 切换为 `deepseek-v4-flash`。

### 特殊字段

DeepSeek-V4-Pro 等推理模型在响应中返回 `reasoning_content` 字段，已由 `CompletionsResponse` / `ResponseMessage` 解析，并经 `toAIMessage()` 映射到 `AIMessage.reasoningContent`，供应商工厂层无需特殊处理。流式场景下 `CompletionsStreamChunk.Delta.reasoningContent` 同样可获取增量推理内容。

### 使用示例

```kotlin
val ai = rac {
    deepseek {
        apiKey(System.getenv("DEEPSEEK_API_KEY"))
        model("deepseek-v4-pro") // 切换到 V4-Pro 推理模型
    }
}
```

### 已知限制

- 不支持 Anthropic 协议与 Responses API；调用 `respond { }` 会失败
- baseUrl 不含 `/v1`，与多数 OpenAI 兼容供应商不同，覆盖时注意保持一致

---

## OpenAI

- **工厂函数**：`OpenAIProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { openai { ... } }`
- **注册名**：`openai`
- **baseUrl**：`https://api.openai.com/v1`
- **默认模型**：`gpt-4o-mini`
- **默认协议**：`COMPLETIONS`（Chat Completions），但用户可显式调用 `respond { }` 走 Responses API
- **鉴权方式**：Bearer Token

### 特殊字段

- `reasoning_effort`：通过 `ChatRequestBuilder.reasoningEffort` 设置（如 `"low"` / `"medium"` / `"high"`），仅推理模型支持，映射到请求体 `reasoning_effort` 字段
- OpenAI 推理模型不返回 `reasoning_content`（推理过程走 Responses API 的 reasoning items）

### 使用示例

```kotlin
val ai = rac {
    openai {
        apiKey(System.getenv("OPENAI_API_KEY"))
        model("gpt-4o")
    }
}
// 显式走 Responses API：
// ai.respond { input("...") }
```

### 已知限制

- Responses API 当前仅 OpenAI 官方支持，其他供应商调用 `respond { }` 会失败
- `baseUrl` 覆盖常用于自建代理或 Azure OpenAI 兼容端点

---

## Anthropic

- **工厂函数**：`AnthropicProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { anthropic { ... } }`
- **注册名**：`anthropic`
- **baseUrl**：`https://api.anthropic.com/v1`
- **默认模型**：`claude-3-5-sonnet-20241022`
- **默认协议**：`ANTHROPIC`（Messages API），`chat { }` 自动路由到 `anthropicClient`
- **鉴权方式**：`x-api-key: <apiKey>` 头（**非** Bearer Token），由 `RAC.buildHeaders` 在 `ANTHROPIC` 分支注入

### 特殊字段

- **`anthropic-version` 头**：必填，由工厂自动注入 `anthropic-version: 2023-06-01` 到 `defaultHeaders`，与 `extraHeaders` 合并（`extraHeaders` 优先，可覆盖以使用 Beta API）
- **顶层 `system` 字段**：Anthropic 将 system 消息作为顶层 `system` 字段而非 messages 列表项，由 `ChatRequestBuilder.buildAnthropic()` 自动抽取并拼接
- **`max_tokens` 必填**：Anthropic 要求 `max_tokens` 必填，`buildAnthropic` 在 `maxTokens` 为 null 时默认 `4096`
- **content blocks**：响应 `content` 为 content blocks 列表（`text` / `tool_use`），由 `AnthropicResponse.toAIMessage()` 映射

### 使用示例

```kotlin
val ai = rac {
    anthropic {
        apiKey(System.getenv("ANTHROPIC_API_KEY"))
    }
}
val resp = ai.chat {
    system("你是助手")
    user("你好")
}
// 流式必须用 anthropicStream：
// ai.anthropicStream { user("...") }
```

### 已知限制

- 不支持 Completions API 与 Responses API
- 调用 `respond { }` 或在 Anthropic 供应商上调用 `chatStream { }` 会抛 `RACException`，需改用 `anthropicStream { }`
- `reasoningEffort` 被忽略（Anthropic 协议无此字段）

---

## Kimi（Moonshot AI）

- **工厂函数**：`KimiProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { kimi { ... } }`
- **注册名**：`kimi`
- **baseUrl**：`https://api.moonshot.cn/v1`（Moonshot 官方 OpenAI 兼容端点）
- **默认模型**：`moonshot-v1-8k`（8k 上下文窗口版本）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    kimi {
        apiKey(System.getenv("KIMI_API_KEY"))
        model("moonshot-v1-128k") // 长上下文
    }
}
```

### 已知限制

- `moonshot-v1-8k` 上下文窗口仅 8k tokens，长上下文场景需切换至 `moonshot-v1-32k` / `moonshot-v1-128k`
- 模型名需与 Moonshot 官方文档一致，错误模型名会返回 404

---

## GLM（智谱 AI）

- **工厂函数**：`GlmProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { glm { ... } }`
- **注册名**：`glm`
- **baseUrl**：`https://open.bigmodel.cn/api/paas/v4`（智谱开放平台 OpenAI 兼容端点）
- **默认模型**：`glm-4-flash`（免费档快速模型）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    glm {
        apiKey(System.getenv("GLM_API_KEY"))
        model("glm-4-plus") // 生产档
    }
}
```

### 已知限制

- `glm-4-flash` 为免费档，存在速率限制与并发上限，生产环境建议切换至 `glm-4` / `glm-4-plus`
- 流式响应需通过 `stream` 参数显式开启（`chatStream { }` 已自动处理）

---

## MiniMax

- **工厂函数**：`MiniMaxProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { minimax { ... } }`
- **注册名**：`minimax`
- **baseUrl**：`https://api.minimaxi.chat/v1`（MiniMax OpenAI 兼容端点）
- **默认模型**：`abab6.5-chat`（通用对话模型）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    minimax {
        apiKey(System.getenv("MINIMAX_API_KEY"))
    }
}
```

### 已知限制

- `abab6.5-chat` 上下文窗口受限，超长输入需切换至 `abab6.5s` 或其他长上下文模型
- 部分高级特性（如语音合成、视频生成）不在 Chat Completions 端点支持范围内

---

## Ollama

- **工厂函数**：`OllamaProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { ollama { ... } }`
- **注册名**：`ollama`
- **baseUrl**：`http://localhost:11434/v1`（本地默认）
- **默认模型**：`llama3.1`
- **默认协议**：`COMPLETIONS`（Ollama 提供 OpenAI 兼容的 `/v1/chat/completions` 端点）
- **鉴权方式**：无（本地模式）/ Bearer Token（云端模式）

### 两种模式

Ollama 是 `ModelProvider.apiKey` 设计为可空的关键场景，支持两种模式：

1. **本地模式（默认）**：`baseUrl` 与 `apiKey` 均为 null 时，使用 `http://localhost:11434/v1`、`apiKey = null`（无鉴权）。此时 `RAC.buildHeaders` 不会添加 `Authorization` 头。
2. **云端模式**：调用方通过 `baseUrl` 与 `apiKey` 同时覆盖，连接远程托管的 Ollama 服务。此时 `apiKey` 非 null，作为 Bearer token 注入。

### 使用示例

```kotlin
// 本地模式
val ai = rac {
    ollama { model("llama3.1") }
}
// 云端模式
val aiCloud = rac {
    ollama {
        baseUrl("https://your-ollama-host/v1")
        apiKey(System.getenv("OLLAMA_API_KEY"))
    }
}
```

### 已知限制

- 本地模式下若 Ollama 未启动或端口被占用，请求将在 HttpClient 层失败
- 仅设置 `baseUrl` 而不设 `apiKey` 时，适用于"自定义地址但无鉴权"场景
- 模型需先通过 `ollama pull` 拉取到本地

---

## Doubao（火山引擎方舟）

- **工厂函数**：`DoubaoProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { doubao { ... } }`
- **注册名**：`doubao`
- **baseUrl**：`https://ark.cn-beijing.volces.com/api/v3`（火山方舟 OpenAI 兼容端点）
- **默认模型**：`doubao-seed-1-6`（豆包 Seed 1.6 通用模型）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    doubao {
        apiKey(System.getenv("DOUBAO_API_KEY"))
        model("ep-20240xxxxxxxxxx-xxxxx") // 接入点 ID
    }
}
```

### 已知限制

- 火山方舟实际调用使用"接入点 ID"（endpoint id）作为 `model` 参数，`doubao-seed-1-6` 为预置模型名，生产环境通常需在 `config.model` 中覆盖为实际 endpoint id（形如 `ep-xxxxxxxx`）
- 接入点需在火山方舟控制台显式创建并启用，否则返回 404

---

## Qwen（阿里 DashScope）

- **工厂函数**：`QwenProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { qwen { ... } }`
- **注册名**：`qwen`
- **baseUrl**：`https://dashscope.aliyuncs.com/compatible-mode/v1`（DashScope OpenAI 兼容模式端点）
- **默认模型**：`qwen-plus`（均衡档位通义千问模型）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    qwen {
        apiKey(System.getenv("QWEN_API_KEY"))
        model("qwen-max") // 高精度档位
    }
}
```

### 已知限制

- 此 baseUrl 仅适用于 OpenAI 兼容模式，原生 DashScope 协议端点不同，不可混用
- `qwen-plus` 为均衡档位，对长上下文或高精度场景可切换至 `qwen-max` / `qwen-turbo`
- 部分高级参数（如 `enable_search`）需通过 `extraHeaders` 或请求体显式开启

---

## MIMO（小米）

- **工厂函数**：`MimoProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { mimo { ... } }`
- **注册名**：`mimo`
- **baseUrl**：`https://api.mimo.xiaomi.com/v1`（小米 MIMO OpenAI 兼容端点）
- **默认模型**：`mimo-7b`（7B 参数量轻量模型）
- **鉴权方式**：Bearer Token

### 使用示例

```kotlin
val ai = rac {
    mimo {
        apiKey(System.getenv("MIMO_API_KEY"))
    }
}
```

### 已知限制

- `mimo-7b` 为 7B 参数量轻量模型，复杂推理与长上下文能力有限，建议用于轻量任务
- 模型上下文窗口较小，长输入可能被截断或报错

---

## Gemini

- **工厂函数**：`GeminiProvider(config: ProviderConfig): ModelProvider`
- **DSL**：`rac { gemini { ... } }`
- **注册名**：`gemini`
- **baseUrl**：`https://generativelanguage.googleapis.com/v1beta/openai`（Google 官方 OpenAI 兼容端点）
- **默认模型**：`gemini-1.5-flash`
- **默认协议**：`COMPLETIONS`（通过 OpenAI 兼容端点接入，复用 Completions API 客户端）
- **鉴权方式**：Bearer Token（Google API key 直接作为 Bearer token）

### 特殊说明

RAC 使用 Google 官方提供的 OpenAI 兼容端点，而非 Gemini 原生 API（`/v1beta/models/{model}:generateContent`）。这样 Gemini 与其他 OpenAI 兼容供应商共用 `COMPLETIONS` 协议，由 Completions API 客户端统一处理 `/chat/completions` 请求与响应解析。模型名使用 Gemini 原生命名（不含 `models/` 前缀），与 OpenAI 兼容端点的约定一致。

### 使用示例

```kotlin
val ai = rac {
    gemini {
        apiKey(System.getenv("GEMINI_API_KEY"))
        model("gemini-2.0-flash")
    }
}
```

### 已知限制

- 不调用 Gemini 原生 API（如 `:generateContent`、`:streamGenerateContent`），若需使用原生特性（如多模态 grounding、安全设置），需另外实现原生客户端
- `apiKey` 为 null 时端点返回 401，调用方必须设置有效的 Google API key
- `baseUrl` 覆盖时，覆盖后的端点仍须兼容 OpenAI Chat Completions 协议
