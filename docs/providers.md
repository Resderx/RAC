# 供应商详解

RAC 通过统一的 `ModelProvider` 接口抽象 11 家 LLM 供应商。每家供应商提供一个工厂函数（如 `DeepSeekProvider(config, models)`）与一个 `llm { providers { } }` 块内的 DSL 扩展（如 `deepseek { }`）。本文档汇总所有供应商的连接元数据、默认配置、模型预设与已知限制。

所有供应商配置均通过 `ProviderConfig`（连接信息）+ `Map<String, ModelConfig>`（模型注册表）传入工厂函数。DSL 作用域 `ProviderDsl` 提供以下方法：

| 方法 | 作用 |
| --- | --- |
| `apiKey(key)` | 覆盖 API 密钥，`null` 沿用供应商默认 |
| `baseUrl(url)` | 覆盖 API 基础地址 |
| `header(name, value)` | 追加单个自定义请求头 |
| `headers(map)` | 批量追加自定义请求头 |
| `models { }` | 进入 `ModelsBuilder` 作用域，逐个注册模型 |

`ModelsBuilder` 提供两种 `model()` 重载：

| 重载 | 作用 |
| --- | --- |
| `model(name) { ... }` | 手动指定模型名与 `ModelConfig`（block 内字段全 null 时沿用服务端默认） |
| `model(preset) { ... }` | 从预设枚举（`ModelPreset`）读取模型名与推荐配置，block 可覆盖部分字段 |

> 鉴权头（`Authorization: Bearer` 或 `x-api-key`）不由供应商工厂硬编码，而是由 `Llm.buildHeaders(provider)` 按供应商 `defaultApiType` 在每次请求时动态注入，`apiKey` 为 `null` 时不添加鉴权头。

## 供应商总览

| 供应商 | baseUrl | 默认 API 协议 | 工厂默认模型 | 鉴权方式 | 预设枚举 |
| --- | --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | Completions | `deepseek-v4-flash` | Bearer Token | `DeepSeekModel` |
| OpenAI | `https://api.openai.com/v1` | Completions | `gpt-4o-mini` | Bearer Token | `OpenAIModel` |
| Anthropic | `https://api.anthropic.com/v1` | Anthropic | `claude-3-5-sonnet-20241022` | `x-api-key` | `AnthropicModel` |
| Kimi | `https://api.moonshot.cn/v1` | Completions | `moonshot-v1-8k` | Bearer Token | `KimiModel` |
| GLM | `https://open.bigmodel.cn/api/paas/v4` | Completions | `glm-4-flash` | Bearer Token | `GlmModel` |
| MiniMax | `https://api.minimaxi.chat/v1` | Completions | `abab6.5-chat` | Bearer Token | `MinimaxModel` |
| Ollama | `http://localhost:11434/v1` | Completions | `llama3.1` | 无（本地）/ Bearer（云端） | — |
| Doubao | `https://ark.cn-beijing.volces.com/api/v3` | Completions | `doubao-seed-1-6` | Bearer Token | `DoubaoModel` |
| Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Completions | `qwen-plus` | Bearer Token | `QwenModel` |
| MIMO | `https://api.mimo.xiaomi.com/v1` | Completions | `mimo-7b` | Bearer Token | `MimoModel` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | Completions | `gemini-1.5-flash` | Bearer Token | `GeminiModel` |

> **工厂默认模型 vs 预设模型**：表中"工厂默认模型"是 `models = emptyMap()` 时由工厂函数回退的旧模型名；推荐通过 `models { model(PresetEnum.XXX) }` 使用预设枚举获取截至 2026-07 的最新模型名与调优配置。

---

## DeepSeek

- **工厂函数**：`DeepSeekProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { deepseek { ... } } }`
- **注册名**：`deepseek`
- **baseUrl**：`https://api.deepseek.com`（不含 `/v1` 后缀，DeepSeek 官方约定）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`deepseek-v4-flash`
- **鉴权方式**：Bearer Token，由 `Llm.buildHeaders` 注入 `Authorization: Bearer <apiKey>`

