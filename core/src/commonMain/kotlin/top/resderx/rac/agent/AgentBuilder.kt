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

@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package top.resderx.rac.agent

import com.resderx.rac.dsl.Llm
import com.resderx.rac.dsl.RacDslMarker
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Agent 的 DSL 构建器——以声明式风格配置系统提示词、工具集与轮数上限。
 *
 * - 作用：在 `agent(llm) { }` 块内逐步配置 [Agent] 的全部参数，最终构建不可变 [Agent] 实例
 * - 必要性：提供类型安全的 DSL API 替代直接构造 [Agent]，支持 `prompts()` + `tools { }` 分块声明
 * - 设计思路：
 *   1. 持有 [llm] 引用（复用其 HttpClient 与供应商配置，不创建新连接）
 *   2. 内部维护 [ToolRegistry] 累积工具注册，`_systemPrompt` 与 `_maxRounds` 为可变 var
 *   3. [tools] 方法接收 [ToolsScope] 的 inline lambda，在块内用 `tool<Args>()` 注册工具；
 *      `inline` 确保 `reified` 在嵌套块中可用，KMP 友好无需反射
 *   4. 标注 @RacDslMarker 防止嵌套 DSL 作用域污染
 * - 实现方式：类持有可变状态，每个 DSL 方法返回 Unit，[build] 收集为不可变 [Agent]
 * - 边缘情况：
 *   - 未调 `prompts()` → `_systemPrompt` 为 null，[Agent] 不自动注入 system 消息
 *   - 未注册任何 `tool` → [ToolRegistry] 为空，[Agent.run] 等价于普通 `chat`
 *   - 未调 `maxRounds()` → 默认 10 轮
 * - 优点：DSL 结构清晰，prompts 与 tools 分块声明，符合人类直觉
 *
 * @property llm 底层 LLM 调用入口
 */
@RacDslMarker
class AgentBuilder(val llm: Llm) {

    /** 内部工具注册表，累积 `tools { }` 块内注册的条目。 */
    @PublishedApi
    internal val _tools: ToolRegistry = ToolRegistry()

    /** 系统提示词，null 表示不注入。 */
    private var _systemPrompt: String? = null

    /** 最大工具调用循环轮数，默认 10。 */
    private var _maxRounds: Int = 10

    /**
     * 设置系统提示词——首次调用且 session 为空时自动注入。
     *
     * - 作用：声明 Agent 的人设与行为约束，由 [Agent.run] 在首次调用时自动补到 session 头部
     * - 命名：用 `prompts`（复数）替代 `system`，语义更直观，与底层 `SystemMessage` 解耦
     *
     * @param text 系统指令文本（如"你是一个天气助手"）
     */
    fun prompts(text: String) {
        _systemPrompt = text
    }

    /**
     * 声明工具集——在 [ToolsScope] 作用域内用 `tool<Args>()` 注册类型安全工具。
     *
     * - 作用：将工具注册逻辑收敛到独立块内，避免与 `prompts`/`maxRounds` 等配置混在一起
     * - 必要性：DSL 结构清晰，`tools { }` 块内只关心工具声明，符合人类直觉
     * - 设计：`inline` 确保 `ToolsScope.tool<Args>` 的 `reified` 在块内可用；
     *   [ToolsScope] 持有 `_tools` 引用，`tool<Args>` 注册时自动生成 JSON Schema
     * - 用法：
     *   ```
     *   tools {
     *       tool<WeatherArgs>("get_weather", "查询天气") { args ->
     *           "${args.city}: 晴"
     *       }
     *   }
     *   ```
     *
     * @param block 在 [ToolsScope] 作用域内注册工具
     */
    inline fun tools(block: ToolsScope.() -> Unit) {
        val scope = ToolsScope(_tools)
        scope.block()
    }

    /**
     * 设置最大工具调用循环轮数。
     *
     * @param n 最大轮数（不含首轮模型调用），需 > 0
     */
    fun maxRounds(n: Int) {
        require(n > 0) { "maxRounds must be positive, but was $n" }
        _maxRounds = n
    }

    /**
     * 构建不可变的 [Agent] 实例。
     *
     * - 作用：收集 builder 累积的全部配置，产出可复用的 [Agent]
     * - 可见性：`@PublishedApi internal`，仅供顶层 [agent] inline DSL 函数调用
     */
    @PublishedApi
    internal fun build(): Agent = Agent(
        llm = llm,
        systemPrompt = _systemPrompt,
        tools = _tools,
        maxRounds = _maxRounds,
    )
}

/**
 * 工具声明作用域——在 `tools { }` 块内提供 `tool<Args>()` 注册 API。
 *
 * - 作用：作为 `agent { tools { } }` 的块内 receiver，隔离工具注册逻辑，防止作用域污染
 * - 必要性：`tool<Args>` 需 `inline reified`，必须定义在类内作为成员方法；
 *   独立作用域避免 `tool` 与 `AgentBuilder` 的其他方法混在一起
 * - 设计：持有 [registry] 引用（指向 [AgentBuilder._tools]），`tool<Args>` 委托 `registry.register`
 * - 可见性：构造函数 `@PublishedApi internal`，外部无法直接构造，只能通过 [AgentBuilder.tools] 进入
 *
 * @property registry 工具注册表引用（由 [AgentBuilder._tools] 传入）
 */
