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

import com.resderx.rac.api.anthropic.AnthropicStreamEvent
import com.resderx.rac.api.anthropic.ContentBlock
import com.resderx.rac.api.anthropic.Delta as AnthropicDelta
import com.resderx.rac.api.completions.CompletionsStreamChunk
import com.resderx.rac.api.responses.OutputItem
import com.resderx.rac.api.responses.ResponsesStreamEvent
import com.resderx.rac.dsl.toFinishReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 流式事件聚合器。
 *
 * - 作用：将 Completions/Anthropic/Responses 三种 API 的原始流式事件聚合为统一的 [StreamEvent] 序列
 * - 必要性：原始流式事件直接映射 JSON 结构，存在工具调用参数碎片化、三种 API 格式不兼容等问题；
 *   聚合器维护累积状态，屏蔽协议差异，输出语义化的增量+累积事件
 * - 设计思路：三个 Flow 扩展函数分别处理三种 API 的原始事件流；
 *   每个聚合器内部维护 contentBuf/reasoningBuf/toolCallMap 等累积状态；
 *   流结束时产出 Done 事件，携带完整的 AIMessage（与非流式返回一致）
 * - KMP 兼容：早期版本用 `@JvmName` 解决 JVM 泛型擦除导致的签名冲突，但 `@JvmName` 是 JVM 专属
 *   注解，commonMain 无法解析（非 JVM 平台编译失败）。现改为三个**不同名**函数，
 *   既避免 JVM 签名冲突，又保持 KMP 全平台兼容，且函数名自带 API 来源语义
 * - 实现：使用 Kotlin Flow 的 flow { collect { emit } } 模式，状态在 flow 块内局部维护
 * - 时间复杂度：O(n)，n 为原始事件数量
 * - 空间复杂度：O(累积的 content + reasoning + toolCalls 大小)
 */

/**
 * 工具调用累积器（内部状态）。
 *
 * - 作用：在流式聚合过程中累积单个工具调用的 id/name/arguments
 * - 必要性：流式响应中工具调用的 id/name 和 arguments 分多个 chunk 到达，需累积
 * - 设计：可变状态类，id 和 name 首次设置后不再覆盖，arguments 追加拼接
 */
private class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val argumentsBuilder: StringBuilder = StringBuilder(),
)

/**
 * 将 Completions API 流式 chunk 聚合为统一 [StreamEvent]。
 *
 * - 作用：处理 [CompletionsStreamChunk] 序列，产出语义化的 [StreamEvent]
 * - 处理逻辑：
 *   - delta.content → [StreamEvent.TextDelta]
 *   - delta.reasoningContent → [StreamEvent.ReasoningDelta]
 *   - delta.toolCalls → [StreamEvent.ToolCallDelta]（按 index 聚合 arguments 片段）
 *   - 流结束 → [StreamEvent.Done]（携带完整 AIMessage）
 * - 边缘：工具调用首帧含 id 和 name、arguments 为空；后续帧仅含 arguments 片段
 */
fun Flow<CompletionsStreamChunk>.toCompletionsStreamEvents(): Flow<StreamEvent> = flow {
    // 累积状态
    val contentBuf = StringBuilder()
    val reasoningBuf = StringBuilder()
    val toolCallMap = mutableMapOf<Int, ToolCallAccumulator>()
    var finishReason: FinishReason = FinishReason.UNKNOWN
    var usage: Usage? = null

    collect { chunk ->
        // 累积 usage（通常仅最后一个 chunk 含 usage）
        chunk.usage?.let { usage = it }

        // 处理每个 choice（通常只有一个）
        chunk.choices.forEach { choice ->
            val delta = choice.delta

            // 文本内容增量
            delta.content?.takeIf { it.isNotEmpty() }?.let { text ->
                contentBuf.append(text)
                emit(StreamEvent.TextDelta(delta = text, accumulated = contentBuf.toString()))
            }

            // 推理内容增量
            delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { text ->
                reasoningBuf.append(text)
                emit(StreamEvent.ReasoningDelta(delta = text, accumulated = reasoningBuf.toString()))
            }

            // 工具调用增量（按 index 聚合）
            delta.toolCalls?.forEach { tc ->
                val idx = tc.index
                val acc = toolCallMap.getOrPut(idx) { ToolCallAccumulator() }

                // 首帧含 id 和 name，后续帧不含
                tc.id?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                tc.function?.name?.takeIf { it.isNotEmpty() }?.let { acc.name = it }

                // arguments 片段累积
                val argsDelta = tc.function?.arguments ?: ""
                if (argsDelta.isNotEmpty()) {
                    acc.argumentsBuilder.append(argsDelta)
                }

                emit(StreamEvent.ToolCallDelta(
                    index = idx,
                    id = tc.id ?: "",
                    name = tc.function?.name ?: "",
                    argumentsDelta = argsDelta,
                    argumentsAccumulated = acc.argumentsBuilder.toString(),
                ))
            }

            // 结束原因（通常仅最后一个 chunk 含 finishReason）
            choice.finishReason?.let { finishReason = it.toFinishReason() }
        }
    }

    // 产出 Done 事件，直接平铺所有字段（无需再构造 AIMessage 中转）
    // 按 index 升序排序工具调用（toSortedMap 是 JVM 专属，改用 toList().sortedBy 保持 KMP 兼容）
    val toolCalls = toolCallMap.toList().sortedBy { it.first }.map { (_, acc) ->
        ToolCall(id = acc.id, name = acc.name, arguments = acc.argumentsBuilder.toString())
    }
    emit(StreamEvent.Done(
        content = contentBuf.toString(),
        reasoningContent = reasoningBuf.toString().ifEmpty { null },
        toolCalls = toolCalls,
        usage = usage,
        finishReason = finishReason,
    ))
}

