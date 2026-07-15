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

import top.resderx.rac.providers.ModelConfig

/**
 * Google Gemini 供应商的模型预设枚举。
 *
 * - 作用：为 Google Gemini 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://ai.google.dev/gemini-api/docs/models
 * - 模型列表：
 *   - [PRO_3]：gemini-3-pro，旗舰模型
 *   - [FLASH_3]：gemini-3-flash，轻量快速模型
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class GeminiModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** Gemini 3 Pro——Google 旗舰模型，多模态能力强，适合复杂推理。 */
    PRO_3(
        modelName = "gemini-3-pro",
        recommendedConfig = ModelConfig(
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Gemini 3 Flash——轻量快速模型，低延迟，适合日常任务。 */
    FLASH_3(
        modelName = "gemini-3-flash",
        recommendedConfig = ModelConfig(),
    ),
}
