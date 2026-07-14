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
 * 模型预设接口——按供应商区分的枚举类（如 [DeepSeekModel]、[OpenAIModel]）统一实现本接口。
 *
 * - 作用：为每个主流模型提供预填好的 [ModelConfig]（maxTokens/temperature/reasoningEffort 等），
 *   用户通过 `model(DeepSeekModel.V4_FLASH)` 一行代码即可注册带推荐配置的模型，
 *   免去手写模板代码的繁琐，同时享受针对该模型特性的调优
 * - 必要性：实际使用中，每个模型的推荐 maxTokens、是否支持推理、合适温度等参数各不相同；
 *   逐个模型手动配置既繁琐又易出错；预设枚举将"模型特性 → 推荐配置"的映射内置于库中
 * - 设计思路：
 *   1. 每个供应商一个独立枚举类（如 `DeepSeekModel`、`OpenAIModel`），而非统一一个 `ModelPreset` 枚举——
 *      更符合现有 provider DSL 的按供应商组织结构，IDE 自动补全更友好
 *   2. 枚举项含 `modelName`（实际 API 模型标识）与 `recommendedConfig`（推荐 [ModelConfig]）
 *   3. `recommendedConfig` 按模型特性预填合理值——如推理模型设 reasoningEffort="high" + enableThinking=true，
 *      flash/轻量模型设较低 maxTokens
 *   4. 用户可在 `model(preset) { ... }` 的 block 内覆盖预设值
 * - 实现方式：interface + 各供应商枚举类 implement；枚举项的 recommendedConfig 在构造时初始化
 * - 边缘情况：所有枚举项的 recommendedConfig.maxTokens 均非 null（保证至少有 maxTokens 默认值）
 *
 * @property modelName 模型标识符，与 API 请求体 `model` 字段对应（如 "deepseek-v4-flash"）
 * @property recommendedConfig 推荐的模型配置，含针对该模型特性调优过的参数
 */
interface ModelPreset {
    /** 模型标识符，与 API 请求体 `model` 字段对应（如 "deepseek-v4-flash"）。 */
    val modelName: String

    /** 推荐的模型配置，含针对该模型特性调优过的参数。 */
    val recommendedConfig: ModelConfig
}
