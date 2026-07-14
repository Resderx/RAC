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

package com.resderx.rac.dsl

/**
 * RAC DSL 作用域标记注解。
 *
 * - 作用：标注所有 RAC DSL 构建器类（RacBuilder/ChatRequestBuilder/ToolsBuilder 等），
 *   防止在嵌套 DSL lambda 中误访问外层接收者的成员，避免作用域污染
 * - 必要性：Kotlin DSL 使用带接收者的 lambda，嵌套时若不加限制，内层 lambda 可隐式访问外层接收者成员，
 *   导致作用域污染与难以察觉的 bug；@DslMarker 让编译器在编译期捕获此类跨作用域调用
 * - 设计思路：标注 @DslMarker 的注解类，应用于所有 DSL 构建器；编译器对同一 @DslMarker 标注的接收者
 *   隐式访问外层成员时报错，需显式 `this@OuterReceiver` 才能访问
 * - 实现方式：`annotation class RacDslMarker` 标注 `@DslMarker`，默认作用于类与表达式
 * - 可能的问题：过度使用可能限制合理的嵌套调用，需在显式需要时用 `this@OuterReceiver` 解除限制
 * - 边缘情况：仅限制隐式访问，显式通过 `this@Outer.method()` 仍可调用；同一构建器内方法不受限制
 * - 优点：编译期保证 DSL 作用域清洁，避免意外的跨层调用，提升 DSL 可读性与安全性
 * - 算法/数据结构：注解元数据，无运行时数据结构
 * - 时间复杂度：N/A（编译期检查，无运行时开销）
 * - 空间复杂度：N/A（编译期检查，无运行时开销）
 */
@DslMarker
annotation class RacDslMarker
