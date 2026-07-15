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

package top.resderx.rac.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次模型调用的 token 用量统计。
 *
 * - 作用：记录 prompt/completion/total/reasoning 四类 token 计数
 * - 必要性：跨供应商统一用量字段，便于成本核算与限流；不同供应商返回字段名各异，统一后 DSL 可一致消费
 * - 设计思路：所有数值用 Long 避免溢出；reasoningTokens 可空（仅推理模型如 o1/DeepSeek-R1 返回），
 *   其余字段默认 0 便于部分填充
 * - 实现方式：`@Serializable` 不可变数据类，提供默认值支持部分反序列化
 * - 边缘情况：部分供应商不返回 totalTokens，由 API 客户端按 prompt+completion 计算；流式场景下用量
 *   仅在最后一个 chunk 返回，由客户端聚合
 *
 * @property promptTokens 输入 prompt 的 token 数，默认 0
 * @property completionTokens 模型输出的 token 数，默认 0
 * @property totalTokens 总 token 数，默认 0（部分供应商需客户端计算）
 * @property reasoningTokens 推理 token 数，仅推理模型返回，可空
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)
