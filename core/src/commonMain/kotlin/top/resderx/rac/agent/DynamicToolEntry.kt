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

import top.resderx.rac.exceptions.RACException
import top.resderx.rac.messages.ToolDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 动态工具执行器——散开参数模式的统一执行接口。
 *
 * - 作用：作为 [DynamicToolEntry] 持有的 handler 的静态类型，密封接口约束子类集合为
 *   [Handler0]...[Handler10] 共 11 个，分别对应 0-10 个参数的工具 lambda
 * - 必要性：散开参数模式不绑定单一 `@Serializable` 类型，不同 arity（参数数量）的工具 lambda 签名不同
 *   （`suspend () -> String`、`suspend (A?) -> String`、`suspend (A?, B?) -> String` ...），
 *   无法用单一泛型类表达；密封接口 + 每种 arity 一个子类，是类型安全与可扩展性的平衡点
 * - 设计思路：
 *   1. 密封接口（sealed interface）限定子类必须在同一文件/包内，编译期穷尽匹配，便于后续 `when` 扩展
 *   2. [invoke] 接收已解析的 [JsonObject]（参数键值对）与共享的 [Json] 实例（用于反序列化），
 *      返回结果字符串——与 [ToolEntry.execute] 的对外语义一致，[DynamicToolEntry] 无需关心具体 arity
 *   3. 每个子类持有具体 arity 的 `paramName`/`serializer` 与 handler lambda，[invoke] 内部按序反序列化
 *   4. 所有 lambda 参数统一为 `A?`（可空）——缺失或 JsonNull 传 null，由 handler 内部决定如何处理，
 *      这与 Kotlin 函数参数语义最贴近，也避免在子类层维护 `isNullable` 标记的额外复杂度
 * - 边缘情况：
 *   - 参数缺失（JsonObject 无对应 key）→ [getParamOrNull] 返回 null，handler 收到 null
 *   - 参数显式为 null（`"city": null`）→ [JsonNull] 同样返回 null
 *   - 参数类型不匹配（如声明 string 但传整数）→ `decodeFromJsonElement` 抛 [kotlinx.serialization.SerializationException]，
 *     由 [DynamicToolEntry.execute] 上层捕获包装为 [RACException]
 * - 优点：密封接口保证类型安全与穷尽性；每种子类逻辑独立、易读、易测试
 *
 * @see Handler0 无参数版本
 * @see Handler1 1 参数版本（参数模式的代表实现，注释最详）
 */
sealed interface DynamicHandler {

    /**
     * 执行工具——从 [JsonObject] 按各子类持有的参数名反序列化后调用 handler lambda。
     *
     * - 作用：统一对外入口，[DynamicToolEntry.execute] 解析完 JSON 字符串后委托到此方法
     * - 设计：[json] 由调用方传入，复用 [DynamicToolEntry] 的 [Json] 实例（已开启 ignoreUnknownKeys/isLenient）
     *
     * @param arguments 模型返回的工具参数 JSON 对象（已解析，可能为空对象）
     * @param json 共享的 [Json] 实例，用于反序列化各参数
     * @return handler 返回的结果字符串
     */
    suspend fun invoke(arguments: JsonObject, json: Json): String
}

/**
 * 从 [JsonObject] 中按名取参数——缺失或为 JsonNull 时返回 null，否则用 [serializer] 反序列化。
 *
 * - 作用：统一 [Handler1]...[Handler10] 各参数的取值逻辑，避免在每个子类重复 null 判断
 * - 必要性：散开参数模式下 optional 参数缺失需传 null，必填参数缺失也先取 null 再由 handler/上层判断；
 *   且模型可能返回显式 `"key": null`（[JsonNull]），需与「字段缺失」同等处理为 null
 * - 设计思路：
 *   1. `this[name]` 取键，缺失（key 不存在）→ 返回 null
 *   2. `elem is JsonNull`（显式 null 值）→ 返回 null（与缺失行为一致，简化 handler 逻辑）
 *   3. 否则用 [json].[decodeFromJsonElement] 反序列化为 [T]；类型不匹配时抛 [kotlinx.serialization.SerializationException]
 * - 实现：private 扩展函数，仅本文件内可见，不污染包级 API
 * - 边缘：[serializer] 为非空 [KSerializer]（如 `String.serializer()`），但反序列化结果以 `T?` 返回，
 *   因为本函数自己处理了 null 两种来源，调用方拿到的始终是 `T?`
 *
 * @param T 参数的 Kotlin 类型
 * @param name 参数名
 * @param serializer 参数的 [KSerializer]
 * @param json 共享 [Json] 实例
 * @return 反序列化后的值，缺失或为 null 时返回 null
 */
