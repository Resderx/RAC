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

package top.resderx.rac.agent

import com.resderx.rac.exceptions.RACException
import com.resderx.rac.messages.ToolCall
import com.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * 工具注册条目的统一持有接口——抽象 [ToolEntry]（强类型模式）与 [DynamicToolEntry]（散开参数模式）的共同契约。
 *
 * - 作用：作为 [ToolRegistry] 内部存储 `_tools` 的值类型，让两种注册模式（`tool<Args>` 与 `execute { ... }`）
 *   可以共存于同一个 `Map<String, ToolEntryHolder>` 中，[ToolRegistry] 的查找/导出/执行只需面向本接口编程
 * - 必要性：散开参数模式引入后，[ToolRegistry] 需同时容纳 [ToolEntry]（持有 KSerializer 的强类型条目）
 *   与 [DynamicToolEntry]（持有 [DynamicHandler] 的散开条目）；二者字段与 execute 签名一致（都是
 *   `suspend fun execute(arguments: String): String`），但无共同父类。若不抽离本接口，[ToolRegistry]
 *   需用 `Any` 或两套 Map 存储，丢失类型安全与统一调用入口
 * - 设计思路：
 *   1. 密封接口（sealed interface）限定实现者必须在同一包内——当前为 [ToolEntry] 与 [DynamicToolEntry]，
 *      编译期穷尽匹配，便于未来扩展第三种条目类型时检查覆盖
 *   2. 仅抽象三者真正共享的成员：[name]（查找键）、[definition]（导出给模型）、[execute]（统一调用入口）
 *   3. [execute] 签名 `suspend fun execute(arguments: String): String` 与两种条目现有签名完全一致，
 *      故二者实现时无需任何适配，仅声明 `: ToolEntryHolder` 即可
 *   4. 不暴露 [ToolEntry] 的 `serializer`/`handler` 与 [DynamicToolEntry] 的 `handler`，
 *      [ToolRegistry] 仅作为「按名查找 + 委托执行」的索引，无需感知具体条目内部
 * - 实现方式：sealed interface，三个抽象成员（val name、val definition、suspend fun execute）
 * - 边缘情况：
 *   - 两种条目的 [execute] 内部各自处理 JSON 解析与反序列化异常（抛 SerializationException），
 *     由 [ToolRegistry.execute] 统一捕获包装为 [RACException]，本接口不参与异常处理
 *   - 密封接口保证 `when` 表达式穷尽性，但当前 [ToolRegistry] 不需要区分条目类型，仅调用 [execute]
 * - 优点：统一存储与调用入口、编译期类型安全、密封接口穷尽性、零运行时开销（接口方法直接派发到具体类）
 * - 算法/数据结构：纯接口声明，无数据结构
 * - 时间复杂度：N/A（接口无逻辑）
 * - 空间复杂度：N/A
 */
sealed interface ToolEntryHolder {
    /** 工具名（与 [definition.name] 一致），作为 [ToolRegistry] 内部 Map 的查找键。 */
    val name: String

    /** 工具定义（含 description 与 JSON Schema parameters），供 `chatWithTools` 注入到模型请求。 */
    val definition: ToolDefinition

    /**
     * 执行工具——接收模型返回的 arguments JSON 字符串，返回结果字符串。
     *
     * - 两种实现：[ToolEntry] 反序列化为强类型 Args 再调 handler；[DynamicToolEntry] 解析为 JsonObject 后委托 [DynamicHandler]
     * - 异常：反序列化失败抛 [kotlinx.serialization.SerializationException]，由 [ToolRegistry.execute] 捕获包装
     *
     * @param arguments 模型返回的工具参数 JSON 字符串
     * @return handler 返回的结果字符串
     */
    suspend fun execute(arguments: String): String
}

