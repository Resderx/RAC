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
 * 月之暗面 Kimi 供应商的模型预设枚举。
 *
 * - 作用：为 Kimi 官方截至 2026-07 仍支持的主流文本模型提供预填配置
 * - 文档参考：https://platform.moonshot.cn/docs
 * - 模型列表：
 *   - [K2_5]：kimi-k2.5，最新 K2 系列
 *   - [K2_0905]：kimi-k2-0905-preview，K2 0905 预览版
 *   - [K2_0711]：kimi-k2-0711-preview，K2 0711 预览版
 *   - [K2_TURBO]：kimi-k2-turbo-preview，K2 Turbo 加速版
 *   - [K2_THINKING]：kimi-k2-thinking，K2 思考模型
 *   - [K2_THINKING_TURBO]：kimi-k2-thinking-turbo，K2 思考加速版
 *   - [V1_8K]：moonshot-v1-8k，初代 8K 上下文
 *   - [V1_32K]：moonshot-v1-32k，初代 32K 上下文
 *   - [V1_128K]：moonshot-v1-128k，初代 128K 长上下文
 *
 * @property modelName 模型标识符，传给 API 的 `model` 字段
 * @property recommendedConfig 推荐配置，按模型特性预填
 */
enum class KimiModel(
    override val modelName: String,
    override val recommendedConfig: ModelConfig,
) : ModelPreset {
    /** Kimi K2.5——最新 K2 系列，综合能力强。 */
    K2_5(
        modelName = "kimi-k2.5",
        recommendedConfig = ModelConfig(),
    ),

    /** Kimi K2 0905 Preview——K2 0905 预览版。 */
    K2_0905(
        modelName = "kimi-k2-0905-preview",
        recommendedConfig = ModelConfig(),
    ),

    /** Kimi K2 0711 Preview——K2 0711 预览版。 */
    K2_0711(
        modelName = "kimi-k2-0711-preview",
        recommendedConfig = ModelConfig(),
    ),

    /** Kimi K2 Turbo Preview——K2 Turbo 加速版，低延迟。 */
    K2_TURBO(
        modelName = "kimi-k2-turbo-preview",
        recommendedConfig = ModelConfig(),
    ),

    /** Kimi K2 Thinking——K2 思考模型，深度推理。 */
    K2_THINKING(
        modelName = "kimi-k2-thinking",
        recommendedConfig = ModelConfig(
            reasoningEffort = "high",
            enableThinking = true,
        ),
    ),

    /** Kimi K2 Thinking Turbo——K2 思考加速版，平衡推理与速度。 */
    K2_THINKING_TURBO(
        modelName = "kimi-k2-thinking-turbo",
        recommendedConfig = ModelConfig(
            reasoningEffort = "medium",
            enableThinking = true,
        ),
    ),

    /** Moonshot V1 8K——初代模型，8K 上下文窗口。 */
    V1_8K(
        modelName = "moonshot-v1-8k",
        recommendedConfig = ModelConfig(),
    ),

    /** Moonshot V1 32K——初代模型，32K 上下文窗口。 */
    V1_32K(
        modelName = "moonshot-v1-32k",
        recommendedConfig = ModelConfig(),
    ),

    /** Moonshot V1 128K——初代模型，128K 超长上下文窗口。 */
    V1_128K(
        modelName = "moonshot-v1-128k",
        recommendedConfig = ModelConfig(),
    ),
}
