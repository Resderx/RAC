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

import top.resderx.rac.dsl.RacDslMarker
import top.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.serializer

/**
 * 散开参数式工具声明的 DSL receiver——作为 `tool(name, desc) { }` 块内的接收者，收集参数定义与执行 lambda。
 *
 * - 作用：在 `tools { tool(name, desc) { param(...); execute { a, b -> } } }` DSL 中作为块内 receiver，
 *   提供 `param()` 收集参数元数据、`execute { }` 绑定执行 lambda（按 arity 重载 0-10 个参数），
 *   块结束时由 [ToolsScope.tool] 调用 [build] 汇总成 [DynamicToolEntry] 注册到 [ToolRegistry]
 * - 必要性：散开参数模式的核心目标是「无需定义 data class、无需传 ParamRef、`execute` lambda 直接是工具实现」，
 *   需要一个独立 receiver 来累积 `param()` 调用（构建参数元数据列表）与 `execute { }` 调用（绑定具体 arity 的 handler），
 *   避免与 [ToolsScope]（聚合多个工具声明）的职责混淆；同时 `execute` 的 `inline reified` 重载必须在类成员位置
 *   （Kotlin 限制 top-level inline reified 不可用），独立 receiver 是承载这些重载的最自然位置
 * - 设计思路：
 *   1. 内部维护两个可变状态：[params]（[ParamDef] 列表，按 `param()` 调用顺序累积）与 [handler]（[DynamicHandler]，由 `execute` 赋值一次）
 *   2. `param()` 为普通 fun（无泛型需求），收集一份 [ParamDef] 追加到 [params]——顺序决定了 `execute` lambda 的参数绑定顺序
 *   3. `execute` 提供 11 个重载（0-10 参数）：0 参数为普通 fun（无 reified 需求），1-10 参数为 `inline reified` + `noinline handler`，
 *      内部用 `require(params.size == N)` 校验声明数与 lambda 参数数一致，再按 [params] 顺序取参数名 + `serializer<T>()` 构造对应 [HandlerN]
 *   4. [build] 在块结束时由 [ToolsScope.tool] 调用：校验 [handler] 非空（缺失 `execute` 块则抛异常），用 [buildSchemaFromParams] 生成 JSON Schema，
 *      组装成不可变 [DynamicToolEntry] 返回——本类本身不可复用（一次性 receiver），无重置逻辑
 *   5. 标注 @RacDslMarker 防止嵌套 DSL 作用域污染；构造函数与内部成员用 `@PublishedApi internal` 以便 [ToolsScope.tool] 这个 inline 函数跨文件访问
 * - 实现方式：可变类（MutableList + var），`param`/`execute` 返回 Unit 仅做副作用收集，[build] 一次性汇总为不可变 [DynamicToolEntry]
 * - 边缘情况：
 *   - 未调 `execute { }` → [handler] 为 null，[build] 抛 [IllegalStateException] 提示缺失 execute 块
 *   - `param` 数量与 `execute` arity 不一致 → `require` 抛 [IllegalArgumentException]（如声明 2 个 param 但用 1 参数的 execute）
 *   - 重复调 `execute` → 后调用覆盖 [handler]（不报错，便于 DSL 内调整，与 [ToolRegistry] 同名覆盖的语义一致）
 *   - `param` 数量超过 10 → 无匹配 arity 重载，编译期报错（引导用户改用 `tool<Args>` 强类型模式）
 *   - 空 `params` + 0 参数 `execute` → [Handler0]，schema 为 `{"type":"object"}`，工具无参执行
 * - 优点：
 *   - 零样板：无需定义 data class、无需 ParamRef、无需委托代码，`execute` lambda 即工具实现
 *   - 类型安全：`inline reified` 在编译期绑定 `serializer<T>()`，KMP 友好无需反射
 *   - arity 校验前置：`require` 在 `execute` 调用时即校验，错误尽早暴露
 *   - 与 [ToolEntry]<Args> 强类型模式对称：两者通过 [ToolEntryHolder] 统一存储与分发，可同 `tools { }` 块混用
 * - 算法/数据结构：`param` O(1) 追加；`execute` O(1) 校验 + 构造；[build] O(n) 生成 schema（n = params.size）
 * - 时间复杂度：单次工具声明 O(n)，n 为参数数量
 * - 空间复杂度：O(n)，[params] 列表 + handler 引用
 */