private fun <T> JsonObject.getParamOrNull(name: String, serializer: KSerializer<T>, json: Json): T? {
    val elem = this[name] ?: return null
    if (elem is JsonNull) return null
    return json.decodeFromJsonElement(serializer, elem)
}

/**
 * 0 参数工具的动态 handler。
 *
 * - 作用：包装 `suspend () -> String` lambda，对应 `execute { "pong" }` 这类无参工具
 * - 设计：[invoke] 直接调用 handler，不读取 [JsonObject]——schema 为 `{"type":"object"}`
 * - 边缘：模型即便返回非空 arguments 也被忽略（无参工具无需参数）
 *
 * @property handler 工具执行逻辑，无入参，返回结果字符串
 */
class Handler0(
    private val handler: suspend () -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String = handler()
}

/**
 * 1 参数工具的动态 handler（散开参数模式的代表实现，后续 [Handler2]...[Handler10] 同模式扩展 arity）。
 *
 * - 作用：包装 `suspend (A?) -> String` lambda，持有 1 个参数名 [p1Name] + 序列化器 [s1]，
 *   [invoke] 时按 [p1Name] 从 [JsonObject] 取值并用 [s1] 反序列化为 `A?`，传给 handler
 * - 必要性：1 参数是最小有参场景，该类的实现模式（取值→反序列化→调用）被 [Handler2]...[Handler10] 复用，
 *   仅 arity 不同；抽离为独立子类保证类型安全（lambda 签名 `(A?) -> String` 编译期校验）
 * - 设计思路：
 *   1. 构造期接收参数名 [p1Name] 与序列化器 [s1]（由 `ToolScope.execute` 的 `reified` 重载传入）
 *   2. [invoke] 调 [getParamOrNull] 统一处理「字段缺失」「显式 null」「正常值」三种情况
 *   3. handler 入参统一为 `A?`（可空）——缺失或 null 传 null，由 handler 内部决定如何处理，
 *      避免在子类层维护 `isNullable` 标记的额外复杂度，也最贴近 Kotlin 函数参数语义
 * - 实现方式：所有字段 private val，不可变；泛型 [A] 无上界约束，支持基础类型与 `List<String>` 等泛型
 * - 边缘情况：
 *   - 参数缺失 → [getParamOrNull] 返回 null，handler 收到 null
 *   - 参数类型不匹配 → `decodeFromJsonElement` 抛异常，由上层包装为 [RACException]
 * - 优点：类型安全（编译期校验 lambda 签名）、零反射（用 [KSerializer]）、KMP 友好
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名（对应 JSON Schema properties 的 key）
 * @property s1 第 1 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `A?`（缺失或 null 时为 null），返回结果字符串
 */
class Handler1<A>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val handler: suspend (A?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        return handler(v1)
    }
}

/**
 * 2 参数工具的动态 handler——[Handler1] 的 arity=2 变体。
 *
 * - 作用：包装 `suspend (A?, B?) -> String` lambda，按 [p1Name]/[p2Name] 顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，仅多一组 参数名+序列化器；两个参数独立取值，互不依赖
 * - 边缘：任一参数缺失或为 null → 对应入参为 null，handler 内部自行处理
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?)`，返回结果字符串
 */
class Handler2<A, B>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val handler: suspend (A?, B?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        return handler(v1, v2)
    }
}

/**
 * 3 参数工具的动态 handler——[Handler1] 的 arity=3 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 3 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?)`，返回结果字符串
 */
class Handler3<A, B, C>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val handler: suspend (A?, B?, C?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        return handler(v1, v2, v3)
    }
}

/**
 * 4 参数工具的动态 handler——[Handler1] 的 arity=4 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 4 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @param D 第 4 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?)`，返回结果字符串
 */
class Handler4<A, B, C, D>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val handler: suspend (A?, B?, C?, D?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        return handler(v1, v2, v3, v4)
    }
}

