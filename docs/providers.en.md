**English** | [中文](providers.md)

# Providers In-Depth

RAC abstracts 11 LLM providers behind a unified `ModelProvider` interface. Each provider offers a factory function (e.g. `DeepSeekProvider(config, models)`) and a DSL extension inside the `llm { providers { } }` block (e.g. `deepseek { }`). This document summarizes connection metadata, default config, model presets, and known limitations for every provider.

All providers are configured via `ProviderConfig` (connection info) + `Map<String, ModelConfig>` (model registry) passed to the factory function. The DSL scope `ProviderDsl` exposes:

| Method                | Purpose                                                            |
|-----------------------|-------------------------------------------------------------------|
| `apiKey(key)`         | Override the API key; `null` keeps the provider default           |
| `baseUrl(url)`        | Override the API base URL                                          |
| `header(name, value)` | Append a single custom request header                              |
| `headers(map)`        | Append a batch of custom request headers                           |
| `models { }`          | Enter the `ModelsBuilder` scope to register models one by one      |

`ModelsBuilder` provides two `model()` overloads:

| Overload                | Purpose                                                                                  |
|-------------------------|------------------------------------------------------------------------------------------|
| `model(name) { ... }`   | Specify the model name and `ModelConfig` manually (all-null block → server defaults)     |
| `model(preset) { ... }` | Read the model name and recommended config from a preset enum (`ModelPreset`); block overrides |

> Auth headers (`Authorization: Bearer` or `x-api-key`) are not hardcoded by the provider factory. Instead `Llm.buildHeaders(provider)` dynamically injects them per request based on the provider's `defaultApiType`. When `apiKey` is `null`, no auth header is added.

## Multimodal Input

Each `ModelConfig` carries a `modalities: Set<Modality>` field declaring which input modalities the model accepts (`TEXT` / `IMAGE` / `AUDIO`). Presets prefill this set per model based on its known capabilities. You can override it on any registration:

```kotlin
openai {
    models {
        model(OpenAIModel.GPT_5_5) {
            modalities { image(); audio() }  // declare supported input modalities
        }
    }
}
```

When sending multimodal user messages via `chat { user { } }`, RAC serializes `ImageContent` and `AudioContent` into the format each API expects:

| API         | Image format                                                                                         | Audio format                                          |
|-------------|------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| Completions | `{"type":"image_url","image_url":{"url":"..."}}` (base64 becomes a `data:` URI)                       | Currently a text placeholder (no native audio slot)   |
| Anthropic   | `{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}` (base64) or `{"type":"url","url":"..."}` | Not natively supported by the protocol                |
| Responses   | `{"type":"input_image","image_url":"..."}` (flat string)                                             | Currently a text placeholder                          |

## Provider Overview

| Provider  | baseUrl                                                       | Default API protocol | Factory default model          | Auth              | Preset enum       |
|-----------|---------------------------------------------------------------|----------------------|--------------------------------|-------------------|-------------------|
| DeepSeek  | `https://api.deepseek.com`                                    | Completions          | `deepseek-v4-flash`            | Bearer Token      | `DeepSeekModel`   |
| OpenAI    | `https://api.openai.com/v1`                                   | Completions          | `gpt-4o-mini`                  | Bearer Token      | `OpenAIModel`     |
| Anthropic | `https://api.anthropic.com/v1`                                | Anthropic            | `claude-3-5-sonnet-20241022`   | `x-api-key`       | `AnthropicModel`  |
| Kimi      | `https://api.moonshot.cn/v1`                                  | Completions          | `moonshot-v1-8k`               | Bearer Token      | `KimiModel`       |
| GLM       | `https://open.bigmodel.cn/api/paas/v4`                        | Completions          | `glm-4-flash`                  | Bearer Token      | `GlmModel`        |
| MiniMax   | `https://api.minimaxi.chat/v1`                                | Completions          | `abab6.5-chat`                 | Bearer Token      | `MinimaxModel`    |
| Ollama    | `http://localhost:11434/v1`                                   | Completions          | `llama3.1`                     | none (local) / Bearer (cloud) | —                 |
| Doubao    | `https://ark.cn-beijing.volces.com/api/v3`                    | Completions          | `doubao-seed-1-6`              | Bearer Token      | `DoubaoModel`     |
| Qwen      | `https://dashscope.aliyuncs.com/compatible-mode/v1`           | Completions          | `qwen-plus`                    | Bearer Token      | `QwenModel`       |
| MIMO      | `https://api.mimo.xiaomi.com/v1`                              | Completions          | `mimo-7b`                      | Bearer Token      | `MimoModel`       |
| Gemini    | `https://generativelanguage.googleapis.com/v1beta/openai`     | Completions          | `gemini-1.5-flash`             | Bearer Token      | `GeminiModel`     |

