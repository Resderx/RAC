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
 * DeepSeek 供应商的模型预设枚举。
 *
 * - 作用：为 DeepSeek 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://api-docs.deepseek.com/
 * - 模型列表：
 *   - [V4_PRO]：deepseek-v4-pro，推理旗舰模型，高 maxTokens + 高推理强度
 *   - [V4_FLASH]：deepseek-v4-flash，轻量推理模型，中等推理强度（默认推荐）
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class DeepSeekModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** DeepSeek V4 Pro——推理旗舰，适合复杂数学/代码/逻辑任务。 */
    V4_PRO(
        modelName = "deepseek-v4-pro",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** DeepSeek V4 Flash——轻量推理模型，性价比高，默认推荐。 */
    V4_FLASH(
        modelName = "deepseek-v4-flash",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.0,
            reasoningEffort = "medium",
        ),
    ),
}