/**
 * 将 Anthropic API 流式事件聚合为统一 [StreamEvent]。
 *
 * - 作用：处理 [AnthropicStreamEvent] 序列，产出语义化的 [StreamEvent]
 * - 处理逻辑：
 *   - ContentBlockStart(ToolUse) → [StreamEvent.ToolCallDelta]（工具调用开始，含 id 和 name）
 *   - ContentBlockDelta(TextDelta) → [StreamEvent.TextDelta]
 *   - ContentBlockDelta(InputJsonDelta) → [StreamEvent.ToolCallDelta]（arguments 片段）
 *   - MessageDelta → 累积 stopReason 和 usage
 *   - MessageStop → [StreamEvent.Done]
 * - 边缘：Anthropic 的 content block index 与 tool call index 不一致（text 和 tool_use 混合），
 *   需维护 blockIndex→toolCallIndex 映射
 */
fun Flow<AnthropicStreamEvent>.toAnthropicStreamEvents(): Flow<StreamEvent> = flow {
    val contentBuf = StringBuilder()
    val toolCallMap = mutableMapOf<Int, ToolCallAccumulator>()  // key: 序号化的 tool call index
    val blockIndexToToolCallIndex = mutableMapOf<Int, Int>()    // Anthropic block index → 序号化的 tool call index
    var toolCallCounter = 0
    var stopReason: String? = null
    var inputTokens: Long = 0
    var outputTokens: Long = 0

    collect { event ->
        when (event) {
            is AnthropicStreamEvent.MessageStart -> {
                // 累积 input tokens（在 message_start 中返回）
                event.message?.usage?.let {
                    inputTokens = it.inputTokens
                    outputTokens = it.outputTokens
                }
            }

            is AnthropicStreamEvent.ContentBlockStart -> {
                when (val block = event.contentBlock) {
                    is ContentBlock.ToolUse -> {
                        // 工具调用开始：分配序号化的 tool call index
                        val tcIndex = toolCallCounter++
                        blockIndexToToolCallIndex[event.index] = tcIndex
                        val acc = ToolCallAccumulator(id = block.id, name = block.name)
                        toolCallMap[tcIndex] = acc
                        // 若有初始 input（非流式回退场景），累积到 argumentsBuilder
                        if (block.input.isNotEmpty()) {
                            acc.argumentsBuilder.append(block.input)
                        }
                        emit(StreamEvent.ToolCallDelta(
                            index = tcIndex,
                            id = block.id,
                            name = block.name,
                            argumentsDelta = block.input,
                            argumentsAccumulated = acc.argumentsBuilder.toString(),
                        ))
                    }
                    is ContentBlock.Text -> {
                        // 文本块开始：若有初始文本（非流式回退），累积并发出
                        if (block.text.isNotEmpty()) {
                            contentBuf.append(block.text)
                            emit(StreamEvent.TextDelta(delta = block.text, accumulated = contentBuf.toString()))
                        }
                    }
                    null -> { /* 忽略空块 */ }
                }
            }

            is AnthropicStreamEvent.ContentBlockDelta -> {
                when (val d = event.delta) {
                    is AnthropicDelta.TextDelta -> {
                        d.text?.takeIf { it.isNotEmpty() }?.let { text ->
                            contentBuf.append(text)
                            emit(StreamEvent.TextDelta(delta = text, accumulated = contentBuf.toString()))
                        }
                    }
                    is AnthropicDelta.InputJsonDelta -> {
                        // 工具调用参数片段
                        val tcIndex = blockIndexToToolCallIndex[event.index] ?: return@collect
                        val acc = toolCallMap[tcIndex] ?: return@collect
                        val partial = d.partialJson ?: ""
                        if (partial.isNotEmpty()) {
                            acc.argumentsBuilder.append(partial)
                        }
                        emit(StreamEvent.ToolCallDelta(
                            index = tcIndex,
                            id = "",
                            name = "",
                            argumentsDelta = partial,
                            argumentsAccumulated = acc.argumentsBuilder.toString(),
                        ))
                    }
                    null -> { /* 忽略空 delta */ }
                }
            }

            is AnthropicStreamEvent.ContentBlockStop -> { /* 块结束，无需处理 */ }

            is AnthropicStreamEvent.MessageDelta -> {
                event.delta?.stopReason?.let { stopReason = it }
                event.usage?.let { outputTokens = it.outputTokens }
            }

            is AnthropicStreamEvent.MessageStop -> {
                val finishReason = stopReason.toFinishReason()
                val mappedUsage = Usage(
                    promptTokens = inputTokens,
                    completionTokens = outputTokens,
                    totalTokens = inputTokens + outputTokens,
                )
                // 按 index 升序排序工具调用（KMP 兼容写法，避免 toSortedMap）
                val toolCalls = toolCallMap.toList().sortedBy { it.first }.map { (_, acc) ->
                    ToolCall(id = acc.id, name = acc.name, arguments = acc.argumentsBuilder.toString())
                }
                emit(StreamEvent.Done(
                    content = contentBuf.toString(),
                    reasoningContent = null,  // Anthropic 流式 thinking 单独处理，此处暂不累积
                    toolCalls = toolCalls,
                    usage = mappedUsage,
                    finishReason = finishReason,
                ))
            }
        }
    }
}

