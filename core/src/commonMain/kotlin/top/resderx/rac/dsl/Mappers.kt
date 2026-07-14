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

package top.resderx.rac.dsl

import top.resderx.rac.api.anthropic.AnthropicResponse
import top.resderx.rac.api.anthropic.ContentBlock
import top.resderx.rac.api.completions.CompletionsResponse
import top.resderx.rac.api.responses.OutputItem
import top.resderx.rac.api.responses.ResponsesResponse
import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.FinishReason
import top.resderx.rac.messages.ToolCall
import top.resderx.rac.messages.Usage

/**
 * 将 CompletionsResponse 映射为统一的 AIMessage。
 *
 * - 作用：把 OpenAI Chat Completions 风格响应转换为跨供应商统一的 AIMessage
 * - 必要性：RAC 顶层 chat { } 返回 AIMessage，需在此完成协议层到统一层的映射
 * - 设计思路：取 choices 首项的 message 作为正文；toolCalls 从 ToolCallResponse 映射；
 *   finishReason 经 toFinishReason() 归一化；rawResponse 置 null（调用方按需补充）
 * - 实现方式：扩展函数，字段逐一映射，空安全处理 content 为 null 的纯工具调用场景
 * - 可能的问题：choices 为空时 content 为空字符串，调用方需判断 finishReason 区分异常
 * - 边缘情况：content 为 null（纯工具调用）→ 空字符串；toolCalls 为 null → 空列表
 * - 优点：映射逻辑集中管理，RAC 类保持简洁
 * - 算法/数据结构：线性遍历 choices 与 toolCalls
 * - 时间复杂度：O(m)，m 为 toolCalls 数量
 * - 空间复杂度：O(m)，m 为 toolCalls 数量
 */
fun CompletionsResponse.toAIMessage(): AIMessage {
    val choice = choices.firstOrNull()
    val message = choice?.message
    val toolCalls = message?.toolCalls
        ?.map { ToolCall(id = it.id ?: "", name = it.function?.name ?: "", arguments = it.function?.arguments ?: "") }
        ?: emptyList()
    return AIMessage(
        content = message?.content ?: "",
        reasoningContent = message?.reasoningContent,
        toolCalls = toolCalls,
        usage = usage,
        finishReason = choice?.finishReason.toFinishReason(),
        rawResponse = null,
    )
}

/**
 * 将 ResponsesResponse 映射为统一的 AIMessage。
 *
 * - 作用：把 OpenAI Responses API 响应转换为跨供应商统一的 AIMessage
 * - 必要性：RAC 顶层 respond { } 返回 AIMessage，需在此完成协议层到统一层的映射
 * - 设计思路：output 列表中 MessageOutput 的 content 拼接为正文文本；
 *   FunctionCallOutput 映射为 ToolCall；finishReason 固定为 STOP（Responses API 无显式结束原因）
 * - 实现方式：扩展函数，filterIsInstance 过滤输出项类型，flatMap 拼接文本
 * - 可能的问题：output 仅含 reasoning 项时 content 为空字符串
 * - 边缘情况：output 为空 → content 空字符串、toolCalls 空列表；FunctionCallOutput.callId 为 null 时回退到 id
 * - 优点：映射逻辑集中管理，统一处理 Responses API 的多输出项结构
 * - 算法/数据结构：线性遍历 output 列表
 * - 时间复杂度：O(n)，n 为 output 项数量
 * - 空间复杂度：O(n)，n 为 output 项数量
 */
fun ResponsesResponse.toAIMessage(): AIMessage {
    val textContent = output
        .filterIsInstance<OutputItem.MessageOutput>()
        .flatMap { it.content }
        .mapNotNull { it.text }
        .joinToString("")
    val toolCalls = output
        .filterIsInstance<OutputItem.FunctionCallOutput>()
        .map { ToolCall(id = it.callId ?: it.id ?: "", name = it.name, arguments = it.arguments) }
    return AIMessage(
        content = textContent,
        toolCalls = toolCalls,
        usage = usage,
        finishReason = FinishReason.STOP,
        rawResponse = null,
    )
}

/**
 * 将 AnthropicResponse 映射为统一的 AIMessage。
 *
 * - 作用：把 Anthropic Messages API 响应转换为跨供应商统一的 AIMessage
 * - 必要性：Anthropic 协议与 OpenAI 不同（content blocks 结构），需独立映射
 * - 设计思路：content 列表中 Text 块拼接为正文，ToolUse 块映射为 ToolCall；
 *   AnthropicUsage（input/output tokens）映射为统一 Usage；stopReason 经 toFinishReason() 归一化
 * - 实现方式：扩展函数，filterIsInstance 过滤 ContentBlock 子类型
 * - 可能的问题：Anthropic 的 stopReason 值（end_turn/max_tokens/tool_use）需经 toFinishReason 兼容映射
 * - 边缘情况：content 为空 → 正文空字符串；usage 为 null → usage 字段为 null
 * - 优点：映射逻辑集中管理，统一 Anthropic 的 content blocks 结构
 * - 算法/数据结构：线性遍历 content 列表
 * - 时间复杂度：O(k)，k 为 content 块数量
 * - 空间复杂度：O(k)，k 为 content 块数量
 */
fun AnthropicResponse.toAIMessage(): AIMessage {
    val textContent = content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("") { it.text }
    val toolCalls = content
        .filterIsInstance<ContentBlock.ToolUse>()
        .map { ToolCall(id = it.id, name = it.name, arguments = it.input) }
    val mappedUsage = usage?.let {
        Usage(
            promptTokens = it.inputTokens,
            completionTokens = it.outputTokens,
            totalTokens = it.inputTokens + it.outputTokens,
        )
    }
    return AIMessage(
        content = textContent,
        toolCalls = toolCalls,
        usage = mappedUsage,
        finishReason = stopReason.toFinishReason(),
        rawResponse = null,
    )
}

/**
 * 将供应商返回的 finishReason 字符串映射为统一的 FinishReason 枚举。
 *
 * - 作用：归一化各供应商的结束原因字符串为编译期安全的 FinishReason 枚举
 * - 必要性：OpenAI 用 "stop"/"length"/"tool_calls"/"content_filter"，Anthropic 用 "end_turn"/"max_tokens"/"tool_use"，
 *   需统一映射以便 Agent 流程据此决策
 * - 设计思路：when 表达式覆盖已知值，null 与未识别值统一映射为 UNKNOWN；大小写不敏感
 * - 实现方式：String? 扩展函数，lowercase() 后匹配
 * - 可能的问题：供应商新增结束原因时会落入 UNKNOWN，调用方需容错
 * - 边缘情况：null → UNKNOWN；空字符串 → UNKNOWN；未知值 → UNKNOWN
 * - 优点：集中管理映射规则，新增供应商只需扩展 when 分支
 * - 算法/数据结构：when 表达式，无数据结构
 * - 时间复杂度：O(1)
 * - 空间复杂度：O(1)
 */
fun String?.toFinishReason(): FinishReason = when (this?.lowercase()) {
    "stop", "end_turn", "stop_sequence" -> FinishReason.STOP
    "length", "max_tokens" -> FinishReason.LENGTH
    "tool_calls", "tool_use" -> FinishReason.TOOL_CALLS
    "content_filter" -> FinishReason.CONTENT_FILTER
    "unknown" -> FinishReason.UNKNOWN
    else -> FinishReason.UNKNOWN
}