/**
 * 5 参数工具的动态 handler——[Handler1] 的 arity=5 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?, E?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 5 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @param D 第 4 个参数的 Kotlin 类型
 * @param E 第 5 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?)`，返回结果字符串
 */
class Handler5<A, B, C, D, E>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val handler: suspend (A?, B?, C?, D?, E?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        return handler(v1, v2, v3, v4, v5)
    }
}

/**
 * 6 参数工具的动态 handler——[Handler1] 的 arity=6 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?, E?, F?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 6 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @param D 第 4 个参数的 Kotlin 类型
 * @param E 第 5 个参数的 Kotlin 类型
 * @param F 第 6 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property p6Name 第 6 个参数的参数名
 * @property s6 第 6 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?)`，返回结果字符串
 */
class Handler6<A, B, C, D, E, F>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val p6Name: String,
    private val s6: KSerializer<F>,
    private val handler: suspend (A?, B?, C?, D?, E?, F?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        val v6: F? = arguments.getParamOrNull(p6Name, s6, json)
        return handler(v1, v2, v3, v4, v5, v6)
    }
}

/**
 * 7 参数工具的动态 handler——[Handler1] 的 arity=7 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?, E?, F?, G?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 7 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @param D 第 4 个参数的 Kotlin 类型
 * @param E 第 5 个参数的 Kotlin 类型
 * @param F 第 6 个参数的 Kotlin 类型
 * @param G 第 7 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property p6Name 第 6 个参数的参数名
 * @property s6 第 6 个参数的 [KSerializer]
 * @property p7Name 第 7 个参数的参数名
 * @property s7 第 7 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?)`，返回结果字符串
 */
class Handler7<A, B, C, D, E, F, G>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val p6Name: String,
    private val s6: KSerializer<F>,
    private val p7Name: String,
    private val s7: KSerializer<G>,
    private val handler: suspend (A?, B?, C?, D?, E?, F?, G?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        val v6: F? = arguments.getParamOrNull(p6Name, s6, json)
        val v7: G? = arguments.getParamOrNull(p7Name, s7, json)
        return handler(v1, v2, v3, v4, v5, v6, v7)
    }
}

/**
 * 8 参数工具的动态 handler——[Handler1] 的 arity=8 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?, E?, F?, G?, H?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 8 组 参数名+序列化器
 *
 * @param A 第 1 个参数的 Kotlin 类型
 * @param B 第 2 个参数的 Kotlin 类型
 * @param C 第 3 个参数的 Kotlin 类型
 * @param D 第 4 个参数的 Kotlin 类型
 * @param E 第 5 个参数的 Kotlin 类型
 * @param F 第 6 个参数的 Kotlin 类型
 * @param G 第 7 个参数的 Kotlin 类型
 * @param H 第 8 个参数的 Kotlin 类型
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property p6Name 第 6 个参数的参数名
 * @property s6 第 6 个参数的 [KSerializer]
 * @property p7Name 第 7 个参数的参数名
 * @property s7 第 7 个参数的 [KSerializer]
 * @property p8Name 第 8 个参数的参数名
 * @property s8 第 8 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?)`，返回结果字符串
 */
class Handler8<A, B, C, D, E, F, G, H>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val p6Name: String,
    private val s6: KSerializer<F>,
    private val p7Name: String,
    private val s7: KSerializer<G>,
    private val p8Name: String,
    private val s8: KSerializer<H>,
    private val handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        val v6: F? = arguments.getParamOrNull(p6Name, s6, json)
        val v7: G? = arguments.getParamOrNull(p7Name, s7, json)
        val v8: H? = arguments.getParamOrNull(p8Name, s8, json)
        return handler(v1, v2, v3, v4, v5, v6, v7, v8)
    }
}

/**
 * 9 参数工具的动态 handler——[Handler1] 的 arity=9 变体。
 *
 * - 作用：包装 `suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 9 组 参数名+序列化器
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
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property p6Name 第 6 个参数的参数名
 * @property s6 第 6 个参数的 [KSerializer]
 * @property p7Name 第 7 个参数的参数名
 * @property s7 第 7 个参数的 [KSerializer]
 * @property p8Name 第 8 个参数的参数名
 * @property s8 第 8 个参数的 [KSerializer]
 * @property p9Name 第 9 个参数的参数名
 * @property s9 第 9 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?, I?)`，返回结果字符串
 */