/**
 * 将 Responses API 流式事件聚合为统一 [StreamEvent]。
 *
 * - 作用：处理 [ResponsesStreamEvent] 序列，产出语义化的 [StreamEvent]
 * - 处理逻辑：
 *   - OutputTextDelta → [StreamEvent.TextDelta]
 *   - OutputItemAdded(FunctionCallOutput) → [StreamEvent.ToolCallDelta]（完整工具调用，无碎片化）
 *   - ResponseCompleted → [StreamEvent.Done]
 * - 边缘：Responses API 的函数调用参数不分片（完整给出），工具调用 index 由 outputIndex 推导
 */
fun Flow<ResponsesStreamEvent>.toResponsesStreamEvents(): Flow<StreamEvent> = flow {
    val contentBuf = StringBuilder()
    val toolCallList = mutableListOf<ToolCallAccumulator>()
    var usage: Usage? = null

    collect { event ->
        when (event) {
            is ResponsesStreamEvent.OutputTextDelta -> {
                if (event.delta.isNotEmpty()) {
                    contentBuf.append(event.delta)
                    emit(StreamEvent.TextDelta(delta = event.delta, accumulated = contentBuf.toString()))
                }
            }

            is ResponsesStreamEvent.OutputItemAdded -> {
                // 函数调用输出项（Responses API 一次性给出完整工具调用，无碎片化）
                val item = event.item
                if (item is OutputItem.FunctionCallOutput) {
                    val acc = ToolCallAccumulator(
                        id = item.callId ?: item.id ?: "",
                        name = item.name,
                    )
                    acc.argumentsBuilder.append(item.arguments)
                    val tcIndex = toolCallList.size
                    toolCallList.add(acc)
                    emit(StreamEvent.ToolCallDelta(
                        index = tcIndex,
                        id = acc.id,
                        name = acc.name,
                        argumentsDelta = item.arguments,
                        argumentsAccumulated = acc.argumentsBuilder.toString(),
                    ))
                }
            }

            is ResponsesStreamEvent.ResponseCompleted -> {
                usage = event.response?.usage
                val finishReason = FinishReason.STOP
                val toolCalls = toolCallList.map { acc ->
                    ToolCall(id = acc.id, name = acc.name, arguments = acc.argumentsBuilder.toString())
                }
                emit(StreamEvent.Done(
                    content = contentBuf.toString(),
                    reasoningContent = null,  // Responses API 流式不单独返回 reasoning
                    toolCalls = toolCalls,
                    usage = usage,
                    finishReason = finishReason,
                ))
            }

            // 忽略生命周期事件
            is ResponsesStreamEvent.ResponseCreated,
            is ResponsesStreamEvent.ResponseInProgress,
            is ResponsesStreamEvent.OutputItemDone,
            is ResponsesStreamEvent.ContentPartAdded,
            is ResponsesStreamEvent.ContentPartDone,
            is ResponsesStreamEvent.OutputTextDone,
            is ResponsesStreamEvent.ErrorEvent -> { }
        }
    }

    // 若未收到 ResponseCompleted，仍产出 Done（防御性处理）
    // （正常流程不会走到这里，ResponseCompleted 是必须的结束事件）
}
