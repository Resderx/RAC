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

package top.resderx.rac.providers

/**
 * 单个模型的专属配置，在 `models { model("xxx") { ... } }` 块内声明。
 *
 * - 作用：承载与具体模型绑定的参数（maxTokens/temperature/topP/systemPrompt/reasoningEffort/
 *   stop/seed/enableThinking），与供应商连接配置（[ProviderConfig]）分离，
 *   使一个 provider 下可注册多个不同配置的模型
 * - 必要性：实际使用中常需为同一供应商的不同模型设置不同参数——如推理模型设高 maxTokens +
 *   reasoningEffort=high，轻量模型设低 maxTokens；旧设计将 maxTokens 等放在 chat { } 内逐次
 *   设置，导致每次调用都需重复配置；分离后 model 注册时声明的参数作为默认值，chat { } 内可覆盖
 * - 设计思路：所有字段可空，null 表示沿用服务端默认；由 [ModelBuilder] 构造，build() 产出不可变实例
 * - 实现方式：不可变 data class，所有字段 val 且有默认值 null
 * - 边缘情况：所有字段为 null 时表示"无模型级默认覆盖"，完全沿用服务端默认
 *
 * 定制化字段说明：
 * - stop：停止序列，模型生成到任一字符串时停止
 * - seed：随机种子，用于可重现输出（部分供应商/模型不支持时静默忽略）
 * - enableThinking：思考开关，true 启用扩展思考，false 禁用；具体 API 表现：
 *   Completions 通过 reasoningEffort 间接控制（true 且未设 reasoningEffort 时自动设为 "medium"），
 *   Anthropic 通过 thinking 对象控制，Responses 不支持
 *
 * 多模态字段说明：
 * - modalities：模型支持的输入模态集合（[Modality.TEXT]/[Modality.IMAGE]/[Modality.AUDIO]），
 *   空集表示未声明（由调用方自行判断）；preset 中按模型能力预填，调用方可据此选择合适的
 *   [top.resderx.rac.messages.Content] 子类构造多模态消息
 *
 * @property maxTokens 最大生成 token 数，null 表示沿用服务端默认
 * @property temperature 采样温度，null 表示沿用服务端默认
 * @property topP nucleus sampling 参数，null 表示沿用服务端默认
 * @property systemPrompt 模型专属系统提示词，null 表示不设置（调用方可在 chat { } 内用 system() 覆盖）
 * @property reasoningEffort 推理强度（如 "low"/"medium"/"high"），仅推理模型支持，null 表示不设置
 * @property stop 停止序列，模型生成到任一字符串时立即停止，null 表示不设置
 * @property seed 随机种子，用于确定性输出，null 表示沿用服务端随机
 * @property enableThinking 思考开关，true 启用扩展思考，false 禁用，null 表示沿用默认行为
 * @property modalities 模型支持的输入模态集合，空集表示未声明（由调用方自行判断）
 */
data class ModelConfig(
    val maxTokens: Long? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val systemPrompt: String? = null,
    val reasoningEffort: String? = null,
    val stop: List<String>? = null,
    val seed: Long? = null,
    val enableThinking: Boolean? = null,
    val modalities: Set<Modality> = emptySet(),
)