### 模型预设（`DeepSeekModel`）

| 枚举项 | modelName | maxTokens | temperature | reasoningEffort | enableThinking |
| --- | --- | --- | --- | --- | --- |
| `V4_PRO` | `deepseek-v4-pro` | 8192 | 0.0 | `high` | `true` |
| `V4_FLASH` | `deepseek-v4-flash` | 8192 | 0.0 | `medium` | — |

### 模型迁移提示（2026-07）

旧模型名 `deepseek-chat` 与 `deepseek-reasoner` 将于 **2026-07-24** 停用。迁移映射：

| 旧模型名 | 新模型名 | 说明 |
| --- | --- | --- |
| `deepseek-chat` | `deepseek-v4-flash` | 非思考模式（默认） |
| `deepseek-reasoner` | `deepseek-v4-flash` 思考模式 / `deepseek-v4-pro` | 推理模型，V4-Pro 能力更强 |

### 特殊字段

DeepSeek-V4-Pro 等推理模型在响应中返回 `reasoning_content` 字段，已由 `CompletionsResponse` / `ResponseMessage` 解析，并经 `toAIMessage()` 映射到 `AIMessage.reasoningContent`。流式场景下 `CompletionsStreamChunk.Delta.reasoningContent` 同样可获取增量推理内容。

### 使用示例

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey(System.getenv("DEEPSEEK_API_KEY"))
            models {
                model(DeepSeekModel.V4_FLASH)                    // 完全使用预设
                model(DeepSeekModel.V4_PRO) { maxTokens = 4096 } // 覆盖 maxTokens
            }
        }
    }
}
```

### 已知限制

- 不支持 Anthropic 协议与 Responses API；调用 `respond { }` 会失败
- baseUrl 不含 `/v1`，与多数 OpenAI 兼容供应商不同，覆盖时注意保持一致

---

## OpenAI

- **工厂函数**：`OpenAIProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { openai { ... } } }`
- **注册名**：`openai`
- **baseUrl**：`https://api.openai.com/v1`
- **默认协议**：`ApiType.COMPLETIONS`（Chat Completions），但用户可显式调用 `respond { }` 走 Responses API
- **工厂默认模型**：`gpt-4o-mini`
- **鉴权方式**：Bearer Token

### 模型预设（`OpenAIModel`）

| 枚举项 | modelName | maxTokens | temperature | reasoningEffort | enableThinking |
| --- | --- | --- | --- | --- | --- |
| `GPT_5_5` | `gpt-5.5` | 16384 | 0.7 | `high` | `true` |
| `GPT_5_4` | `gpt-5.4` | 16384 | 0.7 | `high` | `true` |
| `GPT_5_4_MINI` | `gpt-5.4-mini` | 8192 | 0.7 | — | — |
| `GPT_5_4_NANO` | `gpt-5.4-nano` | 4096 | 0.7 | — | — |

### 特殊字段

- `reasoning_effort`：通过 `ChatRequestBuilder.reasoningEffort` 设置（如 `"low"` / `"medium"` / `"high"`），仅推理模型支持，映射到请求体 `reasoning_effort` 字段
- `enableThinking = true` 时若未显式设置 `reasoningEffort`，自动设为 `"medium"`（Completions API 互斥规则）
- OpenAI 推理模型不返回 `reasoning_content`（推理过程走 Responses API 的 reasoning items）

### 使用示例

```kotlin
val ai = llm {
    providers {
        openai {
            apiKey(System.getenv("OPENAI_API_KEY"))
            models { model(OpenAIModel.GPT_5_5) }
        }
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

- **工厂函数**：`AnthropicProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { anthropic { ... } } }`
- **注册名**：`anthropic`
- **baseUrl**：`https://api.anthropic.com/v1`
- **默认协议**：`ApiType.ANTHROPIC`（Messages API），`chat { }` 自动路由到 `anthropicClient`
- **工厂默认模型**：`claude-3-5-sonnet-20241022`
- **鉴权方式**：`x-api-key: <apiKey>` 头（**非** Bearer Token），由 `Llm.buildHeaders` 在 `ANTHROPIC` 分支注入