class Handler9<A, B, C, D, E, F, G, H, I>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val p6Name: String,
    private val s6: KSerializer<F>,
    private val p7Name: String,
    private val s7: KSerializer<G>,
    private val p8Name: String,
    private val s8: KSerializer<H>,
    private val p9Name: String,
    private val s9: KSerializer<I>,
    private val handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        val v6: F? = arguments.getParamOrNull(p6Name, s6, json)
        val v7: G? = arguments.getParamOrNull(p7Name, s7, json)
        val v8: H? = arguments.getParamOrNull(p8Name, s8, json)
        val v9: I? = arguments.getParamOrNull(p9Name, s9, json)
        return handler(v1, v2, v3, v4, v5, v6, v7, v8, v9)
    }
}

/**
 * 10 参数工具的动态 handler——[Handler1] 的 arity=10 变体（散开参数模式支持的最大 arity）。
 *
 * - 作用：包装 `suspend (A?, B?, ..., J?) -> String` lambda，按声明顺序取值反序列化后调用
 * - 设计：与 [Handler1] 同模式，扩展为 10 组 参数名+序列化器；超过 10 参数应改用 `tool<Args>` 强类型模式
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
 * @property p1Name 第 1 个参数的参数名
 * @property s1 第 1 个参数的 [KSerializer]
 * @property p2Name 第 2 个参数的参数名
 * @property s2 第 2 个参数的 [KSerializer]
 * @property p3Name 第 3 个参数的参数名
 * @property s3 第 3 个参数的 [KSerializer]
 * @property p4Name 第 4 个参数的参数名
 * @property s4 第 4 个参数的 [KSerializer]
 * @property p5Name 第 5 个参数的参数名
 * @property s5 第 5 个参数的 [KSerializer]
 * @property p6Name 第 6 个参数的参数名
 * @property s6 第 6 个参数的 [KSerializer]
 * @property p7Name 第 7 个参数的参数名
 * @property s7 第 7 个参数的 [KSerializer]
 * @property p8Name 第 8 个参数的参数名
 * @property s8 第 8 个参数的 [KSerializer]
 * @property p9Name 第 9 个参数的参数名
 * @property s9 第 9 个参数的 [KSerializer]
 * @property p10Name 第 10 个参数的参数名
 * @property s10 第 10 个参数的 [KSerializer]
 * @property handler 工具执行逻辑，接收 `(A?, B?, C?, D?, E?, F?, G?, H?, I?, J?)`，返回结果字符串
 */
class Handler10<A, B, C, D, E, F, G, H, I, J>(
    private val p1Name: String,
    private val s1: KSerializer<A>,
    private val p2Name: String,
    private val s2: KSerializer<B>,
    private val p3Name: String,
    private val s3: KSerializer<C>,
    private val p4Name: String,
    private val s4: KSerializer<D>,
    private val p5Name: String,
    private val s5: KSerializer<E>,
    private val p6Name: String,
    private val s6: KSerializer<F>,
    private val p7Name: String,
    private val s7: KSerializer<G>,
    private val p8Name: String,
    private val s8: KSerializer<H>,
    private val p9Name: String,
    private val s9: KSerializer<I>,
    private val p10Name: String,
    private val s10: KSerializer<J>,
    private val handler: suspend (A?, B?, C?, D?, E?, F?, G?, H?, I?, J?) -> String,
) : DynamicHandler {
    override suspend fun invoke(arguments: JsonObject, json: Json): String {
        val v1: A? = arguments.getParamOrNull(p1Name, s1, json)
        val v2: B? = arguments.getParamOrNull(p2Name, s2, json)
        val v3: C? = arguments.getParamOrNull(p3Name, s3, json)
        val v4: D? = arguments.getParamOrNull(p4Name, s4, json)
        val v5: E? = arguments.getParamOrNull(p5Name, s5, json)
        val v6: F? = arguments.getParamOrNull(p6Name, s6, json)
        val v7: G? = arguments.getParamOrNull(p7Name, s7, json)
        val v8: H? = arguments.getParamOrNull(p8Name, s8, json)
        val v9: I? = arguments.getParamOrNull(p9Name, s9, json)
        val v10: J? = arguments.getParamOrNull(p10Name, s10, json)
        return handler(v1, v2, v3, v4, v5, v6, v7, v8, v9, v10)
    }
}