/**
 * 类型安全的工具注册条目——绑定一个 `@Serializable` 参数类型与对应的处理 lambda。
 *
 * - 作用：作为 [Agent] 工具集的单条条目，持有工具元数据（[definition]）与执行逻辑（[handler]），
 *   并通过 [serializer] 桥接 JSON 字符串（模型返回的 arguments）与强类型 [Args] 参数对象
 * - 必要性：Agent 的自动函数调用需要「按名查找工具 → 反序列化 JSON 参数 → 调用 Kotlin 函数」
 *   三步闭环；[ToolEntry] 把这三步封装在一个条目内，[ToolRegistry] 仅做查找与委托
 * - 设计思路：
 *   1. 泛型 [Args] 约束为 `Any`，对应 `@Serializable` data class（如 `WeatherArgs`）
 *   2. [serializer] 由 `serializer<Args>()` 在 DSL 内获取，编译期确定类型
 *   3. [definition] 的 `parameters` 由 [toJsonSchema] 在注册时一次性生成，运行时零开销
 *   4. [execute] 封装反序列化 + 调用，对外只暴露 String→String 接口，[ToolRegistry] 无需关心 Args 类型
 *   5. 内部 [json] 实例开启 `ignoreUnknownKeys`，容错模型返回的多余字段
 * - 实现方式：不可变数据类式结构（val 字段），[execute] 为 suspend 方法以支持异步 handler
 * - 边缘情况：
 *   - 模型返回空 arguments（`""` 或 `"{}"`）→ 反序列化为默认构造的 Args（需有无参构造或全可选字段）
 *   - 模型返回多余字段 → 因 `ignoreUnknownKeys = true` 被忽略，不报错
 *   - 缺 required 字段 → `decodeFromString` 抛 `SerializationException`，由 [ToolRegistry.execute] 包装为 [RACException]
 * - 优点：类型安全（Args 在编译期确定）、零反射（用 KSerializer 而非 kotlin-reflect）、KMP 友好
 *
 * @property Args 工具参数类型，需为 `@Serializable` 且继承 `Any`
 * @property name 工具名（与 [definition.name] 一致，冗余存储便于快速查找）
 * @property definition 工具定义（含 description 与 JSON Schema parameters），供模型调用参考
 * @property serializer Args 的序列化器，用于反序列化模型返回的 arguments JSON
 * @property handler 工具执行逻辑，接收强类型 [Args]，返回结果字符串（通常为 JSON 或纯文本）
 */
class ToolEntry<Args : Any>(
    override val name: String,
    override val definition: ToolDefinition,
    private val serializer: KSerializer<Args>,
    private val handler: suspend (Args) -> String,
) : ToolEntryHolder {
    /** 工具内部使用的 Json 实例，开启 ignoreUnknownKeys 容错模型多余字段。 */
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 执行工具——反序列化 arguments JSON 为强类型 [Args]，调用 handler 返回结果。
     *
     * - 作用：封装「JSON → Args → 调用 → 结果字符串」闭环，[ToolRegistry] 无需关心 Args 类型
     * - 边缘：反序列化失败抛 [kotlinx.serialization.SerializationException]，由上层捕获包装
     *
     * @param arguments 模型返回的工具参数 JSON 字符串（如 `{"city":"北京"}`）
     * @return handler 返回的结果字符串
     */
    override suspend fun execute(arguments: String): String {
        val args = json.decodeFromString(serializer, arguments)
        return handler(args)
    }
}

/**
 * 工具注册表——管理 [Agent] 的全部工具条目，提供按名查找与批量导出定义。
 *
 * - 作用：作为 [Agent] 的工具集容器，注册 [ToolEntry]、按名查找、导出 [ToolDefinition] 列表
 *   给底层 `chatWithTools`，并在工具调用时委托到对应 [ToolEntry.execute]
 * - 必要性：Agent 的多轮循环中，模型可能返回任意已注册工具的 [ToolCall]；
 *   [ToolRegistry] 作为「工具名 → 执行逻辑」的中央索引，统一查找与分发
 * - 设计思路：
 *   1. 内部用 `Map<String, ToolEntryHolder>` 按名索引，O(1) 查找
 *   2. [register] 接收泛型参数与 KSerializer，构造 [ToolEntry] 并生成 [ToolDefinition]
 *   3. [definitions] 导出 `List<ToolDefinition>` 供 `chatWithTools` 的 `addTools` 注入
 *   4. [execute] 按名查找 [ToolEntry]，委托其 [ToolEntry.execute]，捕获反序列化异常包装为 [RACException]
 *   5. [ToolEntryHolder] 统一 [ToolEntry] 与 [DynamicToolEntry] 的 [ToolEntryHolder.execute] 接口，[ToolRegistry] 无需感知具体条目类型
 * - 实现方式：类持有可变 Map，register 添加，find/execute 查找
 * - 边缘情况：
 *   - 工具名重复 → 后注册的覆盖先注册的（不报错，便于 DSL 内覆盖配置）
 *   - 查找未注册工具 → [execute] 抛 [RACException]，含工具名
 *   - 反序列化失败 → [execute] 抛 [RACException]，含工具名与原始 arguments
 * - 优点：注册与执行解耦，查找 O(1)，泛型擦除由 [ToolEntry.execute] 内部封装
 * - 算法/数据结构：HashMap 查找
 * - 时间复杂度：register O(1)；find/execute O(1)
 * - 空间复杂度：O(n)，n 为工具数量
 */