### 模型预设（`AnthropicModel`）

| 枚举项 | modelName | maxTokens | temperature | enableThinking |
| --- | --- | --- | --- | --- |
| `CLAUDE_OPUS_4_1` | `claude-opus-4-1` | 16384 | 0.0 | `true` |
| `CLAUDE_SONNET_4_6` | `claude-sonnet-4-6` | 8192 | 0.0 | `true` |
| `CLAUDE_OPUS_4` | `claude-opus-4-20250514` | 8192 | 0.0 | — |
| `CLAUDE_SONNET_4` | `claude-sonnet-4-20250514` | 8192 | 0.0 | — |

### 特殊字段

- **`anthropic-version` 头**：必填，由工厂自动注入 `anthropic-version: 2023-06-01` 到 `defaultHeaders`，与 `extraHeaders` 合并（`extraHeaders` 优先，可覆盖以使用 Beta API）
- **顶层 `system` 字段**：Anthropic 将 system 消息作为顶层 `system` 字段而非 messages 列表项，由 `ChatRequestBuilder.buildAnthropic()` 自动抽取并拼接
- **`max_tokens` 必填**：Anthropic 要求 `max_tokens` 必填，`buildAnthropic` 在 `maxTokens` 为 null 时默认 `4096`
- **`thinking` 对象**：`enableThinking = true` 时构造 `{type: "enabled", budget_tokens: maxTokens * 4 / 5}`，`budget_tokens` 强制 `< maxTokens`（API 约束）
- **content blocks**：响应 `content` 为 content blocks 列表（`text` / `tool_use`），由 `AnthropicResponse.toAIMessage()` 映射

### 使用示例

```kotlin
val ai = llm {
    providers {
        anthropic {
            apiKey(System.getenv("ANTHROPIC_API_KEY"))
            models { model(AnthropicModel.CLAUDE_OPUS_4_1) }
        }
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
- `reasoningEffort` 被忽略（Anthropic 协议无此字段，思考行为通过 `thinking` 对象控制）

---

## Kimi（Moonshot AI）

- **工厂函数**：`KimiProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { kimi { ... } } }`
- **注册名**：`kimi`
- **baseUrl**：`https://api.moonshot.cn/v1`（Moonshot 官方 OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`moonshot-v1-8k`（8k 上下文窗口版本）
- **鉴权方式**：Bearer Token

### 模型预设（`KimiModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `K2_5` | `kimi-k2-5` | K2.5 旗舰 |
| `K2_0905` | `kimi-k2-0905` | K2 0905 版 |
| `K2_0711` | `kimi-k2-0711` | K2 0711 版 |
| `K2_TURBO` | `kimi-k2-turbo` | K2 Turbo |
| `K2_THINKING` | `kimi-k2-thinking` | K2 思考模型 |
| `K2_THINKING_TURBO` | `kimi-k2-thinking-turbo` | K2 思考 Turbo |
| `V1_8K` | `moonshot-v1-8k` | 8k 上下文 |
| `V1_32K` | `moonshot-v1-32k` | 32k 上下文 |
| `V1_128K` | `moonshot-v1-128k` | 128k 上下文 |

> `K2_THINKING` / `K2_THINKING_TURBO` 预设了 `enableThinking = true` 与 `reasoningEffort`。

### 使用示例

```kotlin
val ai = llm {
    providers {
        kimi {
            apiKey(System.getenv("KIMI_API_KEY"))
            models { model(KimiModel.V1_128K) } // 长上下文
        }
    }
}
```

### 已知限制

