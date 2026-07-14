/*
 * Copyright 2026 Resderx
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package top.resderx.rac.providers.presets

import com.resderx.rac.providers.ModelConfig

/**
 * Anthropic 供应商的模型预设枚举。
 *
 * - 作用：为 Anthropic 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://docs.anthropic.com/en/docs/about-claude/models
 * - 模型列表：
 *   - [CLAUDE_OPUS_4_1]：claude-opus-4-1，最新 Opus 旗舰
 *   - [CLAUDE_SONNET_4_6]：claude-sonnet-4-6，最新 Sonnet
 *   - [CLAUDE_OPUS_4]：claude-opus-4-20250514，上一代 Opus
 *   - [CLAUDE_SONNET_4]：claude-sonnet-4-20250514，上一代 Sonnet
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class AnthropicModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** Claude Opus 4.1——最新 Opus 旗舰，最强推理能力，启用扩展思考。 */
    CLAUDE_OPUS_4_1(
        modelName = "claude-opus-4-1",
        recommendedConfig = ModelConfig(
            maxTokens = 16384L,
            temperature = 0.0,
            enableThinking = true,
        ),
    ),

    /** Claude Sonnet 4.6——最新 Sonnet，平衡性能与成本，启用扩展思考。 */
    CLAUDE_SONNET_4_6(
        modelName = "claude-sonnet-4-6",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
            enableThinking = true,
        ),
    ),

    /** Claude Opus 4——上一代 Opus，强大推理能力。 */
    CLAUDE_OPUS_4(
        modelName = "claude-opus-4-20250514",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
        ),
    ),

    /** Claude Sonnet 4——上一代 Sonnet，性价比之选。 */
    CLAUDE_SONNET_4(
        modelName = "claude-sonnet-4-20250514",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
        ),
    ),
}
