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

/**
 * 供应商抽象接口，定义一个 LLM 供应商的连接信息与注册的模型集合。
 *
 * - 作用：抽象 11 家 LLM 供应商的连接元数据（baseUrl/apiKey/headers/apiType）与模型注册表（models），
 *   使核心可面向接口编程，新增供应商只需实现此接口无需修改核心
 * - 必要性：跨供应商统一接口；新设计将"连接配置"与"模型配置"分离——一个 provider 配置一次连接，
 *   其下可注册多个不同参数的 [ModelConfig]，避免为同供应商的不同模型重复配置连接
 * - 设计思路：纯只读属性接口，不含可变状态；apiKey 可空以支持 Ollama 等本地无鉴权供应商；
 *   defaultHeaders 提供默认空 Map，供应商可在实现中覆盖以注入鉴权头或版本头；
 *   [models] 为模型名→配置的只读 Map，[defaultModel] 为首个注册模型的便捷访问
 * - 实现方式：interface 声明只读属性，[SimpleModelProvider] 提供默认数据类实现
 * - 边缘情况：apiKey 为 null 时（如 Ollama），API 客户端不应添加 Authorization 头；
 *   models 为空时 defaultModel 会抛异常（应在构建期保证至少注册一个模型）
 *
 * @property name 供应商唯一标识名（如 "openai"、"deepseek"），用作注册表键
 * @property baseUrl 供应商 API 基础地址，不含具体路径
 * @property apiKey API 密钥，本地供应商（如 Ollama）可为 null
 * @property defaultHeaders 默认请求头，默认空 Map；供应商可覆盖以注入鉴权或版本头（如 anthropic-version）
 * @property defaultApiType 默认 API 协议类型，决定调用哪个 API 客户端
 * @property models 已注册的模型配置表，键为模型名，值为 [ModelConfig]；保持注册顺序
 * @property defaultModel 默认模型名，取 [models] 的首个键；调用方未指定 model 时使用
 */
interface ModelProvider {
    val name: String
    val baseUrl: String
    val apiKey: String?
    val defaultHeaders: Map<String, String>
        get() = emptyMap()
    val defaultApiType: ApiType
    val models: Map<String, ModelConfig>
    val defaultModel: String
        get() = models.keys.firstOrNull() ?: error("Provider '$name' has no registered models")
}

/**
 * ModelProvider 的简单数据类实现，供具体供应商复用以减少样板代码。
 *
 * - 作用：提供 ModelProvider 的不可变数据类实现，11 家供应商可直接以此构造而无需重复声明属性
 * - 必要性：避免每个供应商实现重复写一遍相同的属性声明
 * - 设计思路：数据类暴露连接属性为主构造参数，models 为模型注册表（LinkedHashMap 保持顺序）；
 *   defaultModel 由接口默认 getter 从 models 首键派生，无需显式声明
 * - 实现方式：`data class` 实现 ModelProvider，连接属性为构造参数，models 为构造参数
 * - 边缘情况：defaultHeaders 默认空 Map，供应商构造时传入鉴权头；
 *   apiKey 默认 null 支持 Ollama；models 必须非空（defaultModel 访问空表会抛 error）
 *
 * @property name 供应商唯一标识名
 * @property baseUrl 供应商 API 基础地址
 * @property apiKey API 密钥，默认 null（支持本地无鉴权供应商）
 * @property defaultHeaders 默认请求头，默认空 Map
 * @property defaultApiType 默认 API 协议类型
 * @property models 已注册的模型配置表，键为模型名；必须非空
 */
data class SimpleModelProvider(
    override val name: String,
    override val baseUrl: String,
    override val apiKey: String? = null,
    override val defaultHeaders: Map<String, String> = emptyMap(),
    override val defaultApiType: ApiType,
    override val models: Map<String, ModelConfig>,
) : ModelProvider