- `moonshot-v1-8k` 上下文窗口仅 8k tokens，长上下文场景需切换至 `moonshot-v1-32k` / `moonshot-v1-128k` 或 K2 系列
- 模型名需与 Moonshot 官方文档一致，错误模型名会返回 404

---

## GLM（智谱 AI）

- **工厂函数**：`GlmProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { glm { ... } } }`
- **注册名**：`glm`
- **baseUrl**：`https://open.bigmodel.cn/api/paas/v4`（智谱开放平台 OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`glm-4-flash`（免费档快速模型）
- **鉴权方式**：Bearer Token

### 模型预设（`GlmModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `GLM_5_2` | `glm-5.2` | 最新旗舰 |
| `GLM_5_1` | `glm-5.1` | 上一代旗舰 |
| `GLM_5` | `glm-5` | 5 系列 |
| `GLM_4_7_FLASH` | `glm-4.7-flash` | 4.7 Flash 快速版 |

### 使用示例

```kotlin
val ai = llm {
    providers {
        glm {
            apiKey(System.getenv("GLM_API_KEY"))
            models { model(GlmModel.GLM_5_2) }
        }
    }
}
```

### 已知限制

- `glm-4-flash` 为免费档，存在速率限制与并发上限，生产环境建议切换至预设的 5 系列
- 流式响应需通过 `stream` 参数显式开启（`chatStream { }` 已自动处理）

---

## MiniMax

- **工厂函数**：`MiniMaxProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { minimax { ... } } }`
- **注册名**：`minimax`
- **baseUrl**：`https://api.minimaxi.chat/v1`（MiniMax OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`abab6.5-chat`（通用对话模型）
- **鉴权方式**：Bearer Token

### 模型预设（`MinimaxModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `ABAB7` | `abab7` | 7 系列 |
| `M2_5` | `m2.5` | M2.5 |
| `M2` | `m2` | M2 |

### 使用示例

```kotlin
val ai = llm {
    providers {
        minimax {
            apiKey(System.getenv("MINIMAX_API_KEY"))
            models { model(MinimaxModel.ABAB7) }
        }
    }
}
```

### 已知限制

- 上下文窗口受限，超长输入需切换至长上下文模型
- 部分高级特性（如语音合成、视频生成）不在 Chat Completions 端点支持范围内

---

## Ollama

- **工厂函数**：`OllamaProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { ollama { ... } } }`
- **注册名**：`ollama`
- **baseUrl**：`http://localhost:11434/v1`（本地默认）
- **默认协议**：`ApiType.COMPLETIONS`（Ollama 提供 OpenAI 兼容的 `/v1/chat/completions` 端点）
- **工厂默认模型**：`llama3.1`
- **鉴权方式**：无（本地模式）/ Bearer Token（云端模式）

### 两种模式

Ollama 是 `ModelProvider.apiKey` 设计为可空的关键场景，支持两种模式：

1. **本地模式（默认）**：`baseUrl` 与 `apiKey` 均为 null 时，使用 `http://localhost:11434/v1`、`apiKey = null`（无鉴权）。此时 `Llm.buildHeaders` 不会添加 `Authorization` 头。
2. **云端模式**：调用方通过 `baseUrl` 与 `apiKey` 同时覆盖，连接远程托管的 Ollama 服务。此时 `apiKey` 非 null，作为 Bearer token 注入。

### 使用示例

```kotlin
// 本地模式
val ai = llm {
    providers {
        ollama { models { model("llama3.1") } }
    }
}
// 云端模式
val aiCloud = llm {
    providers {
        ollama {
            baseUrl("https://your-ollama-host/v1")
            apiKey(System.getenv("OLLAMA_API_KEY"))
        }
    }
}
```

### 已知限制

- 本地模式下若 Ollama 未启动或端口被占用，请求将在 HttpClient 层失败
- 仅设置 `baseUrl` 而不设 `apiKey` 时，适用于"自定义地址但无鉴权"场景
- 模型需先通过 `ollama pull` 拉取到本地
- Ollama 无预设枚举（模型由本地 `ollama pull` 决定）

