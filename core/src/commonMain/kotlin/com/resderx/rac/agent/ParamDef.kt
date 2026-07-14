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

package com.resderx.rac.agent

/**
 * 散开参数式工具 DSL 的单个参数定义。
 *
 * - 作用：在 `tool(name, desc) { param(...); execute { ... } }` DSL 中，每次 `param()` 调用收集一份
 *   [ParamDef]，用于后续生成工具的 JSON Schema（[ToolDefinition.parameters]）并按声明顺序绑定
 *   `execute` lambda 的入参。它是「散开参数模式」与「强类型 `tool<Args>` 模式」的核心差异点——
 *   前者用一组 [ParamDef] 描述参数，后者用 `@Serializable data class` 描述参数
 * - 必要性：散开参数模式不要求开发者定义 data class，框架需要一份「参数元数据」既能生成 schema
 *   给模型参考，又能按序匹配 `execute` 的 lambda 参数；[ParamDef] 就是这份元数据的不可变载体
 * - 设计思路：
 *   1. 用纯字符串 [type] 表达 JSON Schema 类型（"string"/"integer"/"number"/"boolean"/"array"/"object"），
 *      而非 Kotlin 类型引用——避免引入反射，且与 JSON Schema 规范一一对应，模型可读性最好
 *   2. [name] 与 [type] 为必填，[description]/[required]/[enumValues] 可选，覆盖常见工具参数场景
 *   3. [enumValues] 限定取值集合，直接映射 JSON Schema 的 `enum` 字段，常用于状态/枚举类参数
 *   4. [required] 默认 true——与多数工具参数「必填」的常态一致，optional 参数需显式 `required = false`
 * - 实现方式：不可变 data class，所有字段为 val；data class 自动生成 equals/hashCode/copy/toString，
 *   便于在 `ToolScope.params` 列表中收集与遍历
 * - 边缘情况：
 *   - [type] 不在 JSON Schema 标准枚举内时（如误填 "str"）不在本层校验，由模型侧 schema 校验暴露
 *   - [enumValues] 仅对 [type] == "string" 有意义，本层不强制约束（数组类型也可塞 enum 但语义无意义）
 *   - [required] = false 时，模型可不返回该字段，执行时 handler 对应入参接收 null（见 [DynamicHandler]）
 * - 优点：纯数据、无依赖、易测试；与 JSON Schema 字段一一对应，schema 生成逻辑简单直白
 * - 算法/数据结构：薄数据类，无内部状态
 * - 时间复杂度：构造 O(1)
 * - 空间复杂度：O(1)（不含 enumValues 列表本身的引用大小）
 *
 * @property name 参数名，对应 JSON Schema properties 的 key 与模型返回 arguments 的字段名
 * @property type JSON Schema 类型字符串，如 "string"/"integer"/"number"/"boolean"/"array"/"object"
 * @property description 参数的人类可读描述，供模型理解参数含义，可空（缺失时部分模型调用准确率下降）
 * @property required 是否必填，默认 true；false 时模型可不返回该字段，handler 入参接收 null
 * @property enumValues 可选枚举值列表，限制参数取值范围，仅对字符串类型有意义
 */
data class ParamDef(
    val name: String,
    val type: String,
    val description: String? = null,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)
