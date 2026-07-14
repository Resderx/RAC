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

import com.resderx.rac.api.completions.toCompletionsTool
import com.resderx.rac.api.responses.ResponsesRequest
import com.resderx.rac.messages.ToolDefinition
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ModelProvider

/**
 * Respond 请求的 DSL 构建器，用于 Responses API（OpenAI 新协议）。
 *
 * - 作用：在 `respond { }` / `respondStream { }` 块中构建 Responses API 请求，
 *   包含输入文本、系统指令、工具定义与采样参数，支持运行时切换 model
 * - 必要性：Responses API 与 Completions API 字段不同（input 为字符串、instructions 为系统指令），
 *   需独立构建器
 * - 设计思路：与 [ChatRequestBuilder] 对称，支持 `model()` 切换；build() 时从 provider 的 models
 *   注册表获取 [ModelConfig] 作为默认值，builder 内显式设置的字段覆盖默认值；
 *   标注 @RacDslMarker 防止作用域污染
 * - 边缘情况：未设置 model 时回退到 provider.defaultModel；tools 为空列表时产出 null
 *
 * @property input 用户输入文本，对应 Responses API 的 input 字段
 * @property instructions 系统指令，对应 Responses API 的 instructions 字段，null 表示不设置
 * @property temperature 采样温度，覆盖 ModelConfig 默认值，null 表示沿用
 * @property maxOutputTokens 最大输出 token 数，覆盖 ModelConfig 默认值，null 表示沿用
 */
@RacDslMarker
class RespondRequestBuilder {
    /** 用户输入文本，对应 Responses API 的 input 字段。 */
    var input: String = ""

    /** 系统指令，对应 Responses API 的 instructions 字段，null 表示不设置。 */
    var instructions: String? = null

    /** 采样温度，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var temperature: Double? = null

    /** 最大输出 token 数，覆盖 ModelConfig 默认值，null 表示沿用。 */
    var maxOutputTokens: Long? = null

    /** 运行时切换的目标 model 名称，null 表示用 provider 的默认 model。 */
    internal var modelName: String? = null

    private var _tools: MutableList<ToolDefinition>? = null

    /**
     * 切换到指定 model，从 provider 的 models 注册表查找配置。
     *
     * @param name model 名称（需在 `models { }` 中注册）
     */
    fun model(name: String) {
        this.modelName = name
    }

    /**
     * 声明可用工具集，内容由结构化 [ToolsBuilder] 构建。
     *
     * @param block 在 [ToolsBuilder] 作用域内添加工具定义
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
     * @param provider 提供连接信息与 models 注册表的供应商
     * @return 构建完成的 ResponsesRequest（stream=false）
     */
    internal fun build(provider: ModelProvider): ResponsesRequest {
        val model = modelName ?: provider.defaultModel
        val config: ModelConfig? = provider.models[model]
        return ResponsesRequest(
            model = model,
            input = input,
            instructions = instructions ?: config?.systemPrompt,
            stream = false,
            tools = _tools?.takeIf { it.isNotEmpty() }?.map { it.toCompletionsTool() },
            temperature = temperature ?: config?.temperature,
            maxOutputTokens = maxOutputTokens ?: config?.maxTokens,
        )
    }
}