---

## Doubao（火山引擎方舟）

- **工厂函数**：`DoubaoProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { doubao { ... } } }`
- **注册名**：`doubao`
- **baseUrl**：`https://ark.cn-beijing.volces.com/api/v3`（火山方舟 OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`doubao-seed-1-6`（豆包 Seed 1.6 通用模型）
- **鉴权方式**：Bearer Token

### 模型预设（`DoubaoModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `SEED_2_1_PRO` | `doubao-seed-2.1-pro` | Seed 2.1 Pro 旗舰 |
| `SEED_1_6` | `doubao-seed-1-6` | Seed 1.6 |
| `SEED_1_6_FLASH` | `doubao-seed-1-6-flash` | Seed 1.6 Flash |
| `SEED_1_6_THINKING` | `doubao-seed-1-6-thinking` | Seed 1.6 思考版 |
| `SEED_1_6_VISION` | `doubao-seed-1-6-vision` | Seed 1.6 视觉版 |

> `SEED_1_6_THINKING` 预设了 `enableThinking = true` 与 `reasoningEffort`。

### 使用示例

```kotlin
val ai = llm {
    providers {
        doubao {
            apiKey(System.getenv("DOUBAO_API_KEY"))
            models { model(DoubaoModel.SEED_2_1_PRO) }
        }
    }
}
```

### 已知限制

- 火山方舟生产环境通常需在控制台创建"接入点"（endpoint id，形如 `ep-xxxxxxxx`），通过 `model("ep-...")` 覆盖预设模型名
- 接入点需在火山方舟控制台显式创建并启用，否则返回 404

---

## Qwen（阿里 DashScope）

- **工厂函数**：`QwenProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { qwen { ... } } }`
- **注册名**：`qwen`
- **baseUrl**：`https://dashscope.aliyuncs.com/compatible-mode/v1`（DashScope OpenAI 兼容模式端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`qwen-plus`（均衡档位通义千问模型）
- **鉴权方式**：Bearer Token

### 模型预设（`QwenModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `MAX_3_7` | `qwen3-max-7` | Qwen3 Max 7 |
| `PLUS_3_7` | `qwen3-plus-7` | Qwen3 Plus 7 |
| `MAX_3_6` | `qwen3-max-6` | Qwen3 Max 6 |
| `PLUS_3_6` | `qwen3-plus-6` | Qwen3 Plus 6 |
| `FLASH_3_6` | `qwen3-flash-6` | Qwen3 Flash 6 |
| `MAX_FLASH` | `qwen-max-flash` | Max Flash |

### 使用示例

```kotlin
val ai = llm {
    providers {
        qwen {
            apiKey(System.getenv("QWEN_API_KEY"))
            models { model(QwenModel.MAX_3_7) }
        }
    }
}
```

### 已知限制

- 此 baseUrl 仅适用于 OpenAI 兼容模式，原生 DashScope 协议端点不同，不可混用
- `qwen-plus` 为均衡档位，对长上下文或高精度场景可切换至 `qwen-max` 或 Qwen3 系列预设
- 部分高级参数（如 `enable_search`）需通过 `extraHeaders` 或请求体显式开启

---

## MIMO（小米）