@RacDslMarker
class ToolScope @PublishedApi internal constructor() {

    /** 累积的参数定义列表，按 `param()` 调用顺序追加——顺序决定 `execute` lambda 的参数绑定顺序与 JSON Schema properties 键序。 */
    @PublishedApi
    internal val params: MutableList<ParamDef> = mutableListOf()

    /** 由 `execute { }` 赋值的动态 handler，初始为 null——[build] 时若仍为 null 则抛异常提示缺失 execute 块。 */
    @PublishedApi
    internal var handler: DynamicHandler? = null

    /**
     * 声明一个工具参数——追加一份 [ParamDef] 到 [params]，用于生成 JSON Schema 与按序绑定 `execute` lambda 入参。
     *
     * - 作用：在 `tool(name, desc) { }` 块内调用 N 次，收集 N 个参数定义；调用顺序决定：
     *   1. JSON Schema `properties` 的键序（影响模型生成 arguments 的字段顺序）
     *   2. `execute { a, b -> }` lambda 中 `a`/`b` 对应的参数名（a ↔ 第 1 个 param，b ↔ 第 2 个 param）
     * - 必要性：散开参数模式不绑定 `@Serializable` 类型，需通过显式 `param()` 提供参数元数据（名/类型/描述/必填/枚举）
     *   既能生成 schema 给模型参考，又能按序匹配 `execute` lambda 的入参；这是与 `tool<Args>` 强类型模式的核心差异
     * - 设计：直接构造 [ParamDef] 追加到 [params]，无返回值（不需要 ParamRef 句柄——`execute` 通过位置而非引用绑定参数）
     * - 用法：
     *   ```
     *   tool("get_weather", "查询天气") {
     *       param("city", "string", "城市名称", required = true)
     *       param("date", "string", "日期", required = false)
     *       param("unit", "string", "温度单位", enumValues = listOf("C", "F"))
     *       execute { city, date, unit -> "$city: 晴" }
     *   }
     *   ```
     *
     * @param name 参数名，对应 JSON Schema properties 的 key 与模型返回 arguments 的字段名
     * @param type JSON Schema 类型字符串，如 "string"/"integer"/"number"/"boolean"/"array"/"object"
     * @param description 参数的人类可读描述，供模型理解参数含义，可空（缺失时部分模型调用准确率下降）
     * @param required 是否必填，默认 true；false 时模型可不返回该字段，`execute` lambda 对应入参接收 null
     * @param enumValues 可选枚举值列表，限制参数取值范围，仅对字符串类型有意义
     */
    fun param(
        name: String,
        type: String,
        description: String? = null,
        required: Boolean = true,
        enumValues: List<String>? = null,
    ) {
        params.add(ParamDef(name, type, description, required, enumValues))
    }

    /**
     * 0 参数版本的 `execute`——绑定无参 lambda，对应 `execute { "pong" }` 这类无参工具。
     *
     * - 作用：构造 [Handler0] 包装 `suspend () -> String` lambda，赋值到 [handler]；不读取 [params]（无参工具无需参数元数据）
     * - 设计：非 inline——0 参数无需 reified 获取 serializer，普通 fun 即可；与 1-10 参数的 inline 重载并列存在
     * - 边缘：即便 [params] 非空（误调了 `param()`）也不报错——0 参数工具忽略任何 param 声明，schema 仍按 [params] 生成
     *   （若 [params] 为空则 schema 为 `{"type":"object"}`；若误填了 param 则 schema 含多余字段，但 handler 不消费参数）
     *
     * @param handler 工具执行逻辑，无入参，返回结果字符串
     */
    fun execute(handler: suspend () -> String) {
        this.handler = Handler0(handler)
    }