class ToolRegistry {

    /** 内部按名索引的工具条目映射。 */
    private val _tools: MutableMap<String, ToolEntryHolder> = mutableMapOf()

    /**
     * 注册一个类型安全的工具。
     *
     * - 作用：用泛型 [Args] 与 [KSerializer] 构造 [ToolEntry]，自动生成 JSON Schema 作为 [ToolDefinition.parameters]
     * - 设计：同名工具后注册覆盖先注册（便于 DSL 覆盖配置）
     *
     * @param Args 工具参数类型，需 `@Serializable`
     * @param name 工具名（全局唯一，模型据此发起 ToolCall）
     * @param description 工具功能描述（供模型判断何时调用）
     * @param serializer Args 的序列化器（由 `serializer<Args>()` 获取）
     * @param handler 工具执行逻辑，接收强类型 Args，返回结果字符串
     */
    fun <Args : Any> register(
        name: String,
        description: String,
        serializer: KSerializer<Args>,
        handler: suspend (Args) -> String,
    ) {
        val definition = ToolDefinition(
            name = name,
            description = description,
            parameters = serializer.toJsonSchema(),
        )
        _tools[name] = ToolEntry(
            name = name,
            definition = definition,
            serializer = serializer,
            handler = handler,
        )
    }

    /**
     * 注册一个散开参数模式的工具——用 [ParamDef] 列表描述参数，由 [DynamicHandler] 处理调用。
     *
     * - 作用：与 [register] 平行的另一注册入口，面向 `execute { ... }` DSL：用 [List]<[ParamDef]> 描述
     *   参数（名/类型/描述/是否必填/枚举值），由调用方预先构造好 [DynamicHandler]（[Handler0]...[Handler10] 之一），
     *   本方法构造 [ToolDefinition]（parameters 由 [buildSchemaFromParams] 从 params 生成）并包装为 [DynamicToolEntry] 存入 [_tools]
     * - 必要性：散开参数模式不绑定单一 `@Serializable` 类型，无法走 [register] 的 KSerializer 路径；
     *   [DynamicToolEntry] 与 [ToolEntry] 字段布局一致但 handler 类型不同，统一实现 [ToolEntryHolder]，
     *   故本方法与 [register] 仅在「构造条目」步骤不同，存储与执行路径完全复用
     * - 设计思路：
     *   1. 参数 schema 不走 KSerializer，而是用 [buildSchemaFromParams] 从 [List]<[ParamDef]> 生成字符串
     *   2. [ToolDefinition] 字段与 [register] 一致（name + description + parameters），模型侧无需区分两种注册模式
     *   3. [DynamicToolEntry] 持有 [DynamicHandler]，[execute] 时由 handler 内部按 arity 反序列化参数后调 lambda
     *   4. 同名工具后注册覆盖先注册（与 [register] 一致，便于 DSL 覆盖配置）
     * - 实现方式：构造 [ToolDefinition] + [DynamicToolEntry]，写入 [_tools] Map
     * - 边缘情况：
     *   - params 为空 → [buildSchemaFromParams] 返回 `{"type":"object"}`，对应无参工具（[Handler0]）
     *   - 同名工具已存在 → 后注册覆盖先注册（不报错）
     *   - handler arity 与 params 数量不一致 → 编译期由 DSL 层保证一致，本方法不做运行时校验
     * - 优点：与 [register] 对外语义对称（name + description + 参数描述 + handler），[ToolRegistry] 统一存储为 [ToolEntryHolder]
     * - 算法/数据结构：构造两个不可变对象 + 一次 Map 写入
     * - 时间复杂度：O(n)，n 为 params 数量（[buildSchemaFromParams] 单次遍历）；写入 O(1)
     * - 空间复杂度：O(n)，[ToolDefinition.parameters] 字符串长度与 params 数量线性相关
     *
     * @param name 工具名（全局唯一，模型据此发起 ToolCall）
     * @param description 工具功能描述（供模型判断何时调用）
     * @param params 参数定义列表（按声明顺序，影响 JSON Schema properties 键序与 required 列表）
     * @param handler 动态执行器（[Handler0]...[Handler10] 之一），按 arity 反序列化参数后调用 lambda
     */
    fun registerDynamic(
        name: String,
        description: String,
        params: List<ParamDef>,
        handler: DynamicHandler,
    ) {
        val definition = ToolDefinition(
            name = name,
            description = description,
            parameters = buildSchemaFromParams(params),
        )
        _tools[name] = DynamicToolEntry(
            name = name,
            definition = definition,
            handler = handler,
        )
    }

