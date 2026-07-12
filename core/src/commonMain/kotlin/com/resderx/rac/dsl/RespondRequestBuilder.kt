package com.resderx.rac.dsl

import com.resderx.rac.api.responses.ResponsesRequest
import com.resderx.rac.messages.ToolDefinition
import com.resderx.rac.providers.ModelProvider

/**
 * Respond 请求的 DSL 构建器，用于 Responses API（OpenAI 新协议）。
 *
 * - 作用：在 respond { } / respondStream { } 块中构建 Responses API 请求，
 *   包含输入文本、系统指令、工具定义与采样参数，最终产出不可变的 ResponsesRequest
 * - 必要性：Responses API 与 Completions API 字段不同（input 为字符串、instructions 为系统指令），
 *   需独立构建器；提供 DSL 风格 API 替代直接构造
 * - 设计思路：比 ChatRequestBuilder 更简单，不维护消息列表，仅持有 input 字符串与可选字段；
 *   标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 实现方式：类持有可变 var 字段与可变 List<ToolDefinition>，build() 时产出不可变 ResponsesRequest
 * - 可能的问题：构建器实例非线程安全；input 为空字符串时由服务端报错
 * - 边缘情况：未设置 model 时 build() 回退到 provider.defaultModel；tools 为空列表时产出 null
 * - 优点：DSL 风格简洁，与 ChatRequestBuilder 对称，降低学习成本
 * - 数据结构：扁平可变字段 + MutableList<ToolDefinition>
 * - 时间复杂度：build() 为 O(m)，m 为工具数
 * - 空间复杂度：O(m)，m 为工具数
 *
 * @property input 用户输入文本，对应 Responses API 的 input 字段
 * @property instructions 系统指令，对应 Responses API 的 instructions 字段，null 表示不设置
 * @property model 模型名，覆盖 provider 默认模型，null 表示沿用
 * @property temperature 采样温度，null 表示沿用服务端默认
 * @property maxOutputTokens 最大输出 token 数，null 表示沿用服务端默认
 */
@RacDslMarker
class RespondRequestBuilder {
    /** 用户输入文本，对应 Responses API 的 input 字段。 */
    var input: String = ""

    /** 系统指令，对应 Responses API 的 instructions 字段，null 表示不设置。 */
    var instructions: String? = null

    /** 模型名，覆盖 provider 默认模型，null 表示沿用。 */
    var model: String? = null

    /** 采样温度，null 表示沿用服务端默认。 */
    var temperature: Double? = null

    /** 最大输出 token 数，null 表示沿用服务端默认。 */
    var maxOutputTokens: Long? = null

    private var _tools: MutableList<ToolDefinition>? = null

    /**
     * 声明可用工具集，内容由 ToolsBuilder 构建。
     *
     * @param block 在 ToolsBuilder 作用域内添加工具定义
     */
    fun tools(block: ToolsBuilder.() -> Unit) {
        val builder = ToolsBuilder().apply(block)
        val built = builder.build()
        if (_tools == null) {
            _tools = mutableListOf()
        }
        _tools!!.addAll(built)
    }

    /**
     * 构建不可变的 ResponsesRequest。
     *
     * @param provider 提供默认模型名的供应商
     * @return 构建完成的 ResponsesRequest（stream=false）
     */
    internal fun build(provider: ModelProvider): ResponsesRequest = ResponsesRequest(
        model = model ?: provider.defaultModel,
        input = input,
        instructions = instructions,
        stream = false,
        tools = _tools?.takeIf { it.isNotEmpty() },
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
    )
}
