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

import top.resderx.rac.providers.Modality
import top.resderx.rac.providers.ModelConfig

/**
 * OpenAI 供应商的模型预设枚举。
 *
 * - 作用：为 OpenAI 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://platform.openai.com/docs/models
 * - 模型列表：
 *   - [GPT_5_5]：gpt-5.5，最新旗舰模型
 *   - [GPT_5_4]：gpt-5.4，上一代旗舰
 *   - [GPT_5_4_MINI]：gpt-5.4-mini，轻量版
 *   - [GPT_5_4_NANO]：gpt-5.4-nano，超轻量版
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class OpenAIModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** GPT-5.5——OpenAI 最新旗舰模型，适合复杂推理与长文本生成。 */
    GPT_5_5(
        modelName = "gpt-5.5",
        recommendedConfig = ModelConfig(
            reasoningEffort = "high",
            enableThinking = true,
            modalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO),
        ),
    ),

    /** GPT-5.4——上一代旗舰，性能稳定，成本低于 5.5。 */
    GPT_5_4(
        modelName = "gpt-5.4",
        recommendedConfig = ModelConfig(
            reasoningEffort = "high",
            enableThinking = true,
            modalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO),
        ),
    ),

    /** GPT-5.4 Mini——轻量版，速度快成本低，适合日常对话。 */
    GPT_5_4_MINI(
        modelName = "gpt-5.4-mini",
        recommendedConfig = ModelConfig(
            modalities = setOf(Modality.TEXT, Modality.IMAGE),
        ),
    ),

    /** GPT-5.4 Nano——超轻量版，极致速度，适合简单任务与边缘部署。 */
    GPT_5_4_NANO(
        modelName = "gpt-5.4-nano",
        recommendedConfig = ModelConfig(
            modalities = setOf(Modality.TEXT, Modality.IMAGE),
        ),
    ),
}