    /**
     * 注册一个已构造好的散开参数工具条目——直接写入 [_tools]，跳过 schema 生成（[entry] 内已含 [ToolDefinition]）。
     *
     * - 作用：与 [registerDynamic] 平行的另一注册入口，面向 `ToolsScope.tool(name, desc) { ... }` DSL 的内部路径——
     *   DSL 在 `ToolScope.build` 内已用 [buildSchemaFromParams] 生成 schema 并组装成 [DynamicToolEntry]，
     *   本方法仅做一次 Map 写入，避免重复生成 schema
     * - 必要性：[ToolsScope.tool] 是 `inline fun`，需访问 [ToolRegistry] 的 internal 成员；
     *   `inline fun` 的函数体在调用方模块内联展开，无法直接访问 [ToolRegistry] 的 private [_tools]，
     *   故提供一个公开的 `registerDynamicEntry` 方法作为 inline 函数的安全入口（而非把 [_tools] 暴露为 `@PublishedApi internal`）
     * - 设计：直接 `_tools[entry.name] = entry`，与 [register]/[registerDynamic] 同名覆盖语义一致（便于 DSL 覆盖配置）
     * - 实现方式：单次 Map 写入，O(1)
     * - 边缘：同名工具已存在 → 后注册覆盖先注册（不报错）；[entry] 的 schema/arity 一致性已在 [ToolScope.execute] 内 `require` 校验
     * - 优点：避免在 inline 函数体内重复 schema 生成逻辑；保持 [_tools] 私有，仅通过受控方法暴露写入入口
     *
     * @param entry 已构造好的散开参数工具条目（含 name/definition/handler）
     */
    fun registerDynamicEntry(entry: DynamicToolEntry) {
        _tools[entry.name] = entry
    }

    /**
     * 按名查找工具条目。
     *
     * @param name 工具名
     * @return 对应的 [ToolEntryHolder]，未注册返回 null
     */
    fun find(name: String): ToolEntryHolder? = _tools[name]

    /**
     * 导出全部工具定义，供 `chatWithTools` 的 `addTools` 注入到请求。
     *
     * @return 工具定义列表（空注册表返回空列表）
     */
    fun definitions(): List<ToolDefinition> = _tools.values.map { it.definition }

    /**
     * 执行指定工具调用——反序列化参数 + 调用 handler。
     *
     * - 作用：Agent 多轮循环中作为 `toolExecutor` 回调，自动完成参数反序列化与函数调用
     * - 错误处理：工具未注册 → [RACException]；反序列化失败 → [RACException]（含原始 arguments）
     *
     * @param toolCall 模型返回的工具调用（含 name 与 arguments JSON）
     * @return handler 返回的结果字符串
     * @throws RACException 工具未注册或参数反序列化失败
     */
    suspend fun execute(toolCall: ToolCall): String {
        val entry = _tools[toolCall.name]
            ?: throw RACException("未注册的工具: ${toolCall.name}")
        return try {
            entry.execute(toolCall.arguments)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw RACException(
                "工具 ${toolCall.name} 参数反序列化失败，原始 arguments: ${toolCall.arguments}",
                e,
            )
        }
    }
}