> **Factory default model vs preset model**: "Factory default model" in the table is the legacy name the factory function falls back to when `models = emptyMap()`. We recommend using preset enums via `models { model(PresetEnum.XXX) }` to get the latest model names (as of 2026-07) and tuned configs.

---

## DeepSeek

- **Factory function**:
  `DeepSeekProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { deepseek { ... } } }`
- **Registered name**: `deepseek`
- **baseUrl**: `https://api.deepseek.com` (no `/v1` suffix — DeepSeek's convention)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `deepseek-v4-flash`
- **Auth**: Bearer Token, injected as `Authorization: Bearer <apiKey>` by `Llm.buildHeaders`

### Model presets (`DeepSeekModel`)

| Enum item   | modelName           | maxTokens | temperature | reasoningEffort | enableThinking | modalities |
|-------------|---------------------|-----------|-------------|-----------------|----------------|------------|
| `V4_PRO`    | `deepseek-v4-pro`   | 8192      | 0.0         | `high`          | `true`         | TEXT       |
| `V4_FLASH`  | `deepseek-v4-flash` | 8192      | 0.0         | `medium`        | —              | TEXT       |

### Model migration notice (2026-07)

Legacy model names `deepseek-chat` and `deepseek-reasoner` will be retired on **2026-07-24**. Migration mapping:

| Legacy name           | New name                                              | Notes                                  |
|-----------------------|-------------------------------------------------------|----------------------------------------|
| `deepseek-chat`       | `deepseek-v4-flash`                                   | Non-thinking mode (default)            |
| `deepseek-reasoner`   | `deepseek-v4-flash` thinking mode / `deepseek-v4-pro` | Reasoning model; V4-Pro is more capable |

### Special fields

DeepSeek-V4-Pro and other reasoning models return a `reasoning_content` field in the response. This is parsed by `CompletionsResponse` / `ResponseMessage` and mapped to `AIMessage.reasoningContent` via `toAIMessage()`. In streaming scenarios, `CompletionsStreamChunk.Delta.reasoningContent` provides incremental reasoning content.

### Usage example

```kotlin
val ai = llm {
    providers {
        deepseek {
            apiKey(System.getenv("DEEPSEEK_API_KEY"))
            models {
                model(DeepSeekModel.V4_FLASH)                    // fully use preset
                model(DeepSeekModel.V4_PRO) { maxTokens = 4096 } // override maxTokens
            }
        }
    }
}
```

### Known limitations

- Does not support the Anthropic protocol or Responses API; calling `respond { }` will fail
- baseUrl does not include `/v1`, unlike most OpenAI-compatible providers — preserve this when overriding

---

## OpenAI

- **Factory function**:
  `OpenAIProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { openai { ... } } }`
- **Registered name**: `openai`
- **baseUrl**: `https://api.openai.com/v1`
- **Default protocol**: `ApiType.COMPLETIONS` (Chat Completions); callers can explicitly call `respond { }` for the Responses API
- **Factory default model**: `gpt-4o-mini`
- **Auth**: Bearer Token

### Model presets (`OpenAIModel`)

| Enum item       | modelName      | maxTokens | temperature | reasoningEffort | enableThinking | modalities        |
|-----------------|----------------|-----------|-------------|-----------------|----------------|-------------------|
| `GPT_5_5`       | `gpt-5.5`      | 16384     | 0.7         | `high`          | `true`         | TEXT + IMAGE + AUDIO |
| `GPT_5_4`       | `gpt-5.4`      | 16384     | 0.7         | `high`          | `true`         | TEXT + IMAGE + AUDIO |
| `GPT_5_4_MINI`  | `gpt-5.4-mini` | 8192      | 0.7         | —               | —              | TEXT + IMAGE      |
| `GPT_5_4_NANO`  | `gpt-5.4-nano` | 4096      | 0.7         | —               | —              | TEXT + IMAGE      |

### Special fields

- `reasoning_effort`: set via `ChatRequestBuilder.reasoningEffort` (e.g. `"low"` / `"medium"` / `"high"`); reasoning models only; maps to the `reasoning_effort` request body field
- When `enableThinking = true` and `reasoningEffort` is not explicitly set, it auto-defaults to `"medium"` (Completions API mutual-exclusion rule)
- OpenAI reasoning models do not return `reasoning_content` (reasoning happens via Responses API reasoning items)

### Usage example

```kotlin
val ai = llm {
    providers {
        openai {
            apiKey(System.getenv("OPENAI_API_KEY"))
            models { model(OpenAIModel.GPT_5_5) }
        }
    }
}
// explicitly use Responses API:
// ai.respond { input("...") }
```

### Known limitations

- The Responses API is currently only supported by OpenAI officially; other providers calling `respond { }` will fail
- `baseUrl` overrides are commonly used for self-hosted proxies or Azure OpenAI-compatible endpoints

---

## Anthropic

- **Factory function**:
  `AnthropicProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { anthropic { ... } } }`
- **Registered name**: `anthropic`
- **baseUrl**: `https://api.anthropic.com/v1`
- **Default protocol**: `ApiType.ANTHROPIC` (Messages API); `chat { }` auto-routes to `anthropicClient`
- **Factory default model**: `claude-3-5-sonnet-20241022`
- **Auth**: `x-api-key: <apiKey>` header (**not** Bearer Token), injected by `Llm.buildHeaders` in the `ANTHROPIC` branch

### Model presets (`AnthropicModel`)

| Enum item            | modelName                  | maxTokens | temperature | enableThinking | modalities   |
|----------------------|----------------------------|-----------|-------------|----------------|--------------|
| `CLAUDE_OPUS_4_1`    | `claude-opus-4-1`          | 16384     | 0.0         | `true`         | TEXT + IMAGE |
| `CLAUDE_SONNET_4_6`  | `claude-sonnet-4-6`        | 8192      | 0.0         | `true`         | TEXT + IMAGE |
| `CLAUDE_OPUS_4`      | `claude-opus-4-20250514`   | 8192      | 0.0         | —              | TEXT + IMAGE |
| `CLAUDE_SONNET_4`    | `claude-sonnet-4-20250514` | 8192      | 0.0         | —              | TEXT + IMAGE |

### Special fields

- **`anthropic-version` header**: required; auto-injected as `anthropic-version: 2023-06-01` into `defaultHeaders` by the factory, merged with `extraHeaders` (extraHeaders take precedence, allowing Beta API usage)
- **Top-level `system` field**: Anthropic puts system messages as a top-level `system` field rather than a messages-list entry; `ChatRequestBuilder.buildAnthropic()` automatically extracts and concatenates them
- **`max_tokens` is required**: Anthropic requires `max_tokens`; `buildAnthropic` defaults to `4096` when null
- **`thinking` object**: when `enableThinking = true`, builds `{type: "enabled", budget_tokens: maxTokens * 4 / 5}`, with `budget_tokens` forced `< maxTokens` (API constraint)
- **content blocks**: the response `content` is a list of content blocks (`text` / `tool_use`), mapped by `AnthropicResponse.toAIMessage()`

### Multimodal format

Anthropic serializes `ImageContent` to its native format (preferring base64):

```json
{"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}
```

When only a URL is provided:

```json
{"type":"url","url":"https://example.com/cat.jpg"}
```

### Usage example

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
    system("You are an assistant")
    user("Hello")
}
// streaming must use anthropicStream:
// ai.anthropicStream { user("...") }
```

### Known limitations

- Does not support the Completions API or Responses API
- Calling `respond { }` or `chatStream { }` on an Anthropic provider throws `RACException`; use `anthropicStream { }` instead
- `reasoningEffort` is ignored (the Anthropic protocol has no such field; thinking is controlled via the `thinking` object)

---

## Kimi (Moonshot AI)

- **Factory function**:
  `KimiProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { kimi { ... } } }`
- **Registered name**: `kimi`
- **baseUrl**: `https://api.moonshot.cn/v1` (Moonshot's official OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `moonshot-v1-8k` (8k context window version)
- **Auth**: Bearer Token