    /**
     * 1 参数版本的 `execute`（散开参数模式的代表实现，后续 2-10 参数版本为 arity 变体，参考本注释）。
     *
     * - 作用：绑定 `suspend (A?) -> String` lambda 到 [Handler1]，按 [params] 第 1 个元素的 [ParamDef.name] 取参数名、
     *   用 `serializer<A>()` 获取序列化器，构造 [Handler1] 赋值到 [handler]——块结束时由 [build] 汇总
     * - 必要性：1 参数是最小有参场景，该重载的实现模式（校验 arity → 取参数名 → 取 serializer → 构造 HandlerN）
     *   被 2-10 参数版本完全复用，仅 arity 与泛型数量不同；抽离为独立重载保证编译期类型安全
     *   （lambda 签名 `(A?) -> String` 由 Kotlin 编译器校验，`serializer<A>()` 由 reified 在编译期绑定）
     * - 设计思路：
     *   1. `inline fun <reified A>` + `noinline handler`——`inline` 让 `reified` 可用，`noinline` 避免大 lambda 被内联膨胀
     *   2. `require(params.size == 1)` 校验声明数与 lambda 参数数一致——不一致时抛 [IllegalArgumentException]，
     *      错误消息明确指出「声明了 X 个 param，但 execute 接收 1 个参数」
     *   3. `params[0].name` 取第 1 个参数名（按声明顺序），`serializer<A>()` 获取 A 的 [kotlinx.serialization.KSerializer]
     *   4. handler 入参统一为 `A?`（可空）——缺失或 JsonNull 传 null，由 handler 内部决定如何处理，
     *      这与 [Handler1] 的 lambda 签名一致，也最贴近 Kotlin 函数参数语义
     * - 实现方式：构造 [Handler1] 赋值到 [handler]；泛型 [A] 无上界约束，支持基础类型（String/Int/Double/Boolean）与 `List<String>` 等泛型
     * - 边缘情况：
     *   - [params] 为空 → `require` 抛 [IllegalArgumentException]（声明了 0 个 param，但 execute 接收 1 个参数）
     *   - [params] 多于 1 → `require` 抛 [IllegalArgumentException]（声明了 N 个 param，但 execute 接收 1 个参数）
     *   - A 为 `List<String>` 等泛型 → `serializer<List<String>>()` 在 kotlinx.serialization 1.11+ 已稳定支持
     *   - 重复调 `execute` → 后调用覆盖 [handler]（不报错，便于 DSL 内调整）
     * - 优点：编译期类型安全（reified 绑定 serializer）、零反射、KMP 友好；arity 校验前置尽早暴露错误
     *
     * @param A 第 1 个参数的 Kotlin 类型（需有配套 serializer，基础类型与 `List<String>` 等泛型均支持）
     * @param handler 工具执行逻辑，接收 `A?`（缺失或 null 时为 null），返回结果字符串
     */
    inline fun <reified A> execute(noinline handler: suspend (A?) -> String) {
        require(params.size == 1) { "execute 接收 1 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler1(
            params[0].name,
            serializer<A>(),
            handler,
        )
    }

    /**
     * 2 参数版本的 `execute`——[execute] 的 arity=2 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?) -> String` lambda 到 [Handler2]，按 [params] 顺序取 2 个参数名 + 2 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同；两个参数独立取值，互不依赖
     * - 边缘：`require(params.size == 2)` 校验声明数；任一参数缺失或为 null → 对应入参为 null，handler 内部自行处理
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?)`，返回结果字符串
     */
    inline fun <reified A, reified B> execute(noinline handler: suspend (A?, B?) -> String) {
        require(params.size == 2) { "execute 接收 2 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler2(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            handler,
        )
    }

