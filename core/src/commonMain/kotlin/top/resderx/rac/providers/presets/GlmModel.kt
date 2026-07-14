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
 * 智谱 GLM 供应商的模型预设枚举。
 *
 * - 作用：为智谱 GLM 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://open.bigmodel.cn/dev/api
 * - 模型列表：
 *   - [GLM_5_2]：glm-5.2，最新旗舰
 *   - [GLM_5_1]：glm-5.1，上一代旗舰
 *   - [GLM_5]：glm-5，初代 5 系列
 *   - [GLM_4_7_FLASH]：glm-4.7-flash，轻量快速
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class GlmModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** GLM-5.2——智谱最新旗舰，强大推理能力。 */
    GLM_5_2(
        modelName = "glm-5.2",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** GLM-5.1——上一代旗舰，性能稳定。 */
    GLM_5_1(
        modelName = "glm-5.1",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),

    /** GLM-5——初代 5 系列，通用能力强。 */
    GLM_5(
        modelName = "glm-5",
        recommendedConfig = ModelConfig(
            maxTokens = 8192L,
            temperature = 0.7,
        ),
    ),

    /** GLM-4.7 Flash——轻量快速模型，低延迟。 */
    GLM_4_7_FLASH(
        modelName = "glm-4.7-flash",
        recommendedConfig = ModelConfig(
            maxTokens = 4096L,
            temperature = 0.7,
        ),
    ),
}
