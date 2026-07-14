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

package com.resderx.rac.messages

import kotlinx.serialization.Serializable

/**
 * 统一的模型响应载体。
 *
 * - 作用：封装一次模型调用的完整结果（正文/推理/工具调用/用量/结束原因/原始响应）
 * - 必要性：跨供应商统一返回类型，DSL 顶层 `ai.chat{}` 返回此类型
 * - 设计思路：不可变数据类，所有可选字段用可空类型 + 默认值，便于部分填充
 * - 实现方式：`@Serializable` 支持持久化，rawResponse 用于调试与自定义解析
 * - 可能的问题：rawResponse 可能很大，长上下文场景注意内存
 * - 边缘情况：content 可能为空字符串（纯工具调用时），toolCalls 默认空列表
 * - 优点：字段语义清晰，与 OpenAI/Anthropic/DeepSeek 字段对齐
 * - 数据结构：扁平数据类
 * - 时间复杂度：构造 O(1)
 * - 空间复杂度：O(content + reasoningContent + toolCalls 大小)
 *
 * @property content 模型返回的正文文本，纯工具调用时可能为空字符串
 * @property reasoningContent 推理过程文本，仅推理模型返回，可空
 * @property toolCalls 模型发起的工具调用列表，默认空
 * @property usage token 用量统计，可空（部分供应商/流式末尾才返回）
 * @property finishReason 生成结束原因，默认 UNKNOWN
 * @property rawResponse 原始响应字符串，用于调试与自定义解析，可空
 */
@Serializable
data class AIMessage(
    val content: String,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null,
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val rawResponse: String? = null,
)