/**
 * 散开参数模式的工具注册条目——持有工具元数据与 [DynamicHandler]，对外仅暴露 String→String 执行接口。
 *
 * - 作用：作为 [ToolRegistry] 中与 [ToolEntry] 平行的另一种条目类型，持有工具名 [name]、
 *   [definition]（含 description 与 JSON Schema parameters）与 [handler]（[DynamicHandler]），
 *   [execute] 解析 JSON 字符串为 [JsonObject] 后委托给 [handler.invoke]
 * - 必要性：散开参数模式不绑定单一 `@Serializable` 类型，[DynamicHandler] 是密封接口（11 种 arity 子类），
 *   [DynamicToolEntry] 把「元数据 + handler」封装成统一条目，[ToolRegistry] 无需关心具体 arity
 * - 设计思路：
 *   1. 与 [ToolEntry] 字段布局一致（[name]/[definition]/[handler]），便于 [ToolRegistry] 用密封接口统一存储
 *   2. [handler] 为 [DynamicHandler] 而非泛型 lambda——密封接口多态分发，避免在 [ToolRegistry] 暴露泛型
 *   3. 内部 [json] 实例开启 `ignoreUnknownKeys`+`isLenient`，容错模型返回的多余字段与格式宽松
 *   4. [definition.parameters] 在构造前已由 [buildSchemaFromParams] 从 [ParamDef] 列表生成，运行时零开销
 * - 实现方式：不可变结构（val 字段），[execute] 为 suspend 方法以支持异步 handler
 * - 边缘情况：
 *   - 空 arguments（`""` 或空白）→ 当作空 [JsonObject]，handler 各参数收到 null
 *   - 非 object 的合法 JSON（如数组/原始值）→ 兜底为空 [JsonObject]，避免类型转换异常
 *   - 反序列化失败 → `decodeFromJsonElement` 抛 [kotlinx.serialization.SerializationException]，
 *     由 [ToolRegistry.execute] 上层捕获包装为 [RACException]
 * - 优点：与 [ToolEntry] 对外语义一致（execute(arguments: String): String），[ToolRegistry] 可统一调用
 * - 算法/数据结构：薄持有类，[execute] 内一次 JSON 解析 + 委托
 * - 时间复杂度：[execute] O(n)，n 为参数数量（[Handler.invoke] 内逐个反序列化）
 * - 空间复杂度：O(n)，[JsonObject] + 反序列化临时对象
 *
 * @property name 工具名（与 [definition.name] 一致，冗余存储便于快速查找）
 * @property definition 工具定义（含 description 与 JSON Schema parameters），供模型调用参考
 * @property handler 动态执行器（[Handler0]...[Handler10] 之一），按 arity 反序列化参数后调用 lambda
 */
class DynamicToolEntry(
    override val name: String,
    override val definition: ToolDefinition,
    private val handler: DynamicHandler,
) : ToolEntryHolder {
    /**
     * 工具内部使用的 [Json] 实例——开启 ignoreUnknownKeys 容错模型多余字段，isLenient 宽松解析。
     *
     * - 必要性：模型可能返回 schema 未声明的多余字段（如内部标记），ignoreUnknownKeys 避免报错；
     *   isLenient 容错引号/逗号等格式瑕疵，提升对各类模型输出的兼容性
     */
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 执行工具——解析 arguments JSON 字符串为 [JsonObject]，委托给 [handler.invoke]。
     *
     * - 作用：封装「JSON 字符串 → [JsonObject] → 委托 handler」闭环，[ToolRegistry] 无需关心 arity
     * - 设计：空/空白 arguments 当作空 [JsonObject]（无参工具或全 optional 工具的常见场景）；
     *   非 object 的合法 JSON（数组/原始值）兜底为空 [JsonObject]，避免 `as JsonObject` 抛 ClassCastException
     * - 边缘：
     *   - 非法 JSON（如 `{bad`）→ `parseToJsonElement` 抛 [kotlinx.serialization.SerializationException]，
     *     由上层 [ToolRegistry.execute] 捕获包装为 [RACException]
     *   - 空字符串 → 走 isBlank 分支，返回空 [JsonObject]，不抛异常
     *
     * @param arguments 模型返回的工具参数 JSON 字符串（如 `{"city":"北京"}`），可为空字符串
     * @return handler 返回的结果字符串
     */
    override suspend fun execute(arguments: String): String {
        val obj: JsonObject = if (arguments.isBlank()) {
            JsonObject(emptyMap())
        } else {
            json.parseToJsonElement(arguments) as? JsonObject ?: JsonObject(emptyMap())
        }
        return handler.invoke(obj, json)
    }
}