### Model presets (`KimiModel`)

| Enum item            | modelName                | Notes              | modalities |
|----------------------|--------------------------|--------------------|------------|
| `K2_5`               | `kimi-k2-5`              | K2.5 flagship      | TEXT       |
| `K2_0905`            | `kimi-k2-0905`           | K2 0905 release    | TEXT       |
| `K2_0711`            | `kimi-k2-0711`           | K2 0711 release    | TEXT       |
| `K2_TURBO`           | `kimi-k2-turbo`          | K2 Turbo           | TEXT       |
| `K2_THINKING`        | `kimi-k2-thinking`       | K2 thinking model  | TEXT       |
| `K2_THINKING_TURBO`  | `kimi-k2-thinking-turbo` | K2 thinking Turbo  | TEXT       |
| `V1_8K`              | `moonshot-v1-8k`         | 8k context         | TEXT       |
| `V1_32K`             | `moonshot-v1-32k`        | 32k context        | TEXT       |
| `V1_128K`            | `moonshot-v1-128k`       | 128k context       | TEXT       |

> `K2_THINKING` / `K2_THINKING_TURBO` presets set `enableThinking = true` and an appropriate `reasoningEffort`.

### Usage example

```kotlin
val ai = llm {
    providers {
        kimi {
            apiKey(System.getenv("KIMI_API_KEY"))
            models { model(KimiModel.V1_128K) } // long context
        }
    }
}
```