- **工厂函数**：`MimoProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { mimo { ... } } }`
- **注册名**：`mimo`
- **baseUrl**：`https://api.mimo.xiaomi.com/v1`（小米 MIMO OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`
- **工厂默认模型**：`mimo-7b`（7B 参数量轻量模型）
- **鉴权方式**：Bearer Token

### 模型预设（`MimoModel`）

| 枚举项 | modelName | 备注 |
| --- | --- | --- |
| `V2_5_PRO` | `mimo-v2.5-pro` | V2.5 Pro 旗舰 |
| `V2_FLASH` | `mimo-v2-flash` | V2 Flash 快速版 |

### 使用示例

```kotlin
val ai = llm {
    providers {
        mimo {
            apiKey(System.getenv("MIMO_API_KEY"))
            models { model(MimoModel.V2_5_PRO) }
        }
    }
}
```

### 已知限制

- `mimo-7b` 为 7B 参数量轻量模型，复杂推理与长上下文能力有限，建议使用 V2.5 Pro 预设
- 模型上下文窗口较小，长输入可能被截断或报错

---

## Gemini

- **工厂函数**：`GeminiProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**：`llm { providers { gemini { ... } } }`
- **注册名**：`gemini`
- **baseUrl**：`https://generativelanguage.googleapis.com/v1beta/openai`（Google 官方 OpenAI 兼容端点）
- **默认协议**：`ApiType.COMPLETIONS`（通过 OpenAI 兼容端点接入，复用 Completions API 客户端）
- **工厂默认模型**：`gemini-1.5-flash`
- **鉴权方式**：Bearer Token（Google API key 直接作为 Bearer token）

### 模型预设（`GeminiModel`）

| 枚举项 | modelName | maxTokens | temperature | reasoningEffort | enableThinking |
| --- | --- | --- | --- | --- | --- |
| `PRO_3` | `gemini-3-pro` | 8192 | 0.7 | `high` | `true` |
| `FLASH_3` | `gemini-3-flash` | 8192 | 0.7 | — | — |

### 特殊说明

RAC 使用 Google 官方提供的 OpenAI 兼容端点，而非 Gemini 原生 API（`/v1beta/models/{model}:generateContent`）。这样 Gemini 与其他 OpenAI 兼容供应商共用 `COMPLETIONS` 协议，由 Completions API 客户端统一处理 `/chat/completions` 请求与响应解析。模型名使用 Gemini 原生命名（不含 `models/` 前缀），与 OpenAI 兼容端点的约定一致。

### 使用示例

```kotlin
val ai = llm {
    providers {
        gemini {
            apiKey(System.getenv("GEMINI_API_KEY"))
            models { model(GeminiModel.PRO_3) }
        }
    }
}
```

### 已知限制

- 不调用 Gemini 原生 API（如 `:generateContent`、`:streamGenerateContent`），若需使用原生特性（如多模态 grounding、安全设置），需另外实现原生客户端
- `apiKey` 为 null 时端点返回 401，调用方必须设置有效的 Google API key
- `baseUrl` 覆盖时，覆盖后的端点仍须兼容 OpenAI Chat Completions 协议

---

## 跨供应商用法

### 运行时切换供应商与模型

`chat { }` 内通过 `provider(name)` 与 `model(name)` 切换，复用其他供应商的连接与已注册模型配置：

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey(System.getenv("DEEPSEEK_API_KEY"))
            models { model(DeepSeekModel.V4_FLASH) }
        }
        openai {
            apiKey(System.getenv("OPENAI_API_KEY"))
            models { model(OpenAIModel.GPT_5_4_MINI) }
        }
    }
}

// 默认走 deepseek
val r1 = ai.chat { user("你好") }

// 运行时切到 openai
val r2 = ai.chat {
    provider("openai")
    model("gpt-5.4-mini")
    user("你好")
}
```

### 混合供应商预设

不同供应商的预设枚举互不冲突，可在同一个 `llm { }` 块内混合使用：

```kotlin
val ai = llm {
    providers {
        deepseek { models { model(DeepSeekModel.V4_PRO) } }
        anthropic { models { model(AnthropicModel.CLAUDE_OPUS_4_1) } }
        openai { models { model(OpenAIModel.GPT_5_5) } }
    }
}
```

### 默认供应商选择规则

- 首个注册的供应商自动成为默认（基于 `LinkedHashMap` 插入序）
- 可通过 `defaultProviderName = "xxx"` 在 `llm { }` 块顶层覆盖
- `defaultProviderName` 指向未注册的供应商时 `build()` 抛 `RACException`
