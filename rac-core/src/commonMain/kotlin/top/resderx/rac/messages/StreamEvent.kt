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

/**
 * 统一语义化流式事件。
 *
 * - 作用：将 Completions/Anthropic/Responses 三种 API 的原始流式事件聚合为统一的事件序列，
 *   屏蔽底层协议差异，提供增量(delta)与累积值(accumulated)双视角
 * - 必要性：原始流式事件直接映射 JSON 结构，存在工具调用参数碎片化、字段大量 nullable、
 *   三种 API 格式互不兼容等问题；本接口统一抽象，使用户切换 API 无需改处理逻辑
 * - 设计思路：密封接口 + 四种事件类型，覆盖文本/推理/工具调用/结束四种语义；
 *   每个增量事件都带 accumulated 字段，用户无需自行维护累积状态；
 *   Done 事件返回完整 AIMessage，与非流式 chat() 返回类型一致
 * - 实现方式：由 StreamAggregator 将原始事件逐个转换产出
 * - 事件序列示例（含工具调用）：
 *   1. TextDelta("好的") → 模型开始回答
 *   2. ToolCallDelta(index=0, id="call_1", name="get_weather", argumentsDelta="", argumentsAccumulated="") → 工具调用开始
 *   3. ToolCallDelta(index=0, id="", name="", argumentsDelta='{"ci', argumentsAccumulated='{"ci') → 参数片段
 *   4. ToolCallDelta(index=0, id="", name="", argumentsDelta='ty":"BJ"}', argumentsAccumulated='{"city":"BJ"}') → 参数片段
 *   5. Done(message=AIMessage(content="好的", toolCalls=[ToolCall(...)])) → 流结束
 * - 时间复杂度：每个事件处理 O(1)
 * - 空间复杂度：O(累积的 content + reasoningContent + toolCalls 大小)
 */
sealed interface StreamEvent {

    /**
     * 文本输出增量。
     *
     * - 作用：模型生成的正文片段（非推理部分）
     * - 触发：Completions delta.content / Anthropic text_delta / Responses output_text.delta
     *
     * @property delta 本次新增的文本片段
     * @property accumulated 到目前为止累积的完整正文文本
     */
    data class TextDelta(
        val delta: String,
        val accumulated: String,
    ) : StreamEvent

    /**
     * 推理输出增量。
     *
     * - 作用：模型的思考过程片段（DeepSeek reasoning_content / Anthropic thinking）
     * - 触发：Completions delta.reasoningContent / Anthropic thinking_delta
     * - 边缘：非推理模型不产生此事件
     *
     * @property delta 本次新增的推理文本片段
     * @property accumulated 到目前为止累积的完整推理文本
     */
    data class ReasoningDelta(
        val delta: String,
        val accumulated: String,
    ) : StreamEvent

    /**
     * 工具调用增量（已聚合，不再碎片化）。
     *
     * - 作用：模型发起的工具调用片段，自动聚合 arguments 碎片
     * - 触发：Completions delta.toolCalls / Anthropic tool_use / Responses function_call
     * - 设计：首个片段含 id 和 name（argumentsDelta 可能为空），后续片段仅含 argumentsDelta
     * - 边缘：id 和 name 在首次出现时非空，后续为空字符串；argumentsAccumulated 是完整参数
     *
     * @property index 工具调用索引（同一响应中可能有多个工具调用）
     * @property id 工具调用 ID，仅首次出现时非空，后续为空字符串
     * @property name 工具名称，仅首次出现时非空，后续为空字符串
     * @property argumentsDelta 本次新增的参数 JSON 片段
     * @property argumentsAccumulated 到目前为止累积的完整参数 JSON 字符串
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String,
        val name: String,
        val argumentsDelta: String,
        val argumentsAccumulated: String,
    ) : StreamEvent

    /**
     * 流结束事件——自包含整条消息的全部信息。
     *
     * - 作用：标识流式响应结束，直接携带整条 AI 消息的所有字段（正文/推理/工具调用/用量/结束原因）
     * - 必要性：用户只需收集本事件即可获得完整结果，无需自行拼接增量，也不必通过嵌套对象访问
     * - 设计思路：将 [AIMessage] 的所有字段平铺到本事件顶层，避免 `event.message.content` 这种嵌套访问；
     *   同时提供 [toAIMessage] 扩展函数，便于需要统一 [AIMessage] 类型的场景（如持久化、传递给下游）转换
     * - 字段语义：与 [AIMessage] 完全对齐，content 为空字符串表示纯工具调用，reasoningContent 为 null 表示非推理模型
     *
     * @property content 模型返回的正文文本（累积完整版），纯工具调用时为空字符串
     * @property reasoningContent 推理过程完整文本，仅推理模型返回，非推理模型为 null
     * @property toolCalls 模型发起的完整工具调用列表（已聚合所有碎片），默认空
     * @property usage token 用量统计，部分供应商/流式末尾才返回，可能为 null
     * @property finishReason 生成结束原因
     * @property rawResponse 原始响应字符串，流式场景通常为 null
     */
    data class Done(
        val content: String,
        val reasoningContent: String?,
        val toolCalls: List<ToolCall>,
        val usage: Usage?,
        val finishReason: FinishReason,
        val rawResponse: String? = null,
    ) : StreamEvent

    /**
     * 将 [Done] 事件转换为统一的 [AIMessage]。
     *
     * - 作用：当需要将流式结束事件作为非流式 [AIMessage] 传递给下游（如 chatWithTools 循环、持久化）时使用
     * - 必要性：[Done] 平铺字段后不再是 [AIMessage] 子类型，需显式转换
     */
    fun Done.toAIMessage(): AIMessage = AIMessage(
        content = content,
        reasoningContent = reasoningContent,
        toolCalls = toolCalls,
        usage = usage,
        finishReason = finishReason,
        rawResponse = rawResponse,
    )
}