### Known limitations

- `moonshot-v1-8k` has only an 8k context window; for long contexts switch to `moonshot-v1-32k` / `moonshot-v1-128k` or the K2 series
- Model names must match Moonshot's official docs; incorrect names return 404

---

## GLM (Zhipu AI)

- **Factory function**:
  `GlmProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { glm { ... } } }`
- **Registered name**: `glm`
- **baseUrl**: `https://open.bigmodel.cn/api/paas/v4` (Zhipu's OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `glm-4-flash` (free-tier fast model)
- **Auth**: Bearer Token

### Model presets (`GlmModel`)

| Enum item        | modelName       | Notes              | modalities   |
|------------------|-----------------|--------------------|--------------|
| `GLM_5_2`        | `glm-5.2`       | Latest flagship    | TEXT + IMAGE |
| `GLM_5_1`        | `glm-5.1`       | Previous flagship  | TEXT + IMAGE |
| `GLM_5`          | `glm-5`         | 5 series           | TEXT + IMAGE |
| `GLM_4_7_FLASH`  | `glm-4.7-flash` | 4.7 Flash fast     | TEXT + IMAGE |

### Usage example

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

### Known limitations

- `glm-4-flash` is a free tier with rate limits and concurrency caps; production should switch to the 5-series presets
- Streaming responses require the `stream` parameter (handled automatically by `chatStream { }`)

---

## MiniMax

- **Factory function**:
  `MiniMaxProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { minimax { ... } } }`
- **Registered name**: `minimax`
- **baseUrl**: `https://api.minimaxi.chat/v1` (MiniMax OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `abab6.5-chat` (general chat model)
- **Auth**: Bearer Token

### Model presets (`MinimaxModel`)

| Enum item  | modelName      | Notes   | modalities |
|------------|----------------|---------|------------|
| `ABAB7`    | `abab7`        | 7 series| TEXT       |
| `M2_5`     | `m2.5`         | M2.5    | TEXT       |
| `M2`       | `m2`           | M2      | TEXT       |

### Usage example

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

### Known limitations

- Context window is limited; very long inputs require switching to a long-context model
- Some advanced features (speech synthesis, video generation) are outside the Chat Completions endpoint

---

## Ollama

- **Factory function**:
  `OllamaProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { ollama { ... } } }`
- **Registered name**: `ollama`
- **baseUrl**: `http://localhost:11434/v1` (local default)
- **Default protocol**: `ApiType.COMPLETIONS` (Ollama provides an OpenAI-compatible `/v1/chat/completions` endpoint)
- **Factory default model**: `llama3.1`
- **Auth**: none (local mode) / Bearer Token (cloud mode)

### Two modes

Ollama is the key scenario where `ModelProvider.apiKey` is nullable, supporting two modes:

1. **Local mode (default)**: when both `baseUrl` and `apiKey` are null, uses `http://localhost:11434/v1` with `apiKey = null` (no auth). `Llm.buildHeaders` will not add an `Authorization` header.
2. **Cloud mode**: the caller overrides both `baseUrl` and `apiKey` to connect to a remotely hosted Ollama service. `apiKey` is non-null and injected as a Bearer token.

### Usage example

```kotlin
// local mode
val ai = llm {
    providers {
        ollama { models { model("llama3.1") } }
    }
}
// cloud mode
val aiCloud = llm {
    providers {
        ollama {
            baseUrl("https://your-ollama-host/v1")
            apiKey(System.getenv("OLLAMA_API_KEY"))
        }
    }
}
```

### Known limitations

- In local mode, if Ollama is not running or the port is occupied, requests fail at the HttpClient layer
- Setting only `baseUrl` without `apiKey` fits the "custom address but no auth" scenario
- Models must be pulled locally via `ollama pull` first
- Ollama has no preset enum (models are determined by local `ollama pull`)

---

## Doubao (Volcengine Ark)

- **Factory function**:
  `DoubaoProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { doubao { ... } } }`
- **Registered name**: `doubao`
- **baseUrl**: `https://ark.cn-beijing.volces.com/api/v3` (Volcengine Ark OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `doubao-seed-1-6` (Doubao Seed 1.6 general model)
- **Auth**: Bearer Token

### Model presets (`DoubaoModel`)

| Enum item            | modelName                  | Notes                | modalities   |
|----------------------|----------------------------|----------------------|--------------|
| `SEED_2_1_PRO`       | `doubao-seed-2.1-pro`      | Seed 2.1 Pro flagship| TEXT + IMAGE |
| `SEED_1_6`           | `doubao-seed-1.6`          | Seed 1.6             | TEXT + IMAGE |
| `SEED_1_6_FLASH`     | `doubao-seed-1.6-flash`    | Seed 1.6 Flash       | TEXT + IMAGE |
| `SEED_1_6_THINKING`  | `doubao-seed-1.6-thinking` | Seed 1.6 thinking    | TEXT         |
| `SEED_1_6_VISION`    | `doubao-seed-1.6-vision`   | Seed 1.6 vision      | TEXT + IMAGE |

> `SEED_1_6_THINKING` preset sets `enableThinking = true` and an appropriate `reasoningEffort`.

### Usage example

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

### Known limitations

- Volcengine Ark production typically requires creating an "endpoint" (endpoint id, shaped like `ep-xxxxxxxx`) in the console; override the preset model name via `model("ep-...")`
- Endpoints must be explicitly created and enabled in the Volcengine Ark console, otherwise 404

---

## Qwen (Alibaba DashScope)

- **Factory function**:
  `QwenProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { qwen { ... } } }`
- **Registered name**: `qwen`
- **baseUrl**: `https://dashscope.aliyuncs.com/compatible-mode/v1` (DashScope OpenAI-compatible mode endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `qwen-plus` (balanced-tier Qwen model)
- **Auth**: Bearer Token

### Model presets (`QwenModel`)

| Enum item    | modelName        | Notes          | modalities   |
|--------------|------------------|----------------|--------------|
| `MAX_3_7`    | `qwen3-max-7`    | Qwen3 Max 7    | TEXT + IMAGE |
| `PLUS_3_7`   | `qwen3-plus-7`   | Qwen3 Plus 7   | TEXT + IMAGE |
| `MAX_3_6`    | `qwen3-max-6`    | Qwen3 Max 6    | TEXT + IMAGE |
| `PLUS_3_6`   | `qwen3-plus-6`   | Qwen3 Plus 6   | TEXT + IMAGE |
| `FLASH_3_6`  | `qwen3-flash-6`  | Qwen3 Flash 6  | TEXT + IMAGE |
| `MAX_FLASH`  | `qwen-max-flash` | Max Flash      | TEXT + IMAGE |

### Usage example

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

### Known limitations

- This baseUrl is for the OpenAI-compatible mode only; the native DashScope protocol endpoint differs and must not be mixed
- `qwen-plus` is the balanced tier; switch to `qwen-max` or the Qwen3 presets for long-context or high-precision scenarios
- Some advanced parameters (e.g. `enable_search`) must be enabled explicitly via `extraHeaders` or the request body

---

## MIMO (Xiaomi)

- **Factory function**:
  `MimoProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { mimo { ... } } }`
- **Registered name**: `mimo`
- **baseUrl**: `https://api.mimo.xiaomi.com/v1` (Xiaomi MIMO OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS`
- **Factory default model**: `mimo-7b` (7B lightweight model)
- **Auth**: Bearer Token

### Model presets (`MimoModel`)

| Enum item    | modelName       | Notes            | modalities |
|--------------|-----------------|------------------|------------|
| `V2_5_PRO`   | `mimo-v2.5-pro` | V2.5 Pro flagship| TEXT       |
| `V2_FLASH`   | `mimo-v2-flash` | V2 Flash fast    | TEXT       |

### Usage example

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

### Known limitations

- `mimo-7b` is a 7B lightweight model with limited reasoning and long-context capability; prefer the V2.5 Pro preset
- Small context window; long inputs may be truncated or rejected

---

## Gemini

- **Factory function**:
  `GeminiProvider(config: ProviderConfig = ProviderConfig(), models: Map<String, ModelConfig> = emptyMap()): ModelProvider`
- **DSL**: `llm { providers { gemini { ... } } }`
- **Registered name**: `gemini`
- **baseUrl**: `https://generativelanguage.googleapis.com/v1beta/openai` (Google's official OpenAI-compatible endpoint)
- **Default protocol**: `ApiType.COMPLETIONS` (via the OpenAI-compatible endpoint, reusing the Completions API client)
- **Factory default model**: `gemini-1.5-flash`
- **Auth**: Bearer Token (Google API key used directly as Bearer token)

### Model presets (`GeminiModel`)

| Enum item  | modelName        | maxTokens | temperature | reasoningEffort | enableThinking | modalities        |
|------------|------------------|-----------|-------------|-----------------|----------------|-------------------|
| `PRO_3`    | `gemini-3-pro`   | 8192      | 0.7         | `high`          | `true`         | TEXT + IMAGE + AUDIO |
| `FLASH_3`  | `gemini-3-flash` | 8192      | 0.7         | —               | —              | TEXT + IMAGE + AUDIO |

### Special note

RAC uses Google's official OpenAI-compatible endpoint, not the Gemini native API (`/v1beta/models/{model}:generateContent`). This lets Gemini share the `COMPLETIONS` protocol with other OpenAI-compatible providers, handled by the Completions API client for `/chat/completions` requests and responses. Model names use Gemini's native naming (without the `models/` prefix), matching the OpenAI-compatible endpoint convention.

### Usage example

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

### Known limitations

- Does not call the Gemini native API (e.g. `:generateContent`, `:streamGenerateContent`); native features (multimodal grounding, safety settings) require a separate native client
- When `apiKey` is null the endpoint returns 401; callers must set a valid Google API key
- If overriding `baseUrl`, the new endpoint must still be OpenAI Chat Completions compatible

---

## Cross-provider usage

### Runtime provider/model switching

Inside `chat { }`, use `provider(name)` and `model(name)` to switch, reusing another provider's connection and registered model config:

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

// default goes to deepseek
val r1 = ai.chat { user("Hello") }

// switch to openai at runtime
val r2 = ai.chat {
    provider("openai")
    model("gpt-5.4-mini")
    user("Hello")
}
```

### Mixing provider presets

Presets from different providers don't conflict; they can be mixed inside the same `llm { }` block:

```kotlin
val ai = llm {
    providers {
        deepseek { models { model(DeepSeekModel.V4_PRO) } }
        anthropic { models { model(AnthropicModel.CLAUDE_OPUS_4_1) } }
        openai { models { model(OpenAIModel.GPT_5_5) } }
    }
}
```

### Default provider selection rule

- The first registered provider becomes the default (based on `LinkedHashMap` insertion order)
- Override with `defaultProviderName = "xxx"` at the top of the `llm { }` block
- If `defaultProviderName` points to an unregistered provider, `build()` throws `RACException`
