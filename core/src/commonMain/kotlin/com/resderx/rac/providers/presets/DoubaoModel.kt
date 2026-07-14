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

package com.resderx.rac.providers.presets

import com.resderx.rac.providers.ModelConfig

/**
 * 字节跳动豆包（Doubao）供应商的模型预设枚举。
 *
 * - 作用：为豆包官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://www.volcengine.com/docs/82379
 * - 模型列表：
 *   - [SEED_2_1_PRO]：doubao-seed-2.1-pro，最新旗舰
 *   - [SEED_1_6]：doubao-seed-1.6，1.6 系列标准版
 *   - [SEED_1_6_FLASH]：doubao-seed-1.6-flash，1.6 轻量版
 *   - [SEED_1_6_THINKING]：doubao-seed-1.6-thinking，1.6 思考模型
 *   - [SEED_1_6_VISION]：doubao-seed-1.6-vision，1.6 视觉模型（文本兼容）
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class DoubaoModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** Doubao Seed 2.1 Pro——最新旗舰，强大推理能力。 */
    SEED_2_1_PRO(
        modelName = "doubao-seed-2.1-pro",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Doubao Seed 1.6——1.6 系列标准版，通用能力强。 */
    SEED_1_6(
        modelName = "doubao-seed-1.6",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),

    /** Doubao Seed 1.6 Flash——1.6 轻量版，低延迟。 */
    SEED_1_6_FLASH(
        modelName = "doubao-seed-1.6-flash",
        recommendedConfig = ModelConfig(
            maxTokens = 4096L,
            temperature = 0.7,
        ),
    ),

    /** Doubao Seed 1.6 Thinking——1.6 思考模型，深度推理。 */
    SEED_1_6_THINKING(
        modelName = "doubao-seed-1.6-thinking",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Doubao Seed 1.6 Vision——1.6 视觉模型，支持文本输入。 */
    SEED_1_6_VISION(
        modelName = "doubao-seed-1.6-vision",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),
}
