package com.resderx.rac.providers

/**
 * 供应商抽象接口，定义一个 LLM 供应商所需的连接与默认配置信息。
 *
 * - 作用：抽象 11 家 LLM 供应商的连接元数据（baseUrl/apiKey/headers）与默认调用参数（API 类型/模型）
 * - 必要性：跨供应商统一接口，使 RAC 核心可面向接口编程，新增供应商只需实现此接口无需修改核心
 * - 设计思路：纯只读属性接口，不含可变状态；apiKey 可空以支持 Ollama 等本地无鉴权供应商；
 *   defaultHeaders 提供默认空 Map，供应商可在实现中覆盖以注入鉴权头或版本头
 * - 实现方式：interface 声明 6 个只读属性，defaultHeaders 提供默认 getter 返回空 Map
 * - 边缘情况：apiKey 为 null 时（如 Ollama），API 客户端不应添加 Authorization 头；
 *   defaultHeaders 可被调用方传入的额外 headers 覆盖或合并
 * - 优点：接口最小化，供应商实现只需提供连接信息，调用逻辑由 API 客户端统一处理
 *
 * @property name 供应商唯一标识名（如 "openai"、"deepseek"），用作 ProviderRegistry 的键
 * @property baseUrl 供应商 API 基础地址，不含具体路径
 * @property apiKey API 密钥，本地供应商（如 Ollama）可为 null
 * @property defaultHeaders 默认请求头，默认空 Map；供应商可覆盖以注入鉴权或版本头（如 anthropic-version）
 * @property defaultApiType 默认 API 协议类型，决定调用哪个 API 客户端
 * @property defaultModel 默认模型名，调用方未指定 model 时使用
 */
interface ModelProvider {
    val name: String
    val baseUrl: String
    val apiKey: String?
    val defaultHeaders: Map<String, String>
        get() = emptyMap()
    val defaultApiType: ApiType
    val defaultModel: String
}

/**
 * ModelProvider 的简单数据类实现，供具体供应商复用以减少样板代码。
 *
 * - 作用：提供 ModelProvider 的不可变数据类实现，11 家供应商可直接以此构造而无需重复声明属性
 * - 必要性：避免每个供应商实现重复写一遍相同的属性声明
 * - 设计思路：数据类暴露所有属性为主构造参数，defaultHeaders 默认空 Map，apiKey 默认 null；
 *   供应商可通过命名参数构造，无需额外子类
 * - 实现方式：`data class` 实现 ModelProvider，所有属性为构造参数并提供默认值
 * - 边缘情况：defaultHeaders 默认空 Map，供应商构造时传入鉴权头；apiKey 默认 null 支持 Ollama
 *
 * @property name 供应商唯一标识名
 * @property baseUrl 供应商 API 基础地址
 * @property apiKey API 密钥，默认 null（支持本地无鉴权供应商）
 * @property defaultHeaders 默认请求头，默认空 Map
 * @property defaultApiType 默认 API 协议类型
 * @property defaultModel 默认模型名
 */
data class SimpleModelProvider(
    override val name: String,
    override val baseUrl: String,
    override val apiKey: String? = null,
    override val defaultHeaders: Map<String, String> = emptyMap(),
    override val defaultApiType: ApiType,
    override val defaultModel: String,
) : ModelProvider