/**
 * 从 [ParamDef] 列表生成工具参数的 JSON Schema 字符串。
 *
 * - 作用：散开参数模式下，[DynamicToolEntry] 构造前调用本函数把 `ToolScope.params`（[List]<[ParamDef]>）
 *   转为 [ToolDefinition.parameters] 所需的 JSON Schema 字符串，供模型调用参考
 * - 必要性：散开参数模式无 `@Serializable` data class，无法复用 [toJsonSchema]（依赖 [SerialDescriptor]），
 *   需要从 [ParamDef] 的纯字符串字段直接构造 JSON Schema；本函数与 [toJsonSchema] 输出格式一致，
 *   仅数据来源不同（前者来自 [ParamDef]，后者来自 KSerializer descriptor）
 * - 设计思路：
 *   1. 顶层固定 `{"type":"object", ...}`
 *   2. `properties`：每个 [ParamDef] 对应一项，由 [buildParamSchema] 生成（含 type + description + enum）
 *   3. `required`：仅含 `required = true` 的 [ParamDef.name]；全 optional 时省略该字段（与 [toJsonSchema] 一致）
 *   4. 无 params 时仅输出 `{"type":"object"}`，不输出空 properties/required（与无参工具的 schema 约定一致）
 * - 实现：用 `buildJsonObject`/`buildJsonArray` 构造不可变 JSON 树再 `toString()`，与 [SchemaGenerator] 风格统一
 * - 边缘情况：
 *   - 空列表 → `{"type":"object"}`（无参工具）
 *   - [ParamDef.description] 为 null/空 → 该项不含 description 字段
 *   - [ParamDef.enumValues] 为 null/空 → 该项不含 enum 字段
 *   - 全 optional → 不输出 required 字段
 * - 优点：纯函数、无副作用、易测试；与 [toJsonSchema] 输出结构一致，模型侧无需区分两种模式
 * - 算法/数据结构：单次遍历 [List]<[ParamDef]>，构造 JSON 树
 * - 时间复杂度：O(n)，n 为 params 数量
 * - 空间复杂度：O(n)，生成对应大小的 JSON 树
 *
 * @param params 参数定义列表（按声明顺序，影响 properties 的键序）
 * @return JSON Schema 字符串
 */
fun buildSchemaFromParams(params: List<ParamDef>): String {
    // 无参数工具：仅输出 {"type":"object"}，不输出空 properties/required（与无参工具 schema 约定一致）
    if (params.isEmpty()) {
        return buildJsonObject { put("type", JsonPrimitive("object")) }.toString()
    }
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        // properties：每个 ParamDef 对应一项 属性名 → 属性 schema（type + description + enum）
        put("properties", buildJsonObject {
            for (p in params) {
                put(p.name, buildParamSchema(p))
            }
        })
        // required：仅含 required = true 的参数名；全 optional 时省略该字段
        val requiredNames = params.filter { it.required }.map { it.name }
        if (requiredNames.isNotEmpty()) {
            put("required", buildJsonArray {
                requiredNames.forEach { add(JsonPrimitive(it)) }
            })
        }
    }.toString()
}

/**
 * 生成单个 [ParamDef] 对应的属性 schema——含 type + description（若有）+ enum（若有）。
 *
 * - 作用：被 [buildSchemaFromParams] 调用，构造 properties 里单个属性的 JSON Schema 对象
 * - 设计：type 字段始终输出；description 仅在非 null/非空时输出；enum 仅在 enumValues 非 null/非空时输出
 * - 边缘：[ParamDef.type] 不在本层校验合法性（如误填 "str"），由模型侧 schema 校验暴露
 *
 * @param param 单个参数定义
 * @return 属性 schema 的 [JsonObject] 表示
 */
private fun buildParamSchema(param: ParamDef): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(param.type))
    if (!param.description.isNullOrEmpty()) {
        put("description", JsonPrimitive(param.description))
    }
    if (!param.enumValues.isNullOrEmpty()) {
        put("enum", buildJsonArray {
            param.enumValues.forEach { add(JsonPrimitive(it)) }
        })
    }
}
