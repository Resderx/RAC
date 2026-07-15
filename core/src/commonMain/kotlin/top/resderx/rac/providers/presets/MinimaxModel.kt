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
 * MiniMax 供应商的模型预设枚举。
 *
 * - 作用：为 MiniMax 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://platform.minimaxi.com/document
 * - 模型列表：
 *   - [ABAB7]：abab7，通用大模型
 *   - [M2_5]：MiniMax-M2.5，M2.5 系列
 *   - [M2]：MiniMax-M2，M2 系列
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class MinimaxModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** ABAB7——通用大模型，综合能力强。 */
    ABAB7(
        modelName = "abab7",
        recommendedConfig = ModelConfig(modalities = setOf(Modality.TEXT)),
    ),

    /** MiniMax-M2.5——M2.5 系列，增强推理能力。 */
    M2_5(
        modelName = "MiniMax-M2.5",
        recommendedConfig = ModelConfig(
            reasoningEffort = "high",
            enableThinking = true,
            modalities = setOf(Modality.TEXT),
        ),
    ),

    /** MiniMax-M2——M2 系列，通用能力强。 */
    M2(
        modelName = "MiniMax-M2",
        recommendedConfig = ModelConfig(modalities = setOf(Modality.TEXT)),
    ),
}