@RacDslMarker
class ToolsScope @PublishedApi internal constructor(
    @PublishedApi internal val registry: ToolRegistry,
) {
    /**
     * 注册一个类型安全的工具——自动从 [Args] 生成 JSON Schema 并绑定处理 lambda。
     *
     * - 作用：声明一个可被模型调用的工具，框架自动完成参数反序列化与函数调用
     * - 必要性：替代手写 `param()` JSON Schema + 手动 `Json.decodeFromString` 的样板代码
     * - 设计：`inline reified` 在编译期获取 `serializer<Args>()`，KMP 友好无需反射
     * - 用法：
     *   ```
     *   tool<WeatherArgs>("get_weather", "查询天气") { args ->
     *       "北京今天晴，25°C"
     *   }
     *   ```
     *
     * @param Args 工具参数类型，需为 `@Serializable` data class
     * @param name 工具名（全局唯一，模型据此发起 ToolCall）
     * @param description 工具功能描述（供模型判断何时调用）
     * @param handler 工具执行逻辑，接收强类型 [Args]，返回结果字符串
     */
    inline fun <reified Args : Any> tool(
        name: String,
        description: String,
        noinline handler: suspend (Args) -> String,
    ) {
        val serializer: KSerializer<Args> = serializer()
        registry.register(name, description, serializer, handler)
    }

    /**
     * 注册一个散开参数模式的工具——在 [ToolScope] 作用域内用 `param()` 声明参数、`execute { }` 绑定 lambda。
     *
     * - 作用：作为 `tool<Args>` 强类型模式的平行入口，无需定义 `@Serializable data class`——
     *   开发者直接在 block 内用 `param(name, type, ...)` 声明参数元数据、用 `execute { a, b -> ... }` 写工具实现，
     *   框架按 `param` 声明顺序自动绑定 `execute` lambda 的入参，并生成对应的 JSON Schema
     * - 必要性：散开参数模式降低工具声明的样板代码——简单工具（1-3 参数、基础类型）无需定义 data class，
     *   `execute` lambda 即工具实现，与 `tool<Args>` 各有适用场景，可在同一 `tools { }` 块内混用
     * - 设计思路：
     *   1. `inline fun tool(name, description, block)`——`inline` 让 block 内 `execute { }` 的 `reified` 重载可用
     *      （`ToolScope.execute` 的 inline reified 重载需在 inline 上下文调用以绑定 serializer）
     *   2. block 不是 `noinline`——`ToolScope.() -> Unit` 的 inline lambda 让 block 内的 `execute` 调用
     *      在调用点展开，reified 类型参数从调用方上下文推断
     *   3. `scope.block()` 执行用户声明（`param()` + `execute { }`），累积 [ToolScope.params] 与 [ToolScope.handler]
     *   4. `scope.build(name, description)` 汇总成不可变 [DynamicToolEntry]（含 schema 生成与 handler 绑定）
     *   5. `registry.registerDynamicEntry(entry)` 写入 [ToolRegistry]——本方法不直接操作 registry 内部状态，
     *      通过受控方法暴露写入入口（保持 [ToolRegistry] 内部 Map 私有）
     * - 实现方式：构造 [ToolScope] → 执行 block → [ToolScope.build] → [ToolRegistry.registerDynamicEntry]
     * - 边缘情况：
     *   - block 内未调 `execute { }` → [ToolScope.build] 抛 [IllegalStateException]（提示缺失 execute 块）
     *   - `param` 数量与 `execute` arity 不一致 → [ToolScope.execute] 内 `require` 抛 [IllegalArgumentException]
     *   - `param` 数量超过 10 → 无匹配 `execute` arity 重载，编译期报错（引导用户改用 `tool<Args>`）
     *   - 同名工具重复注册 → 后注册覆盖先注册（与 [ToolRegistry.register] 一致）
     * - 优点：零样板（无 data class、无 ParamRef）、类型安全（reified 绑定 serializer）、KMP 友好（无反射）、
     *   与 `tool<Args>` 对称（同一 `tools { }` 块内可混用）
     *
     * @param name 工具名（全局唯一，模型据此发起 ToolCall）
     * @param description 工具功能描述（供模型判断何时调用）
     * @param block 在 [ToolScope] 作用域内声明参数（`param()`）与实现（`execute { }`）
     */
    inline fun tool(
        name: String,
        description: String,
        block: ToolScope.() -> Unit,
    ) {
        val scope = ToolScope()
        scope.block()
        val entry = scope.build(name, description)
        registry.registerDynamicEntry(entry)
    }
}

/**
 * 顶层 Agent DSL 入口——在块内配置系统提示词与工具集，返回 [Agent] 实例。
 *
 * - 作用：作为 Agent 功能的主入口，声明式配置「LLM + prompts + tools」三要素
 * - 必要性：统一 Agent 构建方式，与 `llm { }` DSL 风格一致
 * - 设计：`inline` 确保 `reified` 在 `tools { tool<Args>() }` 内可用；复用传入的 [llm] 实例
 * - 用法：
 *   ```
 *   val agent = agent(llm) {
 *       prompts("你是天气助手")
 *       tools {
 *           tool<WeatherArgs>("get_weather", "查询天气") { args ->
 *               weatherService.getWeather(args.city)
 *           }
 *       }
 *   }
 *   ```
 *
 * @param llm 已配置好的 [Llm] 实例（由 `llm { providers { } }` 创建）
 * @param block 在 [AgentBuilder] 作用域内配置 prompts 与工具
 * @return 构建完成的 [Agent]
 */
inline fun agent(llm: Llm, block: AgentBuilder.() -> Unit): Agent {
    val builder = AgentBuilder(llm)
    builder.block()
    return builder.build()
}
