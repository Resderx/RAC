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

package top.resderx.rac.providers

import com.resderx.rac.exceptions.RACException

/**
 * 供应商注册表，按 name 管理所有已注册的 ModelProvider 实例。
 *
 * - 作用：作为全局供应商目录，按名称存取 ModelProvider，供 RAC 入口与 DSL 查询
 * - 必要性：RAC 支持多供应商并存，需要集中注册表管理生命周期与查找
 * - 设计思路：内部持有 MutableMap，对外暴露只读 Map 视图；register 按 provider.name 去重覆盖；
 *   get 未找到时抛 RACException 而非 IllegalArgumentException，统一异常体系
 * - 实现方式：类封装 LinkedHashMap（保持注册顺序），get/getOrNull/contains 提供查询，providers 为只读视图
 * - 可能的问题：register 非线程安全，多线程并发注册需调用方自行同步
 * - 边缘情况：重复注册同名供应商会静默覆盖旧值；get 未注册的 name（含空字符串）抛 RACException
 * - 优点：只读视图防止外部篡改内部状态；operator contains 支持 `"openai" in registry` 惯用语法
 * - 数据结构：LinkedHashMap 保持插入顺序，便于确定性遍历
 * - 时间复杂度：register/get/getOrNull/contains 均为 O(1)（HashMap 查找）
 * - 空间复杂度：O(n)，n 为已注册供应商数量
 */
class ProviderRegistry {
    private val _providers: MutableMap<String, ModelProvider> = linkedMapOf()

    /**
     * 已注册供应商的只读视图，键为 provider.name，保持注册顺序。
     *
     * - 边缘情况：返回内部 Map 的只读类型视图，外部无法通过类型系统修改；空注册表返回空 Map
     */
    val providers: Map<String, ModelProvider>
        get() = _providers

    /**
     * 注册一个供应商，按 provider.name 存入；同名供应商会被覆盖。
     *
     * - 边缘情况：重复注册同名供应商静默覆盖旧值
     *
     * @param provider 要注册的供应商实例
     */
    fun register(provider: ModelProvider) {
        _providers[provider.name] = provider
    }

    /**
     * 按名称获取供应商，未找到时抛 RACException。
     *
     * - 边缘情况：name 未注册时抛 RACException，message 包含缺失的 name 便于排查
     *
     * @param name 供应商名称
     * @return 对应的 ModelProvider
     * @throws RACException 当 name 未注册时
     */
    fun get(name: String): ModelProvider =
        _providers[name] ?: throw RACException("Provider '$name' not found in registry")

    /**
     * 按名称获取供应商，未找到时返回 null。
     *
     * - 边缘情况：name 未注册时返回 null，调用方据此做容错处理
     *
     * @param name 供应商名称
     * @return 对应的 ModelProvider，未找到时为 null
     */
    fun getOrNull(name: String): ModelProvider? = _providers[name]

    /**
     * 检查是否已注册指定名称的供应商。
     *
     * - 边缘情况：支持 `"openai" in registry` 操作符语法；空字符串 name 返回 false
     *
     * @param name 供应商名称
     * @return 已注册返回 true，否则 false
     */
    operator fun contains(name: String): Boolean = name in _providers
}