    /**
     * 3 参数版本的 `execute`——[execute] 的 arity=3 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?) -> String` lambda 到 [Handler3]，按 [params] 顺序取 3 个参数名 + 3 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C> execute(noinline handler: suspend (A?, B?, C?) -> String) {
        require(params.size == 3) { "execute 接收 3 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler3(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            handler,
        )
    }

    /**
     * 4 参数版本的 `execute`——[execute] 的 arity=4 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?) -> String` lambda 到 [Handler4]，按 [params] 顺序取 4 个参数名 + 4 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D> execute(noinline handler: suspend (A?, B?, C?, D?) -> String) {
        require(params.size == 4) { "execute 接收 4 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler4(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            handler,
        )
    }

    /**
     * 5 参数版本的 `execute`——[execute] 的 arity=5 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?, E?) -> String` lambda 到 [Handler5]，按 [params] 顺序取 5 个参数名 + 5 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E> execute(noinline handler: suspend (A?, B?, C?, D?, E?) -> String) {
        require(params.size == 5) { "execute 接收 5 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler5(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            handler,
        )
    }

    /**
     * 6 参数版本的 `execute`——[execute] 的 arity=6 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?, E?, F?) -> String` lambda 到 [Handler6]，按 [params] 顺序取 6 个参数名 + 6 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param F 第 6 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E, reified F> execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?) -> String) {
        require(params.size == 6) { "execute 接收 6 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler6(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            params[5].name, serializer<F>(),
            handler,
        )
    }

    /**
     * 7 参数版本的 `execute`——[execute] 的 arity=7 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?, E?, F?, G?) -> String` lambda 到 [Handler7]，按 [params] 顺序取 7 个参数名 + 7 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param F 第 6 个参数的 Kotlin 类型
     * @param G 第 7 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G> execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?) -> String) {
        require(params.size == 7) { "execute 接收 7 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler7(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            params[5].name, serializer<F>(),
            params[6].name, serializer<G>(),
            handler,
        )
    }

    /**
     * 8 参数版本的 `execute`——[execute] 的 arity=8 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?, E?, F?, G?, H?) -> String` lambda 到 [Handler8]，按 [params] 顺序取 8 个参数名 + 8 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param F 第 6 个参数的 Kotlin 类型
     * @param G 第 7 个参数的 Kotlin 类型
     * @param H 第 8 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G, reified H> execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?) -> String) {
        require(params.size == 8) { "execute 接收 8 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler8(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            params[5].name, serializer<F>(),
            params[6].name, serializer<G>(),
            params[7].name, serializer<H>(),
            handler,
        )
    }

    /**
     * 9 参数版本的 `execute`——[execute] 的 arity=9 变体，参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?) -> String` lambda 到 [Handler9]，按 [params] 顺序取 9 个参数名 + 9 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param F 第 6 个参数的 Kotlin 类型
     * @param G 第 7 个参数的 Kotlin 类型
     * @param H 第 8 个参数的 Kotlin 类型
     * @param I 第 9 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?, I?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G, reified H, reified I> execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?) -> String) {
        require(params.size == 9) { "execute 接收 9 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler9(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            params[5].name, serializer<F>(),
            params[6].name, serializer<G>(),
            params[7].name, serializer<H>(),
            params[8].name, serializer<I>(),
            handler,
        )
    }

    /**
     * 10 参数版本的 `execute`——[execute] 的 arity=10 变体（散开参数模式支持的最大 arity），参考 1 参数版本注释。
     *
     * - 作用：绑定 `suspend (A?, B?, ..., J?) -> String` lambda 到 [Handler10]，按 [params] 顺序取 10 个参数名 + 10 个 serializer
     * - 设计：与 1 参数版本同模式，仅 arity 与泛型数量不同；超过 10 参数应改用 `tool<Args>` 强类型模式
     * - 边缘：11+ 参数无对应 arity 重载，编译期报错（引导用户改用 `tool<Args>` 强类型模式）
     *
     * @param A 第 1 个参数的 Kotlin 类型
     * @param B 第 2 个参数的 Kotlin 类型
     * @param C 第 3 个参数的 Kotlin 类型
     * @param D 第 4 个参数的 Kotlin 类型
     * @param E 第 5 个参数的 Kotlin 类型
     * @param F 第 6 个参数的 Kotlin 类型
     * @param G 第 7 个参数的 Kotlin 类型
     * @param H 第 8 个参数的 Kotlin 类型
     * @param I 第 9 个参数的 Kotlin 类型
     * @param J 第 10 个参数的 Kotlin 类型
     * @param handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?, I?, J?)`，返回结果字符串
     */
    inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified G, reified H, reified I, reified J> execute(noinline handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?, J?) -> String) {
        require(params.size == 10) { "execute 接收 10 个参数，但声明了 ${params.size} 个 param" }
        this.handler = Handler10(
            params[0].name, serializer<A>(),
            params[1].name, serializer<B>(),
            params[2].name, serializer<C>(),
            params[3].name, serializer<D>(),
            params[4].name, serializer<E>(),
            params[5].name, serializer<F>(),
            params[6].name, serializer<G>(),
            params[7].name, serializer<H>(),
            params[8].name, serializer<I>(),
            params[9].name, serializer<J>(),
            handler,
        )
    }

    /**
     * 汇总块内声明的 params 与 handler，构造不可变 [DynamicToolEntry]——由 [ToolsScope.tool] 在 block 结束后调用。
     *
     * - 作用：作为 `tool(name, desc) { }` 块的收尾步骤，把累积的 [params]（[ParamDef] 列表）与 [handler]（[DynamicHandler]）
     *   连同工具名与描述，组装成不可变 [DynamicToolEntry] 返回；调用方（[ToolsScope.tool]）再调 [ToolRegistry.registerDynamicEntry] 注册
     * - 调用时机：仅在 `tool(name, desc) { ... }` block lambda 执行完毕后由 [ToolsScope.tool] 调用一次——
     *   此时 [params] 已累积全部 `param()` 调用、[handler] 已被 `execute { }` 赋值；本方法不再修改状态，纯汇总产出
     * - 必要性：[ToolRegistry] 需要不可变 [DynamicToolEntry] 存储，[ToolScope] 作为可变 receiver 不应直接暴露给 registry；
     *   [build] 把可变状态「快照」为不可变条目，是可变 → 不可变的转换边界
     * - 设计：
     *   1. [handler] 为 null（即用户未在 block 内调 `execute { }`）→ 抛 [IllegalStateException]，消息明确指出哪个工具名缺失 execute 块
     *   2. [ToolDefinition.parameters] 由 [buildSchemaFromParams]([params]) 现场生成 JSON Schema 字符串
     *   3. 返回的 [DynamicToolEntry] 字段全 val，[handler] 字段为 private（外部仅通过 [ToolEntryHolder.execute] 调用）
     * - 异常处理：
     *   - [handler] == null → 抛 [IllegalStateException]（"tool '$name' 缺少 execute { } 块"），
     *     由 [ToolsScope.tool] 上层调用方感知（block 内必须含 `execute { }` 才合法）
     *   - 不在此校验 [params] 数量与 [handler] arity 的一致性——`execute` 重载内部已用 `require` 校验，
     *     重复校验无意义；若用户绕过 `execute` 直接赋值 [handler]（不可能，[handler] 为 internal）则无此保障
     * - 可见性：`@PublishedApi internal`，仅供同模块内 [ToolsScope.tool] 这个 inline 函数跨文件访问；
     *   外部 DSL 用户无法直接调用（必须通过 `tool(name, desc) { }` 入口）
     *
     * @param name 工具名（与 [ToolDefinition.name] 一致）
     * @param description 工具描述（与 [ToolDefinition.description] 一致）
     * @return 不可变 [DynamicToolEntry]，含 name/definition/handler
     * @throws IllegalStateException 当 block 内未调 `execute { }`（[handler] 为 null）
     */
    @PublishedApi
    internal fun build(name: String, description: String): DynamicToolEntry {
        val h = handler ?: throw IllegalStateException("tool '$name' 缺少 execute { } 块")
        return DynamicToolEntry(
            name = name,
            definition = ToolDefinition(
                name = name,
                description = description,
                parameters = buildSchemaFromParams(params),
            ),
            handler = h,
        )
    }
}
