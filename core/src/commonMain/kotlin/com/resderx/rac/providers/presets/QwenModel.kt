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
 * 阿里通义千问（Qwen）供应商的模型预设枚举。
 *
 * - 作用：为 Qwen 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://help.aliyun.com/zh/model-studio/
 * - 模型列表：
 *   - [MAX_3_7]：qwen3.7-max-preview，最新 Max 旗舰
 *   - [PLUS_3_7]：qwen3.7-plus-preview，最新 Plus
 *   - [MAX_3_6]：qwen3.6-max-preview，上一代 Max
 *   - [PLUS_3_6]：qwen3.6-plus，上一代 Plus
 *   - [FLASH_3_6]：qwen3.6-flash，轻量快速
 *   - [MAX_FLASH]：qwen-max-flash，Max 系列轻量版
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class QwenModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** Qwen3.7 Max Preview——最新 Max 旗舰，最强推理能力。 */
    MAX_3_7(
        modelName = "qwen3.7-max-preview",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Qwen3.7 Plus Preview——最新 Plus，平衡性能与成本。 */
    PLUS_3_7(
        modelName = "qwen3.7-plus-preview",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),

    /** Qwen3.6 Max Preview——上一代 Max 旗舰。 */
    MAX_3_6(
        modelName = "qwen3.6-max-preview",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Qwen3.6 Plus——上一代 Plus，性价比之选。 */
    PLUS_3_6(
        modelName = "qwen3.6-plus",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),

    /** Qwen3.6 Flash——轻量快速，低延迟。 */
    FLASH_3_6(
        modelName = "qwen3.6-flash",
        recommendedConfig = ModelConfig(
            maxTokens = 4096L,
            temperature = 0.7,
        ),
    ),

    /** Qwen Max Flash——Max 系列轻量版，速度更快。 */
    MAX_FLASH(
        modelName = "qwen-max-flash",
        recommendedConfig = ModelConfig(
            maxTokens = 4096L,
            temperature = 0.7,
        ),
    ),
}
